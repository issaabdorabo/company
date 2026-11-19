package com.example.util

import com.example.data.DetectedVideo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

object YouTubeExtractor {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun extractVideoId(url: String?): String? {
        if (url.isNullOrBlank()) return null
        val patterns = listOf(
            Regex("""(?:youtube\.com/watch\?(?:.*&)?v=|m\.youtube\.com/watch\?(?:.*&)?v=)([a-zA-Z0-9_-]{11})"""),
            Regex("""(?:youtube\.com/shorts/|m\.youtube\.com/shorts/)([a-zA-Z0-9_-]{11})"""),
            Regex("""youtu\.be/([a-zA-Z0-9_-]{11})"""),
            Regex("""youtube\.com/embed/([a-zA-Z0-9_-]{11})""")
        )
        for (pattern in patterns) {
            val match = pattern.find(url)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    suspend fun extractDirectStreams(videoId: String): List<DetectedVideo> = withContext(Dispatchers.IO) {
        val fromIos = fetchViaInnerTube(
            videoId = videoId,
            clientName = "IOS",
            clientVersion = "19.29.1",
            userAgent = "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
        )
        if (fromIos.isNotEmpty()) return@withContext fromIos

        val fromAndroid = fetchViaInnerTube(
            videoId = videoId,
            clientName = "ANDROID",
            clientVersion = "19.29.37",
            userAgent = "com.google.android.youtube/19.29.37 (Linux; U; Android 14) gzip"
        )
        if (fromAndroid.isNotEmpty()) return@withContext fromAndroid

        fetchFromWatchHtml(videoId)
    }

    private fun fetchViaInnerTube(
        videoId: String,
        clientName: String,
        clientVersion: String,
        userAgent: String
    ): List<DetectedVideo> {
        return try {
            val payload = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", clientName)
                        put("clientVersion", clientVersion)
                        put("hl", "ar")
                        put("gl", "SA")
                    })
                })
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
            val request = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", userAgent)
                .header("Content-Type", "application/json")
                .post(body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val text = response.body?.string() ?: return emptyList()
                parsePlayerResponse(JSONObject(text), videoId)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun fetchFromWatchHtml(videoId: String): List<DetectedVideo> {
        return try {
            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId")
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36"
                )
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return emptyList()
                val html = response.body?.string() ?: return emptyList()
                val marker = "ytInitialPlayerResponse = "
                val startIdx = html.indexOf(marker)
                if (startIdx == -1) return emptyList()
                val jsonStart = html.indexOf('{', startIdx)
                if (jsonStart == -1) return emptyList()
                val jsonText = extractBalancedJson(html, jsonStart) ?: return emptyList()
                parsePlayerResponse(JSONObject(jsonText), videoId)
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parsePlayerResponse(json: JSONObject, videoId: String): List<DetectedVideo> {
        val playability = json.optJSONObject("playabilityStatus")?.optString("status")
        if (playability != null && playability != "OK") return emptyList()

        val videoDetails = json.optJSONObject("videoDetails")
        val rawTitle = videoDetails?.optString("title")?.takeIf { it.isNotBlank() } ?: "YouTube_$videoId"
        val streamingData = json.optJSONObject("streamingData") ?: return emptyList()

        val results = mutableListOf<DetectedVideo>()

        // Prefer combined progressive formats (audio + video in a single MP4)
        val formats = streamingData.optJSONArray("formats")
        if (formats != null) {
            for (i in 0 until formats.length()) {
                val fmt = formats.optJSONObject(i) ?: continue
                val directUrl = fmt.optString("url").takeIf { it.isNotBlank() } ?: continue
                val mimeRaw = fmt.optString("mimeType", "video/mp4").substringBefore(';').trim()
                val quality = fmt.optString("qualityLabel").ifBlank {
                    val h = fmt.optInt("height", 0)
                    if (h > 0) "${h}p" else "MP4"
                }
                results.add(
                    DetectedVideo(
                        url = directUrl,
                        title = "$rawTitle ($quality)",
                        mimeType = mimeRaw,
                        sourceTag = "youtube",
                        qualityLabel = quality
                    )
                )
            }
        }

        // Fallback to adaptive video MP4 if no progressive streams found
        if (results.isEmpty()) {
            val adaptive = streamingData.optJSONArray("adaptiveFormats")
            if (adaptive != null) {
                for (i in 0 until adaptive.length()) {
                    val fmt = adaptive.optJSONObject(i) ?: continue
                    val mimeRaw = fmt.optString("mimeType", "").substringBefore(';').trim()
                    if (!mimeRaw.startsWith("video/mp4")) continue
                    val directUrl = fmt.optString("url").takeIf { it.isNotBlank() } ?: continue
                    val quality = fmt.optString("qualityLabel").ifBlank { "HD" }
                    results.add(
                        DetectedVideo(
                            url = directUrl,
                            title = "$rawTitle ($quality فيديو)",
                            mimeType = mimeRaw,
                            sourceTag = "youtube",
                            qualityLabel = quality
                        )
                    )
                }
            }
        }

        return results.distinctBy { it.url }.take(6)
    }

    private fun extractBalancedJson(text: String, start: Int): String? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            if (escaped) {
                escaped = false
                continue
            }
            if (c == '\\' && inString) {
                escaped = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '{') depth++
                else if (c == '}') {
                    depth--
                    if (depth == 0) {
                        return text.substring(start, i + 1)
                    }
                }
            }
        }
        return null
    }
}
