package com.wuming.musicFW

import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.tabs.TabLayoutMediator
import com.wuming.musicFW.managers.AppSettings
import com.wuming.musicFW.managers.PermissionManager
import com.wuming.musicFW.models.LyricsLine
import com.wuming.musicFW.models.SongInfo
import com.wuming.musicFW.services.FloatingLyricsService
import com.wuming.musicFW.services.MediaNotificationService
import com.wuming.musicFW.services.MusicWallpaperService
import com.wuming.musicFW.services.NetEaseMusicApi
import com.wuming.musicFW.ui.MainPagerAdapter
import com.wuming.musicFW.utils.LogHelper
import com.wuming.musicFW.utils.LyricsParser
import com.wuming.musicFW.utils.TimeFormatter
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {
    private lateinit var adapter: MainPagerAdapter
    private var floating = false
    private var wallpaperBtnRef: android.widget.Button? = null
    // ActivityResultLauncher 替代废弃的 startActivityForResult
    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // 通过 Intent 传递授权数据到 Service（比静态变量更可靠）
                val intent = Intent(this, MusicWallpaperService::class.java).apply {
                    action = "AUDIO_CAPTURE"
                    putExtra("resultCode", result.resultCode)
                    putExtra("resultData", result.data)
                }
                startForegroundService(intent)
                log("内录授权成功！实时音频已启动")
            }
        } else {
            log("内录授权被拒绝", "WARN")
        }
    }
    private var currentSong: SongInfo? = null
    private var lyrics: List<LyricsLine> = emptyList()
    private var lyricIdx = -1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    // 日志缓冲区：fragment 未就绪时暂存
    private val logBuffer = mutableListOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AppSettings.init(this)
        setContentView(R.layout.activity_main)
        val pager = findViewById<androidx.viewpager2.widget.ViewPager2>(R.id.viewPager)
        val tabs = findViewById<com.google.android.material.tabs.TabLayout>(R.id.tabLayout)
        // 预创建所有 fragment（日志页立即可用）
        pager.offscreenPageLimit = 2
        adapter = MainPagerAdapter(this)
        pager.adapter = adapter
        TabLayoutMediator(tabs, pager) { tab, pos ->
            tab.text = when (pos) { 0 -> "\uD83C\uDFB5 音乐"; 1 -> "\uD83D\uDCCB 日志"; 2 -> "\u2699 设置"; else -> "" }
        }.attach()

        pager.postDelayed({
            bindFragments()
            flushLogBuffer()
        }, 300)

        // ★ 通过 MediaSession 获取音乐数据（NotificationListenerService 提供跨 App 访问权限）
        //    系统会在用户授予通知监听权限后自动启动服务
        MediaNotificationService.setCallbacks(
            onSongChanged = { s -> runOnUiThread { onSongChange(s) } },
            onPositionUpdate = { p -> runOnUiThread { onPosition(p) } },
            onLyricLine = { lyric -> runOnUiThread { onNotifyLyric(lyric) } },
            onAlbumArtChanged = { bmp -> runOnUiThread { onAlbumArt(bmp) } },
            onLog = { msg, level -> runOnUiThread { log("[MediaSession] $msg", level) } }
        )
        // 触发扫描（延迟等待服务就绪）
        pager.postDelayed({
            MediaNotificationService.triggerScan(this)
            log("已触发首次 MediaSession 扫描")
        }, 1000)

        FloatingLyricsService.onCloseCallback = {
            runOnUiThread { floating = false; adapter.getMusic()?.floatingBtn?.text = "开启悬浮"; log("悬浮窗已关闭") }
        }
        log("应用已初始化")
    }

    private fun bindFragments() {
        val m = adapter.getMusic()
        val s = adapter.getSettings()
        m?.floatingBtn?.setOnClickListener { toggleFloating() }
        s?.permBtn?.setOnClickListener {
            PermissionManager.openNotificationListenerSettings(this)
            log("请在设置中找到\"Music Floating Window\"并开启通知监听权限")
        }
        s?.overlayBtn?.setOnClickListener { PermissionManager.openOverlaySettings(this) }
        s?.wallpaperBtn?.setOnClickListener {
            if (MusicWallpaperService.isRunning()) {
                val intent = Intent(this@MainActivity, MusicWallpaperService::class.java).apply {
                    action = "STOP"
                }
                startService(intent)
                s.wallpaperBtn.text = "开启屏幕光晕"
                log("屏幕光晕已关闭")
            } else {
                if (!PermissionManager.hasOverlayPermission(this@MainActivity)) {
                    log("需要悬浮窗权限以显示屏幕光晕")
                    PermissionManager.openOverlaySettings(this@MainActivity)
                    return@setOnClickListener
                }
                // 请求录音权限以获取真实音频 (拒绝后仍可用模拟节拍)
                if (!PermissionManager.hasRecordAudioPermission(this@MainActivity)) {
                    PermissionManager.requestRecordAudioPermission(this@MainActivity)
                }
                log("正在启动 MusicWallpaperService...")
                val intent = Intent(this@MainActivity, MusicWallpaperService::class.java)
                android.util.Log.d("MainActivity", "启动服务: $intent")
                log("屏幕光晕已开启")
                // 请求 AudioPlaybackCapture 授权 (Android 10+)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                        captureLauncher.launch(mpm.createScreenCaptureIntent())
                        log("请授权屏幕录制以获取实时音频")
                    } catch (e: Exception) {
                        log("启动内录授权失败: ${e.message}")
                    }
                } else {
                    log("Android 10+ 才支持实时音频内录")
                }
            }
        }
        log("界面已绑定")
    }

    private fun flushLogBuffer() {
        val f = adapter.getLog() ?: return
        synchronized(logBuffer) {
            for (line in logBuffer) {
                val cur = f.debugLog.text.toString()
                f.debugLog.text = if (cur.length > 3000) (line + cur).substring(0, 3000) else "$line$cur"
            }
            logBuffer.clear()
        }
    }

    private fun onSongChange(s: SongInfo) {
        log("歌曲: ${s.title} - ${s.artist}")
        currentSong = s
        adapter.getMusic()?.let { m ->
            m.songName.text = s.title; m.artist.text = s.artist; m.album.text = s.album
            if (s.albumArt != null) m.albumArtIv.setImageBitmap(s.albumArt)
        }
        if (floating && s.albumArt != null) {
            FloatingLyricsService.requestUpdateArt(this, s.albumArt)
        }
        MusicWallpaperService.updateSongInfo(s.title, s.artist, s.albumArt)
        MusicWallpaperService.updatePlayingState(true)
        fetchLyrics(s.artist, s.title)
    }

    private fun onAlbumArt(bmp: android.graphics.Bitmap?) {
        adapter.getMusic()?.albumArtIv?.setImageBitmap(bmp)
        if (floating) FloatingLyricsService.requestUpdateArt(this, bmp)
        MusicWallpaperService.albumArt = bmp
    }

    private fun onPosition(pos: Long) {
        adapter.getMusic()?.positionTv?.text = TimeFormatter.formatTime(pos)
        if (lyrics.isEmpty()) return
        val idx = LyricsParser.findCurrentLineIndex(lyrics, pos)
        if (idx == lyricIdx) return
        lyricIdx = idx; renderLyrics()
        if (floating && idx >= 0) FloatingLyricsService.requestUpdate(this, lyrics[idx].text, currentSong?.title ?: "")
        if (idx >= 0) MusicWallpaperService.updateLyric(lyrics[idx].text)
        // ★ 动态发光：使用MusicWallpaperService的beat数据驱动光晕效果
        if (floating) {
            try {
                // 通过反射获取MusicWallpaperService的beat数据
                val wallpaperClass = Class.forName("com.wuming.musicFW.services.MusicWallpaperService")
                val beatField = wallpaperClass.getDeclaredField("beat")
                beatField.isAccessible = true
                val beat = beatField.get(null)
                val energyField = beat.javaClass.getDeclaredField("energy")
                energyField.isAccessible = true
                val energy = energyField.getFloat(beat)
                
                // 通过反射获取FloatingLyricsService实例并设置光晕级别
                val serviceClass = Class.forName("com.wuming.musicFW.services.FloatingLyricsService")
                val instanceField = serviceClass.getDeclaredField("instance")
                instanceField.isAccessible = true
                val instance = instanceField.get(null)
                if (instance != null) {
                    val lyricTvField = serviceClass.getDeclaredField("lyricTv")
                    lyricTvField.isAccessible = true
                    val lyricTv = lyricTvField.get(instance)
                    if (lyricTv != null) {
                        val setLevelMethod = lyricTv.javaClass.getMethod("setMusicLevel", Float::class.java)
                        setLevelMethod.invoke(lyricTv, energy)
                    }
                }
            } catch (e: Exception) {
                // 如果反射失败，使用简单的模拟效果
                val level = ((pos % 2000) / 2000f).let { x -> 0.5f + 0.5f * kotlin.math.sin(x * Math.PI * 2).toFloat() }
                try {
                    val serviceClass = Class.forName("com.wuming.musicFW.services.FloatingLyricsService")
                    val instanceField = serviceClass.getDeclaredField("instance")
                    instanceField.isAccessible = true
                    val instance = instanceField.get(null)
                    if (instance != null) {
                        val lyricTvField = serviceClass.getDeclaredField("lyricTv")
                        lyricTvField.isAccessible = true
                        val lyricTv = lyricTvField.get(instance)
                        if (lyricTv != null) {
                            val setLevelMethod = lyricTv.javaClass.getMethod("setMusicLevel", Float::class.java)
                            setLevelMethod.invoke(lyricTv, level)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // ★ 从 MediaSession metadata 获取的歌词行
    private var lastNotifyLyric = ""
    private fun onNotifyLyric(lyric: String) {
        if (lyric == lastNotifyLyric) return
        lastNotifyLyric = lyric
        log("媒体歌词: $lyric")
        adapter.getMusic()?.lyricsTv?.text = "当前歌词:\n$lyric"
        if (floating) FloatingLyricsService.requestUpdate(this, lyric, currentSong?.title ?: "")
        MusicWallpaperService.updateLyric(lyric)
    }

    private fun fetchLyrics(art: String, name: String) {
        // 清空上次歌词，防止串歌
        lyrics = emptyList()
        lyricIdx = -1
        lastNotifyLyric = ""
        scope.launch {
            try {
                log("搜索歌词: $art - $name")
                val result = NetEaseMusicApi.searchSong("$art $name") ?: run {
                    log("搜索失败"); adapter.getMusic()?.lyricsTv?.text = "暂无歌词"; clearFloatingLyrics(); return@launch
                }
                val id = result.get("id")?.asLong ?: run { log("未找到歌曲 ID"); return@launch }
                log("找到歌曲 ID: $id")
                val lyricJson = NetEaseMusicApi.getLyric(id) ?: run {
                    log("获取歌词失败-接口返回null"); adapter.getMusic()?.lyricsTv?.text = "暂无歌词"; clearFloatingLyrics(); return@launch
                }
                val lrc = lyricJson.getAsJsonObject("lrc") ?: run {
                    log("歌词无 lrc 字段: ${lyricJson?.toString()?.take(200)}")
                    adapter.getMusic()?.lyricsTv?.text = "暂无歌词"; clearFloatingLyrics(); return@launch
                }
                val text = lrc.get("lyric")?.asString ?: ""
                if (text.isEmpty()) {
                    adapter.getMusic()?.lyricsTv?.text = "纯音乐，暂无歌词"
                    clearFloatingLyrics()
                    return@launch
                }
                lyrics = LyricsParser.parseLyric(text)
                log("歌词 ${lyrics.size} 行"); renderLyrics()
            } catch (e: Exception) { log("歌词出错: ${e.message}") }
        }
    }

    private fun renderLyrics() {
        val m = adapter.getMusic() ?: return
        if (lyrics.isEmpty()) { m.lyricsTv.text = "暂无歌词"; return }
        val safeIdx = if (lyricIdx in lyrics.indices) lyricIdx else 0
        val padLines = 5 // 上下留白行数，让首尾歌词也能居中
        val sb = StringBuilder()
        // 顶部留白
        repeat(padLines) { sb.append("<br>") }
        for ((i, l) in lyrics.withIndex()) {
            val ts = TimeFormatter.formatTime(l.time)
            val text = l.text
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
            if (i == safeIdx) {
                sb.append("<font color='#6200EE'><big><b> ▸ [$ts] $text</b></big></font>")
            } else if (i < safeIdx) {
                sb.append("<font color='#999999'>   [$ts] $text</font>")
            } else {
                sb.append("<font color='#424242'>   [$ts] $text</font>")
            }
            sb.append("<br>")
        }
        // 底部留白
        repeat(padLines) { sb.append("<br>") }
        try {
            m.lyricsTv.text = android.text.Html.fromHtml(sb.toString(), android.text.Html.FROM_HTML_MODE_LEGACY)
        } catch (e: Exception) {
            m.lyricsTv.text = buildString {
                repeat(padLines) { append("\n") }
                for ((i, l) in lyrics.withIndex()) {
                    append(if (i == safeIdx) "▸ " else "  ")
                    append("[${TimeFormatter.formatTime(l.time)}] ${l.text}\n")
                }
                repeat(padLines) { append("\n") }
            }
        }
        m.lyricsTv.post {
            val lineIdx = safeIdx + padLines
            val layout = m.lyricsTv.layout ?: return@post
            // 当前行垂直中心
            val lineCenter = (layout.getLineTop(lineIdx) + layout.getLineBottom(lineIdx)) / 2f
            // 父控件 ScrollView 可见区域高度（去掉 padding）
            val parentVisible = m.lyricsScroll.height - m.lyricsScroll.paddingTop - m.lyricsScroll.paddingBottom
            val scrollY = (lineCenter - parentVisible / 2f).toInt().coerceAtLeast(0)
            m.lyricsScroll.smoothScrollTo(0, scrollY)
        }
    }

    private fun toggleFloating() { if (floating) hideFloating() else showFloating() }
    private fun showFloating() {
        if (!PermissionManager.hasOverlayPermission(this)) {
            log("需要悬浮窗权限"); PermissionManager.openOverlaySettings(this); return
        }
        val text = if (lyricIdx >= 0 && lyrics.isNotEmpty()) lyrics[lyricIdx].text else "歌词加载中..."
        FloatingLyricsService.requestShow(this, text, currentSong?.title ?: "")
        floating = true; adapter.getMusic()?.floatingBtn?.text = "关闭悬浮"; log("悬浮窗已开启")
    }
    private fun hideFloating() {
        FloatingLyricsService.requestHide(this); floating = false
        adapter.getMusic()?.floatingBtn?.text = "开启悬浮"; log("悬浮窗已关闭")
    }

    private fun clearFloatingLyrics() {
        if (floating) FloatingLyricsService.requestUpdate(this, "暂无歌词", currentSong?.title ?: "")
        MusicWallpaperService.updateLyric("")
    }

    /** 写入 UI 日志，支持级别: INFO, VERBOSE, WARN, ERROR */
    private fun log(msg: String, level: String = "INFO") {
        val ts = TimeFormatter.formatTimestamp(System.currentTimeMillis())
        val prefix = when (level) {
            "VERBOSE" -> "  "
            "WARN" -> "⚠ "
            "ERROR" -> "❌ "
            else -> ""
        }
        val line = "$ts $prefix$msg\n"
        // 输出到 logcat
        when (level) {
            "ERROR" -> LogHelper.e(msg)
            "WARN" -> LogHelper.w(msg)
            else -> LogHelper.d(msg)
        }
        runOnUiThread {
            val f = adapter.getLog()
            if (f != null) {
                val cur = f.debugLog.text.toString()
                f.debugLog.text = if (cur.length > 3000) (line + cur).substring(0, 3000) else "$line$cur"
            } else {
                synchronized(logBuffer) { logBuffer.add(line) }
            }
        }
    }

    override fun onDestroy() { scope.cancel(); if (floating) FloatingLyricsService.requestHide(this); super.onDestroy() }
}