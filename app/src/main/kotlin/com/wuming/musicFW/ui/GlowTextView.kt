package com.wuming.musicFW.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import kotlin.math.max
import kotlin.math.min

/**
 * 动态发光歌词TextView，支持随音乐音量/能量变化动态调整发光强度
 */
class GlowTextView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyle: Int = 0
) : androidx.appcompat.widget.AppCompatTextView(context, attrs, defStyle) {
    // 发光参数
    var glowColor: Int = Color.CYAN
    var glowBase: Float = 12f
    var glowMax: Float = 36f
    var glowAlpha: Int = 180

    private var anim: ValueAnimator? = null
    private var animLevel: Float = 0f

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setMusicLevel(level: Float) {
        val target = min(1f, max(0f, level))
        anim?.cancel()
        anim = ValueAnimator.ofFloat(animLevel, target).apply {
            duration = 180
            addUpdateListener {
                animLevel = it.animatedValue as Float
                // 直接更新阴影半径，无需重写 onDraw
                val glow = glowBase + (glowMax - glowBase) * animLevel
                if (glow > 1f && glowAlpha > 0) {
                    val color = (glowColor and 0x00FFFFFF) or (glowAlpha.shl(24))
                    paint.setShadowLayer(glow, 0f, 0f, color)
                } else {
                    paint.setShadowLayer(0f, 0f, 0f, 0)
                }
                invalidate()
            }
            start()
        }
    }
}