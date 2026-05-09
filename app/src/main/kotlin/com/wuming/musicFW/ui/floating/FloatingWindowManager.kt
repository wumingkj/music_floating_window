package com.wuming.musicFW.ui.floating

import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import com.wuming.musicFW.utils.LogHelper

class FloatingWindowManager(private val context: Context) {

    private var windowManager: WindowManager? = null
    private var floatingLyricsView: FloatingLyricsView? = null
    private var isShowing = false

    private val layoutParams: WindowManager.LayoutParams by lazy {
        val typeValue = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
        
        WindowManager.LayoutParams(
            typeValue,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            format = PixelFormat.TRANSLUCENT
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 300
        }
    }

    fun show(lyrics: String) {
        if (isShowing) {
            updateLyrics(lyrics)
            return
        }

        try {
            windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            floatingLyricsView = FloatingLyricsView(context)
            floatingLyricsView?.setLyrics(lyrics)

            windowManager?.addView(floatingLyricsView, layoutParams)
            isShowing = true
            LogHelper.d("悬浮窗已显示")
        } catch (e: Exception) {
            LogHelper.e("显示悬浮窗失败: ${e.message}")
            e.printStackTrace()
        }
    }

    fun updateLyrics(lyrics: String) {
        if (isShowing) {
            floatingLyricsView?.setLyrics(lyrics)
            LogHelper.d("歌词已更新: $lyrics")
        }
    }

    fun hide() {
        if (isShowing) {
            try {
                windowManager?.removeView(floatingLyricsView)
                floatingLyricsView = null
                windowManager = null
                isShowing = false
                LogHelper.d("悬浮窗已隐藏")
            } catch (e: Exception) {
                LogHelper.e("隐藏悬浮窗失败: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    fun isShowing(): Boolean = isShowing
}