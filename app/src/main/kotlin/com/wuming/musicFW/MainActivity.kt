package com.wuming.musicFW

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
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
    private var listening = false
    private var floating = false
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
            // 刷新缓冲区到界面
            flushLogBuffer()
        }, 300)

        MediaNotificationService.setCallbacks(
            onSongChanged = { s -> runOnUiThread { onSongChange(s) } },
            onPositionUpdate = { p -> runOnUiThread { onPosition(p) } },
            onLyricLine = { lyric -> runOnUiThread { onNotifyLyric(lyric) } },
            onAlbumArtChanged = { bmp -> runOnUiThread { onAlbumArt(bmp) } }
        )
        FloatingLyricsService.onCloseCallback = {
            runOnUiThread { floating = false; adapter.getMusic()?.floatingBtn?.text = "开启悬浮"; log("悬浮窗已关闭") }
        }
        log("应用已初始化")
    }

    private fun bindFragments() {
        val m = adapter.getMusic()
        val s = adapter.getSettings()
        m?.listenBtn?.setOnClickListener { if (listening) stopListening() else startListening() }
        m?.permissionBtn?.setOnClickListener {
            PermissionManager.requestPostNotificationPermission(this, this)
            PermissionManager.openNotificationListenerSettings(this)
        }
        m?.floatingBtn?.setOnClickListener { toggleFloating() }
        s?.permBtn?.setOnClickListener {
            PermissionManager.requestPostNotificationPermission(this, this)
            PermissionManager.openNotificationListenerSettings(this)
        }
        s?.overlayBtn?.setOnClickListener { PermissionManager.openOverlaySettings(this) }
        s?.wallpaperBtn?.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_SET_WALLPAPER).apply {
                    component = android.content.ComponentName(
                        this@MainActivity, com.wuming.musicFW.services.MusicWallpaperService::class.java
                    )
                }
                startActivity(intent)
            } catch (_: Exception) {
                startActivity(Intent(Intent.ACTION_SET_WALLPAPER))
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
        if (!listening) { listening = true; updateMusicBtn() }
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
    }

    // ★ 从通知正文直接获取的当前歌词行（实时、准确）
    private var lastNotifyLyric = ""
    private fun onNotifyLyric(lyric: String) {
        if (lyric == lastNotifyLyric) return
        lastNotifyLyric = lyric
        log("通知歌词: $lyric")
        adapter.getMusic()?.lyricsTv?.text = "当前歌词:\n$lyric"
        if (floating) FloatingLyricsService.requestUpdate(this, lyric, currentSong?.title ?: "")
        MusicWallpaperService.updateLyric(lyric)
    }

    private fun fetchLyrics(art: String, name: String) {
        scope.launch {
            try {
                log("搜索歌词: $art - $name")
                val result = NetEaseMusicApi.searchSong("$art $name") ?: run {
                    log("搜索失败"); adapter.getMusic()?.lyricsTv?.text = "暂无歌词"; return@launch
                }
                val id = result.get("id")?.asLong ?: run { log("未找到歌曲 ID"); return@launch }
                log("找到歌曲 ID: $id")
                val lyricJson = NetEaseMusicApi.getLyric(id) ?: run {
                    log("获取歌词失败-接口返回null"); adapter.getMusic()?.lyricsTv?.text = "暂无歌词"; return@launch
                }
                val lrc = lyricJson.getAsJsonObject("lrc") ?: run {
                    log("歌词无 lrc 字段: ${lyricJson?.toString()?.take(200)}")
                    adapter.getMusic()?.lyricsTv?.text = "暂无歌词"; return@launch
                }
                val text = lrc.get("lyric")?.asString ?: ""
                if (text.isEmpty()) { adapter.getMusic()?.lyricsTv?.text = "暂无歌词"; return@launch }
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

    private fun startListening() {
        if (!PermissionManager.hasNotificationListenerPermission(this)) {
            log("未开启通知监听权限"); PermissionManager.openNotificationListenerSettings(this); return
        }
        listening = true; updateMusicBtn(); log("开始监听通知")
    }
    private fun stopListening() { listening = false; updateMusicBtn(); log("停止监听") }
    private fun updateMusicBtn() { adapter.getMusic()?.listenBtn?.text = if (listening) "停止监听" else "开始监听" }

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

    private fun log(msg: String) {
        val ts = TimeFormatter.formatTimestamp(System.currentTimeMillis())
        val line = "$ts $msg\n"
        // 始终输出到 logcat（adb 可看）
        LogHelper.d(msg)
        runOnUiThread {
            val f = adapter.getLog()
            if (f != null) {
                val cur = f.debugLog.text.toString()
                f.debugLog.text = if (cur.length > 3000) (line + cur).substring(0, 3000) else "$line$cur"
            } else {
                // fragment 未就绪 → 入缓冲区
                synchronized(logBuffer) { logBuffer.add(line) }
            }
        }
    }

    override fun onDestroy() { scope.cancel(); if (floating) FloatingLyricsService.requestHide(this); super.onDestroy() }
}
