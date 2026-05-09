package com.wuming.musicFW.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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
    lateinit var listenBtn: Button
    lateinit var permissionBtn: Button
    lateinit var floatingBtn: Button

    override fun onCreateView(inf: LayoutInflater, vg: ViewGroup?, si: Bundle?): View {
        val v = inf.inflate(R.layout.fragment_music, vg, false)
        songName = v.findViewById(R.id.songNameTextView)
        artist = v.findViewById(R.id.artistTextView)
        album = v.findViewById(R.id.albumTextView)
        positionTv = v.findViewById(R.id.positionTextView)
        lyricsTv = v.findViewById(R.id.lyricsTextView)
        lyricsScroll = v.findViewById(R.id.lyricsScroll)
        listenBtn = v.findViewById(R.id.listenButton)
        permissionBtn = v.findViewById(R.id.permissionButton)
        floatingBtn = v.findViewById(R.id.floatingButton)
        return v
    }
}
