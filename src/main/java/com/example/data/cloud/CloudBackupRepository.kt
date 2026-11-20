package com.example.data.cloud

import android.content.Context
import com.example.BuildConfig
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
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

data class CloudBackupItem(
    val id: String = "",
    val userEmail: String = "",
    val name: String = "",
    val orgName: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val warehouseCount: Long = 0L,
    val itemCount: Long = 0L,
    val voucherCount: Long = 0L,
    val sizeBytes: Long = 0L,
    val createdAtMillis: Long = 0L,
    val updatedAtMillis: Long = 0L,
    val backupJson: String = ""
)

class CloudBackupRepository(
    @Suppress("UNUSED_PARAMETER") context: Context? = null,
    private val httpClient: OkHttpClient = defaultDriveHttpClient()
) {
    suspend fun fetchCloudBackupsFromDrive(
        accessToken: String,
        accountEmail: String
    ): Result<List<CloudBackupItem>> = withContext(Dispatchers.IO) {
        try {
            val q = "'appDataFolder' in parents and trashed = false and appProperties has { key='$PROP_TYPE' and value='$PROP_TYPE_VALUE' }"
            val encodedQ = URLEncoder.encode(q, "UTF-8")
            val encodedFields = URLEncoder.encode(
                "files(id,name,description,size,createdTime,modifiedTime,appProperties)",
                "UTF-8"
            )
            val url = "$DRIVE_API_BASE/files?q=$encodedQ&spaces=appDataFolder&fields=$encodedFields&orderBy=modifiedTime%20desc&pageSize=100"

            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
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
                                fallback = "تعذر جلب النسخ الاحتياطية من Google Drive"
                            )
                        )
                    )
                }
                val root = JSONObject(respBody)
                val filesArray = root.optJSONArray("files") ?: JSONArray()
                val items = buildList {
                    for (i in 0 until filesArray.length()) {
                        val fileJson = filesArray.optJSONObject(i) ?: continue
                        add(parseDriveFileToBackupItem(fileJson, accountEmail))
                    }
                }.sortedByDescending { maxOf(it.updatedAtMillis, it.createdAtMillis) }
                Result.success(items)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun createCloudBackup(
        accessToken: String,
        userEmail: String,
        name: String,
        orgName: String,
        warehouseCount: Int,
        itemCount: Int,
        voucherCount: Int,
        backupJson: String
    ): Result<CloudBackupItem> = withContext(Dispatchers.IO) {
        try {
            val cleanName = name.trim().ifBlank { "نسخة_سحابية" }.take(120)
            val cleanEmail = userEmail.trim().take(160)
            val cleanOrg = orgName.trim().take(160)
            val jsonBytes = backupJson.toByteArray(Charsets.UTF_8)

            val folderId = ensureBackupFolderId(accessToken).getOrNull()

            val safeFileTitle = cleanName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val metadataJson = JSONObject().apply {
                put("name", "$safeFileTitle.json")
                put("mimeType", "application/json")
                put("description", cleanName)
                if (!folderId.isNullOrBlank()) {
                    put("parents", JSONArray().put(folderId))
                }
                put(
                    "appProperties",
                    JSONObject().apply {
                        put(PROP_TYPE, PROP_TYPE_VALUE)
                        put(PROP_BACKUP_NAME, cleanName)
                        put(PROP_USER_EMAIL, cleanEmail)
                        put(PROP_ORG_NAME, cleanOrg)
                        put(PROP_APP_VERSION, BuildConfig.VERSION_NAME.take(32))
                        put(PROP_WAREHOUSE_COUNT, warehouseCount.coerceAtLeast(0).toString())
                        put(PROP_ITEM_COUNT, itemCount.coerceAtLeast(0).toString())
                        put(PROP_VOUCHER_COUNT, voucherCount.coerceAtLeast(0).toString())
                    }
                )
            }.toString()

            val boundary = "smart_inv_boundary_${UUID.randomUUID().toString().replace("-", "")}"
            val multipartBody = buildString {
                append("--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(metadataJson)
                append("\r\n--$boundary\r\n")
                append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
                append(backupJson)
                append("\r\n--$boundary--")
            }

            val encodedFields = URLEncoder.encode(
                "id,name,description,size,createdTime,modifiedTime,appProperties",
                "UTF-8"
            )
            val request = Request.Builder()
                .url("$DRIVE_UPLOAD_BASE/files?uploadType=multipart&fields=$encodedFields")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(
                    multipartBody.toByteArray(Charsets.UTF_8)
                        .toRequestBody("multipart/related; boundary=$boundary".toMediaType())
                )
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
                                fallback = "تعذر رفع النسخة الاحتياطية إلى Google Drive"
                            )
                        )
                    )
                }
                val fileJson = JSONObject(respBody)
                val created = parseDriveFileToBackupItem(fileJson, cleanEmail).copy(
                    sizeBytes = jsonBytes.size.toLong().coerceAtLeast(1L),
                    backupJson = backupJson
                )
                Result.success(created)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun downloadBackupJson(
        accessToken: String,
        fileId: String
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            require(fileId.isNotBlank()) { "معرف النسخة في Google Drive غير صالح." }
            val request = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId?alt=media")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
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
                                fallback = "تعذر تنزيل محتوى النسخة من Google Drive"
                            )
                        )
                    )
                }
                if (respBody.isBlank()) {
                    return@withContext Result.failure(
                        IllegalStateException("ملف النسخة المختار في Google Drive فارغ.")
                    )
                }
                Result.success(respBody)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun renameCloudBackup(
        accessToken: String,
        fileId: String,
        newName: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(fileId.isNotBlank()) { "معرف النسخة غير صالح." }
            val cleanName = newName.trim().take(120)
            require(cleanName.isNotEmpty()) { "يرجى إدخال اسم صالح للنسخة الاحتياطية." }

            val safeFileTitle = cleanName.replace(Regex("[\\\\/:*?\"<>|]"), "_")
            val patchJson = JSONObject().apply {
                put("name", "$safeFileTitle.json")
                put("description", cleanName)
                put("appProperties", JSONObject().put(PROP_BACKUP_NAME, cleanName))
            }.toString()

            val request = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId")
                .addHeader("Authorization", "Bearer $accessToken")
                .patch(patchJson.toRequestBody(JSON_MEDIA_TYPE))
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
                                fallback = "تعذر تعديل اسم النسخة في Google Drive"
                            )
                        )
                    )
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun deleteCloudBackup(
        accessToken: String,
        fileId: String
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            require(fileId.isNotBlank()) { "معرف النسخة غير صالح." }
            val request = Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId")
                .addHeader("Authorization", "Bearer $accessToken")
                .delete()
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
                                fallback = "تعذر حذف النسخة من Google Drive"
                            )
                        )
                    )
                }
                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun ensureBackupFolderId(@Suppress("UNUSED_PARAMETER") accessToken: String): Result<String> =
        Result.success("appDataFolder")

    private fun parseDriveFileToBackupItem(fileJson: JSONObject, fallbackEmail: String): CloudBackupItem {
        val props = fileJson.optJSONObject("appProperties")
        val fileNameFallback = fileJson.optString("name").removeSuffix(".json")
        val displayName = props?.optString(PROP_BACKUP_NAME)?.takeIf { it.isNotBlank() }
            ?: fileJson.optString("description").takeIf { it.isNotBlank() }
            ?: fileNameFallback

        return CloudBackupItem(
            id = fileJson.optString("id"),
            userEmail = props?.optString(PROP_USER_EMAIL)?.takeIf { it.isNotBlank() } ?: fallbackEmail,
            name = displayName,
            orgName = props?.optString(PROP_ORG_NAME).orEmpty(),
            appVersion = props?.optString(PROP_APP_VERSION).orEmpty().ifBlank { BuildConfig.VERSION_NAME },
            warehouseCount = props?.optString(PROP_WAREHOUSE_COUNT)?.toLongOrNull() ?: 0L,
            itemCount = props?.optString(PROP_ITEM_COUNT)?.toLongOrNull() ?: 0L,
            voucherCount = props?.optString(PROP_VOUCHER_COUNT)?.toLongOrNull() ?: 0L,
            sizeBytes = fileJson.optString("size").toLongOrNull() ?: 0L,
            createdAtMillis = parseIsoTimestampMillis(fileJson.optString("createdTime")),
            updatedAtMillis = parseIsoTimestampMillis(fileJson.optString("modifiedTime"))
        )
    }

    private fun parseIsoTimestampMillis(iso: String): Long {
        if (iso.isBlank()) return 0L
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSX",
            "yyyy-MM-dd'T'HH:mm:ssX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply {
                    timeZone = TimeZone.getTimeZone("UTC")
                }.parse(iso)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return 0L
    }

    companion object {
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val BACKUP_FOLDER_NAME = "SmartInventoryBackups"

        private const val PROP_TYPE = "backupType"
        private const val PROP_TYPE_VALUE = "smart_inventory_backup"
        private const val PROP_BACKUP_NAME = "backupName"
        private const val PROP_USER_EMAIL = "userEmail"
        private const val PROP_ORG_NAME = "orgName"
        private const val PROP_APP_VERSION = "appVersion"
        private const val PROP_WAREHOUSE_COUNT = "warehouseCount"
        private const val PROP_ITEM_COUNT = "itemCount"
        private const val PROP_VOUCHER_COUNT = "voucherCount"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultDriveHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(45, TimeUnit.SECONDS)
                .writeTimeout(45, TimeUnit.SECONDS)
                .build()
        }
    }
}
