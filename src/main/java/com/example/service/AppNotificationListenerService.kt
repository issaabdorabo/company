package com.example.service

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.example.data.BrowserConfigRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** Forwards visible app notifications to the owner's configured Telegram chat after Android grants access. */
class AppNotificationListenerService : NotificationListenerService() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
    private val lastContentByKey = ConcurrentHashMap<String, Int>()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        super.onNotificationPosted(sbn)
        if (sbn.packageName == packageName) return
        // Telegram is the delivery channel; forwarding its own notifications would create feedback loops.
        if (sbn.packageName in TELEGRAM_CLIENT_PACKAGES) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if ((notification.flags and Notification.FLAG_ONGOING_EVENT) != 0) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val textLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            ?.joinToString("\n") { it.toString() }
        val text = sequenceOf(
            extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
            extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
            textLines,
            extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()
        ).filterNotNull().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        if (title.isBlank() && text.isBlank()) return
        // Ignore the bot's own forwarded-notification messages to prevent a Telegram notification loop.
        if (text.contains("🔔 إشعار جديد")) return
        if (isSensitive(title, text)) return

        val contentHash = (title + "\n" + text).hashCode()
        val previous = lastContentByKey.put(sbn.key, contentHash)
        if (previous == contentHash) return
        if (lastContentByKey.size > 800) {
            lastContentByKey.keys.take(200).forEach { lastContentByKey.remove(it) }
        }

        val appLabel = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        }.getOrDefault(sbn.packageName)
        val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
            .format(java.util.Date(sbn.postTime))
        val message = buildString {
            appendLine("🔔 إشعار جديد")
            appendLine("التطبيق: ${appLabel.take(120)}")
            if (title.isNotBlank()) appendLine("العنوان: ${title.take(300)}")
            if (text.isNotBlank()) appendLine("المحتوى: ${text.take(2800)}")
            append("الوقت: $timestamp")
        }.take(3900)

        serviceScope.launch {
            runCatching { sendToTelegram(message) }
                .onFailure { Log.w(TAG, "Could not forward notification to Telegram", it) }
        }
    }

    private fun isSensitive(title: String, text: String): Boolean {
        val value = (title + " " + text).lowercase()
        val sensitiveTerms = listOf(
            "verification code", "one-time password", "one time password", "otp",
            "login code", "sign-in code", "passcode", "password reset", "security code",
            "رمز التحقق", "رمز التأكيد", "رمز الدخول", "رمز تسجيل الدخول", "كلمة المرور"
        )
        return sensitiveTerms.any { term -> value.contains(term) }
    }

    private fun sendToTelegram(message: String) {
        val config = BrowserConfigRepository(applicationContext).loadTelegramConfig()
        if (!config.isConfigured) return
        val payload = JSONObject()
            .put("chat_id", config.chatId)
            .put("text", message)
            .toString()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot${config.botToken}/sendMessage")
            .post(payload.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            val ok = response.isSuccessful && runCatching { JSONObject(body).optBoolean("ok") }.getOrDefault(false)
            if (!ok) Log.w(TAG, "Telegram rejected notification forward (HTTP ${response.code})")
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AppNotificationListener"
        private val TELEGRAM_CLIENT_PACKAGES = setOf(
            "org.telegram.messenger",
            "org.telegram.messenger.web",
            "org.thunderdog.challegram"
        )
    }
}
