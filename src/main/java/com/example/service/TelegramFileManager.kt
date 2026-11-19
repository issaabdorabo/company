package com.example.service

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Telegram-controlled file browser. All commands are restricted to the configured chat ID. */
class TelegramFileManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val client: OkHttpClient,
    private val botToken: () -> String,
    private val allowedChatId: () -> String
) {
    private data class Action(val file: File, val page: Int = 0)

    private val actions = ConcurrentHashMap<String, Action>()
    private var pollingJob: Job? = null
    @Volatile private var updateOffset = 0L
    private val root: File get() = Environment.getExternalStorageDirectory().canonicalFile
    private val maxTelegramBytes = 49L * 1024L * 1024L
    private val pageSize = 8

    fun start() {
        if (pollingJob?.isActive == true) return
        pollingJob = scope.launch(Dispatchers.IO) {
            while (isActive) {
                val token = botToken()
                val chat = allowedChatId()
                if (token.isBlank() || chat.isBlank()) {
                    delay(15_000)
                    continue
                }
                try {
                    pollUpdates(token, chat)
                } catch (_: Exception) {
                    delay(5_000)
                }
            }
        }
    }

    fun stop() {
        pollingJob?.cancel()
        pollingJob = null
    }

    suspend fun notifyDownloadCompleted(fileName: String, savedToGallery: Boolean) {
        if (botToken().isBlank() || allowedChatId().isBlank()) return
        val suffix = if (savedToGallery) "وحُفظ في المعرض" else "اكتمل التنزيل"
        sendText("✅ $suffix: ${fileName.take(180)}")
    }

    suspend fun notifyDownloadFailed(fileName: String, reason: String) {
        if (botToken().isBlank() || allowedChatId().isBlank()) return
        sendText("❌ تعذر تنزيل ${fileName.take(100)}\nالسبب: ${reason.take(250)}")
    }

    private suspend fun pollUpdates(token: String, chatId: String) {
        val url = "https://api.telegram.org/bot$token/getUpdates?timeout=20&offset=$updateOffset&allowed_updates=%5B%22message%22%2C%22callback_query%22%5D"
        val request = Request.Builder().url(url).get().build()
        client.newBuilder().readTimeout(30, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IllegalStateException("Telegram HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            val results = JSONObject(body).optJSONArray("result") ?: return
            for (i in 0 until results.length()) {
                val update = results.optJSONObject(i) ?: continue
                updateOffset = maxOf(updateOffset, update.optLong("update_id") + 1L)
                val message = update.optJSONObject("message")
                if (message != null) {
                    val chat = message.optJSONObject("chat")?.optString("id").orEmpty()
                    val sender = message.optJSONObject("from")?.optString("id").orEmpty()
                    if (chat == chatId && sender == chatId) {
                        val text = message.optString("text", "").trim()
                        if (text == "/files" || text.startsWith("/files@")) {
                            showDirectory(chatId, root, 0, null)
                        } else if (text == "/start" || text == "/help") {
                            sendText("مرحباً!\nاستخدم /files لفتح مدير الملفات.\nاختر مجلداً للدخول، أو اضغط 📦 لإرسال المجلد مضغوطاً، أو اضغط 📤 بجانب الملف لإرساله.")
                        }
                    }
                }
                val callback = update.optJSONObject("callback_query")
                if (callback != null) handleCallback(callback, chatId)
            }
        }
    }

    private suspend fun handleCallback(callback: JSONObject, allowedChat: String) {
        val callbackId = callback.optString("id")
        val message = callback.optJSONObject("message")
        val chatId = message?.optJSONObject("chat")?.optString("id").orEmpty()
        val senderId = callback.optJSONObject("from")?.optString("id").orEmpty()
        if (chatId != allowedChat || senderId != allowedChat) {
            answerCallback(callbackId, "غير مصرح لك باستخدام مدير الملفات")
            return
        }
        val data = callback.optString("data")
        val messageId = message?.optInt("message_id", -1) ?: -1
        if (data == "noop") {
            answerCallback(callbackId)
            return
        }
        when {
            data.startsWith("open:") -> {
                val action = actions[data.substringAfter(':')]
                if (action != null && isInsideRoot(action.file) && action.file.isDirectory) {
                    answerCallback(callbackId)
                    showDirectory(chatId, action.file, 0, messageId)
                } else answerCallback(callbackId, "تعذر فتح المجلد")
            }
            data.startsWith("back:") -> {
                val action = actions[data.substringAfter(':')]
                val parent = action?.file?.parentFile
                if (parent != null && isInsideRoot(parent)) {
                    answerCallback(callbackId)
                    showDirectory(chatId, parent, 0, messageId)
                } else answerCallback(callbackId, "أنت في المجلد الرئيسي")
            }
            data.startsWith("page:") -> {
                val action = actions[data.substringAfter(':')]
                if (action != null && isInsideRoot(action.file) && action.file.isDirectory) {
                    answerCallback(callbackId)
                    showDirectory(chatId, action.file, action.page, messageId)
                } else answerCallback(callbackId, "انتهت صلاحية هذا الزر")
            }
            data.startsWith("send:") -> {
                val action = actions[data.substringAfter(':')]
                answerCallback(callbackId, "جارٍ إرسال الملف إلى المحادثة…")
                if (action != null && isInsideRoot(action.file) && action.file.isFile) uploadFile(action.file)
                else sendText("تعذر العثور على الملف أو لم يعد متاحاً")
            }
            data.startsWith("zip:") -> {
                val action = actions[data.substringAfter(':')]
                answerCallback(callbackId, "جارٍ تجهيز المجلد وإرساله…")
                if (action != null && isInsideRoot(action.file) && action.file.isDirectory) uploadFolder(action.file)
                else sendText("تعذر العثور على المجلد")
            }
            else -> answerCallback(callbackId, "طلب غير معروف")
        }
    }

    private suspend fun showDirectory(chatId: String, directory: File, page: Int, editMessageId: Int?) {
        if (!hasStorageAccess()) {
            sendText("⚠️ يحتاج مدير الملفات إلى صلاحية الوصول إلى جميع الملفات. افتح التطبيق وامنح صلاحية إدارة كل الملفات، ثم أرسل /files مجدداً.")
            return
        }
        val safeDir = runCatching { directory.canonicalFile }.getOrNull()
        if (safeDir == null || !isInsideRoot(safeDir) || !safeDir.isDirectory) {
            sendText("تعذر فتح هذا المسار")
            return
        }
        val entries = safeDir.listFiles()?.filter { file ->
            !file.name.startsWith(".") && !isRestrictedStoragePath(file) && isInsideRoot(file)
        }?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })).orEmpty()
        val totalPages = maxOf(1, (entries.size + pageSize - 1) / pageSize)
        val safePage = page.coerceIn(0, totalPages - 1)
        val visible = entries.drop(safePage * pageSize).take(pageSize)
        val keyboard = JSONArray()

        if (safeDir != root) {
            val parent = safeDir.parentFile
            if (parent != null && isInsideRoot(parent)) {
                val id = remember(parent)
                keyboard.put(JSONArray().put(button("⬅️ رجوع", "back:$id")))
            }
        }
        for (entry in visible) {
            val id = remember(entry)
            if (entry.isDirectory) {
                keyboard.put(JSONArray()
                    .put(button("📁 ${entry.name.take(35)}", "open:$id"))
                    .put(button("📦 ZIP", "zip:$id")))
            } else {
                val size = formatBytes(entry.length())
                keyboard.put(JSONArray().put(button("📤 ${entry.name.take(35)} • $size", "send:$id")))
            }
        }
        if (totalPages > 1) {
            val nav = JSONArray()
            if (safePage > 0) nav.put(button("◀️ السابق", "page:${remember(safeDir, safePage - 1)}"))
            nav.put(button("${safePage + 1}/$totalPages", "noop"))
            if (safePage < totalPages - 1) nav.put(button("التالي ▶️", "page:${remember(safeDir, safePage + 1)}"))
            keyboard.put(nav)
        }
        keyboard.put(JSONArray().put(button("🏠 البداية", "open:${remember(root)}")))

        val relative = safeDir.relativeTo(root).path.let { if (it == "." || it.isBlank()) "/" else it }
        val title = "📂 مدير الملفات\nالمسار: /$relative\nعدد العناصر: ${entries.size}\nالصفحة: ${safePage + 1}/$totalPages\n\n📁 فتح مجلد  |  📦 إرسال مجلد ZIP\n📤 إرسال ملف"
        val markup = JSONObject().put("inline_keyboard", keyboard)
        if (editMessageId != null && editMessageId > 0) {
            editMessage(chatId, editMessageId, title, markup)
        } else {
            sendText(title, markup)
        }
    }

    private suspend fun uploadFile(file: File) {
        if (!file.exists() || !file.isFile) return sendText("الملف غير موجود")
        if (file.length() > maxTelegramBytes) return sendText("⚠️ حجم الملف أكبر من الحد المدعوم حالياً (49 ميجابايت): ${file.name}")
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        val endpoint = when {
            mime.startsWith("image/") && file.length() <= 10L * 1024L * 1024L -> "sendPhoto"
            mime.startsWith("video/") -> "sendVideo"
            else -> "sendDocument"
        }
        val field = when (endpoint) { "sendPhoto" -> "photo"; "sendVideo" -> "video"; else -> "document" }
        val requestBody = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", allowedChatId())
            .addFormDataPart(field, file.name, file.asRequestBody(mime.toMediaTypeOrNull()))
            .build()
        val ok = postMultipart(endpoint, requestBody)
        sendText(if (ok) "✅ تم إرسال: ${file.name}" else "❌ فشل إرسال: ${file.name}")
    }

    private suspend fun uploadFolder(folder: File) {
        val total = folder.walkTopDown().filter { it.isFile && !it.isHidden && !isRestrictedStoragePath(it) }.sumOf { it.length() }
        if (total > maxTelegramBytes - 1024 * 1024) {
            sendText("⚠️ المجلد أكبر من الحد المسموح لإرساله كملف ZIP (49 ميجابايت). اختر مجلداً أصغر.")
            return
        }
        val zip = File(context.cacheDir, "telegram_${UUID.randomUUID()}.zip")
        try {
            withContext(Dispatchers.IO) {
                ZipOutputStream(BufferedOutputStream(FileOutputStream(zip))).use { output ->
                    val base = folder.canonicalFile
                    folder.walkTopDown().filter { it.isFile && !it.isHidden && !isRestrictedStoragePath(it) }.forEach { file ->
                        val canonical = file.canonicalFile
                        if (!isInsideRoot(canonical) || isRestrictedStoragePath(canonical)) return@forEach
                        val name = canonical.relativeTo(base).invariantSeparatorsPath
                        output.putNextEntry(ZipEntry(name))
                        canonical.inputStream().use { it.copyTo(output) }
                        output.closeEntry()
                    }
                }
            }
            if (zip.length() > maxTelegramBytes) {
                sendText("⚠️ الملف المضغوط تجاوز حد Telegram المسموح")
                return
            }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", allowedChatId())
                .addFormDataPart("document", "${folder.name}.zip", zip.asRequestBody("application/zip".toMediaType()))
                .build()
            val ok = postMultipart("sendDocument", body)
            sendText(if (ok) "✅ تم إرسال المجلد مضغوطاً: ${folder.name}.zip" else "❌ تعذر إرسال المجلد")
        } catch (e: Exception) {
            sendText("❌ تعذر ضغط المجلد: ${e.localizedMessage ?: "خطأ غير معروف"}")
        } finally {
            zip.delete()
        }
    }

    private suspend fun postMultipart(endpoint: String, body: MultipartBody): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(apiUrl(endpoint)).post(body).build()
        client.newCall(request).execute().use { response ->
            response.isSuccessful && JSONObject(response.body?.string().orEmpty()).optBoolean("ok")
        }
    }

    private suspend fun sendText(text: String, markup: JSONObject? = null) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("chat_id", allowedChatId()).put("text", text.take(3900))
        if (markup != null) json.put("reply_markup", markup)
        postJson("sendMessage", json)
    }

    private suspend fun editMessage(chatId: String, messageId: Int, text: String, markup: JSONObject) = withContext(Dispatchers.IO) {
        val json = JSONObject().put("chat_id", chatId).put("message_id", messageId)
            .put("text", text.take(3900)).put("reply_markup", markup)
        postJson("editMessageText", json)
    }

    private suspend fun answerCallback(id: String, text: String = "") = withContext(Dispatchers.IO) {
        postJson("answerCallbackQuery", JSONObject().put("callback_query_id", id).put("text", text.take(180)))
    }

    private fun postJson(endpoint: String, json: JSONObject) {
        val request = Request.Builder().url(apiUrl(endpoint))
            .post(json.toString().toRequestBody("application/json; charset=utf-8".toMediaType())).build()
        client.newCall(request).execute().close()
    }

    private fun apiUrl(endpoint: String) = "https://api.telegram.org/bot${botToken()}/$endpoint"
    private fun button(label: String, data: String) = JSONObject().put("text", label).put("callback_data", data)
    private fun remember(file: File, page: Int = 0): String {
        val id = UUID.randomUUID().toString().replace("-", "").take(16)
        actions[id] = Action(file.canonicalFile, page)
        if (actions.size > 500) actions.keys.take(150).forEach { actions.remove(it) }
        return id
    }
    private fun isInsideRoot(file: File): Boolean = runCatching {
        val base = root.canonicalPath.trimEnd(File.separatorChar)
        val path = file.canonicalPath
        path == base || path.startsWith(base + File.separator)
    }.getOrDefault(false)
    private fun hasStorageAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    private fun isRestrictedStoragePath(file: File): Boolean = runCatching {
        val relative = file.canonicalFile.relativeTo(root).invariantSeparatorsPath
        relative == "Android/data" || relative.startsWith("Android/data/") ||
            relative == "Android/obb" || relative.startsWith("Android/obb/")
    }.getOrDefault(true)
    private fun formatBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}
