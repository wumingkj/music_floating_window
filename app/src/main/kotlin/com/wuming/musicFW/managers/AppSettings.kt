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

    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)
        textStyle = prefs.getInt("textStyle", 0)
        typingEffect = prefs.getBoolean("typingEffect", false)
        fontSize = prefs.getInt("fontSize", 20)
        bgAlpha = prefs.getInt("bgAlpha", 200)
    }

    fun save() {
        prefs.edit().apply {
            putInt("textStyle", textStyle)
            putBoolean("typingEffect", typingEffect)
            putInt("fontSize", fontSize)
            putInt("bgAlpha", bgAlpha)
            apply()
        }
    }
}
