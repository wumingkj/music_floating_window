package com.wuming.musicFW.managers

import android.content.Context
import android.content.SharedPreferences

object AppSettings {
    private const val NAME = "music_fw_prefs"

    // 悬浮窗文本样式: 0=白, 1=霓虹青, 2=彩虹渐变
    var textStyle: Int = 0
    // 打字效果
    var typingEffect: Boolean = false
    // 字体大小 sp
    var fontSize: Int = 20
    // 背景透明度 0~255
    var bgAlpha: Int = 200

    // ═══ 光晕效果配置 ═══
    // 光晕亮度 0~100, 默认50
    var glowIntensity: Int = 50
    // 光晕扩散深度 0~100, 默认50
    var glowSpread: Int = 50
    // 色相变化速度 0~100, 默认30
    var glowHueSpeed: Int = 30
    // 粒子数量 0~100, 默认30
    var glowParticles: Int = 30
    // beat灵敏度 0~100, 默认50
    var glowBeatSens: Int = 50

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        textStyle = prefs.getInt("textStyle", 0)
        typingEffect = prefs.getBoolean("typingEffect", false)
        fontSize = prefs.getInt("fontSize", 20)
        bgAlpha = prefs.getInt("bgAlpha", 200)
        glowIntensity = prefs.getInt("glowIntensity", 50)
        glowSpread = prefs.getInt("glowSpread", 50)
        glowHueSpeed = prefs.getInt("glowHueSpeed", 30)
        glowParticles = prefs.getInt("glowParticles", 30)
        glowBeatSens = prefs.getInt("glowBeatSens", 50)
    }

    fun save() {
        prefs.edit().apply {
            putInt("textStyle", textStyle)
            putBoolean("typingEffect", typingEffect)
            putInt("fontSize", fontSize)
            putInt("bgAlpha", bgAlpha)
            putInt("glowIntensity", glowIntensity)
            putInt("glowSpread", glowSpread)
            putInt("glowHueSpeed", glowHueSpeed)
            putInt("glowParticles", glowParticles)
            putInt("glowBeatSens", glowBeatSens)
            apply()
        }
    }
}
