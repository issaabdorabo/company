package com.example.data.cloud

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

data class DriveSyncFolderPair(
    val mainToSubFolderId: String,
    val subToMainFolderId: String
)

data class DriveSharedSyncFolder(
    val id: String,
    val name: String,
    val direction: String,
    val ownerEmail: String,
    val recipientEmail: String
)

data class DriveSyncEventFile(
    val id: String,
    val name: String,
    val createdAtMillis: Long
)

/**
 * Shared-folder transport for append-only collaboration events.
 *
 * The main device owns both folders. It shares main-to-sub as reader and
 * sub-to-main as writer with the invited account. This keeps one sub-user
 * from modifying another sub-user's event stream.
 */
class GoogleDriveSharedSyncRepository(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    suspend fun createOrReuseDeviceFolders(
        accessToken: String,
        ownerUserId: String,
        ownerEmail: String,
        recipientEmail: String
    ): Result<DriveSyncFolderPair> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanOwnerId = ownerUserId.trim()
            val cleanOwnerEmail = ownerEmail.trim().lowercase(Locale.ROOT)
            val cleanRecipient = recipientEmail.trim().lowercase(Locale.ROOT)
            require(cleanOwnerId.isNotBlank()) { "معرف المستخدم الرئيسي غير متاح." }
            require(cleanOwnerEmail.contains("@")) { "بريد المستخدم الرئيسي غير صالح." }
            require(cleanRecipient.contains("@")) { "أدخل بريداً إلكترونياً صحيحاً للمستخدم الفرعي." }
            require(!cleanOwnerEmail.equals(cleanRecipient, ignoreCase = true)) {
                "لا يمكن ربط البريد نفسه بالمستخدم الرئيسي والفرعي."
            }

            val ownerKey = stableKey(cleanOwnerId)
            val recipientKey = stableKey(cleanRecipient)
            val mainToSubName = "SmartInventory_${ownerKey}_${recipientKey}_MAIN_TO_SUB"
            val subToMainName = "SmartInventory_${ownerKey}_${recipientKey}_SUB_TO_MAIN"

            val mainToSubId = findOrCreateFolder(
                accessToken, mainToSubName, cleanOwnerEmail, cleanRecipient, "MAIN_TO_SUB"
            )
            val subToMainId = findOrCreateFolder(
                accessToken, subToMainName, cleanOwnerEmail, cleanRecipient, "SUB_TO_MAIN"
            )

            setUserPermission(accessToken, mainToSubId, cleanRecipient, "reader")
            setUserPermission(accessToken, subToMainId, cleanRecipient, "writer")
            DriveSyncFolderPair(mainToSubId, subToMainId)
        }
    }

    suspend fun findFoldersSharedWithAccount(
        accessToken: String,
        recipientEmail: String
    ): Result<List<DriveSharedSyncFolder>> = withContext(Dispatchers.IO) {
        runCatching {
            val cleanEmail = recipientEmail.trim().lowercase(Locale.ROOT)
            require(cleanEmail.contains("@")) { "حساب Google المحدد لا يحتوي على بريد صالح." }
            val q = "sharedWithMe = true and mimeType = '$FOLDER_MIME' and trashed = false and appProperties has { key='$PROP_SYNC_TYPE' and value='$PROP_SYNC_TYPE_VALUE' } and appProperties has { key='$PROP_RECIPIENT_EMAIL' and value='$cleanEmail' }"
            val fields = URLEncoder.encode(
                "files(id,name,appProperties,owners(emailAddress))",
                "UTF-8"
            )
            val url = "$DRIVE_API_BASE/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=$fields&pageSize=100&orderBy=name"
            val root = executeJson(
                Request.Builder().url(url).addHeader("Authorization", "Bearer $accessToken").get().build(),
                "تعذر العثور على مجلد المزامنة المشترك"
            )
            val files = root.optJSONArray("files") ?: JSONArray()
            buildList {
                for (i in 0 until files.length()) {
                    val file = files.optJSONObject(i) ?: continue
                    val props = file.optJSONObject("appProperties") ?: continue
                    val direction = props.optString(PROP_DIRECTION)
                    if (direction != "MAIN_TO_SUB" && direction != "SUB_TO_MAIN") continue
                    val owners = file.optJSONArray("owners")
                    val ownerEmail = owners?.optJSONObject(0)?.optString("emailAddress").orEmpty()
                    add(
                        DriveSharedSyncFolder(
                            id = file.optString("id"),
                            name = file.optString("name"),
                            direction = direction,
                            ownerEmail = ownerEmail,
                            recipientEmail = props.optString(PROP_RECIPIENT_EMAIL)
                        )
                    )
                }
            }
        }
    }

    suspend fun uploadEvent(
        accessToken: String,
        folderId: String,
        eventId: String,
        eventJson: String,
        createdAtMillis: Long = System.currentTimeMillis()
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(folderId.isNotBlank()) { "مجلد المزامنة غير محدد." }
            require(eventId.isNotBlank()) { "معرف عملية المزامنة غير صالح." }
            require(eventJson.isNotBlank()) { "بيانات عملية المزامنة فارغة." }
            val safeEventId = eventId.replace(Regex("[^A-Za-z0-9_-]"), "_").take(100)
            val fileName = "event_${createdAtMillis}_$safeEventId.json"
            val metadata = JSONObject().apply {
                put("name", fileName)
                put("mimeType", JSON_MIME)
                put("parents", JSONArray().put(folderId))
                put(
                    "appProperties",
                    JSONObject().apply {
                        put(PROP_SYNC_TYPE, PROP_EVENT_TYPE_VALUE)
                        put(PROP_EVENT_ID, eventId)
                        put(PROP_CREATED_AT, createdAtMillis.toString())
                    }
                )
            }
            val boundary = "smart_inv_sync_${System.nanoTime()}"
            val body = buildString {
                append("--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(metadata.toString())
                append("\r\n--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(eventJson)
                append("\r\n--$boundary--")
            }
            val url = "$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=id"
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .post(body.toByteArray(Charsets.UTF_8).toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()
            executeJson(request, "تعذر رفع عملية المزامنة إلى Google Drive").optString("id")
                .also { require(it.isNotBlank()) { "لم يُرجع Google Drive معرف ملف العملية." } }
        }
    }

    suspend fun listEvents(
        accessToken: String,
        folderId: String,
        afterMillis: Long = 0L
    ): Result<List<DriveSyncEventFile>> = withContext(Dispatchers.IO) {
        runCatching {
            require(folderId.isNotBlank()) { "مجلد المزامنة غير محدد." }
            val after = if (afterMillis > 0L) {
                " and createdTime > '${formatDriveTime(afterMillis)}'"
            } else ""
            val q = "'$folderId' in parents and trashed = false and appProperties has { key='$PROP_SYNC_TYPE' and value='$PROP_EVENT_TYPE_VALUE' }$after"
            val fields = URLEncoder.encode("files(id,name,createdTime)", "UTF-8")
            val url = "$DRIVE_API_BASE/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=$fields&pageSize=1000&orderBy=createdTime"
            val root = executeJson(
                Request.Builder().url(url).addHeader("Authorization", "Bearer $accessToken").get().build(),
                "تعذر قراءة عمليات المزامنة من Google Drive"
            )
            val files = root.optJSONArray("files") ?: JSONArray()
            buildList {
                for (i in 0 until files.length()) {
                    val file = files.optJSONObject(i) ?: continue
                    add(
                        DriveSyncEventFile(
                            id = file.optString("id"),
                            name = file.optString("name"),
                            createdAtMillis = parseDriveTime(file.optString("createdTime"))
                        )
                    )
                }
            }.sortedBy { it.createdAtMillis }
        }
    }

    suspend fun downloadEvent(
        accessToken: String,
        fileId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            require(fileId.isNotBlank()) { "معرف ملف المزامنة غير صالح." }
            val request = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
            executeText(request, "تعذر تنزيل عملية المزامنة من Google Drive")
        }
    }

    suspend fun revokeAccountAccess(
        accessToken: String,
        folderIds: List<String>,
        recipientEmail: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val email = recipientEmail.trim().lowercase(Locale.ROOT)
            folderIds.filter(String::isNotBlank).distinct().forEach { folderId ->
                val url = "$DRIVE_API_BASE/files/$folderId/permissions?fields=permissions(id,type,emailAddress)"
                val root = executeJson(
                    Request.Builder().url(url).addHeader("Authorization", "Bearer $accessToken").get().build(),
                    "تعذر قراءة صلاحيات مجلد المزامنة"
                )
                val permissions = root.optJSONArray("permissions") ?: JSONArray()
                for (i in 0 until permissions.length()) {
                    val permission = permissions.optJSONObject(i) ?: continue
                    if (permission.optString("type") == "user" &&
                        permission.optString("emailAddress").equals(email, ignoreCase = true)
                    ) {
                        val permissionId = permission.optString("id")
                        if (permissionId.isNotBlank()) {
                            val delete = Request.Builder()
                                .url("$DRIVE_API_BASE/files/$folderId/permissions/$permissionId")
                                .addHeader("Authorization", "Bearer $accessToken")
                                .delete()
                                .build()
                            executeNoContent(delete, "تعذر إلغاء مشاركة مجلد المزامنة")
                        }
                    }
                }
            }
        }
    }

    private suspend fun findOrCreateFolder(
        accessToken: String,
        folderName: String,
        ownerEmail: String,
        recipientEmail: String,
        direction: String
    ): String {
        val escapedName = folderName.replace("'", "\\'")
        val q = "name = '$escapedName' and mimeType = '$FOLDER_MIME' and trashed = false and appProperties has { key='$PROP_SYNC_TYPE' and value='$PROP_SYNC_TYPE_VALUE' } and appProperties has { key='$PROP_RECIPIENT_EMAIL' and value='$recipientEmail' }"
        val fields = URLEncoder.encode("files(id,name,appProperties)", "UTF-8")
        val listUrl = "$DRIVE_API_BASE/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=$fields&pageSize=20"
        val listed = executeJson(
            Request.Builder().url(listUrl).addHeader("Authorization", "Bearer $accessToken").get().build(),
            "تعذر فحص مجلدات المزامنة الحالية"
        )
        val files = listed.optJSONArray("files") ?: JSONArray()
        for (i in 0 until files.length()) {
            val file = files.optJSONObject(i) ?: continue
            val props = file.optJSONObject("appProperties") ?: continue
            if (props.optString(PROP_DIRECTION) == direction) {
                return file.optString("id").takeIf { it.isNotBlank() }
                    ?: error("معرف مجلد المزامنة غير صالح.")
            }
        }

        val metadata = JSONObject().apply {
            put("name", folderName)
            put("mimeType", FOLDER_MIME)
            put("description", "مجلد مزامنة مخزني الذكي - $direction")
            put(
                "appProperties",
                JSONObject().apply {
                    put(PROP_SYNC_TYPE, PROP_SYNC_TYPE_VALUE)
                    put(PROP_DIRECTION, direction)
                    put(PROP_OWNER_EMAIL, ownerEmail)
                    put(PROP_RECIPIENT_EMAIL, recipientEmail)
                }
            )
        }
        val request = Request.Builder()
            .url("$DRIVE_API_BASE/files?fields=id,name")
            .addHeader("Authorization", "Bearer $accessToken")
            .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()
        return executeJson(request, "تعذر إنشاء مجلد المزامنة المشترك").optString("id")
            .takeIf { it.isNotBlank() } ?: error("لم يُرجع Google Drive معرف المجلد.")
    }

    private suspend fun setUserPermission(
        accessToken: String,
        folderId: String,
        email: String,
        role: String
    ) {
        val listUrl = "$DRIVE_API_BASE/files/$folderId/permissions?fields=permissions(id,type,emailAddress,role)"
        val listed = executeJson(
            Request.Builder().url(listUrl).addHeader("Authorization", "Bearer $accessToken").get().build(),
            "تعذر قراءة صلاحيات مجلد المزامنة"
        )
        val permissions = listed.optJSONArray("permissions") ?: JSONArray()
        var existingId: String? = null
        for (i in 0 until permissions.length()) {
            val permission = permissions.optJSONObject(i) ?: continue
            if (permission.optString("type") == "user" &&
                permission.optString("emailAddress").equals(email, ignoreCase = true)
            ) {
                existingId = permission.optString("id").takeIf { it.isNotBlank() }
                break
            }
        }
        val body = JSONObject().put("role", role)
        val request = if (existingId != null) {
            Request.Builder()
                .url("$DRIVE_API_BASE/files/$folderId/permissions/$existingId")
                .addHeader("Authorization", "Bearer $accessToken")
                .patch(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        } else {
            body.put("type", "user").put("emailAddress", email)
            Request.Builder()
                .url("$DRIVE_API_BASE/files/$folderId/permissions?sendNotificationEmail=true")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        }
        executeNoContent(request, "تعذر مشاركة مجلد المزامنة مع البريد المحدد")
    }

    private suspend fun executeJson(request: Request, fallback: String): JSONObject {
        val body = executeText(request, fallback)
        return JSONObject(body)
    }

    private suspend fun executeText(request: Request, fallback: String): String {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                error(if (message.isNotBlank()) "$fallback: $message (HTTP ${response.code})" else "$fallback (HTTP ${response.code})")
            }
            return body
        }
    }

    private suspend fun executeNoContent(request: Request, fallback: String) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                val message = runCatching {
                    JSONObject(body).optJSONObject("error")?.optString("message")
                }.getOrNull().orEmpty()
                error(if (message.isNotBlank()) "$fallback: $message (HTTP ${response.code})" else "$fallback (HTTP ${response.code})")
            }
        }
    }

    private fun stableKey(value: String): String {
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(value.lowercase(Locale.ROOT).toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it) }
    }

    private fun formatDriveTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.format(Date(millis)) + "Z"

    private fun parseDriveTime(value: String): Long = runCatching {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSX", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(value)?.time ?: 0L
    }.getOrElse {
        runCatching {
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssX", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }.parse(value)?.time ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val FOLDER_MIME = "application/vnd.google-apps.folder"
        private const val JSON_MIME = "application/json"
        private const val PROP_SYNC_TYPE = "syncType"
        private const val PROP_SYNC_TYPE_VALUE = "smart_inventory_sync_folder"
        private const val PROP_EVENT_TYPE_VALUE = "smart_inventory_sync_event"
        private const val PROP_DIRECTION = "direction"
        private const val PROP_OWNER_EMAIL = "ownerEmail"
        private const val PROP_RECIPIENT_EMAIL = "recipientEmail"
        private const val PROP_EVENT_ID = "eventId"
        private const val PROP_CREATED_AT = "createdAt"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .build()
    }
}
