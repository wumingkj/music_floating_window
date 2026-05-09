package com.wuming.musicFW.services

import android.content.ComponentName
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wuming.musicFW.models.SongInfo
import com.wuming.musicFW.utils.LogHelper
import kotlinx.coroutines.*

class MediaNotificationService : NotificationListenerService() {

    companion object {
        var onAlbumArtChanged: ((android.graphics.Bitmap?) -> Unit)? = null
        var onSongChanged: ((SongInfo) -> Unit)? = null
        var onPositionUpdate: ((Long) -> Unit)? = null
        var onLyricLine: ((String) -> Unit)? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun setCallbacks(
            onSongChanged: ((SongInfo) -> Unit)? = null,
            onPositionUpdate: ((Long) -> Unit)? = null,
            onLyricLine: ((String) -> Unit)? = null,
            onAlbumArtChanged: ((android.graphics.Bitmap?) -> Unit)? = null
        ) {
            this.onSongChanged = onSongChanged
            this.onPositionUpdate = onPositionUpdate
            this.onLyricLine = onLyricLine
            this.onAlbumArtChanged = onAlbumArtChanged
        }
    }

    private var currentController: MediaController? = null
    private var currentSongInfo: SongInfo? = null
    private var positionJob: Job? = null
    private var pollJob: Job? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (!isMusicApp(pkg)) return

        // 部分音乐 App（如 QQ音乐）会把歌词放在通知正文
        try {
            val extras = sbn.notification.extras ?: return
            val lyric = extras.getString(android.app.Notification.EXTRA_INFO_TEXT) ?: ""
                       .ifEmpty { extras.getString(android.app.Notification.EXTRA_TEXT) ?: "" }
            if (lyric.isNotEmpty() && lyric.length > 2 && !lyric.contains(" - ")) {
                onLyricLine?.invoke(lyric)
                LogHelper.d("通知歌词: $lyric")
            }
        } catch (_: Exception) {}

        queryCurrentMedia()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // 通知被移除 → 延迟后重新扫描（给新 App 启动时间）
        scope.launch { delay(1000); queryCurrentMedia() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogHelper.d("媒体监听服务已连接")
        queryCurrentMedia()
        startPolling()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogHelper.d("媒体监听服务已断开")
        stopAll()
    }

    // ── 定期轮询（解决同通知更新不触发回调的问题） ──

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                delay(2000)
                // 只在有播放状态或没有数据时轮询
                if (currentController?.playbackState?.state == PlaybackState.STATE_PLAYING
                    || currentSongInfo == null) {
                    queryCurrentMedia()
                }
            }
        }
    }

    private fun stopPolling() { pollJob?.cancel(); pollJob = null }
    private fun stopAll() { stopPolling(); stopPositionUpdates() }

    // ── 音乐 App 检测 ──

    private fun isMusicApp(pkg: String): Boolean {
        return pkg.contains("netease") || pkg.contains("cloudmusic") ||
               pkg.contains("qqmusic") || pkg.contains("kugou") ||
               pkg.contains("kuwo") || pkg.contains("spotify") ||
               pkg.contains("youtube")
    }

    // ── 扫描活跃 MediaSession ──

    private fun queryCurrentMedia() {
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager

            @Suppress("DEPRECATION")
            val controllers = msm.getActiveSessions(
                ComponentName(this, MediaNotificationService::class.java)
            )

            // 遍历所有 controller，注册回调并匹配歌曲
            val musicControllers = controllers.filter { c ->
                val pkg = c.packageName ?: return@filter false
                isMusicApp(pkg) || c.metadata != null
            }

            // 优先取正在播放的
            val playing = musicControllers
                .firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
            if (playing != null) { handleController(playing); return }

            // 其次取第一个有元数据的
            for (c in musicControllers) {
                if (c.metadata != null) { handleController(c); return }
            }
        } catch (e: Exception) {
            LogHelper.e("扫描媒体失败: ${e.message}")
        }
    }

    // ── 处理单个 MediaController ──

    private fun handleController(controller: MediaController) {
        val meta = controller.metadata ?: return
        val title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "未知艺术家"
        val album = meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val art = meta.getBitmap(android.media.MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: meta.getBitmap(android.media.MediaMetadata.METADATA_KEY_ART)

        val info = SongInfo(title, artist, album, art)
        if (currentSongInfo?.title != title || currentSongInfo?.artist != artist) {
            currentSongInfo = info
            onSongChanged?.invoke(info)
            onAlbumArtChanged?.invoke(art)
            LogHelper.d("歌曲更新: $title - $artist")
        } else if (currentSongInfo?.albumArt !== art) {
            onAlbumArtChanged?.invoke(art)
        }

        // 如果 controller 变了，重新注册回调
        if (currentController !== controller) {
            currentController?.unregisterCallback(mediaCallback)
            currentController = controller
            controller.registerCallback(mediaCallback)
        }

        if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) {
            startPositionUpdates(controller)
        } else {
            stopPositionUpdates()
        }
    }

    private val mediaCallback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            state?.let {
                if (it.state == PlaybackState.STATE_PLAYING) {
                    currentController?.let { c -> startPositionUpdates(c) }
                } else {
                    stopPositionUpdates()
                }
            }
        }

        override fun onMetadataChanged(meta: android.media.MediaMetadata?) {
            queryCurrentMedia()
        }

        override fun onSessionDestroyed() {
            LogHelper.d("媒体会话已销毁，重新扫描")
            scope.launch { delay(500); queryCurrentMedia() }
        }
    }

    // ── 播放位置更新 ──

    private fun startPositionUpdates(controller: MediaController) {
        stopPositionUpdates()
        positionJob = scope.launch {
            while (isActive) {
                val state = controller.playbackState
                if (state?.state == PlaybackState.STATE_PLAYING) {
                    onPositionUpdate?.invoke(state.position)
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionJob?.cancel(); positionJob = null
    }

    override fun onDestroy() {
        currentController?.unregisterCallback(mediaCallback)
        stopAll(); scope.cancel(); super.onDestroy()
    }
}