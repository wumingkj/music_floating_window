package com.wuming.musicFW.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object NetEaseMusicApi {
    private const val SEARCH_URL = "https://music.163.com/api/search/get"
    private const val BASE_URL = "http://music.163.com/api"
    private const val REFERER = "http://music.163.com/"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    suspend fun searchSong(keyword: String): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val encodedKeyword = URLEncoder.encode(keyword, "UTF-8")
            val formBody = FormBody.Builder()
                .add("type", "1")
                .add("s", encodedKeyword)
                .build()

            val request = Request.Builder()
                .url(SEARCH_URL)
                .post(formBody)
                .header("Referer", REFERER)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            val json = gson.fromJson(body, JsonObject::class.java)

            if (json.get("code")?.asInt == 200) {
                val result = json.getAsJsonObject("result")
                if (result != null && result.has("songs")) {
                    val songs = result.getAsJsonArray("songs")
                    if (songs.size() > 0) {
                        return@withContext songs[0].asJsonObject
                    }
                }
            }
            null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    suspend fun getLyric(songId: Long): JsonObject? = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL/song/lyric?id=$songId&lv=1&kv=1&tv=-1"
            
            val request = Request.Builder()
                .url(url)
                .header("Referer", REFERER)
                .get()
                .build()

            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: return@withContext null
            
            gson.fromJson(body, JsonObject::class.java)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun getShareUrl(songId: Long): String {
        return "https://music.163.com/song?id=$songId"
    }
}