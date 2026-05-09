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

class NotificationListenerService : NotificationListenerService() {

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
        val packageName = sbn.packageName ?: return

        if (isMusicApp(packageName)) {
            queryCurrentMedia()
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        LogHelper.d("通知监听服务已连接")
        queryCurrentMedia()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        LogHelper.d("通知监听服务已断开")
        stopPositionUpdates()
    }

    private fun isMusicApp(packageName: String): Boolean {
        return packageName.contains("netease") ||
               packageName.contains("cloudmusic") ||
               packageName.contains("qqmusic") ||
               packageName.contains("kugou") ||
               packageName.contains("kuwo") ||
               packageName.contains("spotify") ||
               packageName.contains("youtube") ||
               packageName.contains("music")
    }

    private fun queryCurrentMedia() {
        try {
            val mediaSessionManager = getSystemService(MEDIA_SESSION_SERVICE) as MediaSessionManager
            
            @Suppress("DEPRECATION")
            val controllers = mediaSessionManager.getActiveSessions(
                ComponentName(this, NotificationListenerService::class.java)
            )

            val playingController = controllers
                .filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
                .filter { it.metadata != null }
                .firstOrNull()

            if (playingController != null) {
                handleMediaController(playingController)
            } else if (controllers.isNotEmpty() && controllers[0].metadata != null) {
                handleMediaController(controllers[0])
            }
        } catch (e: Exception) {
            LogHelper.e("获取媒体信息失败: ${e.message}")
            e.printStackTrace()
        }
    }

    private fun handleMediaController(controller: MediaController) {
        val metadata = controller.metadata ?: return
        
        val title = metadata.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return
        val artist = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: "未知艺术家"
        val album = metadata.getString(android.media.MediaMetadata.METADATA_KEY_ALBUM) ?: ""

        val newSongInfo = SongInfo(title, artist, album)
        
        if (currentSongInfo?.title != newSongInfo.title || currentSongInfo?.artist != newSongInfo.artist) {
            currentSongInfo = newSongInfo
            onSongChanged?.invoke(newSongInfo)
            LogHelper.d("歌曲信息更新: $title - $artist")
        }

        if (mediaController != controller) {
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
                    mediaController?.let { controller ->
                        startPositionUpdates(controller)
                    }
                } else {
                    stopPositionUpdates()
                }
            }
        }

        override fun onMetadataChanged(metadata: android.media.MediaMetadata?) {
            queryCurrentMedia()
        }
    }

    private fun startPositionUpdates(controller: MediaController) {
        stopPositionUpdates()
        
        positionUpdateJob = coroutineScope.launch {
            while (isActive) {
                controller.playbackState?.let { state ->
                    if (state.state == PlaybackState.STATE_PLAYING) {
                        val position = state.position
                        onPositionUpdate?.invoke(position)
                    }
                }
                delay(500)
            }
        }
    }

    private fun stopPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = null
    }

    fun getCurrentPosition(): Long {
        return mediaController?.playbackState?.position ?: 0L
    }

    override fun onDestroy() {
        super.onDestroy()
        mediaController?.unregisterCallback(mediaCallback)
        stopPositionUpdates()
        coroutineScope.cancel()
    }
}