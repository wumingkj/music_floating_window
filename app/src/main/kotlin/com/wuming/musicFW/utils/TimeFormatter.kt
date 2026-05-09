package com.wuming.musicFW.utils

import java.text.SimpleDateFormat
import java.util.*

object TimeFormatter {
    fun formatTime(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    fun formatTimeWithMillis(millis: Long): String {
        val totalSeconds = millis / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        val ms = (millis % 1000) / 10
        return String.format("%02d:%02d.%02d", minutes, seconds, ms)
    }

    fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        return sdf.format(Date(millis))
    }

    fun parseTime(timeStr: String): Long {
        val parts = timeStr.split(":")
        if (parts.size != 2) return 0
        
        val minutes = parts[0].toLongOrNull() ?: 0
        val secondsParts = parts[1].split(".")
        val seconds = secondsParts[0].toLongOrNull() ?: 0
        val millis = if (secondsParts.size > 1) {
            secondsParts[1].toLongOrNull() ?: 0
        } else 0
        
        return minutes * 60000 + seconds * 1000 + millis
    }
}