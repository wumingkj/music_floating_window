package com.wuming.musicFW.services

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.wuming.musicFW.R
import com.wuming.musicFW.utils.LogHelper

class FloatingLyricsService : Service() {
    private var wm: WindowManager? = null
    private var floatView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var lastX = 0f; private var lastY = 0f; private var isDragging = false

    companion object {
        private var instance: FloatingLyricsService? = null
        private var songTitle = ""
        fun isRunning(): Boolean = instance != null
        fun requestShow(ctx: Context, lyric: String, title: String = "") {
            songTitle = title
            ctx.startService(Intent(ctx, FloatingLyricsService::class.java).apply {
                action = "SHOW"; putExtra("lyrics", lyric)
            })
        }
        fun requestHide(ctx: Context) {
            ctx.startService(Intent(ctx, FloatingLyricsService::class.java).apply { action = "HIDE" })
        }
        fun requestUpdate(ctx: Context, lyric: String, title: String = "") {
            songTitle = title
            ctx.startService(Intent(ctx, FloatingLyricsService::class.java).apply {
                action = "UPDATE"; putExtra("lyrics", lyric)
            })
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate(); instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        LogHelper.d("FloatingLyricsService 创建")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            LogHelper.d("无悬浮窗权限"); return START_NOT_STICKY
        }
        when (intent?.action) {
            "SHOW" -> showFloating(intent.getStringExtra("lyrics") ?: "")
            "UPDATE" -> updateLyrics(intent.getStringExtra("lyrics") ?: "")
            "HIDE" -> hideFloating()
        }
        return START_NOT_STICKY
    }

    private fun showFloating(lyrics: String) {
        if (floatView != null) { updateLyrics(lyrics); return }

        floatView = LayoutInflater.from(this).inflate(R.layout.floating_lyrics, null)
        floatView!!.findViewById<TextView>(R.id.floatingLyricText).text = lyrics
        floatView!!.findViewById<TextView>(R.id.floatingSongTitle).text =
            songTitle.ifEmpty { "歌词悬浮窗" }
        floatView!!.findViewById<View>(R.id.floatingCloseBtn).setOnClickListener { hideFloating() }
        floatView!!.setOnTouchListener { v, e -> onTouch(v, e) }

        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else WindowManager.LayoutParams.TYPE_PHONE

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        ).apply { gravity = Gravity.TOP or Gravity.START; x = 0; y = 200 }

        try { wm?.addView(floatView, params); LogHelper.d("悬浮窗已显示: $lyrics")
        } catch (e: Exception) { LogHelper.e("显示悬浮窗失败: ${e.message}") }
    }

    private fun onTouch(v: View, e: MotionEvent): Boolean {
        val lp = params ?: return false
        when (e.action) {
            MotionEvent.ACTION_DOWN -> { lastX = e.rawX; lastY = e.rawY; isDragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = e.rawX - lastX; val dy = e.rawY - lastY
                if (!isDragging && (kotlin.math.abs(dx) > 10 || kotlin.math.abs(dy) > 10)) isDragging = true
                if (isDragging) {
                    lp.x = (lp.x + dx).toInt(); lp.y = (lp.y + dy).toInt()
                    try { wm?.updateViewLayout(v, lp) } catch (_: Exception) {}
                    lastX = e.rawX; lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP -> if (!isDragging) v.performClick()
        }
        return true
    }

    private fun updateLyrics(lyrics: String) {
        if (floatView == null) { showFloating(lyrics); return }
        floatView!!.findViewById<TextView>(R.id.floatingLyricText).text = lyrics
        floatView!!.findViewById<TextView>(R.id.floatingSongTitle).text =
            songTitle.ifEmpty { "歌词悬浮窗" }
        LogHelper.d("歌词更新: $lyrics")
    }

    private fun hideFloating() {
        floatView?.let {
            try { wm?.removeView(it) } catch (_: Exception) {}
            floatView = null; params = null; LogHelper.d("悬浮窗已隐藏")
        }
    }

    override fun onDestroy() { hideFloating(); instance = null; super.onDestroy() }
}
