package com.wuming.musicFW.services

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.wuming.musicFW.models.SongInfo
import com.wuming.musicFW.utils.LogHelper
import kotlinx.coroutines.*

/**
 * 媒体监听服务
 *
 * 继承 NotificationListenerService 只是为了获取跨 App MediaSession 访问权限。
 * ⚠ 不处理任何通知内容 —— 所有数据均通过 MediaSession 获取。
 */
class MediaNotificationService : NotificationListenerService() {

    companion object {
        var onAlbumArtChanged: ((android.graphics.Bitmap?) -> Unit)? = null
        var onSongChanged: ((SongInfo) -> Unit)? = null
        var onPositionUpdate: ((Long) -> Unit)? = null
        var onLyricLine: ((String) -> Unit)? = null
        /** 日志回调：level 取值 INFO / VERBOSE / WARN / ERROR */
        var onLog: ((String, String) -> Unit)? = null
        /** 服务实例引用（供 MusicWallpaperService 获取音频 session ID） */
        private var _instance: MediaNotificationService? = null
        private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun setCallbacks(
            onSongChanged: ((SongInfo) -> Unit)? = null,
            onPositionUpdate: ((Long) -> Unit)? = null,
            onLyricLine: ((String) -> Unit)? = null,
            onAlbumArtChanged: ((android.graphics.Bitmap?) -> Unit)? = null,
            onLog: ((String, String) -> Unit)? = null
        ) {
            this.onSongChanged = onSongChanged
            this.onPositionUpdate = onPositionUpdate
            this.onLyricLine = onLyricLine
            this.onAlbumArtChanged = onAlbumArtChanged
            this.onLog = onLog
        }

        /** 手动触发一次扫描 */
        fun triggerScan(context: Context) {
            try {
                val intent = Intent(context, MediaNotificationService::class.java).apply {
                    action = "SCAN"
                }
                context.startService(intent)
                LogHelper.d("triggerScan: 发送 START 意图")
            } catch (e: Exception) {
                LogHelper.e("triggerScan 失败: ${e.message}")
            }
        }
    }

    private var currentController: MediaController? = null
    private var currentSongInfo: SongInfo? = null
    private var positionJob: Job? = null
    private var pollJob: Job? = null

    // ═══ 通知回调 — 不处理任何内容，仅做日志 ═══

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        onLog?.let { it("通知触发 → 扫描 MediaSession", "INFO") }
        queryCurrentMedia()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        onLog?.let { it("通知移除 → 延迟重新扫描", "INFO") }
        scope.launch { delay(1000); queryCurrentMedia() }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        _instance = this
        LogHelper.d("MediaNotificationService 已连接（MediaSession 权限已就绪）")
        onLog?.let { it("服务已连接，开始扫描 MediaSession...", "INFO") }
        queryCurrentMedia()
        startPolling()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogHelper.d("MediaNotificationService 已断开")
        _instance = null
        stopAll()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        LogHelper.d("onStartCommand: action=${intent?.action}")
        if (intent?.action == "SCAN") {
            onLog?.let { it("收到 SCAN 指令，立即扫描 MediaSession...", "INFO") }
            queryCurrentMedia()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        currentController?.unregisterCallback(mediaCallback)
        stopAll()
        scope.cancel()
        LogHelper.d("MediaNotificationService 已销毁")
        super.onDestroy()
    }

    // ── 定期轮询（解决部分 App 不触发 MediaSession 回调的问题） ──

    private fun startPolling() {
        stopPolling()
        pollJob = scope.launch {
            while (isActive) {
                delay(2000)
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
               pkg.contains("youtube") || pkg.contains("music")
    }

    // ── 检查通知监听权限是否已开启 ──

    private fun hasNlsPermission(): Boolean {
        val enabled = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_notification_listeners"
        )
        return enabled?.contains(packageName) == true
    }

    // ── 扫描活跃 MediaSession ──

    private fun queryCurrentMedia() {
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager

            val controllers = if (hasNlsPermission()) {
                onLog?.let { it("通知监听权限已开启，使用 NLS 方式扫描...", "VERBOSE") }
                @Suppress("DEPRECATION")
                msm.getActiveSessions(
                    ComponentName(this, MediaNotificationService::class.java)
                )
            } else {
                onLog?.let { it("通知监听权限未开启，尝试免权限降级扫描...", "INFO") }
                @Suppress("DEPRECATION")
                msm.getActiveSessions(null)
            }

            onLog?.let { it("扫描到 ${controllers.size} 个活跃 MediaSession", "INFO") }
            for (c in controllers) {
                onLog?.let { it("  └ ${c.packageName} | 元数据=${c.metadata != null} | 播放中=${c.playbackState?.state == PlaybackState.STATE_PLAYING}", "VERBOSE") }
            }

            // 过滤出音乐 App 或有元数据的会话
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

            if (musicControllers.isEmpty()) {
                if (!hasNlsPermission()) {
                    onLog?.let { it("❗ 未开启通知监听权限，只能扫描到本 App 的 MediaSession", "WARN") }
                    onLog?.let { it("请在设置页点击「通知监听权限」开启后，重新播放音乐", "WARN") }
                } else {
                    onLog?.let { it("未找到音乐类 MediaSession，请确保正在播放音乐", "INFO") }
                }
            }
        } catch (e: SecurityException) {
            LogHelper.e("扫描媒体失败(权限): ${e.message}")
            onLog?.let { it("❌ 权限不足: ${e.message}", "ERROR") }
            onLog?.let { it("请前往设置 → 通知监听 → 开启\"Music Floating Window\"", "ERROR") }
        } catch (e: Exception) {
            LogHelper.e("扫描媒体失败: ${e.message}")
            onLog?.let { it("扫描失败: ${e.message}", "ERROR") }
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
        val duration = meta.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION)

        // ★ 打印所有获取到的媒体数据（同时推送到 UI 日志）
        onLog?.let { log ->
            log("╔══ MediaSession 数据 ══╗", "VERBOSE")
            log("包名: ${controller.packageName}", "VERBOSE")
            log("标题: $title", "VERBOSE")
            log("艺术家: $artist", "VERBOSE")
            log("专辑: $album", "VERBOSE")
            log("时长: ${duration}ms", "VERBOSE")
            log("封面: ${if (art != null) "有 (${art.width}x${art.height})" else "无"}", "VERBOSE")
        }

        // 打印播放状态
        val state = controller.playbackState
        val stateStr = when (state?.state) {
            PlaybackState.STATE_PLAYING -> "播放中"
            PlaybackState.STATE_PAUSED -> "已暂停"
            PlaybackState.STATE_STOPPED -> "已停止"
            PlaybackState.STATE_NONE -> "无状态"
            else -> "${state?.state}"
        }
        onLog?.let { log ->
            log("播放状态: $stateStr", "VERBOSE")
            log("播放位置: ${state?.position ?: 0}ms", "VERBOSE")
        }

        // 尝试从 metadata 获取歌词（部分 App 会放）
        var lyricFromMedia = ""
        try {
            val lyricKeys = listOf(
                "android.media.metadata.LYRICS",
                "android.media.metadata.TEXT",
                "lyrics", "text"
            )
            for (key in lyricKeys) {
                val lyric = meta.getString(key)
                if (!lyric.isNullOrEmpty() && lyric.length > 2) {
                    lyricFromMedia = lyric
                    onLog?.let { it("歌词来源: $key", "VERBOSE") }
                    break
                }
            }
        } catch (e: Exception) { /* 忽略 */ }

        if (lyricFromMedia.isNotEmpty()) {
            onLog?.let { it("媒体歌词(前100): ${lyricFromMedia.take(100)}", "VERBOSE") }
            onLyricLine?.invoke(lyricFromMedia)
        } else {
            onLog?.let { it("媒体歌词: 无", "VERBOSE") }
        }
        onLog?.let { it("╚══════════════════════╝", "VERBOSE") }

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
                    currentController?.let { c ->
                        startPositionUpdates(c)
                        queryCurrentMedia() // 播放状态变化时重新扫描
                    }
                } else {
                    stopPositionUpdates()
                }
            }
        }

        override fun onMetadataChanged(meta: android.media.MediaMetadata?) {
            LogHelper.d("MediaSession 元数据变化，重新扫描")
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
}