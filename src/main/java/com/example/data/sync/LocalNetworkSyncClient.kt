package com.example.data.sync

import com.example.data.cloud.CloudEntityUpsert
import com.example.data.cloud.DiscoveredMainDevice
import com.example.data.cloud.PairingResult
import com.example.data.cloud.SyncSnapshotResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class SyncApiException(val errorCode: String, message: String) : Exception(message)

/**
 * Direct OkHttp client used by Sub-Devices to pair and sync with the Main Device over Wi-Fi/Hotspot LAN.
 */
class LocalNetworkSyncClient {
    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .writeTimeout(8, TimeUnit.SECONDS)
        .build()

    fun normalizeBaseUrl(rawHost: String): String {
        val trimmed = rawHost.trim().removeSuffix("/")
        val withScheme = if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)
        ) {
            trimmed
        } else {
            "http://$trimmed"
        }
        val hostPart = withScheme.substringAfter("://")
        return if (hostPart.contains(":")) {
            withScheme
        } else {
            "$withScheme:${LocalNetworkSyncServer.DEFAULT_PORT}"
        }
    }

    suspend fun pingMainDevice(rawHost: String): Result<DiscoveredMainDevice> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(rawHost)
            val request = Request.Builder()
                .url("$baseUrl/api/ping")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                if (!response.isSuccessful || json == null) {
                    throw SyncApiException("PING_FAILED", "تعذر الوصول إلى الجهاز الرئيسي على العنوان المحدد")
                }
                val code = json.optString("workspaceCode")
                val orgName = json.optString("orgName")
                DiscoveredMainDevice(
                    serviceName = "SmartInv-$code",
                    workspaceCode = code,
                    hostAddress = baseUrl.removePrefix("http://"),
                    orgName = orgName
                )
            }
        }
    }

    suspend fun pairWithMainDevice(
        rawHost: String,
        workspaceCode: String,
        password: String,
        installationId: String
    ): Result<PairingResult> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(rawHost)
            val payload = JSONObject().apply {
                put("workspaceCode", workspaceCode.trim().uppercase())
                put("password", password.trim())
                put("installationId", installationId)
            }
            val request = Request.Builder()
                .url("$baseUrl/api/pair")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                if (!response.isSuccessful || json == null) {
                    val code = json?.optString("error").orEmpty().ifBlank { "PAIR_FAILED" }
                    val msg = json?.optString("message").orEmpty().ifBlank {
                        "فشل الربط مع الجهاز الرئيسي (${response.code})"
                    }
                    throw SyncApiException(code, msg)
                }

                val returnedCode = json.optString("workspaceCode")
                val permissions = LocalSyncJson.permissionsFromJson(json.optJSONObject("permissions"))
                val entities = LocalSyncJson.recordsFromJsonArray(json.optJSONArray("entities"), returnedCode)

                PairingResult(
                    workspaceCode = returnedCode,
                    orgName = json.optString("orgName"),
                    deviceSlotId = json.optString("deviceSlotId"),
                    deviceName = json.optString("deviceName"),
                    sessionToken = json.optString("sessionToken"),
                    permissions = permissions,
                    syncVersion = json.optLong("syncVersion", 1L),
                    entities = entities
                )
            }
        }
    }

    suspend fun fetchSyncSnapshot(
        rawHost: String,
        sessionToken: String
    ): Result<SyncSnapshotResult> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(rawHost)
            val request = Request.Builder()
                .url("$baseUrl/api/sync")
                .header("Authorization", "Bearer $sessionToken")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                if (!response.isSuccessful || json == null) {
                    val code = json?.optString("error").orEmpty().ifBlank {
                        if (response.code == 403) "KICKED" else "SYNC_FAILED"
                    }
                    val msg = json?.optString("message").orEmpty().ifBlank {
                        "تعذر التزامن مع الجهاز الرئيسي (${response.code})"
                    }
                    throw SyncApiException(code, msg)
                }

                val workspaceCode = json.optString("workspaceCode")
                val permissions = LocalSyncJson.permissionsFromJson(json.optJSONObject("permissions"))
                val isKicked = json.optBoolean("isKicked", false) || permissions.isKicked
                val entities = LocalSyncJson.recordsFromJsonArray(json.optJSONArray("entities"), workspaceCode)

                SyncSnapshotResult(
                    workspaceCode = workspaceCode,
                    orgName = json.optString("orgName"),
                    permissions = permissions,
                    isKicked = isKicked,
                    syncVersion = json.optLong("syncVersion", 1L),
                    entities = entities
                )
            }
        }
    }

    suspend fun unlinkFromMain(rawHost: String, sessionToken: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(rawHost)
            val request = Request.Builder()
                .url("$baseUrl/api/unlink")
                .header("Authorization", "Bearer $sessionToken")
                .post(JSONObject().put("reason", "USER_UNLINK").toString().toRequestBody(jsonMediaType))
                .build()
            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                if (!response.isSuccessful || json?.optBoolean("ok") != true) {
                    val code = json?.optString("error").orEmpty().ifBlank { "UNLINK_FAILED" }
                    throw SyncApiException(code, json?.optString("message").orEmpty().ifBlank { "تعذر تسجيل فك الارتباط على الجهاز الرئيسي" })
                }
            }
        }
    }

    suspend fun pushEntitiesToMain(
        rawHost: String,
        sessionToken: String,
        actionType: String,
        entities: List<CloudEntityUpsert>,
        operationTitle: String = "",
        operationDetails: String = "",
        actorName: String = "",
        warehouseIds: Set<Long> = emptySet(),
        targetItemIds: Set<Long> = emptySet(),
        targetVoucherIds: Set<Long> = emptySet()
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val baseUrl = normalizeBaseUrl(rawHost)
            val payload = JSONObject().apply {
                put("actionType", actionType)
                put("operationTitle", operationTitle)
                put("operationDetails", operationDetails)
                put("actorName", actorName)
                put("warehouseIds", org.json.JSONArray().apply { warehouseIds.forEach { put(it) } })
                put("targetItemIds", org.json.JSONArray().apply { targetItemIds.forEach { put(it) } })
                put("targetVoucherIds", org.json.JSONArray().apply { targetVoucherIds.forEach { put(it) } })
                put("entities", LocalSyncJson.upsertsToJsonArray(entities))
            }
            val request = Request.Builder()
                .url("$baseUrl/api/entities")
                .header("Authorization", "Bearer $sessionToken")
                .post(payload.toString().toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val rawBody = response.body?.string().orEmpty()
                val json = runCatching { JSONObject(rawBody) }.getOrNull()
                if (!response.isSuccessful || json == null) {
                    val code = json?.optString("error").orEmpty().ifBlank {
                        if (response.code == 403) "PERMISSION_DENIED" else "PUSH_FAILED"
                    }
                    val msg = json?.optString("message").orEmpty().ifBlank {
                        "تعذر إرسال التعديلات إلى الجهاز الرئيسي (${response.code})"
                    }
                    throw SyncApiException(code, msg)
                }
                json.optLong("syncVersion", 1L)
            }
        }
    }
}
