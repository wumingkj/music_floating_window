package com.wuming.musicFW.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.audiofx.Visualizer
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.*
import com.wuming.musicFW.managers.AppSettings
import com.wuming.musicFW.utils.LogHelper
import kotlin.math.*
import kotlin.random.Random

class MusicWallpaperService : Service() {

    companion object {
        private var instance: MusicWallpaperService? = null
        fun isRunning() = instance != null

        var songTitle = ""
        var songArtist = ""
        var albumArt: Bitmap? = null
        var isPlaying = false
        var currentLyric = ""

        fun updateSongInfo(title: String, artist: String, art: Bitmap?) {
            songTitle = title
            songArtist = artist
            albumArt = art
        }

        fun updateLyric(lyric: String) {
            currentLyric = lyric
        }

        fun updatePlayingState(playing: Boolean) {
            isPlaying = playing
        }

        private const val CHANNEL_ID = "edge_glow"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: GlowView? = null
    private lateinit var windowManager: WindowManager

    private var frame = 0
    private var colorHue = 0f
    private val particles = mutableListOf<Particle>()
    private val beat = BeatData()
    private val ripples = mutableListOf<Ripple>()
    private var drawing = false

    // ═══ 真实音频分析 ═══
    private var visualizer: Visualizer? = null
    private var fftData: ByteArray? = null
    private var realAudio = false
    private var prevEnergySmooth = 0f

    // 模拟节拍回退
    private var beatTimer = 0f
    private var nextBeatAt = 20f

    data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var radius: Float, var alpha: Float,
        var color: Int, var life: Float, var maxLife: Float
    )

    data class BeatData(
        var bass: Float = 0f,
        var mid: Float = 0f,
        var treble: Float = 0f,
        var energy: Float = 0f,
        var beatPulse: Float = 0f
    )

    data class Ripple(val startTime: Int, val hue: Float, val maxRadius: Float)

    // ═══════════════════════════════════════════
    //  Service 生命周期
    // ═══════════════════════════════════════════

    override fun onCreate() {
        super.onCreate()
        instance = this
        
        android.util.Log.d("MusicWallpaperService", "=== MusicWallpaperService onCreate ===")
        
        // 初始化设置
        AppSettings.init(this)
        android.util.Log.d("MusicWallpaperService", "Service created, settings: intensity=${AppSettings.glowIntensity}, speed=${AppSettings.glowHueSpeed}")
        
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        createNotificationChannel()
        startForeground(1, buildNotification())
        android.util.Log.d("MusicWallpaperService", "前台服务已启动")
        
        setupVisualizer()
        showOverlay()
        
        android.util.Log.d("MusicWallpaperService", "=== MusicWallpaperService onCreate 完成 ===")
    }

    override fun onDestroy() {
        instance = null
        drawing = false
        handler.removeCallbacks(drawRunnable)
        visualizer?.release()
        visualizer = null
        removeOverlay()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        android.util.Log.d("MusicWallpaperService", "=== onStartCommand ===")
        android.util.Log.d("MusicWallpaperService", "action: ${intent?.action}")
        android.util.Log.d("MusicWallpaperService", "startId: $startId")
        
        if (intent?.action == "STOP") {
            android.util.Log.d("MusicWallpaperService", "收到 STOP 命令，停止服务")
            stopSelf()
            return START_NOT_STICKY
        }
        
        android.util.Log.d("MusicWallpaperService", "服务正在运行")
        return START_STICKY
    }

    // ═══════════════════════════════════════════
    //  通知渠道
    // ═══════════════════════════════════════════

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "屏幕光晕", NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "音乐边框光晕效果"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val status = when {
            realAudio -> "🎵 实时音频"
            isPlaying -> "🎵 模拟节拍 (授权录音可获实时)"
            else -> "⏸ 待机中"
        }
        val text = if (isPlaying) "$songTitle - $songArtist" else "光晕待机中"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕光晕 · $status")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .build()
        } else {
            Notification.Builder(this)
                .setContentTitle("屏幕光晕 · $status")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setPriority(Notification.PRIORITY_LOW)
                .build()
        }
    }

    // ═══════════════════════════════════════════
    //  真实音频可视化 (Visualizer)
    // ═══════════════════════════════════════════

    private fun setupVisualizer() {
        // 检查录音权限
        val hasRecordPermission = android.Manifest.permission.RECORD_AUDIO.let { perm ->
            android.content.pm.PackageManager.PERMISSION_GRANTED == checkSelfPermission(perm)
        }
        LogHelper.d("录音权限: $hasRecordPermission")
        android.util.Log.d("MusicWallpaperService", "录音权限: $hasRecordPermission")
        
        if (!hasRecordPermission) {
            LogHelper.e("缺少录音权限，无法使用 Visualizer")
            realAudio = false
            return
        }
        
        // 1️⃣ 先试全局混音 Visualizer(0)
        var sessionId = 0
        var vis: Visualizer? = null
        try {
            vis = Visualizer(0)
            LogHelper.d("Visualizer(0) 全局混音创建成功")
        } catch (e: Exception) {
            LogHelper.e("Visualizer(0) 失败: ${e.message}")
            // 2️⃣ 延迟重试，等 MediaNotificationService 就绪后再取 session ID
            retryVisualizerWithSession(1)
        }
        
        if (vis == null) {
            realAudio = false
            return
        }
        
        // 初始化成功
        try {
            val captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(2048)
            vis.captureSize = captureSize
            fftData = ByteArray(captureSize)
            
            vis.setDataCaptureListener(
                object : Visualizer.OnDataCaptureListener {
                    override fun onWaveFormDataCapture(
                        visualizer: Visualizer?, waveform: ByteArray?, samplingRate: Int
                    ) {}

                    override fun onFftDataCapture(
                        visualizer: Visualizer?, fft: ByteArray?, samplingRate: Int
                    ) {
                        fft?.let { src ->
                            fftData?.let { dst -> src.copyInto(dst, 0, 0, minOf(src.size, dst.size)) }
                        }
                        if (frame % 30 == 0) {
                            val sampleBins = (0 until minOf(8, fft?.let { it.size / 2 } ?: 0)).joinToString(", ") { i ->
                                String.format("%.2f", fft?.let { fftMagnitude(it, i) } ?: 0f)
                            }
                            LogHelper.d("FFT[session=$sessionId]: [$sampleBins]")
                        }
                    }
                },
                Visualizer.getMaxCaptureRate(),
                true, false // waveform=false, fft=true
            )
            vis.enabled = true
            visualizer = vis
            realAudio = true
            LogHelper.d("Visualizer 已启用 (session=$sessionId)，实时音频模式")
        } catch (e: Exception) {
            vis.release()
            realAudio = false
            LogHelper.e("Visualizer 初始化失败: ${e.message}")
        }
    }

    /** 延迟重试：等 MediaNotificationService 就绪后通过 MediaController 取 session ID */
    private fun retryVisualizerWithSession(attempt: Int) {
        if (attempt > 5) { // 最多重试5次
            LogHelper.e("Visualizer 重试 $attempt 次后放弃，使用模拟节拍")
            return
        }
        handler.postDelayed({
            try {
                val sessionId = com.wuming.musicFW.services.MediaNotificationService.getAudioSessionId()
                LogHelper.d("重试 #$attempt: 获取到 sessionId=$sessionId")
                if (sessionId > 0) {
                    val vis = Visualizer(sessionId)
                    val captureSize = Visualizer.getCaptureSizeRange()[1].coerceAtMost(2048)
                    vis.captureSize = captureSize
                    fftData = ByteArray(captureSize)
                    vis.setDataCaptureListener(
                        object : Visualizer.OnDataCaptureListener {
                            override fun onWaveFormDataCapture(v: Visualizer?, w: ByteArray?, r: Int) {}
                            override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, r: Int) {
                                fft?.let { src ->
                                    fftData?.let { dst -> src.copyInto(dst, 0, 0, minOf(src.size, dst.size)) }
                                }
                                if (frame % 30 == 0) {
                                    val sample = (0 until minOf(8, fft?.let { it.size / 2 } ?: 0)).joinToString(", ") { i ->
                                        String.format("%.2f", fft?.let { fftMagnitude(it, i) } ?: 0f)
                                    }
                                    LogHelper.d("FFT[session=$sessionId]: [$sample]")
                                }
                            }
                        },
                        Visualizer.getMaxCaptureRate(),
                        true, false
                    )
                    vis.enabled = true
                    visualizer = vis
                    realAudio = true
                    LogHelper.d("Visualizer($sessionId) 延迟重试成功！实时音频模式")
                } else {
                    retryVisualizerWithSession(attempt + 1)
                }
            } catch (e: Exception) {
                LogHelper.e("重试 #$attempt 失败: ${e.message}")
                retryVisualizerWithSession(attempt + 1)
            }
        }, 2000L) // 每2秒重试一次
    }

    // ═══════════════════════════════════════════
    //  透明覆盖层管理
    // ═══════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun showOverlay() {
        try {
            val glowView = GlowView(this)
            overlayView = glowView

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            )
            params.gravity = Gravity.TOP or Gravity.START
            windowManager.addView(glowView, params)

            glowView.post {
                initParticles(glowView.width.toFloat(), glowView.height.toFloat())
                drawing = true
                android.util.Log.d("MusicWallpaperService", "Overlay shown, drawing=$drawing, view size: ${glowView.width}x${glowView.height}")
                handler.post(drawRunnable)
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicWallpaperService", "Failed to show overlay: ${e.message}", e)
            drawing = false
        }
    }

    private fun removeOverlay() {
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        overlayView = null
    }

    // ═══════════════════════════════════════════
    //  动画循环
    // ═══════════════════════════════════════════

    private val drawRunnable = object : Runnable {
        override fun run() {
            if (!drawing) {
                android.util.Log.d("MusicWallpaperService", "DrawRunnable stopped: drawing=$drawing")
                return
            }
            frame++
            colorHue = (colorHue + AppSettings.glowHueSpeed * 0.005f) % 360f
            updateBeat()
            // 每60帧更新一次通知（显示实时状态）
            if (frame % 60 == 0) {
                try {
                    val nm = getSystemService(NotificationManager::class.java)
                    nm?.notify(1, buildNotification())
                } catch (_: Exception) {}
            }
            val view = overlayView
            if (view == null) {
                android.util.Log.w("MusicWallpaperService", "DrawRunnable: overlayView is null")
                handler.postDelayed(this, 100)
                return
            }
            val w = view.width.toFloat()
            val h = view.height.toFloat()
            if (w <= 0f || h <= 0f) {
                android.util.Log.d("MusicWallpaperService", "DrawRunnable: invalid view size: ${view.width}x${view.height}")
                handler.postDelayed(this, 100)
                return
            }
            updateParticles(w, h)
            view.invalidate()
            handler.postDelayed(this, 33) // ~30fps
        }
    }

    // ═══════════════════════════════════════════
    //  粒子系统 (精简)
    // ═══════════════════════════════════════════

    private fun initParticles(w: Float, h: Float) {
        particles.clear()
        val count = AppSettings.glowParticles
        val initCount = (count * 0.8f).toInt().coerceAtLeast(5)
        for (i in 0 until initCount) {
            particles.add(Particle(
                x = Random.nextFloat() * w,
                y = Random.nextFloat() * h,
                vx = (Random.nextFloat() - 0.5f) * 0.3f,
                vy = -Random.nextFloat() * 0.4f - 0.05f,
                radius = Random.nextFloat() * 2f + 0.3f,
                alpha = Random.nextFloat() * 0.15f + 0.02f,
                color = Color.HSVToColor(255, arrayOf(Random.nextFloat() * 360f, 0.5f, 0.9f).toFloatArray()),
                life = Random.nextFloat() * 200f,
                maxLife = Random.nextFloat() * 200f + 100f
            ))
        }
    }

    private fun updateParticles(w: Float, h: Float) {
        for (p in particles) {
            p.life++
            if (p.life > p.maxLife || p.y < -50f || p.x < -50f || p.x > w + 50f) {
                p.x = Random.nextFloat() * w
                p.y = h + Random.nextFloat() * 100f
                p.life = 0f
                p.maxLife = Random.nextFloat() * 200f + 100f
                p.radius = Random.nextFloat() * 2f + 0.3f
                p.color = Color.HSVToColor(255, arrayOf(Random.nextFloat() * 360f, 0.5f, 0.9f).toFloatArray())
            }
            val speedMult = if (isPlaying) 1f + beat.energy * 0.5f else 0.2f
            p.x += p.vx * speedMult + sin(p.life * 0.02f) * 0.15f
            p.y += p.vy * speedMult
            val lifeRatio = p.life / p.maxLife
            p.alpha = when {
                lifeRatio < 0.1f -> lifeRatio / 0.1f
                lifeRatio > 0.8f -> (1f - lifeRatio) / 0.2f
                else -> 1f
            } * (if (isPlaying) 0.15f else 0.03f)
        }

        // beat时从四边喷射粒子
        val particleCount = AppSettings.glowParticles
        if (isPlaying && beat.beatPulse > 0.5f && particleCount > 10) {
            val burstCount = max(1, (particleCount * 0.05f).toInt())
            for (k in 0 until burstCount) {
                val side = Random.nextInt(4)
                val px = when (side) { 0 -> Random.nextFloat() * w; 1 -> w; 2 -> Random.nextFloat() * w; else -> 0f }
                val py = when (side) { 0 -> 0f; 1 -> Random.nextFloat() * h; 2 -> h; else -> Random.nextFloat() * h }
                val angle = when (side) {
                    0 -> Random.nextFloat() * PI.toFloat()
                    1 -> (PI.toFloat() / 2 + Random.nextFloat() * PI.toFloat())
                    2 -> Random.nextFloat() * PI.toFloat() + PI.toFloat()
                    else -> (-PI.toFloat() / 2 + Random.nextFloat() * PI.toFloat())
                }
                val spd = Random.nextFloat() * 2f + 0.8f
                particles.add(Particle(
                    x = px, y = py,
                    vx = cos(angle) * spd, vy = sin(angle) * spd,
                    radius = Random.nextFloat() * 2.5f + 0.8f,
                    alpha = 0.5f,
                    color = Color.HSVToColor(255, arrayOf(colorHue + Random.nextFloat() * 60f, 0.8f, 1f).toFloatArray()),
                    life = 0f, maxLife = Random.nextFloat() * 40f + 20f
                ))
            }
        }
        if (particles.size > particleCount * 2) {
            particles.subList(0, particles.size - particleCount * 2).clear()
        }
    }

    // ═══════════════════════════════════════════
    //  音频节拍引擎
    // ═══════════════════════════════════════════

    private fun updateBeat() {
        beat.beatPulse *= 0.85f

        if (isPlaying) {
            if (realAudio && fftData != null) {
                updateBeatFromAudio()
            } else {
                if (frame % 60 == 0) {
                    LogHelper.d("模拟节拍模式 (设备不支持 Visualizer)")
                }
                updateBeatSimulated()
            }
        } else {
            beat.bass *= 0.9f
            beat.mid *= 0.9f
            beat.treble *= 0.9f
            beat.energy *= 0.9f
        }

        if (ripples.size > 3) {
            ripples.subList(0, ripples.size - 3).clear()
        }
    }

    /** 从 Visualizer FFT 数据分析真实音频 */
    private fun updateBeatFromAudio() {
        val fft = fftData ?: return
        val numBins = fft.size / 2
        if (numBins < 4) return

        // 每30帧打印一次 FFT 采样数据，证明确实获取到了音频
        if (frame % 30 == 0) {
            val sampleBins = (0 until minOf(16, numBins)).joinToString(", ") { i ->
                String.format("%.2f", fftMagnitude(fft, i))
            }
            android.util.Log.i("Visualizer", "=== FFT 前16个bin幅度: [$sampleBins] ===")
            android.util.Log.i("Visualizer", "FFT总bin数=$numBins, captureSize=${fft.size}")
            // 也输出到 MusicFW 标签，方便 adb logcat -s MusicFW 查看
            LogHelper.d("FFT 数据: bins=$numBins, 采样=[$sampleBins]")
        }

        // 频段划分: bass 0~5%, mid 5~25%, treble 25%~
        val bassEnd = max(2, (numBins * 0.05f).toInt())
        val midEnd = max(bassEnd + 1, (numBins * 0.25f).toInt())

        var bassSum = 0f
        var midSum = 0f
        var trebleSum = 0f

        for (i in 1 until bassEnd) {
            val mag = fftMagnitude(fft, i)
            bassSum += mag
        }
        for (i in bassEnd until midEnd) {
            val mag = fftMagnitude(fft, i)
            midSum += mag
        }
        for (i in midEnd until numBins) {
            val mag = fftMagnitude(fft, i)
            trebleSum += mag
        }

        // 归一化到 0~1 (灵敏度系数由设置控制)
        val sens = AppSettings.glowBeatSens * 0.03f // 50 -> 1.5x
        beat.bass = (bassSum / max(1, bassEnd - 1) * sens).coerceIn(0f, 1f)
        beat.mid = (midSum / max(1, midEnd - bassEnd) * sens * 1.3f).coerceIn(0f, 1f)
        beat.treble = (trebleSum / max(1, numBins - midEnd) * sens * 1.6f).coerceIn(0f, 1f)
        beat.energy = beat.bass * 0.5f + beat.mid * 0.3f + beat.treble * 0.2f

        // 打印音频分析数据
        if (frame % 30 == 0) { // 每30帧打印一次，避免日志过多
            android.util.Log.d("MusicWallpaperService", "=== 音频分析数据 ===")
            android.util.Log.d("MusicWallpaperService", "FFT bins: $numBins")
            android.util.Log.d("MusicWallpaperService", "Bass bins: 1~$bassEnd, Mid bins: $bassEnd~$midEnd, Treble bins: $midEnd~$numBins")
            android.util.Log.d("MusicWallpaperService", "Bass: ${String.format("%.3f", beat.bass)}")
            android.util.Log.d("MusicWallpaperService", "Mid: ${String.format("%.3f", beat.mid)}")
            android.util.Log.d("MusicWallpaperService", "Treble: ${String.format("%.3f", beat.treble)}")
            android.util.Log.d("MusicWallpaperService", "Energy: ${String.format("%.3f", beat.energy)}")
            android.util.Log.d("MusicWallpaperService", "Sensitivity: ${String.format("%.3f", sens)}")
            android.util.Log.d("MusicWallpaperService", "====================")
        }

        // Beat 检测: 灵敏度越高阈值越低
        val threshold = 1.5f - AppSettings.glowBeatSens * 0.01f // 50->1.0, 100->0.5
        prevEnergySmooth = prevEnergySmooth * 0.92f + beat.bass * 0.08f
        if (beat.bass > prevEnergySmooth * threshold && beat.bass > 0.1f) {
            beat.beatPulse = beat.bass.coerceAtMost(1f)
            val view = overlayView ?: return
            val maxDim = min(view.width, view.height).toFloat()
            ripples.add(Ripple(frame, colorHue, maxDim * 0.5f))
            android.util.Log.d("MusicWallpaperService", "Beat detected! Pulse: ${String.format("%.3f", beat.beatPulse)}")
        }
    }

    /** FFT bin 幅度计算 */
    private fun fftMagnitude(fft: ByteArray, bin: Int): Float {
        val idx = bin * 2
        if (idx + 1 >= fft.size) return 0f
        val re = fft[idx].toInt() / 128f
        val im = fft[idx + 1].toInt() / 128f
        return sqrt(re * re + im * im)
    }

    /** 模拟节拍 (Visualizer 不可用时的降级方案) */
    private fun updateBeatSimulated() {
        beatTimer++
        if (beatTimer >= nextBeatAt) {
            beatTimer = 0f
            nextBeatAt = Random.nextFloat() * 12f + 18f // 18~30帧 (比之前慢)
            val intensity = Random.nextFloat() * 0.2f + 0.4f // 比之前低
            beat.bass = intensity * (Random.nextFloat() * 0.4f + 0.3f)
            beat.mid = intensity * (Random.nextFloat() * 0.3f + 0.15f)
            beat.treble = intensity * (Random.nextFloat() * 0.2f + 0.1f)
            beat.energy = beat.bass * 0.5f + beat.mid * 0.3f + beat.treble * 0.2f

            // 每60帧打印一次模拟数据
            if (frame % 60 == 0) {
                android.util.Log.d("MusicWallpaperService", "=== 模拟节拍数据 ===")
                android.util.Log.d("MusicWallpaperService", "Bass: ${String.format("%.3f", beat.bass)}")
                android.util.Log.d("MusicWallpaperService", "Mid: ${String.format("%.3f", beat.mid)}")
                android.util.Log.d("MusicWallpaperService", "Treble: ${String.format("%.3f", beat.treble)}")
                android.util.Log.d("MusicWallpaperService", "Energy: ${String.format("%.3f", beat.energy)}")
                android.util.Log.d("MusicWallpaperService", "====================")
            }

            if (beat.energy > 0.3f) {
                beat.beatPulse = beat.energy
                val view = overlayView ?: return
                val maxDim = min(view.width, view.height).toFloat()
                ripples.add(Ripple(frame, colorHue, maxDim * 0.4f))
                android.util.Log.d("MusicWallpaperService", "模拟 Beat detected! Pulse: ${String.format("%.3f", beat.beatPulse)}")
            }
        }
    }

    // ═══════════════════════════════════════════
    //  渲染: 边缘光晕 (克制版)
    // ═══════════════════════════════════════════

    private fun drawEdgeGlow(canvas: Canvas, w: Float, h: Float) {
        // 亮度倍率: intensity 0~100 -> 0.5~4.0 (加大强度)
        val intMul = AppSettings.glowIntensity * 0.035f + 0.5f
        // 呼吸基础亮度
        val baseBreath = sin(frame * 0.02f) * 0.1f + 0.2f
        val pulse = beat.beatPulse
        val playBoost = if (isPlaying) 0.25f else 0f

        val brightness = min(1f, (baseBreath + playBoost + pulse) * intMul)
        if (brightness < 0.02f) return

        // 扩散深度: spread 0~100 -> 30~300px 基础 (加大)
        val spreadBase = AppSettings.glowSpread * 2.7f + 30f
        val edgeDepth = spreadBase + pulse * spreadBase * 1.2f + (if (isPlaying) 30f else 0f)

        val hue1 = colorHue % 360
        val hue2 = (colorHue + 120f) % 360
        val hue3 = (colorHue + 240f) % 360

        val a1 = (brightness * 180).toInt().coerceIn(0, 255)
        val a2 = ((brightness * 0.6f) * 180).toInt().coerceIn(0, 255)
        if (a1 <= 2) return

        // ── 扫光 (加快速度) ──
        val sweepSpeed = if (isPlaying) 4.0f else 1.5f
        val perimeter = 2f * (w + h)
        val sweepPos = (frame * sweepSpeed) % perimeter
        var sweepX: Float; var sweepY: Float
        when {
            sweepPos < w -> { sweepX = sweepPos; sweepY = 0f }
            sweepPos < w + h -> { sweepX = w; sweepY = sweepPos - w }
            sweepPos < 2 * w + h -> { sweepX = 2 * w + h - sweepPos; sweepY = h }
            else -> { sweepX = 0f; sweepY = perimeter - sweepPos }
        }
        val sweepRadius = spreadBase * 2.0f + pulse * spreadBase * 1.0f
        val sweepAlpha = (brightness * 180).toInt().coerceIn(0, 255)
        if (sweepAlpha > 2) {
            val sweepColor = Color.HSVToColor(sweepAlpha, arrayOf(hue1, 0.8f, 1f).toFloatArray())
            canvas.saveLayer(null, null)
            canvas.clipRect(
                max(0f, sweepX - sweepRadius), max(0f, sweepY - sweepRadius),
                min(w, sweepX + sweepRadius), min(h, sweepY + sweepRadius)
            )
            canvas.drawRect(0f, 0f, w, h, Paint().apply {
                shader = RadialGradient(sweepX, sweepY, sweepRadius, intArrayOf(sweepColor, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            })
            canvas.restore()
        }

        // ── 四边光晕带 (加大颜色深度) ──
        val topColor = Color.HSVToColor(a1, arrayOf(hue1, 0.8f, 1f).toFloatArray())
        val botColor = Color.HSVToColor(a1, arrayOf(hue2, 0.8f, 1f).toFloatArray())
        val leftColor = Color.HSVToColor(a2, arrayOf(hue3, 0.8f, 1f).toFloatArray())
        val rightColor = Color.HSVToColor(a2, arrayOf(hue1, 0.8f, 1f).toFloatArray())

        canvas.drawRect(0f, 0f, w, edgeDepth, Paint().apply {
            shader = LinearGradient(0f, 0f, 0f, edgeDepth, topColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, h - edgeDepth, w, h, Paint().apply {
            shader = LinearGradient(0f, h, 0f, h - edgeDepth, botColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        canvas.drawRect(0f, 0f, edgeDepth, h, Paint().apply {
            shader = LinearGradient(0f, 0f, edgeDepth, 0f, leftColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })
        canvas.drawRect(w - edgeDepth, 0f, w, h, Paint().apply {
            shader = LinearGradient(w, 0f, w - edgeDepth, 0f, rightColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
        })

        // ── 四角光点 (加大) ──
        val cornerRadius = spreadBase * 1.2f + pulse * spreadBase * 0.6f
        val cornerAlpha = (brightness * 150).toInt().coerceIn(0, 255)
        if (cornerAlpha > 2) {
            val corners = listOf(
                Triple(0f, 0f, hue1),
                Triple(w, 0f, hue2),
                Triple(w, h, hue3),
                Triple(0f, h, hue1)
            )
            for ((cx2, cy2, hue) in corners) {
                val cc = Color.HSVToColor(cornerAlpha, arrayOf(hue, 0.7f, 1f).toFloatArray())
                canvas.saveLayer(null, null)
                canvas.clipRect(
                    max(0f, cx2 - cornerRadius), max(0f, cy2 - cornerRadius),
                    min(w, cx2 + cornerRadius), min(h, cy2 + cornerRadius)
                )
                canvas.drawRect(0f, 0f, w, h, Paint().apply {
                    shader = RadialGradient(cx2, cy2, cornerRadius, intArrayOf(cc, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                })
                canvas.restore()
            }
        }

        // ── beat时边线闪白 (加强) ──
        if (pulse > 0.2f) {
            val flashW = 2f + pulse * 4f
            val flashAlpha = (pulse * 220).toInt().coerceIn(0, 255)
            val flashColor = Color.argb(flashAlpha, 255, 255, 255)
            val fp = Paint().apply { color = flashColor }
            canvas.drawRect(0f, 0f, w, flashW, fp)
            canvas.drawRect(0f, h - flashW, w, h, fp)
            canvas.drawRect(0f, 0f, flashW, h, fp)
            canvas.drawRect(w - flashW, 0f, w, h, fp)
        }
    }

    // ═══════════════════════════════════════════
    //  自定义渲染视图
    // ═══════════════════════════════════════════

    private inner class GlowView(context: Context) : View(context) {
        init {
            setWillNotDraw(false)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            android.util.Log.d("MusicWallpaperService", "GlowView created")
        }

        override fun onDraw(canvas: Canvas) {
            if (!drawing) {
                android.util.Log.d("MusicWallpaperService", "GlowView.onDraw: drawing=$drawing")
                return
            }
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) {
                android.util.Log.d("MusicWallpaperService", "GlowView.onDraw: invalid size: ${width}x${height}")
                return
            }

            android.util.Log.v("MusicWallpaperService", "GlowView.onDraw: frame=$frame, drawing=$drawing, isPlaying=$isPlaying, hue=$colorHue")

            // ═══ 1) 边缘光晕 ═══
            drawEdgeGlow(canvas, w, h)

            // ═══ 2) 扩散光环 (减弱) ═══
            val cx = w / 2f
            val cy = h / 2f
            val iter = ripples.iterator()
            while (iter.hasNext()) {
                val rip = iter.next()
                val elapsed = frame - rip.startTime
                val duration = 60f // 更慢扩散
                if (elapsed > duration) { iter.remove(); continue }
                val progress = elapsed / duration
                val radius = progress * rip.maxRadius
                val alpha = (1f - progress)
                val ringColor = Color.HSVToColor(
                    (alpha * 80).toInt(), // 降低光环透明度
                    arrayOf((rip.hue + progress * 60f) % 360, 0.5f, 1f).toFloatArray()
                )
                canvas.drawCircle(cx, cy, radius, Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 1.5f + (1f - progress) * 2f
                    color = ringColor
                })
            }

            // ═══ 3) 粒子 ═══
            for (p in particles) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = p.color
                    setShadowLayer(p.radius * 1.5f, 0f, 0f, p.color)
                    this.alpha = (p.alpha * 255).toInt()
                }
                canvas.drawCircle(p.x, p.y, p.radius, paint)
            }
        }
    }
}