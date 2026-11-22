package com.example.data.sync

import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
import com.example.data.cloud.DevicePermissions
import com.example.data.cloud.WarehousePermission
import com.example.data.model.OperationAuditLog
import org.json.JSONArray
import org.json.JSONObject

object LocalSyncJson {
    fun permissionsToJson(p: DevicePermissions): JSONObject = JSONObject().apply {
        put("isMainDevice", p.isMainDevice)
        put("canAddItems", p.canAddItems)
        put("canInward", p.canInward)
        put("canDisburse", p.canDisburse)
        put("canEditDelete", p.canEditDelete)
        put("canEditInventory", p.canEditInventory)
        put("canDeleteItem", p.canDeleteItem)
        put("canEditIssueVoucher", p.canEditIssueVoucher)
        put("canDeleteIssueVoucher", p.canDeleteIssueVoucher)
        put("canEditSupplyVoucher", p.canEditSupplyVoucher)
        put("canDeleteSupplyVoucher", p.canDeleteSupplyVoucher)
        put("canEditCatalogItems", p.canEditCatalogItems)
        put("isKicked", p.isKicked)
        put("canBackup", p.canBackup)
        put("canViewAllWarehouses", p.canViewAllWarehouses)
        put("warehousePermissions", JSONArray().apply {
            p.warehousePermissions.forEach { grant ->
                put(JSONObject().apply {
                    put("warehouseId", grant.warehouseId)
                    put("canView", grant.canView)
                    put("canInward", grant.canInward)
                    put("canDisburse", grant.canDisburse)
                    put("canTransfer", grant.canTransfer)
                    put("canAdjust", grant.canAdjust)
                    put("canReports", grant.canReports)
                    put("canEdit", grant.canEdit)
                    put("canEditInventory", grant.canEditInventory)
                    put("canDeleteItem", grant.canDeleteItem)
                })
            }
        })
    }

    fun permissionsFromJson(obj: JSONObject?): DevicePermissions {
        if (obj == null) return DevicePermissions(isMainDevice = false)
        return DevicePermissions(
            isMainDevice = obj.optBoolean("isMainDevice", false),
            canAddItems = obj.optBoolean("canAddItems", false),
            canInward = obj.optBoolean("canInward", false),
            canDisburse = obj.optBoolean("canDisburse", false),
            canEditDelete = obj.optBoolean("canEditDelete", false),
            canEditInventory = obj.optBoolean("canEditInventory", obj.optBoolean("canEditDelete", false)),
            canDeleteItem = obj.optBoolean("canDeleteItem", obj.optBoolean("canEditDelete", false)),
            canEditIssueVoucher = obj.optBoolean("canEditIssueVoucher", obj.optBoolean("canEditDelete", false)),
            canDeleteIssueVoucher = obj.optBoolean("canDeleteIssueVoucher", obj.optBoolean("canEditDelete", false)),
            canEditSupplyVoucher = obj.optBoolean("canEditSupplyVoucher", obj.optBoolean("canEditDelete", false)),
            canDeleteSupplyVoucher = obj.optBoolean("canDeleteSupplyVoucher", obj.optBoolean("canEditDelete", false)),
            canEditCatalogItems = obj.optBoolean("canEditCatalogItems", false),
            isKicked = obj.optBoolean("isKicked", false),
            canBackup = obj.optBoolean("canBackup", false),
            // Legacy paired slots predate per-warehouse permissions: preserve their existing global access.
            canViewAllWarehouses = obj.optBoolean("canViewAllWarehouses", true),
            warehousePermissions = buildList {
                val grants = obj.optJSONArray("warehousePermissions") ?: JSONArray()
                for (i in 0 until grants.length()) {
                    val g = grants.optJSONObject(i) ?: continue
                    val id = g.optLong("warehouseId", 0L)
                    if (id > 0L) add(WarehousePermission(
                        warehouseId = id,
                        canView = g.optBoolean("canView", true),
                        canInward = g.optBoolean("canInward", false),
                        canDisburse = g.optBoolean("canDisburse", false),
                        canTransfer = g.optBoolean("canTransfer", false),
                        canAdjust = g.optBoolean("canAdjust", false),
                        canReports = g.optBoolean("canReports", false),
                        canEdit = g.optBoolean("canEdit", false),
                        canEditInventory = g.optBoolean("canEditInventory", g.optBoolean("canEdit", false)),
                        canDeleteItem = g.optBoolean("canDeleteItem", g.optBoolean("canEdit", false))
                    ))
                }
            }
        )
    }

    fun auditLogsToJsonArray(logs: List<OperationAuditLog>): JSONArray = JSONArray().apply {
        logs.forEach { log ->
            put(JSONObject().apply {
                put("id", log.id)
                put("timestamp", log.timestamp)
                put("actorName", log.actorName)
                put("deviceName", log.deviceName)
                put("operationType", log.operationType)
                put("title", log.title)
                put("details", log.details)
            })
        }
    }

    fun auditLogsFromJsonArray(arr: JSONArray?): List<OperationAuditLog> {
        if (arr == null) return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(OperationAuditLog(
                    id = 0L,
                    timestamp = obj.optLong("timestamp", System.currentTimeMillis()),
                    actorName = obj.optString("actorName", ""),
                    deviceName = obj.optString("deviceName", "الجهاز الرئيسي"),
                    operationType = obj.optString("operationType", "GENERAL"),
                    title = obj.optString("title", "عملية متزامنة"),
                    details = obj.optString("details", "")
                ))
            }
        }
    }

    fun upsertsToJsonArray(items: List<CloudEntityUpsert>): JSONArray {
        val arr = JSONArray()
        items.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.docId)
                    put("entityType", item.entityType)
                    put("localId", item.localId)
                    put("isDeleted", item.isDeleted)
                    put("payload", mapToJsonObject(item.payload))
                }
            )
        }
        return arr
    }

    fun recordsFromJsonArray(arr: JSONArray?, workspaceCode: String = ""): List<CloudEntityRecord> {
        if (arr == null) return emptyList()
        val result = ArrayList<CloudEntityRecord>(arr.length())
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            result.add(
                CloudEntityRecord(
                    id = obj.optString("id"),
                    workspaceId = workspaceCode,
                    entityType = obj.optString("entityType"),
                    localId = obj.optLong("localId", 0L),
                    payload = jsonObjectToMap(obj.optJSONObject("payload")),
                    isDeleted = obj.optBoolean("isDeleted", false)
                )
            )
        }
        return result
    }

    fun mapToJsonObject(map: Map<String, Any?>): JSONObject {
        val obj = JSONObject()
        map.forEach { (k, v) ->
            obj.put(k, wrapValue(v))
        }
        return obj
    }

    private fun wrapValue(value: Any?): Any {
        return when (value) {
            null -> JSONObject.NULL
            is Map<*, *> -> {
                val obj = JSONObject()
                value.forEach { (k, v) ->
                    if (k != null) obj.put(k.toString(), wrapValue(v))
                }
                obj
            }
            is Iterable<*> -> {
                val arr = JSONArray()
                value.forEach { arr.put(wrapValue(it)) }
                arr
            }
            is Number, is Boolean, is String -> value
            else -> value.toString()
        }
    }

    fun jsonObjectToMap(obj: JSONObject?): Map<String, Any?> {
        if (obj == null) return emptyMap()
        val map = LinkedHashMap<String, Any?>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = unwrapValue(obj.opt(key))
        }
        return map
    }

    private fun unwrapValue(value: Any?): Any? {
        return when (value) {
            null, JSONObject.NULL -> null
            is JSONObject -> jsonObjectToMap(value)
            is JSONArray -> buildList {
                for (i in 0 until value.length()) {
                    add(unwrapValue(value.opt(i)))
                }
            }
            else -> value
        }
    }
}
