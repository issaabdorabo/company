package com.example.data.sync

import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
import com.example.data.cloud.DevicePermissions
import org.json.JSONArray
import org.json.JSONObject

object LocalSyncJson {
    fun permissionsToJson(p: DevicePermissions): JSONObject = JSONObject().apply {
        put("isMainDevice", p.isMainDevice)
        put("canAddItems", p.canAddItems)
        put("canInward", p.canInward)
        put("canDisburse", p.canDisburse)
        put("canEditDelete", p.canEditDelete)
        put("isKicked", p.isKicked)
    }

    fun permissionsFromJson(obj: JSONObject?): DevicePermissions {
        if (obj == null) return DevicePermissions(isMainDevice = false)
        return DevicePermissions(
            isMainDevice = obj.optBoolean("isMainDevice", false),
            canAddItems = obj.optBoolean("canAddItems", false),
            canInward = obj.optBoolean("canInward", false),
            canDisburse = obj.optBoolean("canDisburse", false),
            canEditDelete = obj.optBoolean("canEditDelete", false),
            isKicked = obj.optBoolean("isKicked", false)
        )
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
