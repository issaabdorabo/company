package com.example.data.sync

import org.json.JSONArray
import org.json.JSONObject

/**
 * Versioned Drive wire format. The sub-device retains operations until the main device
 * acknowledges their operation IDs. Only the owner-authored control document carries
 * permissions and application settings.
 */
data class DriveOperationEnvelope(
    val operationId: String,
    val sequence: Long,
    val createdAtMillis: Long,
    val payloadJson: String
) {
    init {
        require(operationId.isNotBlank()) { "معرّف العملية مطلوب." }
        require(sequence > 0L) { "رقم تسلسل العملية غير صالح." }
        require(createdAtMillis > 0L) { "وقت العملية غير صالح." }
        JSONObject(payloadJson)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("operationId", operationId)
        .put("sequence", sequence)
        .put("createdAtMillis", createdAtMillis)
        .put("payload", JSONObject(payloadJson))
}

data class DriveOperationBatch(
    val workspaceId: String,
    val deviceId: String,
    val deviceName: String,
    val generation: Long,
    val createdAtMillis: Long,
    val operations: List<DriveOperationEnvelope>
) {
    init {
        require(workspaceId.isNotBlank() && deviceId.isNotBlank() && deviceName.isNotBlank())
        require(generation >= 0L && createdAtMillis > 0L)
        require(operations.map { it.operationId }.distinct().size == operations.size) {
            "تحتوي دفعة المزامنة على معرفات عمليات مكررة."
        }
        require(operations.map { it.sequence }.distinct().size == operations.size) {
            "تحتوي دفعة المزامنة على أرقام تسلسل مكررة."
        }
    }

    fun toJson(): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("documentType", DOCUMENT_TYPE)
        .put("workspaceId", workspaceId)
        .put("deviceId", deviceId)
        .put("deviceName", deviceName)
        .put("generation", generation)
        .put("createdAtMillis", createdAtMillis)
        .put("operations", JSONArray().apply { operations.forEach { put(it.toJson()) } })
        .toString()

    companion object {
        const val SCHEMA_VERSION = 1
        const val DOCUMENT_TYPE = "operations"

        fun fromJson(raw: String): DriveOperationBatch {
            val root = JSONObject(raw)
            require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION) {
                "إصدار ملف مزامنة العمليات غير مدعوم."
            }
            require(root.optString("documentType") == DOCUMENT_TYPE) {
                "نوع ملف المزامنة غير صحيح."
            }
            val rows = root.optJSONArray("operations") ?: JSONArray()
            val parsed = buildList {
                for (index in 0 until rows.length()) {
                    val item = rows.optJSONObject(index) ?: error("سجل عملية غير صالح.")
                    val payload = item.optJSONObject("payload") ?: error("حمولة العملية مفقودة.")
                    add(DriveOperationEnvelope(
                        operationId = item.optString("operationId"),
                        sequence = item.optLong("sequence", 0L),
                        createdAtMillis = item.optLong("createdAtMillis", 0L),
                        payloadJson = payload.toString()
                    ))
                }
            }
            return DriveOperationBatch(
                workspaceId = root.optString("workspaceId"),
                deviceId = root.optString("deviceId"),
                deviceName = root.optString("deviceName"),
                generation = root.optLong("generation", -1L),
                createdAtMillis = root.optLong("createdAtMillis", 0L),
                operations = parsed
            )
        }
    }
}

/** Owner-authored control document; sub-devices must reject lower revisions. */
data class DriveDeviceControl(
    val workspaceId: String,
    val deviceId: String,
    val revision: Long,
    val issuedAtMillis: Long,
    val revoked: Boolean,
    val permissionsJson: String,
    val appSettingsJson: String
) {
    init {
        require(workspaceId.isNotBlank() && deviceId.isNotBlank())
        require(revision >= 0L && issuedAtMillis > 0L)
        JSONObject(permissionsJson)
        JSONObject(appSettingsJson)
    }

    fun toJson(): String = JSONObject()
        .put("schemaVersion", SCHEMA_VERSION)
        .put("documentType", DOCUMENT_TYPE)
        .put("workspaceId", workspaceId)
        .put("deviceId", deviceId)
        .put("revision", revision)
        .put("issuedAtMillis", issuedAtMillis)
        .put("revoked", revoked)
        .put("permissions", JSONObject(permissionsJson))
        .put("appSettings", JSONObject(appSettingsJson))
        .toString()

    companion object {
        const val SCHEMA_VERSION = 1
        const val DOCUMENT_TYPE = "device-control"

        fun fromJson(raw: String): DriveDeviceControl {
            val root = JSONObject(raw)
            require(root.optInt("schemaVersion", -1) == SCHEMA_VERSION)
            require(root.optString("documentType") == DOCUMENT_TYPE)
            return DriveDeviceControl(
                workspaceId = root.optString("workspaceId"),
                deviceId = root.optString("deviceId"),
                revision = root.optLong("revision", -1L),
                issuedAtMillis = root.optLong("issuedAtMillis", 0L),
                revoked = root.optBoolean("revoked", false),
                permissionsJson = (root.optJSONObject("permissions") ?: JSONObject()).toString(),
                appSettingsJson = (root.optJSONObject("appSettings") ?: JSONObject()).toString()
            )
        }
    }
}
