package com.wuming.musicFW

import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.wuming.musicFW.managers.PermissionManager
import com.wuming.musicFW.models.LyricsLine
import com.wuming.musicFW.models.SongInfo
import com.wuming.musicFW.services.FloatingLyricsService
import com.wuming.musicFW.services.MediaNotificationService
import com.wuming.musicFW.services.NetEaseMusicApi
import com.wuming.musicFW.utils.LogHelper
import com.wuming.musicFW.utils.LyricsParser
import com.wuming.musicFW.utils.TimeFormatter
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    private lateinit var debugLog: TextView
    private lateinit var songName: TextView
    private lateinit var artist: TextView
    private lateinit var album: TextView
    private lateinit var positionTv: TextView
    private lateinit var lyricsTv: TextView
    private lateinit var listenBtn: Button
    private lateinit var permissionBtn: Button
    private lateinit var floatingBtn: Button
    private lateinit var statusTv: TextView

    private var listening = false
    private var floating = false
    private var currentSong: SongInfo? = null
    private var lyrics: List<LyricsLine> = emptyList()
    private var lyricIdx = -1
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        initViews()
        setupListeners()
        MediaNotificationService.setCallbacks(
            onSongChanged = { s -> runOnUiThread { onSongChange(s) } },
            onPositionUpdate = { p -> runOnUiThread { onPosition(p) } }
        )
        log("应用已初始化")
    }

    private fun initViews() {
        debugLog = findViewById(R.id.debugLogTextView)
        songName = findViewById(R.id.songNameTextView)
        artist = findViewById(R.id.artistTextView)
        album = findViewById(R.id.albumTextView)
        positionTv = findViewById(R.id.positionTextView)
        lyricsTv = findViewById(R.id.lyricsTextView)
        listenBtn = findViewById(R.id.listenButton)
        permissionBtn = findViewById(R.id.permissionButton)
        floatingBtn = findViewById(R.id.floatingButton)
        statusTv = findViewById(R.id.statusTextView)
    }

    private fun setupListeners() {
        listenBtn.setOnClickListener {
            if (listening) stopListening() else startListening()
        }
        permissionBtn.setOnClickListener {
            PermissionManager.requestPostNotificationPermission(this, this)
            PermissionManager.openNotificationListenerSettings(this)
        }
        floatingBtn.setOnClickListener { toggleFloating() }
    }

    // ── 歌曲 / 位置回调 ──────────────────────────────────

    private fun onSongChange(s: SongInfo) {
        log("歌曲: ${s.title} - ${s.artist}")
        currentSong = s
        songName.text = s.title
        artist.text = s.artist
        album.text = s.album
        if (!listening) { listening = true; updateListenText() }
        fetchLyrics(s.artist, s.title)
    }

    private fun onPosition(pos: Long) {
        positionTv.text = TimeFormatter.formatTime(pos)
        if (lyrics.isEmpty()) return
        val idx = LyricsParser.findCurrentLineIndex(lyrics, pos)
        if (idx < 0 || idx == lyricIdx) return
        lyricIdx = idx
        renderLyrics()
        if (floating) FloatingLyricsService.requestUpdate(this, lyrics[idx].text, currentSong?.title ?: "")
    }

    // ── 歌词 ─────────────────────────────────────────────

    private fun fetchLyrics(art: String, name: String) {
        scope.launch {
            try {
                log("搜索歌词: $art - $name")
                val result = NetEaseMusicApi.searchSong("$art $name") ?: run {
                    log("搜索失败"); return@launch
                }
                val id = result.get("id")?.asLong ?: run { log("未找到歌曲 ID"); return@launch }
                log("找到歌曲 ID: $id")
                val lyricJson = NetEaseMusicApi.getLyric(id) ?: run { log("获取歌词失败"); return@launch }
                val lrc = lyricJson.getAsJsonObject("lrc") ?: run {
                    lyricsTv.text = "暂无歌词"; return@launch
                }
                val text = lrc.get("lyric")?.asString ?: ""
                if (text.isEmpty()) { lyricsTv.text = "暂无歌词"; return@launch }
                lyrics = LyricsParser.parseLyric(text)
                log("歌词 ${lyrics.size} 行")
                renderLyrics()
            } catch (e: Exception) {
                log("歌词出错: ${e.message}")
            }
        }
    }

    private fun renderLyrics() {
        if (lyrics.isEmpty()) { lyricsTv.text = "暂无歌词"; return }
        lyricsTv.text = buildString {
            for ((i, l) in lyrics.withIndex()) {
                append(if (i == lyricIdx) "▸ " else "  ")
                append(l.text).append('\n')
            }
        }
    }

    // ── 监听控制 ─────────────────────────────────────────

    private fun startListening() {
        if (!PermissionManager.hasNotificationListenerPermission(this)) {
            log("未开启通知监听权限")
            PermissionManager.openNotificationListenerSettings(this)
            return
        }
        listening = true
        updateListenText()
        log("开始监听通知")
    }

    private fun stopListening() {
        listening = false
        updateListenText()
        log("停止监听")
    }

    private fun updateListenText() {
        listenBtn.text = if (listening) "停止监听" else "开始监听"
    }

    // ── 悬浮窗 ───────────────────────────────────────────

    private fun toggleFloating() {
        if (floating) hideFloating() else showFloating()
    }

    private fun showFloating() {
        if (!PermissionManager.hasOverlayPermission(this)) {
            log("需要悬浮窗权限")
            PermissionManager.openOverlaySettings(this)
            return
        }
        val text = if (lyricIdx >= 0 && lyrics.isNotEmpty()) lyrics[lyricIdx].text
                   else "歌词加载中..."
        FloatingLyricsService.requestShow(this, text, currentSong?.title ?: "")
        floating = true
        floatingBtn.text = "关闭悬浮"
        log("悬浮窗已开启")
    }

    private fun hideFloating() {
        FloatingLyricsService.requestHide(this)
        floating = false
        floatingBtn.text = "开启悬浮"
        log("悬浮窗已关闭")
    }

    // ── 日志 ─────────────────────────────────────────────

    private fun log(msg: String) {
        val ts = TimeFormatter.formatTimestamp(System.currentTimeMillis())
        runOnUiThread {
            val s = "$ts $msg\n"
            val cur = debugLog.text.toString()
            debugLog.text = if (cur.length > 1000) (s + cur).substring(0, 1000) else s + cur
        }
        LogHelper.d(msg)
    }

    override fun onDestroy() {
        scope.cancel()
        positionJob?.cancel()
        if (floating) FloatingLyricsService.requestHide(this)
        super.onDestroy()
    }
}