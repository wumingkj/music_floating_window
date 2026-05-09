package com.wuming.musicFW.services

import android.graphics.*
import android.service.wallpaper.WallpaperService
import android.os.Handler
import android.os.Looper
import android.view.SurfaceHolder
import kotlin.math.*
import kotlin.random.Random

class MusicWallpaperService : WallpaperService() {

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
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onCreateEngine(): Engine = MusicWallpaperEngine()

    data class Particle(
        var x: Float, var y: Float,
        var vx: Float, var vy: Float,
        var radius: Float, var alpha: Float,
        var color: Int, var life: Float, var maxLife: Float
    )

    /** 模拟节拍数据 (低频/中频/高频/总能量 0~1) */
    data class BeatData(
        var bass: Float = 0f,
        var mid: Float = 0f,
        var treble: Float = 0f,
        var energy: Float = 0f,
        var beatPhase: Float = 0f, // 0~1 循环
        var isBeat: Boolean = false
    )

    data class Ripple(val startTime: Int, val hue: Float, val maxRadius: Float)

    data class EdgeGlow(
        var x: Float, var y: Float,
        var intensity: Float,
        var hue: Float,
        var baseRadius: Float
    )

    inner class MusicWallpaperEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private var drawing = false
        private val particles = mutableListOf<Particle>()
        private var frame = 0
        private var albumRotation = 0f
        private var colorHue = 0f
        private var lastBeatFrame = -999

        // 模糊背景缓存
        private var blurredBg: Bitmap? = null
        private var lastArtHash = 0

        // 节拍数据
        private val beat = BeatData()
        // 用于平滑节拍
        private var beatTimer = 0f
        private var beatInterval = 30f // 大约 1 秒一拍 (30帧)
        private var nextBeatAt = 20f

        // 扩散光环
        private val ripples = mutableListOf<Ripple>()

        // 边缘光晕点
        private val edgeGlows = mutableListOf<EdgeGlow>()

        // 边缘均衡器 (四面各一组)
        private val EQ_BAR_COUNT = 40
        private val eqTop = FloatArray(EQ_BAR_COUNT)
        private val eqBottom = FloatArray(EQ_BAR_COUNT)
        private val eqLeft = FloatArray(EQ_BAR_COUNT)
        private val eqRight = FloatArray(EQ_BAR_COUNT)

        private val drawRunnable = object : Runnable {
            override fun run() {
                if (!drawing) return
                drawFrame()
                handler.postDelayed(this, 33) // ~30fps
            }
        }

        override fun onSurfaceCreated(holder: SurfaceHolder?) {
            super.onSurfaceCreated(holder)
            drawing = true
            initParticles()
            initEdgeGlows()
            handler.post(drawRunnable)
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder?) {
            super.onSurfaceDestroyed(holder)
            drawing = false
            handler.removeCallbacks(drawRunnable)
        }

        override fun onVisibilityChanged(visible: Boolean) {
            super.onVisibilityChanged(visible)
            if (visible && !drawing) {
                drawing = true
                handler.post(drawRunnable)
            } else if (!visible) {
                drawing = false
                handler.removeCallbacks(drawRunnable)
            }
        }

        private fun initParticles() {
            particles.clear()
            for (i in 0 until 80) {
                particles.add(Particle(
                    x = Random.nextFloat() * 1080f,
                    y = Random.nextFloat() * 2400f,
                    vx = (Random.nextFloat() - 0.5f) * 0.6f,
                    vy = -Random.nextFloat() * 0.8f - 0.2f,
                    radius = Random.nextFloat() * 4f + 1f,
                    alpha = Random.nextFloat() * 0.3f + 0.05f,
                    color = Color.HSVToColor(255, arrayOf(Random.nextFloat() * 360f, 0.6f, 0.9f).toFloatArray()),
                    life = Random.nextFloat() * 200f,
                    maxLife = Random.nextFloat() * 200f + 100f
                ))
            }
        }

        private fun initEdgeGlows() {
            edgeGlows.clear()
            // 8 个边缘发光点
            for (i in 0 until 8) {
                val hue = i * 45f
                edgeGlows.add(EdgeGlow(0f, 0f, 0f, hue, 80f))
            }
        }

        /** 模拟音乐节拍 - 基于播放状态生成伪随机节拍 */
        private fun updateBeat() {
            if (isPlaying) {
                beatTimer++
                // 变化节拍间隔, 模拟音乐节奏
                if (beatTimer >= nextBeatAt) {
                    beatTimer = 0f
                    nextBeatAt = Random.nextFloat() * 15f + 18f // 18~33帧一拍
                    val intensity = Random.nextFloat() * 0.4f + 0.6f
                    beat.bass = intensity * Random.nextFloat()
                    beat.mid = intensity * Random.nextFloat() * 0.8f
                    beat.treble = intensity * Random.nextFloat() * 0.6f
                    beat.energy = (beat.bass + beat.mid + beat.treble) / 3f
                    beat.isBeat = beat.energy > 0.5f
                    if (beat.isBeat) {
                        lastBeatFrame = frame
                        ripples.add(Ripple(frame, colorHue, min(1080f, 2400f) * 0.8f))
                    }
                }
            } else {
                // 衰减
                beat.bass *= 0.92f
                beat.mid *= 0.92f
                beat.treble *= 0.92f
                beat.energy *= 0.92f
                beat.isBeat = false
            }
            beat.beatPhase = (beat.beatPhase + 0.05f) % 1f

            // 限制扩散光环数量
            if (ripples.size > 8) {
                ripples.subList(0, ripples.size - 8).clear()
            }
        }

        /** 更新边缘光晕位置 - 沿屏幕边缘均匀分布 */
        private fun updateEdgeGlows(w: Float, h: Float) {
            val perimeter = 2f * (w + h)
            for (i in edgeGlows.indices) {
                val g = edgeGlows[i]
                // 沿边缘移动
                val offset = frame * 0.3f + i * perimeter / edgeGlows.size
                val pos = (offset % perimeter + perimeter) % perimeter
                when {
                    pos < w -> { g.x = pos; g.y = 0f }  // 上边
                    pos < w + h -> { g.x = w; g.y = pos - w }  // 右边
                    pos < 2 * w + h -> { g.x = 2 * w + h - pos; g.y = h }  // 下边
                    else -> { g.x = 0f; g.y = perimeter - pos }  // 左边
                }
                // 节拍驱动强度
                val target = if (isPlaying) 0.4f + beat.energy * 0.6f else 0.05f + sin(frame * 0.01f + i) * 0.05f
                g.intensity += (target - g.intensity) * 0.1f
                g.hue = (colorHue + i * 40f) % 360f
                g.baseRadius = 60f + beat.bass * 120f
            }
        }

        /** 更新边缘均衡器 */
        private fun updateEqualizer() {
            val decay = 0.85f
            val arrays = arrayOf(eqTop, eqBottom, eqLeft, eqRight)
            for (arr in arrays) {
                for (i in arr.indices) {
                    arr[i] *= decay
                }
            }
            if (isPlaying && beat.isBeat) {
                // 随机激活几根柱子
                for (arr in arrays) {
                    val count = Random.nextInt(3, 8)
                    for (j in 0 until count) {
                        val idx = Random.nextInt(arr.size)
                        arr[idx] = Random.nextFloat() * beat.energy * 1.5f
                    }
                }
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
                    p.radius = Random.nextFloat() * 4f + 1f
                    p.color = Color.HSVToColor(255, arrayOf(Random.nextFloat() * 360f, 0.6f, 0.9f).toFloatArray())
                }
                val speedMult = if (isPlaying) 1.5f + beat.energy else 0.4f
                p.x += p.vx * speedMult + sin(p.life * 0.02f) * 0.3f
                p.y += p.vy * speedMult
                val lifeRatio = p.life / p.maxLife
                p.alpha = when {
                    lifeRatio < 0.1f -> lifeRatio / 0.1f
                    lifeRatio > 0.8f -> (1f - lifeRatio) / 0.2f
                    else -> 1f
                } * (if (isPlaying) 0.35f else 0.1f)
            }
            // 播放时从边缘喷射粒子
            if (isPlaying && beat.isBeat && frame % 2 == 0) {
                for (k in 0 until 3) {
                    val side = Random.nextInt(4)
                    val px = when (side) { 0 -> Random.nextFloat() * w; 1 -> w; 2 -> Random.nextFloat() * w; else -> 0f }
                    val py = when (side) { 0 -> 0f; 1 -> Random.nextFloat() * h; 2 -> h; else -> Random.nextFloat() * h }
                    val angle = when (side) {
                        0 -> Random.nextFloat() * PI.toFloat() // 向下
                        1 -> (PI.toFloat() / 2 + Random.nextFloat() * PI.toFloat()) // 向左
                        2 -> Random.nextFloat() * PI.toFloat() + PI.toFloat() // 向上
                        else -> (-PI.toFloat() / 2 + Random.nextFloat() * PI.toFloat()) // 向右
                    }
                    val spd = Random.nextFloat() * 3f + 1f
                    particles.add(Particle(
                        x = px, y = py,
                        vx = cos(angle) * spd, vy = sin(angle) * spd,
                        radius = Random.nextFloat() * 3f + 1f,
                        alpha = 0.6f,
                        color = Color.HSVToColor(255, arrayOf(colorHue + Random.nextFloat() * 80f, 0.9f, 1f).toFloatArray()),
                        life = 0f, maxLife = Random.nextFloat() * 60f + 30f
                    ))
                }
            }
            if (particles.size > 200) {
                particles.subList(0, particles.size - 200).clear()
            }
        }

        private fun buildBlurredBg(art: Bitmap?, w: Int, h: Int) {
            val src = art ?: return
            val hash = src.generationId
            if (hash == lastArtHash && blurredBg != null) return
            lastArtHash = hash
            try {
                val scaled = Bitmap.createScaledBitmap(src, w / 8, h / 8, true)
                blurredBg = Bitmap.createScaledBitmap(scaled, w, h, true)
                if (blurredBg != null) {
                    val cv = Canvas(blurredBg!!)
                    cv.drawColor(Color.argb(160, 0, 0, 0))
                }
                if (scaled != blurredBg) scaled.recycle()
            } catch (_: Exception) {}
        }

        private fun drawFrame() {
            frame++
            colorHue = (colorHue + 0.3f) % 360f
            if (isPlaying) albumRotation += 0.3f

            updateBeat()
            updateEqualizer()

            val holder = surfaceHolder ?: return
            val canvas: Canvas? = try { holder.lockCanvas() } catch (_: Exception) { return }
            if (canvas == null) return

            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()

            // ═══════ 1) 背景 ═══════
            buildBlurredBg(albumArt, w.toInt(), h.toInt())
            if (blurredBg != null) {
                canvas.drawBitmap(blurredBg!!, 0f, 0f, null)
            } else {
                val bgPaint = Paint()
                val shader = LinearGradient(0f, 0f, w, h,
                    intArrayOf(0xFF1a1a2e.toInt(), 0xFF16213e.toInt(), 0xFF0f3460.toInt(), 0xFF533483.toInt()),
                    null, Shader.TileMode.CLAMP)
                bgPaint.shader = shader
                canvas.drawRect(0f, 0f, w, h, bgPaint)
            }

            // ═══════ 2) 边缘光晕 (大范围柔光) ═══════
            updateEdgeGlows(w, h)
            for (g in edgeGlows) {
                val glowRadius = g.baseRadius + beat.energy * 80f
                val glowColor = Color.HSVToColor(
                    (g.intensity * 80).toInt(),
                    arrayOf(g.hue, 0.7f, 1f).toFloatArray()
                )
                val glow = RadialGradient(g.x, g.y, glowRadius, glowColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                canvas.drawPaint(Paint().apply { shader = glow })
            }

            // ═══════ 3) 边缘全屏色彩脉冲 (播放时) ═══════
            if (isPlaying) {
                val pulseAlpha = (beat.energy * 40).toInt()
                if (pulseAlpha > 0) {
                    // 四边渐变发光
                    val edgeW = 60f + beat.bass * 100f
                    val pulseColor = Color.HSVToColor(pulseAlpha, arrayOf(colorHue, 0.8f, 1f).toFloatArray())
                    val pulseColor2 = Color.HSVToColor(pulseAlpha, arrayOf((colorHue + 120) % 360, 0.8f, 1f).toFloatArray())
                    val pulseColor3 = Color.HSVToColor(pulseAlpha, arrayOf((colorHue + 240) % 360, 0.8f, 1f).toFloatArray())

                    // 上边
                    val topGrad = LinearGradient(0f, 0f, 0f, edgeW, pulseColor, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, w, edgeW, Paint().apply { shader = topGrad })
                    // 下边
                    val botGrad = LinearGradient(0f, h, 0f, h - edgeW, pulseColor2, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, h - edgeW, w, h, Paint().apply { shader = botGrad })
                    // 左边
                    val leftGrad = LinearGradient(0f, 0f, edgeW, 0f, pulseColor3, Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(0f, 0f, edgeW, h, Paint().apply { shader = leftGrad })
                    // 右边
                    val rightGrad = LinearGradient(w, 0f, w - edgeW, 0f, Color.HSVToColor(pulseAlpha, arrayOf((colorHue + 60) % 360, 0.8f, 1f).toFloatArray()), Color.TRANSPARENT, Shader.TileMode.CLAMP)
                    canvas.drawRect(w - edgeW, 0f, w, h, Paint().apply { shader = rightGrad })
                }
            }

            // ═══════ 4) 扩散光环 (beat时从中心扩散) ═══════
            val cx = w / 2f
            val cy = h / 2f - 40f
            val iter = ripples.iterator()
            while (iter.hasNext()) {
                val rip = iter.next()
                val elapsed = frame - rip.startTime
                val duration = 60f // 约2秒扩散完
                if (elapsed > duration) { iter.remove(); continue }
                val progress = elapsed / duration
                val radius = progress * rip.maxRadius
                val alpha = (1f - progress) * 0.4f
                val ringColor = Color.HSVToColor(
                    (alpha * 255).toInt(),
                    arrayOf((rip.hue + progress * 60f) % 360, 0.7f, 1f).toFloatArray()
                )
                canvas.drawCircle(cx, cy, radius, Paint().apply {
                    style = Paint.Style.STROKE
                    strokeWidth = 2f + (1f - progress) * 4f
                    color = ringColor
                })
            }

            // ═══════ 5) 边缘均衡器柱 ═══════
            drawEdgeEqualizer(canvas, w, h)

            // ═══════ 6) 粒子 ═══════
            updateParticles(w, h)
            for (p in particles) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = p.color
                    setShadowLayer(p.radius * 3f, 0f, 0f, p.color)
                    this.alpha = (p.alpha * 255).toInt()
                }
                canvas.drawCircle(p.x, p.y, p.radius, paint)
            }

            // ═══════ 7) 中心专辑封面 + 光圈 ═══════
            val artRadius = min(w, h) * 0.15f

            // 外层脉动光圈
            val pulseSize = 1f + beat.bass * 0.15f
            val auraPaint = Paint().apply {
                color = Color.HSVToColor(
                    (60 + beat.energy * 80).toInt(),
                    arrayOf(colorHue, 0.5f, 1f).toFloatArray()
                )
                maskFilter = BlurMaskFilter(artRadius * 0.6f * pulseSize, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(cx, cy, artRadius * 1.2f * pulseSize, auraPaint)

            canvas.save()
            canvas.rotate(albumRotation, cx, cy)

            // 外圈光环
            val ringPaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f + beat.energy * 2f
                color = Color.HSVToColor(180, arrayOf(colorHue, 0.7f, 1f).toFloatArray())
                maskFilter = BlurMaskFilter(6f, BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawCircle(cx, cy, artRadius + 8f, ringPaint)

            // 唱片底色
            canvas.drawCircle(cx, cy, artRadius, Paint().apply { color = Color.argb(200, 25, 25, 25) })

            // 唱片纹理
            val linePaint = Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = 0.5f
                color = Color.argb(30, 255, 255, 255)
            }
            for (r in (artRadius * 0.3f).toInt()..(artRadius * 0.95f).toInt() step 5) {
                canvas.drawCircle(cx, cy, r.toFloat(), linePaint)
            }

            // 中心孔
            canvas.drawCircle(cx, cy, artRadius * 0.12f, Paint().apply { color = Color.argb(220, 15, 15, 15) })

            // 专辑图
            albumArt?.let { art ->
                val artSize = (artRadius * 0.85f).toInt()
                if (artSize > 0) {
                    val scaled = Bitmap.createScaledBitmap(art, artSize, artSize, true)
                    val clipPath = Path().apply { addCircle(cx, cy, artRadius * 0.42f, Path.Direction.CW) }
                    canvas.save()
                    canvas.clipPath(clipPath)
                    canvas.drawBitmap(scaled, cx - artSize / 2f, cy - artSize / 2f, null)
                    canvas.restore()
                    if (scaled != art) scaled.recycle()
                }
            }
            canvas.restore()

            // ═══════ 8) 歌曲信息 ═══════
            val textY = cy + artRadius + 45f
            val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.WHITE
                textSize = min(w, h) * 0.04f
                textAlign = Paint.Align.CENTER
                isFakeBoldText = true
                setShadowLayer(6f, 0f, 0f, Color.BLACK)
            }
            if (songTitle.isNotEmpty()) {
                canvas.drawText(songTitle, cx, textY, titlePaint)
            }
            val artistPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(180, 255, 255, 255)
                textSize = min(w, h) * 0.025f
                textAlign = Paint.Align.CENTER
                setShadowLayer(4f, 0f, 0f, Color.BLACK)
            }
            if (songArtist.isNotEmpty()) {
                canvas.drawText(songArtist, cx, textY + min(w, h) * 0.055f, artistPaint)
            }
            if (currentLyric.isNotEmpty()) {
                val lyricPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = Color.HSVToColor(230, arrayOf(colorHue, 0.5f, 1f).toFloatArray())
                    textSize = min(w, h) * 0.03f
                    textAlign = Paint.Align.CENTER
                    setShadowLayer(5f, 0f, 0f, Color.BLACK)
                }
                canvas.drawText(currentLyric, cx, textY + min(w, h) * 0.12f, lyricPaint)
            }

            // ═══════ 9) 暗角 (最后叠加) ═══════
            val vignette = RadialGradient(cx, h / 2, min(w, h) * 0.5f, Color.TRANSPARENT, Color.argb(120, 0, 0, 0), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, Paint().apply { shader = vignette })

            try { holder.unlockCanvasAndPost(canvas) } catch (_: Exception) {}
        }

        /** 绘制屏幕四边的均衡器柱 */
        private fun drawEdgeEqualizer(canvas: Canvas, w: Float, h: Float) {
            val barThickness = 3f
            val maxBarLen = min(w, h) * 0.12f
            val n = EQ_BAR_COUNT

            val hue1 = colorHue
            val hue2 = (colorHue + 120f) % 360

            // 上边 (向上生长)
            for (i in 0 until n) {
                val x = w / (n + 1) * (i + 1)
                val barH = eqTop[i] * maxBarLen
                if (barH < 1f) continue
                val hue = (hue1 + i * 3f) % 360
                canvas.drawRoundRect(x - barThickness, 0f, x + barThickness, barH, 1.5f, 1.5f, Paint().apply {
                    color = Color.HSVToColor(200, arrayOf(hue, 0.8f, 1f).toFloatArray())
                    maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
                })
            }
            // 下边 (向下生长)
            for (i in 0 until n) {
                val x = w / (n + 1) * (i + 1)
                val barH = eqBottom[i] * maxBarLen
                if (barH < 1f) continue
                val hue = (hue2 + i * 3f) % 360
                canvas.drawRoundRect(x - barThickness, h, x + barThickness, h - barH, 1.5f, 1.5f, Paint().apply {
                    color = Color.HSVToColor(200, arrayOf(hue, 0.8f, 1f).toFloatArray())
                    maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
                })
            }
            // 左边 (向左生长)
            for (i in 0 until n) {
                val y = h / (n + 1) * (i + 1)
                val barW = eqLeft[i] * maxBarLen
                if (barW < 1f) continue
                val hue = (hue2 + i * 3f) % 360
                canvas.drawRoundRect(0f, y - barThickness, barW, y + barThickness, 1.5f, 1.5f, Paint().apply {
                    color = Color.HSVToColor(200, arrayOf(hue, 0.8f, 1f).toFloatArray())
                    maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
                })
            }
            // 右边 (向右生长)
            for (i in 0 until n) {
                val y = h / (n + 1) * (i + 1)
                val barW = eqRight[i] * maxBarLen
                if (barW < 1f) continue
                val hue = (hue1 + i * 3f) % 360
                canvas.drawRoundRect(w, y - barThickness, w - barW, y + barThickness, 1.5f, 1.5f, Paint().apply {
                    color = Color.HSVToColor(200, arrayOf(hue, 0.8f, 1f).toFloatArray())
                    maskFilter = BlurMaskFilter(4f, BlurMaskFilter.Blur.NORMAL)
                })
            }
        }
    }
}
