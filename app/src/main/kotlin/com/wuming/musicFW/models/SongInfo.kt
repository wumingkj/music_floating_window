package com.wuming.musicFW.models

import android.graphics.Bitmap

data class SongInfo(
    val title: String,
    val artist: String,
    val album: String,
    val albumArt: Bitmap? = null
)
