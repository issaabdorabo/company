package com.example.data.cloud

import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.filter.eq
import io.github.jan.supabase.postgrest.result.decodeList
import io.github.jan.supabase.postgrest.result.decodeSingle
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.postgresChangeFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
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
            CreateWorkspaceParams(cleanName)
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
        RegisterCloudDeviceParams(
            workspace = workspaceId,
            deviceName = deviceName.trim().ifBlank { "جهاز Android" },
            fingerprint = stableFingerprint
        )
    ).decodeSingle<String>()

    suspend fun heartbeat(deviceId: String, appState: String = "FOREGROUND") {
        client.postgrest.rpc(
            "heartbeat_device",
            HeartbeatDeviceParams(deviceId, appState)
        )
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
            AddWorkspaceMemberParams(
                workspace = workspaceId,
                email = email.trim(),
                role = role,
                displayName = displayName.trim()
            )
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
            GrantWarehouseMembershipParams(
                workspace = workspaceId,
                warehouse = warehouseId,
                user = userId,
                permissions = permissions,
                makeDedicated = makeDedicated
            )
        )
    }

    suspend fun listConversations(workspaceId: String): List<CloudConversation> =
        client.postgrest["conversations"].select {
            eq("workspace_id", workspaceId)
        }.decodeList<CloudConversation>().sortedByDescending { it.createdAt }

    suspend fun createDirectConversation(workspaceId: String, otherUserId: String): String =
        client.postgrest.rpc(
            "create_direct_conversation",
            CreateDirectConversationParams(workspaceId, otherUserId)
        ).decodeSingle<String>()

    suspend fun createWarehouseConversation(
        workspaceId: String,
        warehouseId: String,
        title: String
    ): String = client.postgrest.rpc(
        "create_warehouse_conversation",
        CreateWarehouseConversationParams(workspaceId, warehouseId, title)
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
            SendMessageParams(
                conversation = conversationId,
                clientMessageId = UUID.randomUUID().toString(),
                body = cleanBody
            )
        ).decodeSingle<CloudMessage>()
    }

    fun observeMessages(conversationId: String): Flow<Unit> = callbackFlow {
        val channel = client.realtime.createChannel("cloud-chat-$conversationId")
        val changes = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter = "conversation_id=eq.$conversationId"
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
