package com.wuming.musicFW

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.wuming.musicFW.managers.LyricsManager
import com.wuming.musicFW.models.LyricsLine
import com.wuming.musicFW.models.SongInfo
import com.wuming.musicFW.services.FloatingLyricsService
import com.wuming.musicFW.services.NetEaseMusicApi
import com.wuming.musicFW.utils.LogHelper
import com.wuming.musicFW.utils.TimeFormatter
import com.wuming.musicFW.services.NotificationListenerService
import kotlinx.coroutines.*

class MainActivity : AppCompatActivity() {

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 1001
        private const val OVERLAY_PERMISSION_CODE = 1002
    }

    private lateinit var debugLogTextView: TextView
    private lateinit var songNameTextView: TextView
    private lateinit var artistTextView: TextView
    private lateinit var albumTextView: TextView
    private lateinit var packageNameTextView: TextView
    private lateinit var positionTextView: TextView
    private lateinit var lyricsTextView: TextView
    private lateinit var listenButton: Button
    private lateinit var permissionButton: Button
    private lateinit var floatingButton: Button
    private lateinit var statusTextView: TextView

    private var isListening = false
    private var isFloatingEnabled = false
    private var currentSongInfo: SongInfo? = null
    private var currentLyrics: List<LyricsLine> = emptyList()
    private var currentPosition: Long = 0
    private var currentLyricIndex = -1
    
    private val coroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var positionUpdateJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupListeners()
        setupNotificationService()
    }

    private fun initViews() {
        debugLogTextView = findViewById(R.id.debugLogTextView)
        songNameTextView = findViewById(R.id.songNameTextView)
        artistTextView = findViewById(R.id.artistTextView)
        albumTextView = findViewById(R.id.albumTextView)
        packageNameTextView = findViewById(R.id.packageNameTextView)
        positionTextView = findViewById(R.id.positionTextView)
        lyricsTextView = findViewById(R.id.lyricsTextView)
        listenButton = findViewById(R.id.listenButton)
        permissionButton = findViewById(R.id.permissionButton)
        floatingButton = findViewById(R.id.floatingButton)
        statusTextView = findViewById(R.id.statusTextView)
    }

    private fun setupListeners() {
        listenButton.setOnClickListener {
            if (isListening) {
                stopListening()
            } else {
                startListening()
            }
        }

        permissionButton.setOnClickListener {
            requestNotificationPermission()
        }

        floatingButton.setOnClickListener {
            toggleFloating()
        }
    }

    private fun setupNotificationService() {
        NotificationListenerService.setCallbacks(
            onSongChanged = { songInfo: SongInfo ->
                runOnUiThread {
                    handleSongChanged(songInfo)
                }
            },
            onPositionUpdate = { position: Long ->
                runOnUiThread {
                    handlePositionUpdate(position)
                }
            }
        )
        
        log("应用已初始化")
    }

    private fun handleSongChanged(songInfo: SongInfo) {
        log("歌曲信息更新: ${songInfo.title} - ${songInfo.artist}")
        
        currentSongInfo = songInfo
        songNameTextView.text = "歌曲: ${songInfo.title}"
        artistTextView.text = "歌手: ${songInfo.artist}"
        albumTextView.text = "专辑: ${songInfo.album}"
        
        if (!isListening) {
            isListening = true
            updateListenButton()
        }
        
        fetchLyrics(songInfo.artist, songInfo.title)
    }

    private fun handlePositionUpdate(position: Long) {
        currentPosition = position
        positionTextView.text = "位置: ${TimeFormatter.formatTime(position)}"
        
        if (currentLyrics.isNotEmpty()) {
            val index = com.wuming.musicFW.utils.LyricsParser.findCurrentLineIndex(currentLyrics, position)
            if (index >= 0 && index != currentLyricIndex) {
                currentLyricIndex = index
                updateLyricsDisplay()
                
                if (isFloatingEnabled) {
                    val lyricText = currentLyrics[index].text
                    FloatingLyricsService.requestUpdate(this, lyricText)
                }
            }
        }
    }

    private fun fetchLyrics(artist: String, songName: String) {
        coroutineScope.launch {
            try {
                log("搜索歌词: $artist - $songName")
                val searchResult = NetEaseMusicApi.searchSong("$artist $songName")
                
                if (searchResult != null) {
                    val songId = searchResult.get("id")?.asLong
                    if (songId != null) {
                        log("找到歌曲, ID: $songId")
                        val lyricResult = NetEaseMusicApi.getLyric(songId)
                        
                        if (lyricResult != null && lyricResult.has("lrc")) {
                            val lrc = lyricResult.getAsJsonObject("lrc")
                            val lyricText = lrc.get("lyric")?.asString ?: ""
                            
                            if (lyricText.isNotEmpty()) {
                                currentLyrics = com.wuming.musicFW.utils.LyricsParser.parseLyric(lyricText)
                                log("解析歌词 ${currentLyrics.size} 行")
                                updateLyricsDisplay()
                            } else {
                                lyricsTextView.text = "暂无歌词"
                                log("暂无歌词")
                            }
                        } else {
                            lyricsTextView.text = "暂无歌词"
                            log("暂无歌词")
                        }
                    } else {
                        log("未找到歌曲")
                    }
                } else {
                    log("搜索失败")
                }
            } catch (e: Exception) {
                log("获取歌词出错: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    private fun updateLyricsDisplay() {
        if (currentLyrics.isEmpty()) {
            lyricsTextView.text = "暂无歌词"
            return
        }

        val displayText = buildString {
            for ((index, line) in currentLyrics.withIndex()) {
                val prefix = if (index == currentLyricIndex) "> " else "  "
                append("$prefix${line.text}\n")
            }
        }
        lyricsTextView.text = displayText
    }

    private fun startListening() {
        log("检查通知权限...")
        if (!checkNotificationPermission()) {
            log("需要通知权限，正在请求...")
            requestNotificationPermission()
            return
        }
        
        log("权限已授权，开始监听")
        isListening = true
        updateListenButton()
        log("开始监听通知")
    }

    private fun stopListening() {
        isListening = false
        updateListenButton()
        log("停止监听通知")
    }

    private fun updateListenButton() {
        listenButton.text = if (isListening) "停止监听" else "开始监听"
        listenButton.isEnabled = true
    }

    private fun toggleFloating() {
        if (isFloatingEnabled) {
            hideFloating()
        } else {
            showFloating()
        }
    }

    private fun showFloating() {
        if (!Settings.canDrawOverlays(this)) {
            log("需要悬浮窗权限")
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, OVERLAY_PERMISSION_CODE)
            return
        }

        log("悬浮窗权限检查通过")
        
        val lyricText = if (currentLyricIndex >= 0 && currentLyrics.isNotEmpty()) {
            currentLyrics[currentLyricIndex].text
        } else {
            "歌词加载中..."
        }

        log("准备显示悬浮窗: $lyricText")
        FloatingLyricsService.requestShow(this, lyricText)
        isFloatingEnabled = true
        floatingButton.text = "关闭悬浮"
        log("悬浮窗已开启")
    }

    private fun hideFloating() {
        FloatingLyricsService.requestHide(this)
        isFloatingEnabled = false
        floatingButton.text = "开启悬浮"
        log("悬浮窗已关闭")
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) 
                != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
        
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        startActivity(intent)
        log("请在设置中开启通知监听权限")
    }

    private fun checkNotificationPermission(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val hasPermission = enabledListeners != null && enabledListeners.contains(packageName)
        log("通知权限检查: $hasPermission")
        return hasPermission
    }

    private fun checkNotificationListenerServiceEnabled(): Boolean {
        val enabledListeners = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        val serviceName = "com.wuming.musicFW.services.NotificationListenerService"
        val isEnabled = enabledListeners != null && enabledListeners.contains(serviceName)
        log("通知监听服务状态: $isEnabled")
        return isEnabled
    }

    private fun log(message: String) {
        val timestamp = TimeFormatter.formatTimestamp(System.currentTimeMillis())
        val logMessage = "$timestamp $message\n"
        
        runOnUiThread {
            val currentText = debugLogTextView.text.toString()
            val newText = logMessage + currentText
            debugLogTextView.text = if (newText.length > 1000) {
                newText.substring(0, 1000)
            } else {
                newText
            }
        }
        
        LogHelper.d(message)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            NOTIFICATION_PERMISSION_CODE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    log("通知权限已授予")
                } else {
                    log("通知权限被拒绝")
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION_CODE -> {
                if (Settings.canDrawOverlays(this)) {
                    log("悬浮窗权限已授予")
                    showFloating()
                } else {
                    log("悬浮窗权限被拒绝")
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        coroutineScope.cancel()
        positionUpdateJob?.cancel()
        
        if (isFloatingEnabled) {
            FloatingLyricsService.requestHide(this)
        }
    }
}