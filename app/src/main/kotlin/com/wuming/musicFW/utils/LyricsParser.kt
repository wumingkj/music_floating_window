package com.wuming.musicFW.utils

import com.wuming.musicFW.models.LyricsLine
import java.util.regex.Pattern

object LyricsParser {
    private val LRC_PATTERN = Pattern.compile("\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})\\](.*)")

    fun parseLyric(lyricText: String): List<LyricsLine> {
        if (lyricText.isEmpty()) {
            return emptyList()
        }

        val lines = lyricText.split("\n")
        val result = mutableListOf<LyricsLine>()

        for (line in lines) {
            val match = LRC_PATTERN.matcher(line)
            if (match.find()) {
                val minutes = match.group(1)?.toLongOrNull() ?: 0
                val seconds = match.group(2)?.toLongOrNull() ?: 0
                val millisStr = match.group(3) ?: "0"
                val text = match.group(4)?.trim() ?: ""

                val millis = if (millisStr.length == 2) {
                    millisStr.toLong() * 10
                } else {
                    millisStr.toLong()
                }

                val time = minutes * 60000 + seconds * 1000 + millis

                if (text.isNotEmpty()) {
                    result.add(LyricsLine(time, text))
                }
            }
        }

        return result.sortedBy { it.time }
    }

    fun findCurrentLineIndex(lyrics: List<LyricsLine>, position: Long): Int {
        if (lyrics.isEmpty()) return -1
        
        for (i in lyrics.size - 1 downTo 0) {
            if (position >= lyrics[i].time) {
                return i
            }
        }
        return 0
    }
}