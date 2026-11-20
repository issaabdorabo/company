package com.example.data.cloud

import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

data class GeneratedOtpChallenge(
    val email: String,
    val code: String,
    val refTag: String,
    val sentAtMillis: Long
)

class GmailOtpClient(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    private val random = SecureRandom()

    fun generateRandomOtpCode(): String {
        val number = random.nextInt(900_000) + 100_000
        return number.toString()
    }

    fun generateRefTag(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        return buildString(6) {
            repeat(6) { append(chars[random.nextInt(chars.length)]) }
        }
    }

    suspend fun sendVerificationOtpEmail(
        accessToken: String,
        targetEmail: String,
        otpCode: String,
        refTag: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            val cleanEmail = targetEmail.trim()
            val subjectText = "رمز تأكيد حساب مخزني الذكي [$refTag]"
            val encodedSubject = "=?UTF-8?B?" +
                Base64.encodeToString(subjectText.toByteArray(Charsets.UTF_8), Base64.NO_WRAP) +
                "?="

            val bodyPlain = buildString {
                appendLine("مرحباً بك في نظام مخزني الذكي،")
                appendLine()
                appendLine("رمز التأكيد العشوائي لتوثيق حسابك هو:")
                appendLine(otpCode)
                appendLine()
                appendLine("مرجع التحقق: $refTag")
                appendLine("أدخل الرمز داخل التطبيق أو انتظر تعبئته تلقائياً فور وصول الرسالة.")
            }
            val encodedBody = Base64.encodeToString(
                bodyPlain.toByteArray(Charsets.UTF_8),
                Base64.NO_WRAP
            )

            val mimeRaw = buildString {
                append("From: <$cleanEmail>\r\n")
                append("To: <$cleanEmail>\r\n")
                append("Subject: $encodedSubject\r\n")
                append("MIME-Version: 1.0\r\n")
                append("Content-Type: text/plain; charset=\"UTF-8\"\r\n")
                append("Content-Transfer-Encoding: base64\r\n\r\n")
                append(encodedBody)
            }

            val rawBase64Url = Base64.encodeToString(
                mimeRaw.toByteArray(Charsets.UTF_8),
                Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
            )

            val payload = JSONObject().put("raw", rawBase64Url).toString()
            val request = Request.Builder()
                .url("$GMAIL_BASE_URL/users/me/messages/send")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(payload.toRequestBody(JSON_MEDIA_TYPE))
                .build()

            httpClient.newCall(request).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        GoogleApiHttpException(
                            statusCode = response.code,
                            message = parseGoogleApiError(
                                statusCode = response.code,
                                body = respBody,
                                fallback = "تعذر إرسال رمز التأكيد إلى البريد الإلكتروني"
                            )
                        )
                    )
                }
                val json = runCatching { JSONObject(respBody) }.getOrNull()
                val messageId = json?.optString("id")?.takeIf { it.isNotBlank() }
                if (messageId == null) {
                    Result.failure(IllegalStateException("استجاب Gmail لطلب الإرسال دون إرجاع رقم الرسالة؛ لم يتم تأكيد إرسال رمز التحقق."))
                } else {
                    Result.success(messageId)
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun pollIncomingOtpCode(
        accessToken: String,
        refTag: String,
        expectedEmail: String
    ): Result<String?> = withContext(Dispatchers.IO) {
        try {
            val query = URLEncoder.encode("\"$refTag\"", "UTF-8")
            val listRequest = Request.Builder()
                .url("$GMAIL_BASE_URL/users/me/messages?q=$query&maxResults=5")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()

            val messageIds = httpClient.newCall(listRequest).execute().use { response ->
                val respBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(
                        GoogleApiHttpException(
                            statusCode = response.code,
                            message = parseGoogleApiError(
                                statusCode = response.code,
                                body = respBody,
                                fallback = "تعذر فحص البريد الوارد بحثاً عن رمز التأكيد"
                            )
                        )
                    )
                }
                val json = JSONObject(respBody)
                val messages = json.optJSONArray("messages") ?: JSONArray()
                buildList {
                    for (i in 0 until messages.length()) {
                        val id = messages.optJSONObject(i)?.optString("id").orEmpty()
                        if (id.isNotBlank()) add(id)
                    }
                }
            }

            for (msgId in messageIds) {
                val msgRequest = Request.Builder()
                    .url("$GMAIL_BASE_URL/users/me/messages/$msgId?format=full")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()

                val detected = httpClient.newCall(msgRequest).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val bodyStr = resp.body?.string().orEmpty()
                    val msgJson = runCatching { JSONObject(bodyStr) }.getOrNull() ?: return@use null
                    val labels = msgJson.optJSONArray("labelIds")
                    val hasInboxLabel = buildList {
                        if (labels != null) {
                            for (i in 0 until labels.length()) add(labels.optString(i))
                        }
                    }.any { it == "INBOX" || it == "UNREAD" }
                    if (!hasInboxLabel) return@use null

                    val snippet = msgJson.optString("snippet")
                    val fullText = buildString {
                        appendLine(snippet)
                        appendLine(extractPayloadText(msgJson.optJSONObject("payload")))
                    }
                    if (!fullText.contains(refTag, ignoreCase = true)) {
                        return@use null
                    }
                    extractSixDigitCode(fullText)
                }
                if (!detected.isNullOrBlank()) {
                    return@withContext Result.success(detected)
                }
            }
            Result.success(null)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun extractPayloadText(payload: JSONObject?): String {
        if (payload == null) return ""
        val sb = StringBuilder()
        val bodyData = payload.optJSONObject("body")?.optString("data").orEmpty()
        if (bodyData.isNotBlank()) {
            runCatching {
                val decoded = Base64.decode(bodyData, Base64.URL_SAFE or Base64.NO_WRAP)
                sb.appendLine(String(decoded, Charsets.UTF_8))
            }
        }
        val parts = payload.optJSONArray("parts")
        if (parts != null) {
            for (i in 0 until parts.length()) {
                sb.appendLine(extractPayloadText(parts.optJSONObject(i)))
            }
        }
        return sb.toString()
    }

    fun extractSixDigitCode(rawText: String): String? {
        return Regex("""(?<!\d)\d{6}(?!\d)""").find(rawText)?.value
    }

    companion object {
        private const val GMAIL_BASE_URL = "https://gmail.googleapis.com/gmail/v1"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(20, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}

class GoogleApiHttpException(
    val statusCode: Int,
    override val message: String
) : Exception(message)

internal fun parseGoogleApiError(statusCode: Int, body: String, fallback: String): String {
    val apiMessage = runCatching {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty()
    }.getOrDefault("")
    return when {
        statusCode == 401 -> "انتهت صلاحية تصريح Google؛ جاري إعادة المحاولة."
        statusCode == 403 && apiMessage.contains("disabled", ignoreCase = true) ->
            "واجهة Google API غير مفعلة بعد في المشروع السحابي ($apiMessage)"
        statusCode == 403 -> "$fallback (صلاحية الوصول مرفوضة: ${apiMessage.ifBlank { "403" }})"
        apiMessage.isNotBlank() -> "$fallback ($apiMessage)"
        else -> "$fallback (HTTP $statusCode)"
    }
}
