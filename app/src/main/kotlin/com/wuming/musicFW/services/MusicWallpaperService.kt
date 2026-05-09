package com.wuming.musicFW.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.media.*
import android.media.projection.MediaProjection
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
        var songTitle = ""; var songArtist = ""
        var albumArt: Bitmap? = null; var isPlaying = false
        var currentLyric = ""
        fun updateSongInfo(title: String, artist: String, art: Bitmap?) { songTitle = title; songArtist = artist; albumArt = art }
        fun updateLyric(lyric: String) { currentLyric = lyric }
        fun updatePlayingState(playing: Boolean) { isPlaying = playing }
        private const val CHANNEL_ID = "edge_glow"
    }

    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: GlowView? = null
    private lateinit var windowManager: WindowManager
    private var frame = 0; private var colorHue = 0f
    private val particles = mutableListOf<Particle>()
    private val beat = BeatData()
    private val ripples = mutableListOf<Ripple>()
    private var drawing = false

    // ═══ AudioPlaybackCapture ═══
    private var captureThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var realAudio = false
    private var prevEnergySmooth = 0f

    data class Particle(var x: Float, var y: Float, var vx: Float, var vy: Float,
                        var radius: Float, var alpha: Float, var color: Int, var life: Float, var maxLife: Float)
    data class BeatData(var bass: Float = 0f, var mid: Float = 0f, var treble: Float = 0f,
                        var energy: Float = 0f, var beatPulse: Float = 0f)
    data class Ripple(val startTime: Int, val hue: Float, val maxRadius: Float)

    override fun onCreate() {
        super.onCreate(); instance = this; AppSettings.init(this)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        createNotificationChannel(); startForeground(1, buildNotification())
        showOverlay()
    }

    override fun onDestroy() {
        instance = null; drawing = false; stopAudioCapture()
        handler.removeCallbacks(drawRunnable); removeOverlay(); super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "STOP" -> stopSelf()
            "AUDIO_CAPTURE" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val resultCode = intent.getIntExtra("resultCode", 0)
                    val resultData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
                        intent.getParcelableExtra("resultData", Intent::class.java)
                    else
                        @Suppress("DEPRECATION") intent.getParcelableExtra("resultData") as? Intent
                    if (resultCode != 0 && resultData != null) {
                        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as android.media.projection.MediaProjectionManager
                        val mp = mpm.getMediaProjection(resultCode, resultData)
                        startAudioCapture(mp)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "屏幕光晕", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "音乐边框光晕效果"; setShowBadge(false)
                })
        }
    }

    @Suppress("DEPRECATION")
    private fun buildNotification(): Notification {
        val label = if (realAudio) "🎵 实时音频" else "⏳ 等待授权内录"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("屏幕光晕 · $label")
                .setContentText(if (isPlaying) "$songTitle - $songArtist" else "光晕待机中")
                .setSmallIcon(android.R.drawable.ic_menu_gallery).build()
        } else {
            Notification.Builder(this)
                .setContentTitle("屏幕光晕 · $label")
                .setContentText(if (isPlaying) "$songTitle - $songArtist" else "光晕待机中")
                .setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setPriority(Notification.PRIORITY_LOW).build()
        }
    }

    // ═══════════════════════════════════════════
    //  AudioPlaybackCapture (Android 10+)
    // ═══════════════════════════════════════════

    fun startAudioCapture(mp: MediaProjection) {
        try {
            val config = AudioPlaybackCaptureConfiguration.Builder(mp)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .build()
            val sampleRate = 44100
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val encoding = AudioFormat.ENCODING_PCM_16BIT
            val bufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, encoding)
            audioRecord = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                AudioRecord.Builder()
                    .setAudioPlaybackCaptureConfig(config)
                    .setAudioFormat(AudioFormat.Builder()
                        .setEncoding(encoding).setSampleRate(sampleRate)
                        .setChannelMask(channelConfig).build())
                    .setBufferSizeInBytes(bufferSize * 2)
                    .build()
            } else null ?: return

            audioRecord?.startRecording()
            realAudio = true
            LogHelper.d("AudioPlaybackCapture 已启动，实时音频！")

            captureThread = Thread({ captureLoop() }, "audio-capture").apply { isDaemon = true; start() }
        } catch (e: Exception) {
            realAudio = false
            LogHelper.e("AudioPlaybackCapture 启动失败: ${e.message}")
        }
    }

    private fun stopAudioCapture() {
        captureThread?.interrupt(); captureThread = null
        try { audioRecord?.stop(); audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null
    }

    private fun captureLoop() {
        val buffer = ShortArray(1024)
        val fft = FloatArray(256) // 能量谱
        var smoothBass = 0f
        var beatCooldown = 0

        while (!Thread.currentThread().isInterrupted && drawing) {
            val record = audioRecord ?: break
            val read = record.read(buffer, 0, buffer.size)
            if (read <= 0) { Thread.sleep(10); continue }

            // 分析 PCM 能量
            var totalEnergy = 0f
            var bassEnergy = 0f
            for (i in 0 until read) {
                val sample = abs(buffer[i].toInt()) / 32768f
                totalEnergy += sample
                // 低频近似：连续采样差值大 = 低频多
                if (i > 0) bassEnergy += abs(buffer[i] - buffer[i - 1]) / 65536f
            }
            totalEnergy /= read
            bassEnergy = (bassEnergy / read * 3f).coerceIn(0f, 1f)
            val midEnergy = (totalEnergy * 2f - bassEnergy * 0.5f).coerceIn(0f, 1f)
            val treble = (totalEnergy * 3f).coerceIn(0f, 1f) * 0.3f

            // 平滑
            smoothBass = smoothBass * 0.85f + bassEnergy * 0.15f
            prevEnergySmooth = prevEnergySmooth * 0.9f + totalEnergy * 0.1f

            // 更新 beat 数据（在主线程安全地写）
            val energy = totalEnergy.coerceAtMost(1f)
            beat.bass = smoothBass.coerceAtMost(1f)
            beat.mid = midEnergy.coerceAtMost(1f)
            beat.treble = treble.coerceAtMost(1f)
            beat.energy = energy

            // Beat 检测
            if (beatCooldown > 0) beatCooldown--
            val threshold = 1.2f - AppSettings.glowBeatSens * 0.005f
            if (bassEnergy > smoothBass * threshold && bassEnergy > 0.1f && beatCooldown == 0) {
                beatCooldown = 15
                beat.beatPulse = bassEnergy.coerceAtMost(1f)
            }

            // 每100帧打印一次能量数据
            if (frame % 100 == 0) {
                LogHelper.d("PCM: E=${String.format("%.2f", energy)}, " +
                        "B=${String.format("%.2f", beat.bass)}, " +
                        "M=${String.format("%.2f", beat.mid)}")
            }
            Thread.sleep(20) // ~50fps 分析速率
        }
    }

    // ═══════════════════════════════════════════
    //  覆盖层
    // ═══════════════════════════════════════════

    @Suppress("DEPRECATION")
    private fun showOverlay() {
        try {
            val gv = GlowView(this); overlayView = gv
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                else WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            windowManager.addView(gv, params)
            gv.post {
                initParticles(gv.width.toFloat(), gv.height.toFloat())
                drawing = true; handler.post(drawRunnable)
            }
        } catch (e: Exception) { LogHelper.e("覆盖层失败: ${e.message}") }
    }

    private fun removeOverlay() {
        try { overlayView?.let { windowManager.removeView(it) } } catch (_: Exception) {}
        overlayView = null
    }

    private val drawRunnable = object : Runnable {
        override fun run() {
            if (!drawing) return
            frame++; colorHue = (colorHue + AppSettings.glowHueSpeed * 0.005f) % 360f
            beat.beatPulse *= 0.88f
            if (!isPlaying) { beat.bass *= 0.9f; beat.mid *= 0.9f; beat.treble *= 0.9f; beat.energy *= 0.9f }
            if (!realAudio) { // 没授权时用简单位置模拟
                beat.energy = (0.1f + sin(frame * 0.03f) * 0.05f)
                beat.bass = beat.energy * 0.5f
            }
            val v = overlayView ?: run { handler.postDelayed(this, 100); return }
            val w = v.width.toFloat(); val h = v.height.toFloat()
            if (w <= 0f || h <= 0f) { handler.postDelayed(this, 100); return }
            updateParticles(w, h); v.invalidate()
            if (frame % 60 == 0) {
                try { getSystemService(NotificationManager::class.java)?.notify(1, buildNotification()) } catch (_: Exception) {}
            }
            handler.postDelayed(this, 33)
        }
    }

    private fun initParticles(w: Float, h: Float) {
        particles.clear()
        repeat((AppSettings.glowParticles * 0.8f).toInt().coerceAtLeast(5)) {
            particles.add(Particle(Random.nextFloat() * w, Random.nextFloat() * h,
                (Random.nextFloat() - 0.5f) * 0.3f, -Random.nextFloat() * 0.4f - 0.05f,
                Random.nextFloat() * 2f + 0.3f, Random.nextFloat() * 0.15f + 0.02f,
                Color.HSVToColor(255, arrayOf(Random.nextFloat() * 360f, 0.5f, 0.9f).toFloatArray()),
                Random.nextFloat() * 200f, Random.nextFloat() * 200f + 100f))
        }
    }

    private fun updateParticles(w: Float, h: Float) {
        for (p in particles) {
            p.life++
            if (p.life > p.maxLife || p.y < -50f || p.x < -50f || p.x > w + 50f) {
                p.x = Random.nextFloat() * w; p.y = h + Random.nextFloat() * 100f
                p.life = 0f; p.maxLife = Random.nextFloat() * 200f + 100f
                p.radius = Random.nextFloat() * 2f + 0.3f
                p.color = Color.HSVToColor(255, arrayOf(Random.nextFloat() * 360f, 0.5f, 0.9f).toFloatArray())
            }
            val sm = if (isPlaying) 1f + beat.energy * 0.5f else 0.2f
            p.x += p.vx * sm + sin(p.life * 0.02f) * 0.15f; p.y += p.vy * sm
            val lr = p.life / p.maxLife
            p.alpha = (when { lr < 0.1f -> lr / 0.1f; lr > 0.8f -> (1f - lr) / 0.2f; else -> 1f }) * (if (isPlaying) 0.15f else 0.03f)
        }
        val pc = AppSettings.glowParticles
        if (isPlaying && beat.beatPulse > 0.5f && pc > 10) {
            repeat(max(1, (pc * 0.05f).toInt())) {
                val side = Random.nextInt(4)
                val (px, py) = when (side) {
                    0 -> Pair(Random.nextFloat() * w, 0f)
                    1 -> Pair(w, Random.nextFloat() * h)
                    2 -> Pair(Random.nextFloat() * w, h)
                    else -> Pair(0f, Random.nextFloat() * h)
                }
                val angle = when (side) {
                    0 -> Random.nextFloat() * PI.toFloat()
                    1 -> (PI.toFloat() / 2 + Random.nextFloat() * PI.toFloat())
                    2 -> Random.nextFloat() * PI.toFloat() + PI.toFloat()
                    else -> (-PI.toFloat() / 2 + Random.nextFloat() * PI.toFloat())
                }
                val spd = Random.nextFloat() * 2f + 0.8f
                particles.add(Particle(px, py, cos(angle) * spd, sin(angle) * spd,
                    Random.nextFloat() * 2.5f + 0.8f, 0.5f,
                    Color.HSVToColor(255, arrayOf(colorHue + Random.nextFloat() * 60f, 0.8f, 1f).toFloatArray()),
                    0f, Random.nextFloat() * 40f + 20f))
            }
        }
        if (particles.size > pc * 2) particles.subList(0, particles.size - pc * 2).clear()
    }

    // ═══════════════════════════════════════════
    //  渲染
    // ═══════════════════════════════════════════

    private fun drawEdgeGlow(canvas: Canvas, w: Float, h: Float) {
        val intMul = AppSettings.glowIntensity * 0.035f + 0.5f
        val brightness = min(1f, (sin(frame * 0.02f) * 0.1f + 0.2f + (if (isPlaying) 0.25f else 0f) + beat.beatPulse) * intMul)
        if (brightness < 0.02f) return
        val sp = AppSettings.glowSpread * 2.7f + 30f
        val ed = sp + beat.beatPulse * sp * 1.2f + (if (isPlaying) 30f else 0f)
        val h1 = colorHue % 360; val h2 = (colorHue + 120f) % 360; val h3 = (colorHue + 240f) % 360
        val a1 = (brightness * 180).toInt().coerceIn(0, 255); val a2 = (brightness * 108).toInt().coerceIn(0, 255)
        if (a1 <= 2) return
        val pp = 2f * (w + h); val sp2 = (frame * (if (isPlaying) 4f else 1.5f)) % pp
        val (sx, sy) = when { sp2 < w -> Pair(sp2, 0f); sp2 < w + h -> Pair(w, sp2 - w)
            sp2 < 2 * w + h -> Pair(2 * w + h - sp2, h); else -> Pair(0f, pp - sp2) }
        val sr = sp * 2f + beat.beatPulse * sp; val sa = (brightness * 180).toInt().coerceIn(0, 255)
        if (sa > 2) {
            canvas.saveLayer(null, null)
            canvas.clipRect(max(0f, sx - sr), max(0f, sy - sr), min(w, sx + sr), min(h, sy + sr))
            canvas.drawRect(0f, 0f, w, h, Paint().apply {
                shader = RadialGradient(sx, sy, sr,
                    intArrayOf(Color.HSVToColor(sa, arrayOf(h1, 0.8f, 1f).toFloatArray()), Color.TRANSPARENT),
                    null, Shader.TileMode.CLAMP)
            }); canvas.restore()
        }
        canvas.drawRect(0f, 0f, w, ed, Paint().apply { shader = LinearGradient(0f, 0f, 0f, ed,
            Color.HSVToColor(a1, arrayOf(h1, 0.8f, 1f).toFloatArray()), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        canvas.drawRect(0f, h - ed, w, h, Paint().apply { shader = LinearGradient(0f, h, 0f, h - ed,
            Color.HSVToColor(a1, arrayOf(h2, 0.8f, 1f).toFloatArray()), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        canvas.drawRect(0f, 0f, ed, h, Paint().apply { shader = LinearGradient(0f, 0f, ed, 0f,
            Color.HSVToColor(a2, arrayOf(h3, 0.8f, 1f).toFloatArray()), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        canvas.drawRect(w - ed, 0f, w, h, Paint().apply { shader = LinearGradient(w, 0f, w - ed, 0f,
            Color.HSVToColor(a2, arrayOf(h1, 0.8f, 1f).toFloatArray()), Color.TRANSPARENT, Shader.TileMode.CLAMP) })
        val cr = sp * 1.2f + beat.beatPulse * sp * 0.6f; val ca = (brightness * 150).toInt().coerceIn(0, 255)
        if (ca > 2) for ((cx, cy, hue) in listOf(Triple(0f, 0f, h1), Triple(w, 0f, h2), Triple(w, h, h3), Triple(0f, h, h1))) {
            canvas.saveLayer(null, null)
            canvas.clipRect(max(0f, cx - cr), max(0f, cy - cr), min(w, cx + cr), min(h, cy + cr))
            canvas.drawRect(0f, 0f, w, h, Paint().apply { shader = RadialGradient(cx, cy, cr,
                intArrayOf(Color.HSVToColor(ca, arrayOf(hue, 0.7f, 1f).toFloatArray()), Color.TRANSPARENT),
                null, Shader.TileMode.CLAMP) }); canvas.restore()
        }
        if (beat.beatPulse > 0.2f) {
            val fw = 2f + beat.beatPulse * 4f; val fp = Paint().apply { color = Color.argb((beat.beatPulse * 220).toInt().coerceIn(0, 255), 255, 255, 255) }
            canvas.drawRect(0f, 0f, w, fw, fp); canvas.drawRect(0f, h - fw, w, h, fp)
            canvas.drawRect(0f, 0f, fw, h, fp); canvas.drawRect(w - fw, 0f, w, h, fp)
        }
    }

    private inner class GlowView(context: Context) : View(context) {
        init { setWillNotDraw(false); setLayerType(LAYER_TYPE_SOFTWARE, null) }
        override fun onDraw(canvas: Canvas) {
            if (!drawing) return; super.onDraw(canvas)
            val w = width.toFloat(); val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            drawEdgeGlow(canvas, w, h)
            val cx = w / 2f; val cy = h / 2f
            val it = ripples.iterator()
            while (it.hasNext()) {
                val r = it.next(); val el = frame - r.startTime
                if (el > 60) { it.remove(); continue }
                val p = el / 60f
                canvas.drawCircle(cx, cy, p * r.maxRadius, Paint().apply {
                    style = Paint.Style.STROKE; strokeWidth = 1.5f + (1f - p) * 2f
                    color = Color.HSVToColor(((1f - p) * 80).toInt(), arrayOf((r.hue + p * 60f) % 360, 0.5f, 1f).toFloatArray())
                })
            }
            for (p in particles) {
                val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = p.color; setShadowLayer(p.radius * 1.5f, 0f, 0f, p.color); alpha = (p.alpha * 255).toInt() }
                canvas.drawCircle(p.x, p.y, p.radius, pt)
            }
        }
    }
}
