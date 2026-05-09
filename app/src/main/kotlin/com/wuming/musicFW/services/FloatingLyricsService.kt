package com.wuming.musicFW.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import com.wuming.musicFW.MainActivity
import com.wuming.musicFW.R
import com.wuming.musicFW.ui.floating.FloatingWindowManager
import com.wuming.musicFW.utils.LogHelper

class FloatingLyricsService : Service() {

    companion object {
        private const val NOTIFICATION_ID = 1001
        private const val CHANNEL_ID = "floating_service_channel"
        private const val CHANNEL_NAME = "悬浮窗服务"
        
        private var instance: FloatingLyricsService? = null
        
        fun isRunning(): Boolean = instance != null
        
        fun requestShow(context: Context, lyrics: String) {
            val intent = Intent(context, FloatingLyricsService::class.java).apply {
                action = "SHOW"
                putExtra("lyrics", lyrics)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun requestHide(context: Context) {
            val intent = Intent(context, FloatingLyricsService::class.java).apply {
                action = "HIDE"
            }
            context.startService(intent)
        }
        
        fun requestUpdate(context: Context, lyrics: String) {
            val intent = Intent(context, FloatingLyricsService::class.java).apply {
                action = "UPDATE"
                putExtra("lyrics", lyrics)
            }
            context.startService(intent)
        }
    }

    private var floatingWindowManager: FloatingWindowManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        LogHelper.d("FloatingLyricsService 创建")
        createNotificationChannel()
        floatingWindowManager = FloatingWindowManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 前台服务必需调用
        startForeground(NOTIFICATION_ID, createNotification())
        when (intent?.action) {
            "SHOW" -> {
                val lyrics = intent.getStringExtra("lyrics") ?: ""
                showFloatingWindow(lyrics)
            }
            "UPDATE" -> {
                val lyrics = intent.getStringExtra("lyrics") ?: ""
                updateLyrics(lyrics)
            }
            "HIDE" -> {
                hideFloatingWindow()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        hideFloatingWindow()
        floatingWindowManager = null
        instance = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持悬浮窗服务运行"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("歌词悬浮窗")
            .setContentText("正在运行")
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun showFloatingWindow(lyrics: String) {
        LogHelper.d("显示悬浮窗: $lyrics")
        floatingWindowManager?.show(lyrics)
    }

    private fun updateLyrics(lyrics: String) {
        LogHelper.d("更新歌词: $lyrics")
        floatingWindowManager?.updateLyrics(lyrics)
    }

    private fun hideFloatingWindow() {
        LogHelper.d("隐藏悬浮窗")
        floatingWindowManager?.hide()
    }
}