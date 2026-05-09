package com.wuming.musicFW.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import com.wuming.musicFW.utils.LogHelper

object NetEaseMusicApi {
    private const val BASE_URL = "https://music.163.com/api"
    private const val REFERER = "https://music.163.com/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val gson = Gson()

    suspend fun searchSong(keyword: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val url = "${BASE_URL}/search/get?s=${URLEncoder.encode(keyword, "UTF-8")}&type=1&offset=0&limit=5"
            val request = Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Cookie", "appver=2.10.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return@withContext null
            LogHelper.d("搜索响应: ${bodyStr.take(200)}")
            val json = gson.fromJson(bodyStr, JsonObject::class.java)

            if (json.get("code")?.asInt == 200) {
                val result = json.getAsJsonObject("result")
                val songs = result?.getAsJsonArray("songs")
                if (songs != null && songs.size() > 0) {
                    return@withContext songs[0].asJsonObject
                }
            }
            null
        } catch (e: Exception) {
            LogHelper.e("搜索失败: ${e.message}")
            null
        }
    }

    suspend fun getLyric(songId: Long): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val url = "${BASE_URL}/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
            val request = Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                .header("Cookie", "appver=2.10.0")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val bodyStr = response.body?.string() ?: return@withContext null
            LogHelper.d("歌词响应: ${bodyStr.take(500)}")
            gson.fromJson(bodyStr, JsonObject::class.java)
        } catch (e: Exception) {
            LogHelper.e("获取歌词失败: ${e.message}")
            null
        }
    }
}