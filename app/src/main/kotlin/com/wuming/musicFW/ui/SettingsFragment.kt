package com.wuming.musicFW.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import com.wuming.musicFW.R
import com.wuming.musicFW.managers.AppSettings

class SettingsFragment : Fragment() {
    lateinit var permBtn: Button
    lateinit var overlayBtn: Button
    lateinit var wallpaperBtn: Button
    private lateinit var styleGroup: RadioGroup
    private lateinit var typingSwitch: Switch
    private lateinit var fontSizeSeek: SeekBar
    private lateinit var fontSizeLabel: TextView

    override fun onCreateView(inf: LayoutInflater, vg: ViewGroup?, si: Bundle?): View {
        val v = inf.inflate(R.layout.fragment_settings, vg, false)
        permBtn = v.findViewById(R.id.settingsPermBtn)
        overlayBtn = v.findViewById(R.id.settingsOverlayBtn)
        wallpaperBtn = v.findViewById(R.id.settingsWallpaperBtn)
        styleGroup = v.findViewById(R.id.styleGroup)
        typingSwitch = v.findViewById(R.id.typingSwitch)
        fontSizeSeek = v.findViewById(R.id.fontSizeSeek)
        fontSizeLabel = v.findViewById(R.id.fontSizeLabel)

        // 加载当前设置
        when (AppSettings.textStyle) {
            0 -> styleGroup.check(R.id.styleNormal)
            1 -> styleGroup.check(R.id.styleNeon)
            2 -> styleGroup.check(R.id.styleRainbow)
        }
        typingSwitch.isChecked = AppSettings.typingEffect
        val fs = AppSettings.fontSize - 12
        fontSizeSeek.progress = fs
        fontSizeLabel.text = "${AppSettings.fontSize}sp"

        // 事件
        styleGroup.setOnCheckedChangeListener { _, id ->
            AppSettings.textStyle = when (id) {
                R.id.styleNeon -> 1
                R.id.styleRainbow -> 2
                else -> 0
            }
            AppSettings.save()
        }
        typingSwitch.setOnCheckedChangeListener { _, on ->
            AppSettings.typingEffect = on; AppSettings.save()
        }
        fontSizeSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar?, p: Int, b: Boolean) {
                AppSettings.fontSize = p + 12; fontSizeLabel.text = "${AppSettings.fontSize}sp"
            }
            override fun onStartTrackingTouch(sb: SeekBar?) {}
            override fun onStopTrackingTouch(sb: SeekBar?) { AppSettings.save() }
        })
        return v
    }
}
