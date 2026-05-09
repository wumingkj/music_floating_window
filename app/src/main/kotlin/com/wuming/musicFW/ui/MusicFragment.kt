package com.wuming.musicFW.ui

import android.animation.ObjectAnimator
import android.graphics.Outline
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.Button
import android.widget.ImageView
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.Fragment
import com.wuming.musicFW.R

class MusicFragment : Fragment() {
    lateinit var songName: TextView
    lateinit var artist: TextView
    lateinit var album: TextView
    lateinit var positionTv: TextView
    lateinit var lyricsTv: TextView
    lateinit var lyricsScroll: ScrollView
    lateinit var floatingBtn: Button
    lateinit var albumArtIv: ImageView
    private var rotateAnim: ObjectAnimator? = null

    override fun onCreateView(inf: LayoutInflater, vg: ViewGroup?, si: Bundle?): View {
        val v = inf.inflate(R.layout.fragment_music, vg, false)
        songName = v.findViewById(R.id.songNameTextView)
        artist = v.findViewById(R.id.artistTextView)
        album = v.findViewById(R.id.albumTextView)
        positionTv = v.findViewById(R.id.positionTextView)
        lyricsTv = v.findViewById(R.id.lyricsTextView)
        lyricsScroll = v.findViewById(R.id.lyricsScroll)
        floatingBtn = v.findViewById(R.id.floatingButton)
        albumArtIv = v.findViewById(R.id.albumArtImageView)
        // 圆形裁切
        albumArtIv.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
        albumArtIv.clipToOutline = true
        // 顺时针缓慢旋转（20秒一圈）
        rotateAnim = ObjectAnimator.ofFloat(albumArtIv, "rotation", 0f, 360f).apply {
            duration = 20000
            repeatCount = ObjectAnimator.INFINITE
            interpolator = android.view.animation.LinearInterpolator()
        }
        albumArtIv.post { rotateAnim?.start() }
        return v
    }

    override fun onDestroyView() {
        rotateAnim?.cancel()
        super.onDestroyView()
    }
}
