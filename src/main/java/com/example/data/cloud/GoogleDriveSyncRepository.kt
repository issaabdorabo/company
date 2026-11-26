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
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Central Drive storage for device collaboration. Uses ordinary shared Drive folders,
 * never appDataFolder. The caller supplies an OAuth token authorized for the shared folder.
 * Supabase chat remains isolated in CloudCollaborationRepository.
 */
class GoogleDriveSyncRepository(
    private val httpClient: OkHttpClient = defaultHttpClient()
) {
    suspend fun ensureRootFolder(accessToken: String): DriveSyncFolder {
        val query = "name = '" + escapeQuery(ROOT_FOLDER_NAME) +
            "' and mimeType = '$FOLDER_MIME_TYPE' and 'root' in parents " +
            "and appProperties has { key='$APP_PROPERTY' and value='$APP_VALUE' }"
        val matches = listFiles(accessToken, query)
        return matches.firstOrNull()?.toFolder() ?: createFolder(
            accessToken, "root", ROOT_FOLDER_NAME,
            mapOf(APP_PROPERTY to APP_VALUE, KIND_PROPERTY to ROOT_KIND)
        )
    }

    suspend fun ensureDeviceFolder(
        accessToken: String,
        deviceId: String,
        deviceName: String
    ): DriveSyncFolder {
        require(deviceId.isNotBlank()) { "معرّف الجهاز غير صالح." }
        val root = ensureRootFolder(accessToken)
        val query = "'" + escapeQuery(root.id) + "' in parents and mimeType = '$FOLDER_MIME_TYPE' " +
            "and appProperties has { key='$DEVICE_ID_PROPERTY' and value='" + escapeQuery(deviceId) + "' }"
        val matches = listFiles(accessToken, query)
        return matches.firstOrNull()?.toFolder() ?: createFolder(
            accessToken, root.id, safeDriveName(deviceName.ifBlank { "جهاز فرعي" }),
            mapOf(
                APP_PROPERTY to APP_VALUE,
                KIND_PROPERTY to DEVICE_KIND,
                DEVICE_ID_PROPERTY to deviceId,
                DEVICE_NAME_PROPERTY to deviceName.take(120)
            )
        )
    }

    /** Shares only this device's folder. Reuses an existing permission when possible. */
    suspend fun shareDeviceFolder(accessToken: String, folderId: String, email: String) {
        require(folderId.isNotBlank()) { "مجلد الجهاز غير صالح." }
        val cleanEmail = email.trim().lowercase()
        require(cleanEmail.contains("@")) { "بريد Google للجهاز الفرعي غير صالح." }
        val existing = listPermissions(accessToken, folderId)
            .firstOrNull { it.type == "user" && it.emailAddress.equals(cleanEmail, true) }
        if (existing == null) {
            val body = JSONObject().put("type", "user").put("role", "writer").put("emailAddress", cleanEmail)
            executeJson(
                Request.Builder()
                    .url("$DRIVE_API_BASE/files/$folderId/permissions?sendNotificationEmail=true&supportsAllDrives=true&fields=id,emailAddress,role,type")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .post(body.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
        } else if (existing.role != "writer") {
            executeJson(
                Request.Builder()
                    .url("$DRIVE_API_BASE/files/$folderId/permissions/" + existing.id + "?supportsAllDrives=true&fields=id,emailAddress,role,type")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .patch(JSONObject().put("role", "writer").toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
        }
    }

    /**
     * Updates the one current JSON file for a device/document pair in-place. The JSON is
     * validated before the existing file is touched; duplicate historical files are removed
     * only after the canonical update succeeds.
     */
    suspend fun replaceJsonDocument(
        accessToken: String,
        folderId: String,
        deviceId: String,
        documentType: String,
        fileName: String,
        json: String
    ): DriveSyncFile {
        require(folderId.isNotBlank() && deviceId.isNotBlank()) { "بيانات مجلد المزامنة غير مكتملة." }
        require(documentType.matches(Regex("[A-Za-z0-9_-]{1,40}"))) { "نوع ملف المزامنة غير صالح." }
        require(json.isNotBlank()) { "لا يمكن رفع ملف مزامنة فارغ." }
        JSONObject(json)

        val query = "'" + escapeQuery(folderId) + "' in parents and trashed = false " +
            "and appProperties has { key='$APP_PROPERTY' and value='$APP_VALUE' } " +
            "and appProperties has { key='$DEVICE_ID_PROPERTY' and value='" + escapeQuery(deviceId) + "' } " +
            "and appProperties has { key='$DOCUMENT_TYPE_PROPERTY' and value='" + escapeQuery(documentType) + "' }"
        val existingFiles = listFiles(accessToken, query)
        val existing = existingFiles.firstOrNull()
        val safeName = safeDriveName(fileName).let { if (it.endsWith(".json", true)) it else "$it.json" }

        val canonical = if (existing == null) {
            createJsonDocument(accessToken, folderId, safeName, json, JSONObject()
                .put(APP_PROPERTY, APP_VALUE)
                .put(DEVICE_ID_PROPERTY, deviceId)
                .put(DOCUMENT_TYPE_PROPERTY, documentType))
        } else {
            val response = executeJson(
                Request.Builder()
                    .url("$DRIVE_UPLOAD_BASE/files/" + existing.id + "?uploadType=media&supportsAllDrives=true&fields=id,name,size,modifiedTime")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .patch(json.toRequestBody(JSON_MEDIA_TYPE))
                    .build()
            )
            if (existing.name != safeName) {
                executeJson(
                    Request.Builder()
                        .url("$DRIVE_API_BASE/files/" + existing.id + "?supportsAllDrives=true&fields=id,name")
                        .addHeader("Authorization", "Bearer $accessToken")
                        .patch(JSONObject().put("name", safeName).toString().toRequestBody(JSON_MEDIA_TYPE))
                        .build()
                )
            }
            response.toSyncFile(existing.id, safeName)
        }

        existingFiles.filter { it.id != canonical.id }.forEach { file ->
            runCatching { deleteFile(accessToken, file.id) }
        }
        return canonical
    }

    suspend fun findDeviceDocument(
        accessToken: String,
        folderId: String,
        deviceId: String,
        documentType: String
    ): DriveSyncFile? {
        val query = "'" + escapeQuery(folderId) + "' in parents and trashed = false " +
            "and appProperties has { key='$APP_PROPERTY' and value='$APP_VALUE' } " +
            "and appProperties has { key='$DEVICE_ID_PROPERTY' and value='" + escapeQuery(deviceId) + "' } " +
            "and appProperties has { key='$DOCUMENT_TYPE_PROPERTY' and value='" + escapeQuery(documentType) + "' }"
        return listFiles(accessToken, query).firstOrNull()?.toSyncFile()
    }

    suspend fun downloadJsonDocument(accessToken: String, fileId: String): String {
        require(fileId.isNotBlank()) { "معرّف ملف المزامنة غير صالح." }
        val request = Request.Builder()
            .url("$DRIVE_API_BASE/files/$fileId?alt=media&supportsAllDrives=true")
            .addHeader("Authorization", "Bearer $accessToken")
            .get()
            .build()
        val raw = executeText(request)
        require(raw.isNotBlank()) { "ملف المزامنة الموجود في Google Drive فارغ." }
        JSONObject(raw)
        return raw
    }

    /**
     * Revokes the sub-account's folder permission but retains the folder in the owner's
     * Drive as an archive. The sub-device clears linked local data after a confirmed
     * access-revoked response on its next successful internet connection.
     */
    suspend fun revokeDeviceFolderAccess(accessToken: String, folderId: String, email: String) {
        if (folderId.isBlank() || email.isBlank()) return
        val permission = listPermissions(accessToken, folderId)
            .firstOrNull { it.type == "user" && it.emailAddress.equals(email.trim(), true) }
            ?: return
        val request = Request.Builder()
            .url("$DRIVE_API_BASE/files/$folderId/permissions/" + permission.id + "?supportsAllDrives=true")
            .addHeader("Authorization", "Bearer $accessToken")
            .delete()
            .build()
        executeEmpty(request, allowNotFound = true)
    }

    suspend fun deleteFile(accessToken: String, fileId: String) {
        require(fileId.isNotBlank()) { "معرّف الملف غير صالح." }
        executeEmpty(
            Request.Builder()
                .url("$DRIVE_API_BASE/files/$fileId?supportsAllDrives=true")
                .addHeader("Authorization", "Bearer $accessToken")
                .delete()
                .build(),
            allowNotFound = true
        )
    }

    private suspend fun createFolder(
        accessToken: String,
        parentId: String,
        name: String,
        properties: Map<String, String>
    ): DriveSyncFolder {
        val appProperties = JSONObject().apply { properties.forEach { (key, value) -> put(key, value) } }
        val metadata = JSONObject()
            .put("name", name)
            .put("mimeType", FOLDER_MIME_TYPE)
            .put("parents", JSONArray().put(parentId))
            .put("appProperties", appProperties)
        return executeJson(
            Request.Builder()
                .url("$DRIVE_API_BASE/files?supportsAllDrives=true&fields=id,name,mimeType,parents,appProperties,webViewLink")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(metadata.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()
        ).toFolder()
    }

    private suspend fun createJsonDocument(
        accessToken: String,
        folderId: String,
        fileName: String,
        json: String,
        properties: JSONObject
    ): DriveSyncFile {
        val metadata = JSONObject()
            .put("name", fileName)
            .put("mimeType", JSON_MIME_TYPE)
            .put("parents", JSONArray().put(folderId))
            .put("appProperties", properties)
        val boundary = "smart_inventory_" + UUID.randomUUID().toString().replace("-", "")
        val multipart = buildString {
            append("--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(metadata.toString())
            append("\r\n--$boundary\r\n")
            append("Content-Type: application/json; charset=UTF-8\r\n\r\n")
            append(json)
            append("\r\n--$boundary--")
        }
        return executeJson(
            Request.Builder()
                .url("$DRIVE_UPLOAD_BASE/files?uploadType=multipart&supportsAllDrives=true&fields=id,name,mimeType,size,modifiedTime,appProperties")
                .addHeader("Authorization", "Bearer $accessToken")
                .post(multipart.toByteArray(Charsets.UTF_8)
                    .toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
                .build()
        ).toSyncFile()
    }

    private suspend fun listFiles(accessToken: String, query: String): List<DriveApiFile> {
        val result = mutableListOf<DriveApiFile>()
        var pageToken: String? = null
        do {
            val params = buildString {
                append("q=").append(encode(query))
                append("&spaces=drive&pageSize=100&includeItemsFromAllDrives=true&supportsAllDrives=true")
                append("&fields=").append(encode("nextPageToken,files(id,name,mimeType,parents,appProperties,webViewLink,size,modifiedTime)"))
                if (!pageToken.isNullOrBlank()) append("&pageToken=").append(encode(pageToken))
            }
            val response = executeJson(
                Request.Builder()
                    .url("$DRIVE_API_BASE/files?$params")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .get()
                    .build()
            )
            val files = response.optJSONArray("files") ?: JSONArray()
            for (index in 0 until files.length()) {
                val item = files.optJSONObject(index) ?: continue
                result += DriveApiFile(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    mimeType = item.optString("mimeType"),
                    webViewLink = item.optString("webViewLink"),
                    sizeBytes = item.optString("size").toLongOrNull() ?: 0L,
                    modifiedTime = item.optString("modifiedTime")
                )
            }
            pageToken = response.optString("nextPageToken").takeIf { it.isNotBlank() }
        } while (pageToken != null)
        return result
    }

    private suspend fun listPermissions(accessToken: String, folderId: String): List<DrivePermission> {
        val response = executeJson(
            Request.Builder()
                .url("$DRIVE_API_BASE/files/$folderId/permissions?supportsAllDrives=true&pageSize=100&fields=permissions(id,type,emailAddress,role)")
                .addHeader("Authorization", "Bearer $accessToken")
                .get()
                .build()
        )
        val items = response.optJSONArray("permissions") ?: JSONArray()
        return buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                add(DrivePermission(
                    id = item.optString("id"),
                    type = item.optString("type"),
                    emailAddress = item.optString("emailAddress"),
                    role = item.optString("role")
                ))
            }
        }
    }

    private suspend fun executeJson(request: Request): JSONObject {
        val body = executeText(request)
        return if (body.isBlank()) JSONObject() else JSONObject(body)
    }

    private suspend fun executeText(request: Request): String = withContext(Dispatchers.IO) {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw GoogleApiHttpException(
                    statusCode = response.code,
                    message = parseGoogleApiError(response.code, body, "تعذر تنفيذ طلب Google Drive")
                )
            }
            body
        }
    }

    private suspend fun executeEmpty(request: Request, allowNotFound: Boolean = false) {
        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful || (allowNotFound && response.code == 404)) return@withContext
                throw GoogleApiHttpException(
                    statusCode = response.code,
                    message = parseGoogleApiError(response.code, body, "تعذر حذف العنصر من Google Drive")
                )
            }
        }
    }

    private fun DriveApiFile.toFolder() = DriveSyncFolder(id, name, webViewLink)
    private fun JSONObject.toFolder() = DriveSyncFolder(
        id = optString("id"), name = optString("name"), webViewLink = optString("webViewLink")
    )
    private fun JSONObject.toSyncFile(idFallback: String = optString("id"), nameFallback: String = optString("name")) =
        DriveSyncFile(
            id = optString("id").ifBlank { idFallback },
            name = optString("name").ifBlank { nameFallback },
            sizeBytes = optString("size").toLongOrNull() ?: 0L,
            modifiedTime = optString("modifiedTime")
        )
    private fun DriveApiFile.toSyncFile() = DriveSyncFile(id, name, sizeBytes, modifiedTime)

    private fun safeDriveName(value: String): String = value.trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .take(100)
        .ifBlank { "مزامنة" }

    private fun escapeQuery(value: String) = value.replace("\\", "\\\\").replace("'", "\\'")
    private fun encode(value: String) = URLEncoder.encode(value, "UTF-8")

    private data class DriveApiFile(
        val id: String,
        val name: String,
        val mimeType: String,
        val webViewLink: String,
        val sizeBytes: Long,
        val modifiedTime: String
    )
    private data class DrivePermission(
        val id: String,
        val type: String,
        val emailAddress: String,
        val role: String
    )

    companion object {
        private const val DRIVE_API_BASE = "https://www.googleapis.com/drive/v3"
        private const val DRIVE_UPLOAD_BASE = "https://www.googleapis.com/upload/drive/v3"
        private const val ROOT_FOLDER_NAME = "مخزني الذكي - مزامنة الأجهزة"
        private const val FOLDER_MIME_TYPE = "application/vnd.google-apps.folder"
        private const val JSON_MIME_TYPE = "application/json"
        private const val APP_PROPERTY = "smartInventory"
        private const val APP_VALUE = "centralSync"
        private const val KIND_PROPERTY = "syncKind"
        private const val ROOT_KIND = "root"
        private const val DEVICE_KIND = "device"
        private const val DEVICE_ID_PROPERTY = "deviceId"
        private const val DEVICE_NAME_PROPERTY = "deviceName"
        private const val DOCUMENT_TYPE_PROPERTY = "documentType"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        private fun defaultHttpClient() = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}

data class DriveSyncFolder(val id: String, val name: String, val webViewLink: String = "")
data class DriveSyncFile(val id: String, val name: String, val sizeBytes: Long = 0L, val modifiedTime: String = "")
