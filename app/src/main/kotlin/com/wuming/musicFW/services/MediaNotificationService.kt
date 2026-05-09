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
        var onSongChanged: ((SongInfo) -> Unit)? = null
        var onPositionUpdate: ((Long) -> Unit)? = null

        private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

        fun setCallbacks(
            onSongChanged: ((SongInfo) -> Unit)? = null,
            onPositionUpdate: ((Long) -> Unit)? = null
        ) {
            this.onSongChanged = onSongChanged
            this.onPositionUpdate = onPositionUpdate
        }
    }

    private var mediaController: MediaController? = null
    private var currentSongInfo: SongInfo? = null
    private var positionUpdateJob: Job? = null

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        sbn ?: return
        val pkg = sbn.packageName ?: return
        if (isMusicApp(pkg)) queryCurrentMedia()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {}

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogHelper.d("媒体监听服务已连接")
        queryCurrentMedia()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogHelper.d("媒体监听服务已断开")
        stopPositionUpdates()
    }

    private fun isMusicApp(pkg: String): Boolean {
        return pkg.contains("netease") || pkg.contains("cloudmusic") ||
               pkg.contains("qqmusic") || pkg.contains("kugou") ||
               pkg.contains("kuwo") || pkg.contains("spotify") ||
               pkg.contains("youtube")
    }

    private fun queryCurrentMedia() {
        try {
            val msm = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager

            @Suppress("DEPRECATION")
            val controllers = msm.getActiveSessions(
                ComponentName(this, MediaNotificationService::class.java)
            )

            // 优先取正在播放的
            val playing = controllers
                .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .filter { it.metadata != null }
                .firstOrNull()
            if (playing != null) { handleMediaController(playing); return }

            // 其次取第一个有元数据的
            for (c in controllers) {
                if (c.metadata != null) { handleMediaController(c); return }
            }
        } catch (e: Exception) {
            LogHelper.e("获取媒体信息失败: ${e.message}")
        }
    }

    private fun handleMediaController(controller: MediaController) {
        val meta = controller.metadata ?: return
        val title = meta.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = meta.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "未知艺术家"
        val album = meta.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val info = SongInfo(title, artist, album)
        if (currentSongInfo != info) {
            currentSongInfo = info
            onSongChanged?.invoke(info)
            LogHelper.d("歌曲更新: $title - $artist")
        }

        if (mediaController !== controller) {
            mediaController?.unregisterCallback(mediaCallback)
            mediaController = controller
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
                    mediaController?.let { c -> startPositionUpdates(c) }
                } else {
                    stopPositionUpdates()
                }
            }
        }

        override fun onMetadataChanged(meta: android.media.MediaMetadata?) {
            queryCurrentMedia()
        }
    }

    private fun startPositionUpdates(controller: MediaController) {
        stopPositionUpdates()
        positionUpdateJob = coroutineScope.launch {
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
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    override fun onDestroy() {
        mediaController?.unregisterCallback(mediaCallback)
        stopPositionUpdates()
        coroutineScope.cancel()
        super.onDestroy()
    }
}