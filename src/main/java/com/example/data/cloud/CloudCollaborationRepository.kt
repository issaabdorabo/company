package com.example.data.cloud

import io.github.jan.supabase.auth.auth
import com.example.data.sync.LocalSyncJson
import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
import org.json.JSONObject
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class CloudWorkspace(
    val id: String,
    val name: String,
    val ownerUserId: String,
    val createdAt: String
)

@Serializable
data class CloudWorkspaceMember(
    val workspaceId: String,
    val userId: String,
    val role: String,
    val displayName: String = "",
    val email: String = "",
    val isActive: Boolean = true
)

@Serializable
data class CloudWarehouse(
    val id: String,
    val workspaceId: String,
    val localId: Long? = null,
    val name: String,
    val updatedAt: String = ""
)

@Serializable
data class CloudWarehouseMembership(
    val warehouseId: String,
    val userId: String,
    val permissions: JsonObject
)

@Serializable
data class CloudDevice(
    val id: String,
    val workspaceId: String,
    val userId: String,
    val deviceName: String,
    val deviceFingerprint: String,
    val isRevoked: Boolean = false,
    val lastSeenAt: String? = null,
    val createdAt: String = ""
)

@Serializable
data class CloudConversation(
    val id: String,
    val workspaceId: String,
    val conversationType: String,
    val title: String = "",
    val warehouseId: String? = null,
    val createdBy: String,
    val createdAt: String
)

@Serializable
data class CloudMessage(
    val id: String,
    val conversationId: String,
    val senderUserId: String,
    val clientMessageId: String,
    val body: String,
    val attachmentPath: String? = null,
    val createdAt: String
)

@Serializable
private data class EnsureCloudWarehouseParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_local_id") val localId: Long,
    @SerialName("p_name") val name: String
)

@Serializable
private data class SeedCloudSnapshotParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_device") val device: String,
    @SerialName("p_records") val records: JsonArray
)

@Serializable
private data class PushCloudSnapshotParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_device") val device: String,
    @SerialName("p_client_operation_id") val clientOperationId: String,
    @SerialName("p_operation_type") val operationType: String,
    @SerialName("p_warehouse_ids") val warehouseIds: List<String>,
    @SerialName("p_title") val title: String,
    @SerialName("p_details") val details: String,
    @SerialName("p_records") val records: JsonArray
)

@Serializable
private data class CheckCloudWarehouseAccessParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_device") val device: String,
    @SerialName("p_operation_type") val operationType: String,
    @SerialName("p_warehouse_ids") val warehouseIds: List<String>
)

@Serializable
private data class CloudEntityRecordDto(
    val id: String,
    val workspaceId: String,
    val warehouseId: String? = null,
    val documentId: String,
    val entityType: String,
    val localId: Long,
    val payload: JsonObject,
    val isDeleted: Boolean = false,
    val updatedAt: String
)

@Serializable
private data class CreateWorkspaceParams(
    @SerialName("p_name") val name: String
)

@Serializable
private data class RegisterCloudDeviceParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_device_name") val deviceName: String,
    @SerialName("p_device_fingerprint") val fingerprint: String
)

@Serializable
private data class HeartbeatDeviceParams(
    @SerialName("p_device") val device: String,
    @SerialName("p_app_state") val appState: String
)

@Serializable
private data class AddWorkspaceMemberParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_email") val email: String,
    @SerialName("p_role") val role: String,
    @SerialName("p_display_name") val displayName: String
)

@Serializable
private data class GrantWarehouseMembershipParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_warehouse") val warehouse: String,
    @SerialName("p_user") val user: String,
    @SerialName("p_permissions") val permissions: JsonObject,
    @SerialName("p_make_dedicated") val makeDedicated: Boolean
)

@Serializable
private data class CreateDirectConversationParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_other_user") val otherUser: String
)

@Serializable
private data class CreateWarehouseConversationParams(
    @SerialName("p_workspace") val workspace: String,
    @SerialName("p_warehouse") val warehouse: String,
    @SerialName("p_title") val title: String
)

@Serializable
private data class SendMessageParams(
    @SerialName("p_conversation") val conversation: String,
    @SerialName("p_client_message_id") val clientMessageId: String,
    @SerialName("p_body") val body: String,
    @SerialName("p_attachment_path") val attachmentPath: String? = null
)

/**
 * Authenticated cloud collaboration API. All privileged writes go through SECURITY DEFINER
 * RPCs that validate auth.uid(), workspace membership, warehouse permissions and device identity.
 * The Android application only contains the Supabase publishable key.
 */
class CloudCollaborationRepository {
    private val client get() = SupabaseAuthClient.client

    fun currentUserIdOrNull(): String? =
        client.auth.currentSessionOrNull()?.user?.id

    fun currentUserEmailOrNull(): String? =
        client.auth.currentSessionOrNull()?.user?.email

    suspend fun listMyWorkspaces(): List<CloudWorkspace> {
        val userId = currentUserIdOrNull() ?: error("يجب ربط حساب Google أولاً")
        val memberships = client.postgrest["workspace_members"].select {
            eq("user_id", userId)
            eq("is_active", true)
        }.decodeList<CloudWorkspaceMember>()

        return memberships.mapNotNull { member ->
            runCatching { getWorkspace(member.workspaceId) }.getOrNull()
        }.distinctBy { it.id }
    }

    suspend fun createWorkspace(name: String): CloudWorkspace {
        val cleanName = name.trim()
        require(cleanName.length in 2..100) { "اسم مساحة العمل يجب أن يكون بين حرفين و100 حرف" }
        val workspaceId = client.postgrest.rpc(
            "create_workspace",
            buildJsonObject { put("p_name", JsonPrimitive(cleanName)) }
        ).decodeSingle<String>()
        return getWorkspace(workspaceId)
    }

    suspend fun getWorkspace(workspaceId: String): CloudWorkspace =
        client.postgrest["workspaces"].select {
            eq("id", workspaceId)
        }.decodeSingle<CloudWorkspace>()

    suspend fun registerDevice(
        workspaceId: String,
        deviceName: String,
        stableFingerprint: String
    ): String = client.postgrest.rpc(
        "register_cloud_device",
        buildJsonObject {
            put("p_workspace", JsonPrimitive(workspaceId))
            put("p_device_name", JsonPrimitive(deviceName.trim().ifBlank { "جهاز Android" }))
            put("p_device_fingerprint", JsonPrimitive(stableFingerprint))
        }
    ).decodeSingle<String>()

    suspend fun heartbeat(deviceId: String, appState: String = "FOREGROUND") {
        client.postgrest.rpc(
            "heartbeat_device",
            buildJsonObject {
                put("p_device", JsonPrimitive(deviceId))
                put("p_app_state", JsonPrimitive(appState))
            }
        )
    }

    suspend fun ensureCloudWarehouse(workspaceId: String, localId: Long, name: String): String =
        client.postgrest.rpc(
            "ensure_cloud_warehouse",
            buildJsonObject {
                put("p_workspace", JsonPrimitive(workspaceId))
                put("p_local_id", JsonPrimitive(localId))
                put("p_name", JsonPrimitive(name))
            }
        ).decodeSingle<String>()

    suspend fun seedWorkspaceSnapshot(
        workspaceId: String,
        deviceId: String,
        records: JsonArray
    ): Int = client.postgrest.rpc(
        "seed_cloud_workspace_snapshot",
        buildJsonObject {
            put("p_workspace", JsonPrimitive(workspaceId))
            put("p_device", JsonPrimitive(deviceId))
            put("p_records", records)
        }
    ).decodeSingle<Int>()

    suspend fun pushCloudSnapshot(
        workspaceId: String,
        deviceId: String,
        clientOperationId: String,
        operationType: String,
        warehouseIds: Set<String>,
        title: String,
        details: String,
        records: JsonArray
    ): String = client.postgrest.rpc(
        "push_cloud_entity_snapshot",
        buildJsonObject {
            put("p_workspace", JsonPrimitive(workspaceId))
            put("p_device", JsonPrimitive(deviceId))
            put("p_client_operation_id", JsonPrimitive(clientOperationId))
            put("p_operation_type", JsonPrimitive(operationType))
            put("p_warehouse_ids", buildJsonArray { warehouseIds.forEach { add(JsonPrimitive(it)) } })
            put("p_title", JsonPrimitive(title))
            put("p_details", JsonPrimitive(details))
            put("p_records", records)
        }
    ).decodeSingle<String>()

    suspend fun checkCloudWarehouseAccess(
        workspaceId: String,
        deviceId: String,
        operationType: String,
        warehouseIds: Set<String>
    ): Boolean = client.postgrest.rpc(
        "check_cloud_warehouse_access",
        buildJsonObject {
            put("p_workspace", JsonPrimitive(workspaceId))
            put("p_device", JsonPrimitive(deviceId))
            put("p_operation_type", JsonPrimitive(operationType))
            put("p_warehouse_ids", buildJsonArray { warehouseIds.forEach { add(JsonPrimitive(it)) } })
        }
    ).decodeSingle<Boolean>()

    suspend fun fetchCloudEntitySnapshot(workspaceId: String): List<CloudEntityRecord> {
        val rows = client.postgrest["cloud_entity_records"].select {
            eq("workspace_id", workspaceId)
        }.decodeList<CloudEntityRecordDto>()
        return rows.sortedByDescending { it.updatedAt }
            .distinctBy { it.documentId }
            .map { row ->
                CloudEntityRecord(
                    id = row.documentId,
                    workspaceId = row.workspaceId,
                    entityType = row.entityType,
                    localId = row.localId,
                    payload = LocalSyncJson.jsonObjectToMap(JSONObject(row.payload.toString())),
                    isDeleted = row.isDeleted
                )
            }
    }

    fun encodeCloudEntityRecords(
        entities: List<CloudEntityUpsert>,
        cloudWarehouseIdByLocalId: Map<Long, String>,
        targetWarehouseLocalIds: Set<Long>,
        includeGlobalRecords: Boolean
    ): JsonArray {
        val vouchersById = entities
            .filter { it.entityType == "voucher" }
            .associateBy { it.localId }

        fun number(value: Any?): Long? = (value as? Number)?.toLong()
        fun warehouseScopes(entity: CloudEntityUpsert): Set<Long> = when (entity.entityType) {
            "warehouse" -> setOf(entity.localId)
            "association", "adjustment", "serial" ->
                setOfNotNull(number(entity.payload["warehouseId"]))
            "voucher" -> setOfNotNull(
                number(entity.payload["sourceWarehouseId"]),
                number(entity.payload["destinationWarehouseId"])
            )
            "voucher_item" -> {
                val voucherId = number(entity.payload["voucherId"])
                val voucher = voucherId?.let { vouchersById[it] }
                if (voucher == null) emptySet() else setOfNotNull(
                    number(voucher.payload["sourceWarehouseId"]),
                    number(voucher.payload["destinationWarehouseId"])
                )
            }
            else -> emptySet()
        }

        val globalTypes = setOf(
            "item", "beneficiary", "supplier", "party", "category",
            "unit", "smaller_unit", "item_status"
        )
        val encoded = mutableListOf<Pair<CloudEntityUpsert, Long?>>()
        entities.forEach { entity ->
            val scopes = warehouseScopes(entity).intersect(targetWarehouseLocalIds)
            if (scopes.isNotEmpty()) {
                scopes.forEach { localWarehouseId -> encoded += entity to localWarehouseId }
            } else if (includeGlobalRecords && entity.entityType in globalTypes) {
                encoded += entity to null
            }
        }

        return buildJsonArray {
            encoded.forEach { (entity, localWarehouseId) ->
                val cloudWarehouseId = localWarehouseId?.let { cloudWarehouseIdByLocalId[it] }
                if (localWarehouseId != null && cloudWarehouseId == null) {
                    error("لا يوجد ربط سحابي للمخزن المحلي رقم $localWarehouseId")
                }
                add(
                    buildJsonObject {
                        put("document_id", JsonPrimitive(entity.docId))
                        put("entity_type", JsonPrimitive(entity.entityType))
                        put("local_id", JsonPrimitive(entity.localId))
                        put("warehouse_id", cloudWarehouseId?.let(::JsonPrimitive) ?: JsonNull)
                        put(
                            "payload",
                            Json.parseToJsonElement(LocalSyncJson.mapToJsonObject(entity.payload).toString())
                        )
                        put("is_deleted", JsonPrimitive(entity.isDeleted))
                    }
                )
            }
        }
    }

    suspend fun listWorkspaceMembers(workspaceId: String): List<CloudWorkspaceMember> =
        client.postgrest["workspace_members"].select {
            eq("workspace_id", workspaceId)
        }.decodeList<CloudWorkspaceMember>()

    suspend fun addMemberByEmail(
        workspaceId: String,
        email: String,
        role: String = "SUBUSER",
        displayName: String = ""
    ): String {
        require(email.trim().contains("@")) { "أدخل بريداً إلكترونياً صحيحاً" }
        return client.postgrest.rpc(
            "add_workspace_member_by_email",
            buildJsonObject {
                put("p_workspace", JsonPrimitive(workspaceId))
                put("p_email", JsonPrimitive(email.trim()))
                put("p_role", JsonPrimitive(role))
                put("p_display_name", JsonPrimitive(displayName.trim()))
            }
        ).decodeSingle<String>()
    }

    suspend fun listWarehouses(workspaceId: String): List<CloudWarehouse> =
        client.postgrest["warehouses"].select {
            eq("workspace_id", workspaceId)
        }.decodeList<CloudWarehouse>()

    suspend fun listWarehouseMemberships(warehouseId: String): List<CloudWarehouseMembership> =
        client.postgrest["warehouse_memberships"].select {
            eq("warehouse_id", warehouseId)
        }.decodeList<CloudWarehouseMembership>()

    suspend fun setWarehousePermissions(
        workspaceId: String,
        warehouseId: String,
        userId: String,
        permissions: JsonObject,
        makeDedicated: Boolean
    ) {
        client.postgrest.rpc(
            "grant_warehouse_membership",
            buildJsonObject {
                put("p_workspace", JsonPrimitive(workspaceId))
                put("p_warehouse", JsonPrimitive(warehouseId))
                put("p_user", JsonPrimitive(userId))
                put("p_permissions", permissions)
                put("p_make_dedicated", JsonPrimitive(makeDedicated))
            }
        )
    }

    suspend fun listConversations(workspaceId: String): List<CloudConversation> =
        client.postgrest["conversations"].select {
            eq("workspace_id", workspaceId)
        }.decodeList<CloudConversation>().sortedByDescending { it.createdAt }

    suspend fun createDirectConversation(workspaceId: String, otherUserId: String): String =
        client.postgrest.rpc(
            "create_direct_conversation",
            buildJsonObject {
                put("p_workspace", JsonPrimitive(workspaceId))
                put("p_other_user", JsonPrimitive(otherUserId))
            }
        ).decodeSingle<String>()

    suspend fun createWarehouseConversation(
        workspaceId: String,
        warehouseId: String,
        title: String
    ): String = client.postgrest.rpc(
        "create_warehouse_conversation",
        buildJsonObject {
            put("p_workspace", JsonPrimitive(workspaceId))
            put("p_warehouse", JsonPrimitive(warehouseId))
            put("p_title", JsonPrimitive(title))
        }
    ).decodeSingle<String>()

    suspend fun listMessages(conversationId: String): List<CloudMessage> =
        client.postgrest["messages"].select {
            eq("conversation_id", conversationId)
        }.decodeList<CloudMessage>().sortedBy { it.createdAt }

    suspend fun sendMessage(conversationId: String, body: String): CloudMessage {
        val cleanBody = body.trim()
        require(cleanBody.isNotBlank() && cleanBody.length <= 10_000) {
            "الرسالة فارغة أو أطول من الحد المسموح"
        }
        return client.postgrest.rpc(
            "send_message",
            buildJsonObject {
                put("p_conversation", JsonPrimitive(conversationId))
                put("p_client_message_id", JsonPrimitive(UUID.randomUUID().toString()))
                put("p_body", JsonPrimitive(cleanBody))
                put("p_attachment_path", JsonNull)
            }
        ).decodeSingle<CloudMessage>()
    }

    fun observeMessages(conversationId: String): Flow<Unit> = callbackFlow {
        val channel = client.realtime.channel("cloud-chat-$conversationId")
        val changes = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        val collector = launch {
            changes.collect { trySend(Unit).isSuccess }
        }
        channel.subscribe()
        awaitClose {
            collector.cancel()
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { client.realtime.removeChannel(channel) }
            }
        }
    }
}
