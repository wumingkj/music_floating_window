package com.wuming.musicFW.ui.floating

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.wuming.musicFW.utils.LogHelper

class FloatingLyricsView(context: Context) : LinearLayout(context) {

    private lateinit var lyricsTextView: TextView
    private var lastX = 0f
    private var lastY = 0f
    private var isDragging = false


    init {
        orientation = VERTICAL
        setupView()
        setupTouchListener()
    }

    private fun setupView() {
        setPadding(40, 30, 40, 30)
        
        val background = GradientDrawable()
        background.shape = GradientDrawable.RECTANGLE
        background.cornerRadius = dpToPx(16f)
        background.setColor(Color.parseColor("#CC000000"))
        background.alpha = 230
        setBackground(background)

        lyricsTextView = TextView(context).apply {
            text = ""
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
            setTextColor(Color.WHITE)
            textAlignment = TEXT_ALIGNMENT_CENTER
            layoutParams = LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        addView(lyricsTextView)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        setOnTouchListener { view, event ->
            val params = view.layoutParams as android.view.WindowManager.LayoutParams
            
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.rawX
                    lastY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - lastX
                    val dy = event.rawY - lastY
                    
                    if (!isDragging && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) {
                        isDragging = true
                    }
                    
                    if (isDragging) {
                        params.x = (params.x + dx).toInt()
                        params.y = (params.y + dy).toInt()
                        
                        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
                        try {
                            windowManager.updateViewLayout(this, params)
                        } catch (e: Exception) {
                            LogHelper.e("更新悬浮窗位置失败: ${e.message}")
                        }
                        
                        lastX = event.rawX
                        lastY = event.rawY
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isDragging) {
                        performClick()
                    }
                    true
                }
                else -> false
            }
        }
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    fun setLyrics(lyrics: String) {
        lyricsTextView.text = lyrics
    }

    fun setTextColor(color: Int) {
        lyricsTextView.setTextColor(color)
    }

    fun setTextSize(size: Float) {
        lyricsTextView.setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
    }

    private fun dpToPx(dp: Float): Float {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics
        )
    }
}