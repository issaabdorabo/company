package com.example.data.cloud

import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

import io.github.jan.supabase.auth.auth
import com.example.data.sync.LocalSyncJson
import com.example.data.model.Warehouse
import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
import org.json.JSONObject
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.realtime.PostgresAction
import io.github.jan.supabase.realtime.broadcast
import io.github.jan.supabase.realtime.broadcastFlow
import io.github.jan.supabase.realtime.postgresChangeFlow
import io.github.jan.supabase.realtime.channel
import io.github.jan.supabase.realtime.realtime
import io.github.jan.supabase.storage.storage
import io.ktor.http.ContentType
import io.github.jan.supabase.postgrest.query.filter.FilterOperator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withContext
import kotlinx.coroutines.cancelAndJoin
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
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.util.UUID
import javax.crypto.spec.SecretKeySpec
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.Cipher
import java.security.SecureRandom
import java.security.MessageDigest
import kotlin.time.Duration.Companion.hours

@Serializable
data class CloudWorkspace(
    val id: String,
    val name: String,
    @SerialName("owner_user_id") val ownerUserId: String,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CloudWorkspaceMember(
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("user_id") val userId: String,
    val role: String,
    @SerialName("display_name") val displayName: String = "",
    val email: String = "",
    @SerialName("is_active") val isActive: Boolean = true
)

@Serializable
data class CloudRegisteredUser(
    @SerialName("user_id") val userId: String,
    val email: String,
    @SerialName("display_name") val displayName: String = "",
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class CloudWarehouse(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("local_id") val localId: Long? = null,
    val name: String,
    @SerialName("updated_at") val updatedAt: String = ""
)

@Serializable
data class CloudWarehouseMembership(
    @SerialName("warehouse_id") val warehouseId: String,
    @SerialName("user_id") val userId: String,
    val permissions: JsonObject
)

@Serializable
data class CloudDevice(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("user_id") val userId: String,
    @SerialName("device_name") val deviceName: String,
    @SerialName("device_fingerprint") val deviceFingerprint: String,
    @SerialName("is_revoked") val isRevoked: Boolean = false,
    @SerialName("last_seen_at") val lastSeenAt: String? = null,
    @SerialName("created_at") val createdAt: String = ""
)

@Serializable
data class ConversationPeerDetails(
    @SerialName("user_id") val userId: String,
    @SerialName("display_name") val displayName: String = "",
    val email: String = "",
    @SerialName("device_name") val deviceName: String = ""
)

@Serializable
data class CloudConversation(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("conversation_type") val conversationType: String,
    val title: String = "",
    @SerialName("warehouse_id") val warehouseId: String? = null,
    @SerialName("created_by") val createdBy: String,
    @SerialName("created_at") val createdAt: String,
    val participantDisplayName: String = "",
    val participantDeviceName: String = ""
)

@Serializable
data class CloudMessage(
    val id: String,
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("sender_user_id") val senderUserId: String,
    @SerialName("client_message_id") val clientMessageId: String,
    val body: String,
    @SerialName("attachment_path") val attachmentPath: String? = null,
    @SerialName("created_at") val createdAt: String,
    @SerialName("edited_at") val editedAt: String? = null,
    @SerialName("reply_to_message_id") val replyToMessageId: String? = null
)

@Serializable
data class CloudUserNotification(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("recipient_user_id") val recipientUserId: String,
    @SerialName("notification_type") val notificationType: String,
    val title: String,
    val body: String,
    @SerialName("related_operation_id") val relatedOperationId: String? = null,
    @SerialName("read_at") val readAt: String? = null,
    @SerialName("created_at") val createdAt: String
)

@Serializable
data class CloudOperationAuditEntry(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("operation_id") val operationId: String? = null,
    @SerialName("actor_user_id") val actorUserId: String,
    @SerialName("device_name") val deviceName: String = "",
    @SerialName("operation_type") val operationType: String,
    val title: String,
    val details: String = "",
    @SerialName("created_at") val createdAt: String
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

data class CloudEntitySnapshot(
    val records: List<CloudEntityRecord>,
    val updatedAtSignature: String
)

@Serializable
private data class CloudEntityRecordDto(
    val id: String,
    @SerialName("workspace_id") val workspaceId: String,
    @SerialName("warehouse_id") val warehouseId: String? = null,
    @SerialName("document_id") val documentId: String,
    @SerialName("entity_type") val entityType: String,
    @SerialName("local_id") val localId: Long,
    val payload: JsonObject,
    @SerialName("is_deleted") val isDeleted: Boolean = false,
    @SerialName("updated_at") val updatedAt: String
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
private data class ConversationParticipant(
    @SerialName("conversation_id") val conversationId: String,
    @SerialName("user_id") val userId: String
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

    private companion object {
        const val CHAT_ATTACHMENTS_BUCKET = "chat-attachments"
    }

    private fun firebaseInviteKey(email: String): String =
        email.trim().lowercase().replace(".", ",").replace("#", "_").replace("$", "_")
            .replace("[", "_").replace("]", "_").replace("/", "_")

    private fun inventoryTopic(workspaceId: String, deviceId: String, transportSecret: String): String {
        require(workspaceId.isNotBlank() && deviceId.isNotBlank() && transportSecret.isNotBlank()) {
            "بيانات قناة التعاون غير مكتملة"
        }
        // The topic itself is a capability: the random pairing secret is never placed
        // in the topic, only its SHA-256 digest, making other workspace device topics
        // impractical to enumerate or subscribe to.
        val proof = MessageDigest.getInstance("SHA-256").digest(
            "smart-inventory-topic-v1|$workspaceId|$deviceId|$transportSecret".toByteArray(Charsets.UTF_8)
        ).joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "workspace:$workspaceId:device:$deviceId:pair:$proof"
    }

    /**
     * Private Broadcast topics are scoped by a hash of each pairing secret and all
     * event bodies are separately encrypted end-to-end with AES-GCM. Realtime RLS
     * limits access to active members of the workspace; each device still listens
     * only on its independently derived topic.
     */
    fun observeWorkspaceEvents(
        workspaceId: String,
        deviceId: String,
        transportSecrets: List<String>
    ): Flow<JsonObject> = callbackFlow {
        val secrets = transportSecrets.filter { it.isNotBlank() }.distinct()
        require(secrets.isNotEmpty()) { "مفتاح قناة التعاون غير متوفر" }
        val channels = secrets.map { secret ->
            secret to client.realtime.channel(inventoryTopic(workspaceId, deviceId, secret)) { isPrivate = true }
        }
        val collectors = mutableListOf<Job>()
        try {
            channels.forEach { (secret, channel) ->
                val events = channel.broadcastFlow<JsonObject>("inventory_event")
                collectors += launch {
                    events.collect { envelope ->
                        val decoded = decryptInventoryEnvelope(workspaceId, deviceId, secret, envelope)
                        if (decoded != null) {
                            val authenticatedEvent = buildJsonObject {
                                decoded.forEach { (key, value) -> put(key, value) }
                                // Local-only metadata: this is the exact pairing secret used to
                                // decrypt the frame, never a field transmitted over Realtime.
                                put("_transportSecret", secret)
                            }
                            trySend(authenticatedEvent).isSuccess
                        }
                    }
                }
            }
            channels.forEach { (_, channel) -> channel.subscribe(blockUntilSubscribed = true) }
        } catch (failure: Exception) {
            collectors.forEach { it.cancel() }
            channels.forEach { (_, channel) -> runCatching { client.realtime.removeChannel(channel) } }
            close(failure)
            return@callbackFlow
        }
        awaitClose {
            collectors.forEach { it.cancel() }
            CoroutineScope(Dispatchers.IO).launch {
                channels.forEach { (_, channel) ->
                    runCatching { client.realtime.removeChannel(channel) }
                }
            }
        }
    }

    private fun inventoryKey(workspaceId: String, transportSecret: String): SecretKeySpec {
        require(workspaceId.isNotBlank() && transportSecret.isNotBlank()) {
            "مفتاح تشفير قناة التعاون غير مكتمل"
        }
        val keyBytes = MessageDigest.getInstance("SHA-256").digest(
            "smart-inventory-realtime-v1|$workspaceId|$transportSecret".toByteArray(Charsets.UTF_8)
        )
        return SecretKeySpec(keyBytes, "AES")
    }

    private fun encryptInventoryEnvelope(
        workspaceId: String,
        deviceId: String,
        transportSecret: String,
        payload: JsonObject
    ): JsonObject {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, inventoryKey(workspaceId, transportSecret), GCMParameterSpec(128, nonce))
        cipher.updateAAD(inventoryTopic(workspaceId, deviceId, transportSecret).toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(payload.toString().toByteArray(Charsets.UTF_8))
        return buildJsonObject {
            put("secureVersion", JsonPrimitive(1))
            put("nonce", JsonPrimitive(Base64.encodeToString(nonce, Base64.NO_WRAP)))
            put("ciphertext", JsonPrimitive(Base64.encodeToString(encrypted, Base64.NO_WRAP)))
        }
    }

    private fun decryptInventoryEnvelope(
        workspaceId: String,
        deviceId: String,
        transportSecret: String,
        envelope: JsonObject
    ): JsonObject? {
        return runCatching {
            if (envelope["secureVersion"]?.jsonPrimitive?.contentOrNull != "1") return@runCatching null
            val nonceText = envelope["nonce"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val encryptedText = envelope["ciphertext"]?.jsonPrimitive?.contentOrNull ?: return@runCatching null
            val nonce = Base64.decode(nonceText, Base64.NO_WRAP)
            val encrypted = Base64.decode(encryptedText, Base64.NO_WRAP)
            if (nonce.size != 12) return@runCatching null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, inventoryKey(workspaceId, transportSecret), GCMParameterSpec(128, nonce))
            cipher.updateAAD(inventoryTopic(workspaceId, deviceId, transportSecret).toByteArray(Charsets.UTF_8))
            val plaintext = cipher.doFinal(encrypted)
            Json.parseToJsonElement(String(plaintext, Charsets.UTF_8)) as? JsonObject
        }.getOrNull()
    }

    suspend fun broadcastWorkspaceEvent(
        workspaceId: String,
        recipientDeviceId: String,
        payload: JsonObject,
        transportSecret: String
    ) {
        val channel = client.realtime.channel(inventoryTopic(workspaceId, recipientDeviceId, transportSecret)) { isPrivate = true }
        try {
            channel.subscribe(blockUntilSubscribed = true)
            channel.broadcast(
                event = "inventory_event",
                message = encryptInventoryEnvelope(workspaceId, recipientDeviceId, transportSecret, payload)
            )
        } finally {
            runCatching { client.realtime.removeChannel(channel) }
        }
    }

    /**
     * Publish to one device topic and wait for the ACK on the sender's own topic.
     * The target can never broadcast its operation payload to unrelated sub-devices.
     */
    suspend fun sendWorkspaceEventAndAwaitAck(
        workspaceId: String,
        targetDeviceId: String,
        payload: JsonObject,
        transportSecret: String,
        timeoutMillis: Long = 25_000L,
        expectedSenderDeviceId: String? = null
    ): JsonObject = coroutineScope {
        val senderDeviceId = payload["senderDeviceId"]?.jsonPrimitive?.contentOrNull
            ?: error("معرّف الجهاز المرسل غير موجود")
        require(senderDeviceId.isNotBlank() && targetDeviceId.isNotBlank() && transportSecret.isNotBlank()) {
            "معرّفات أجهزة التعاون أو مفتاح الربط غير مكتملة"
        }
        val operationId = payload["operationId"]?.jsonPrimitive?.contentOrNull
            ?: error("معرّف العملية غير موجود")

        val senderChannel = client.realtime.channel(inventoryTopic(workspaceId, senderDeviceId, transportSecret)) { isPrivate = true }
        val targetChannel = client.realtime.channel(inventoryTopic(workspaceId, targetDeviceId, transportSecret)) { isPrivate = true }
        val ack = CompletableDeferred<JsonObject>()
        val acknowledgements = senderChannel.broadcastFlow<JsonObject>("inventory_event")
        val collector = launch {
            acknowledgements.collect { envelope ->
                val response = decryptInventoryEnvelope(workspaceId, senderDeviceId, transportSecret, envelope) ?: return@collect
                val responseId = response["operationId"]?.jsonPrimitive?.contentOrNull
                val type = response["type"]?.jsonPrimitive?.contentOrNull
                val responderId = response["senderDeviceId"]?.jsonPrimitive?.contentOrNull
                val expectedSenderMatches = expectedSenderDeviceId == null || responderId == expectedSenderDeviceId
                if (type == "ACK" && responseId == operationId && expectedSenderMatches) {
                    ack.complete(response)
                }
            }
        }
        try {
            senderChannel.subscribe(blockUntilSubscribed = true)
            targetChannel.subscribe(blockUntilSubscribed = true)
            targetChannel.broadcast(
                event = "inventory_event",
                message = encryptInventoryEnvelope(workspaceId, targetDeviceId, transportSecret, payload)
            )
            withTimeout(timeoutMillis) { ack.await() }
        } finally {
            collector.cancelAndJoin()
            runCatching { client.realtime.removeChannel(targetChannel) }
            runCatching { client.realtime.removeChannel(senderChannel) }
        }
    }

    fun currentUserIdOrNull(): String? =
        client.auth.currentSessionOrNull()?.user?.id

    fun currentUserEmailOrNull(): String? =
        client.auth.currentSessionOrNull()?.user?.email

    private suspend fun runFirebaseMirror(label: String, action: suspend () -> Unit) {
        try {
            action()
        } catch (failure: Exception) {
            // Firebase rules are deployed separately from the Android APK. Until the
            // project rules are published, keep the existing Supabase workflow usable.
            Log.w("CloudCollaboration", "Firebase mirror unavailable ($label)", failure)
        }
    }

    private suspend fun mirrorFirebaseWorkspaceAccess(workspace: CloudWorkspace) = runFirebaseMirror("workspace access") { withContext(Dispatchers.IO) {
        val firebaseUser = FirebaseAuthClient.auth.currentUser ?: return@withContext
        val root = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
        val workspaceRef = root.child("workspaces").child(workspace.id)
        val workspaceSnapshot = Tasks.await(workspaceRef.get())
        val isSupabaseOwner = workspace.ownerUserId == currentUserIdOrNull()
        val isFirebaseWorkspaceOwner =
            workspaceSnapshot.child("ownerUserId").getValue(String::class.java) == firebaseUser.uid

        if (!workspaceSnapshot.exists() && isSupabaseOwner) {
            Tasks.await(workspaceRef.updateChildren(mapOf(
                "name" to workspace.name,
                "ownerUserId" to firebaseUser.uid,
                "createdAt" to ServerValue.TIMESTAMP
            )))
        } else if (!workspaceSnapshot.exists()) {
            return@withContext
        }

        val memberRef = workspaceRef.child("members").child(firebaseUser.uid)
        val memberSnapshot = Tasks.await(memberRef.get())
        if (memberSnapshot.exists()) return@withContext

        if (isSupabaseOwner || isFirebaseWorkspaceOwner) {
            Tasks.await(memberRef.updateChildren(mapOf(
                "userId" to firebaseUser.uid,
                "email" to (firebaseUser.email ?: "").trim().lowercase(),
                "displayName" to (firebaseUser.displayName ?: ""),
                "role" to "OWNER",
                "active" to true,
                "createdAt" to ServerValue.TIMESTAMP
            )))
            return@withContext
        }

        val email = firebaseUser.email?.trim()?.lowercase().orEmpty()
        if (email.isBlank()) return@withContext
        val inviteKey = firebaseInviteKey(email)
        val invite = Tasks.await(root.child("emailInvites").child(inviteKey).get())
        val expiresAt = invite.child("expiresAt").getValue(Long::class.java) ?: return@withContext
        if (invite.child("active").getValue(Boolean::class.java) != true ||
            invite.child("workspaceId").getValue(String::class.java) != workspace.id ||
            expiresAt <= System.currentTimeMillis() ||
            invite.child("role").getValue(String::class.java) != "SUBUSER") return@withContext

        Tasks.await(memberRef.updateChildren(mapOf(
            "userId" to firebaseUser.uid,
            "email" to email,
            "displayName" to invite.child("displayName").getValue(String::class.java).orEmpty()
                .ifBlank { firebaseUser.displayName.orEmpty() },
            "role" to "SUBUSER",
            "active" to true,
            "inviteKey" to inviteKey,
            "createdAt" to ServerValue.TIMESTAMP
        )))
    } }

    suspend fun listMyWorkspaces(): List<CloudWorkspace> {
        val userId = currentUserIdOrNull() ?: error("يجب ربط حساب Google أولاً")
        val memberships = client.postgrest["workspace_members"].select {
            filter {
            eq("user_id", userId)
            eq("is_active", true)
        
            }
        }.decodeList<CloudWorkspaceMember>()

        val workspaces = memberships.mapNotNull { member ->
            runCatching { getWorkspace(member.workspaceId) }.getOrNull()
        }.distinctBy { it.id }
        workspaces.forEach { mirrorFirebaseWorkspaceAccess(it) }
        return workspaces
    }

    suspend fun canCreateFirstWorkspace(): Boolean =
        client.postgrest.rpc("can_create_first_workspace").decodeAs<Boolean>()

    suspend fun createWorkspace(name: String): CloudWorkspace {
        val cleanName = name.trim()
        require(cleanName.length in 2..100) { "اسم مساحة العمل يجب أن يكون بين حرفين و100 حرف" }
        val workspaceId = client.postgrest.rpc(
            "create_workspace",
            buildJsonObject { put("p_name", JsonPrimitive(cleanName)) }
        ).decodeAs<String>()
        val workspace = getWorkspace(workspaceId)
        mirrorFirebaseWorkspaceAccess(workspace)
        return workspace
    }

    suspend fun getWorkspace(workspaceId: String): CloudWorkspace =
        client.postgrest["workspaces"].select {
            filter {
            eq("id", workspaceId)
        
            }
        }.decodeSingle<CloudWorkspace>()

    suspend fun registerDevice(
        workspaceId: String,
        deviceName: String,
        stableFingerprint: String
    ): String {
        val deviceId = client.postgrest.rpc(
            "register_cloud_device",
            buildJsonObject {
                put("p_workspace", JsonPrimitive(workspaceId))
                put("p_device_name", JsonPrimitive(deviceName.trim().ifBlank { "جهاز Android" }))
                put("p_device_fingerprint", JsonPrimitive(stableFingerprint))
            }
        ).decodeAs<String>()

        runFirebaseMirror("device registration") { withContext(Dispatchers.IO) {
            val user = FirebaseAuthClient.auth.currentUser ?: error("جلسة Firebase غير متاحة")
            val device = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
                .child("workspaces").child(workspaceId).child("devices").child(deviceId)
            val existing = Tasks.await(device.get())
            val cleanDeviceName = deviceName.trim().ifBlank { "جهاز Android" }
            val values = mutableMapOf<String, Any>(
                "deviceName" to cleanDeviceName,
                "appState" to "FOREGROUND",
                "lastSeenAt" to ServerValue.TIMESTAMP
            )
            if (!existing.exists()) values.putAll(mapOf(
                "workspaceId" to workspaceId,
                "userId" to user.uid,
                "deviceFingerprint" to stableFingerprint,
                "isRevoked" to false,
                "createdAt" to ServerValue.TIMESTAMP
            ))
            Tasks.await(device.updateChildren(values))
        } }
        return deviceId
    }

    suspend fun listWorkspaceDevices(workspaceId: String): List<CloudDevice> =
        client.postgrest["devices"].select {
            filter { eq("workspace_id", workspaceId); eq("is_revoked", false) }
        }.decodeList<CloudDevice>().sortedBy { it.deviceName.lowercase() }

    suspend fun replaceCloudWorkspaceSnapshot(
        workspaceId: String,
        deviceId: String,
        records: JsonArray
    ): Int = client.postgrest.rpc(
        "replace_cloud_workspace_snapshot",
        buildJsonObject {
            put("p_workspace", JsonPrimitive(workspaceId))
            put("p_device", JsonPrimitive(deviceId))
            put("p_records", records)
        }
    ).decodeAs<Int>()

    suspend fun heartbeat(
        deviceId: String,
        appState: String = "FOREGROUND",
        workspaceId: String? = null
    ) {
        client.postgrest.rpc(
            "heartbeat_device",
            buildJsonObject {
                put("p_device", JsonPrimitive(deviceId))
                put("p_app_state", JsonPrimitive(appState))
            }
        )
        runFirebaseMirror("device heartbeat") { withContext(Dispatchers.IO) {
            val user = FirebaseAuthClient.auth.currentUser ?: error("جلسة Firebase غير متاحة")
            val activeWorkspaceId = workspaceId?.takeIf { it.isNotBlank() }
                ?: deviceWorkspaceId(deviceId)
            val root = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
            val deviceRef = root.child("workspaces").child(activeWorkspaceId).child("devices").child(deviceId)
            Tasks.await(deviceRef.updateChildren(mapOf(
                "userId" to user.uid,
                "isRevoked" to false,
                "lastSeenAt" to ServerValue.TIMESTAMP,
                "appState" to appState
            )))
            Tasks.await(root.child("users").child(user.uid)
                .updateChildren(mapOf("lastSeenAt" to ServerValue.TIMESTAMP)))
        } }
    }

    private suspend fun deviceWorkspaceId(deviceId: String): String {
        val rows = client.postgrest["devices"].select {
            filter { eq("id", deviceId) }
        }.decodeList<CloudDevice>()
        return rows.firstOrNull()?.workspaceId ?: error("تعذر تحديد مساحة عمل الجهاز")
    }

    /**
     * Supabase stores only the warehouse identity needed to scope memberships and chat.
     * Operational details, stock and vouchers remain in the local Room database.
     */
    suspend fun ensureCloudWarehouse(
        workspaceId: String,
        localId: Long,
        name: String
    ): String = client.postgrest.rpc(
        "ensure_cloud_warehouse",
        buildJsonObject {
            put("p_workspace", JsonPrimitive(workspaceId))
            put("p_local_id", JsonPrimitive(localId))
            put("p_name", JsonPrimitive(name.trim()))
        }
    ).decodeAs<String>()

    suspend fun ensureCloudWarehouse(workspaceId: String, warehouse: Warehouse): String =
        ensureCloudWarehouse(workspaceId, warehouse.id, warehouse.name)

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
    ).decodeAs<Int>()

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
    ).decodeAs<String>()

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
    ).decodeAs<Boolean>()

    suspend fun fetchCloudEntitySnapshot(workspaceId: String): List<CloudEntityRecord> =
        fetchCloudEntitySnapshotVersioned(workspaceId).records

    suspend fun fetchCloudEntitySnapshotVersioned(workspaceId: String): CloudEntitySnapshot {
        val rows = client.postgrest["cloud_entity_records"].select {
            filter {
            eq("workspace_id", workspaceId)
        
            }
        }.decodeList<CloudEntityRecordDto>()
        val signature = rows.maxOfOrNull { it.updatedAt }.orEmpty()
        val records = rows.sortedByDescending { it.updatedAt }
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
        return CloudEntitySnapshot(records, signature)
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
            "unit", "smaller_unit", "item_status", "serial_sub_unit"
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
            filter {
                eq("workspace_id", workspaceId)
            }
        }.decodeList<CloudWorkspaceMember>()

    /**
     * RLS restricts this result to notifications addressed to the signed-in user.
     * The workspace filter keeps the polling query scoped to the active collaboration space.
     */
    suspend fun listMyOperationNotifications(workspaceId: String): List<CloudUserNotification> =
        client.postgrest["user_notifications"].select {
            filter {
                eq("workspace_id", workspaceId)
            }
        }.decodeList<CloudUserNotification>().sortedBy { it.createdAt }

    /**
     * Database RLS limits audit entries to the workspace owner or users assigned to
     * the warehouses referenced by each operation.
     */
    suspend fun listAllowedOperationAuditEntries(workspaceId: String): List<CloudOperationAuditEntry> =
        client.postgrest["operation_audit_logs"].select {
            filter {
                eq("workspace_id", workspaceId)
            }
        }.decodeList<CloudOperationAuditEntry>().sortedBy { it.createdAt }

    suspend fun listRegisteredWorkspaceUsers(workspaceId: String): List<CloudRegisteredUser> =
        client.postgrest.rpc(
            "list_registered_workspace_users",
            buildJsonObject { put("p_workspace", JsonPrimitive(workspaceId)) }
        ).decodeList<CloudRegisteredUser>()

    suspend fun addMemberByEmail(
        workspaceId: String,
        email: String,
        role: String = "SUBUSER",
        displayName: String = ""
    ): String {
        require(email.trim().contains("@")) { "أدخل بريداً إلكترونياً صحيحاً" }
        require(role == "SUBUSER") { "دور الدعوة غير مدعوم" }
        val memberId = client.postgrest.rpc(
            "add_workspace_member_by_email",
            buildJsonObject {
                put("p_workspace", JsonPrimitive(workspaceId))
                put("p_email", JsonPrimitive(email.trim()))
                put("p_role", JsonPrimitive(role))
                put("p_display_name", JsonPrimitive(displayName.trim()))
            }
        ).decodeAs<String>()

        runFirebaseMirror("email invitation") { withContext(Dispatchers.IO) {
            val owner = FirebaseAuthClient.auth.currentUser ?: error("جلسة Firebase غير متاحة")
            val normalizedEmail = email.trim().lowercase()
            val inviteKey = firebaseInviteKey(normalizedEmail)
            val expiry = System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L
            Tasks.await(FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
                .child("emailInvites").child(inviteKey).updateChildren(mapOf(
                    "email" to normalizedEmail,
                    "workspaceId" to workspaceId,
                    "role" to "SUBUSER",
                    "active" to true,
                    "displayName" to displayName.trim(),
                    "createdAt" to ServerValue.TIMESTAMP,
                    "expiresAt" to expiry,
                    "createdBy" to owner.uid
                )))
        } }
        return memberId
    }

    /**
     * Transitional Firebase mirror for the collaboration metadata being moved off Supabase.
     * Inventory rows and operation payloads are deliberately excluded.
     */
    private suspend fun mirrorFirebaseWarehouses(
        workspaceId: String,
        warehouses: List<CloudWarehouse>
    ) = runFirebaseMirror("warehouse metadata") { withContext(Dispatchers.IO) {
        val firebaseUser = FirebaseAuthClient.auth.currentUser ?: return@withContext
        val root = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
        val workspace = root.child("workspaces").child(workspaceId)
        val membership = Tasks.await(workspace.child("members").child(firebaseUser.uid).get())
        val isOwner = Tasks.await(workspace.child("ownerUserId").get())
            .getValue(String::class.java) == firebaseUser.uid
        if (!isOwner && membership.child("active").getValue(Boolean::class.java) != true) {
            return@withContext
        }
        warehouses.forEach { warehouse ->
            Tasks.await(workspace.child("warehouses").child(warehouse.id).updateChildren(mapOf(
                "warehouseId" to warehouse.id,
                "workspaceId" to workspaceId,
                "localId" to (warehouse.localId ?: -1L),
                "name" to warehouse.name,
                "updatedAt" to ServerValue.TIMESTAMP
            )))
        }
    } }

    private suspend fun mirrorFirebaseCurrentUserPermissions(
        warehouseId: String,
        memberships: List<CloudWarehouseMembership>
    ) = runFirebaseMirror("warehouse permissions") { withContext(Dispatchers.IO) {
        val firebaseUser = FirebaseAuthClient.auth.currentUser ?: return@withContext
        val supabaseUserId = currentUserIdOrNull() ?: return@withContext
        val ownMembership = memberships.firstOrNull { it.userId == supabaseUserId } ?: return@withContext
        val root = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
        val workspaceId = Tasks.await(root.child("workspaces").orderByChild("warehouses/$warehouseId/warehouseId").equalTo(warehouseId).get())
            .children.firstOrNull()?.key ?: return@withContext
        val workspace = root.child("workspaces").child(workspaceId)
        val member = Tasks.await(workspace.child("members").child(firebaseUser.uid).get())
        if (member.child("active").getValue(Boolean::class.java) != true &&
            workspace.child("ownerUserId").get().let { Tasks.await(it).getValue(String::class.java) != firebaseUser.uid }) {
            return@withContext
        }
        Tasks.await(workspace.child("warehouseMemberships").child(warehouseId)
            .child(firebaseUser.uid).updateChildren(mapOf(
                "warehouseId" to warehouseId,
                "userId" to firebaseUser.uid,
                "permissionsJson" to ownMembership.permissions.toString(),
                "updatedAt" to ServerValue.TIMESTAMP
            )))
    } }

    suspend fun listWarehouses(workspaceId: String): List<CloudWarehouse> {
        val warehouses = client.postgrest["warehouses"].select {
            filter { eq("workspace_id", workspaceId) }
        }.decodeList<CloudWarehouse>()
        mirrorFirebaseWarehouses(workspaceId, warehouses)
        return warehouses
    }

    suspend fun listWarehouseMemberships(warehouseId: String): List<CloudWarehouseMembership> {
        val memberships = client.postgrest["warehouse_memberships"].select {
            filter { eq("warehouse_id", warehouseId) }
        }.decodeList<CloudWarehouseMembership>()
        mirrorFirebaseCurrentUserPermissions(warehouseId, memberships)
        return memberships
    }

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
        if (userId == currentUserIdOrNull()) {
            mirrorFirebaseCurrentUserPermissions(
                warehouseId,
                listOf(CloudWarehouseMembership(warehouseId, userId, permissions))
            )
        }
    }

    suspend fun listConversations(workspaceId: String, ownerUserId: String): List<CloudConversation> {
        val currentUserId = currentUserIdOrNull() ?: error("يجب تسجيل الدخول بحساب Google")
        val visible = client.postgrest["conversations"].select {
            filter { eq("workspace_id", workspaceId) }
        }.decodeList<CloudConversation>().sortedByDescending { it.createdAt }

        val directIds = visible.filter { it.conversationType == "DIRECT" }.map { it.id }
        val participantsByConversation = directIds.associateWith { id ->
            client.postgrest["conversation_participants"].select {
                filter { eq("conversation_id", id) }
            }.decodeList<ConversationParticipant>().map { it.userId }.toSet()
        }
        // The peer details RPC checks that the caller is a participant, then returns only
        // the other participant's public chat identity and current device name.
        val peersByConversation = directIds.associateWith { id ->
            runCatching {
                client.postgrest.rpc(
                    "get_conversation_peer_details",
                    buildJsonObject { put("p_conversation", JsonPrimitive(id)) }
                ).decodeList<ConversationPeerDetails>().firstOrNull()
            }.getOrNull()
        }
        val enrichedVisible = visible.map { conversation ->
            if (conversation.conversationType != "DIRECT") return@map conversation
            val peer = peersByConversation[conversation.id]
            val peerName = peer?.displayName?.takeIf { it.isNotBlank() }
                ?: peer?.email.orEmpty()
            conversation.copy(
                title = conversation.title.ifBlank { peerName },
                participantDisplayName = peerName,
                participantDeviceName = peer?.deviceName.orEmpty()
            )
        }

        val allWarehouses = listWarehouses(workspaceId)
        val grantsByWarehouse = allWarehouses.associate { warehouse ->
            warehouse.id to listWarehouseMemberships(warehouse.id)
                .filter { it.permissions["canView"]?.toString() == "true" }
        }
        val multiUserWarehouseIds = grantsByWarehouse.filterValues { it.size > 1 }.keys
        if (currentUserId == ownerUserId) {
            return enrichedVisible.filter { conversation ->
                conversation.conversationType != "WAREHOUSE_GROUP" ||
                    conversation.warehouseId in multiUserWarehouseIds
            }
        }

        val assignedWarehouseIds = multiUserWarehouseIds.filter { warehouseId ->
            grantsByWarehouse[warehouseId].orEmpty().any { it.userId == currentUserId }
        }.toSet()
        return enrichedVisible.filter { conversation ->
            when (conversation.conversationType) {
                "DIRECT" -> participantsByConversation[conversation.id].orEmpty().contains(ownerUserId)
                "WAREHOUSE_GROUP" -> conversation.warehouseId in assignedWarehouseIds
                else -> false
            }
        }
    }

    suspend fun createDirectConversation(workspaceId: String, otherUserId: String): String =
        client.postgrest.rpc(
            "create_direct_conversation",
            buildJsonObject {
                put("p_workspace", JsonPrimitive(workspaceId))
                put("p_other_user", JsonPrimitive(otherUserId))
            }
        ).decodeAs<String>()

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
    ).decodeAs<String>()

    suspend fun listMessages(conversationId: String): List<CloudMessage> {
        val hiddenIds = client.postgrest["message_user_deletions"].select {
            filter { eq("user_id", currentUserIdOrNull() ?: error("يجب تسجيل الدخول")) }
        }.decodeList<MessageDeletionRow>().map { it.messageId }.toSet()
        return client.postgrest["messages"].select {
            filter { eq("conversation_id", conversationId) }
        }.decodeList<CloudMessage>().filterNot { it.id in hiddenIds }.sortedBy { it.createdAt }
    }

    suspend fun sendMessage(conversationId: String, body: String, replyToMessageId: String? = null): CloudMessage =
        insertMessage(conversationId, body.trim(), null, replyToMessageId)

    suspend fun sendAttachment(
        conversationId: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): CloudMessage {
        require(bytes.isNotEmpty()) { "الملف فارغ" }
        require(bytes.size <= 20 * 1024 * 1024) { "حجم المرفق يتجاوز 20 ميجابايت" }
        val normalizedMime = mimeType.trim().lowercase()
        val allowedMimeTypes = setOf(
            "image/jpeg", "image/png", "image/webp", "image/gif",
            "application/pdf", "audio/mp4", "audio/aac",
            "audio/ogg", "audio/mpeg", "audio/webm", "audio/3gpp",
            "application/octet-stream", "text/plain", "text/csv", "application/csv",
            "text/markdown", "application/json", "application/xml", "text/xml",
            "application/rtf", "text/rtf", "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "application/vnd.oasis.opendocument.text",
            "application/vnd.oasis.opendocument.spreadsheet",
            "application/vnd.oasis.opendocument.presentation",
            "application/zip", "application/x-zip-compressed",
            "application/x-7z-compressed", "application/vnd.rar", "application/x-rar-compressed"
        )
        require(normalizedMime in allowedMimeTypes) {
            "نوع الملف غير مدعوم. الملفات المدعومة تشمل PDF وWord وExcel وPowerPoint والنصوص وZIP والصور والتسجيلات الصوتية."
        }
        val userId = currentUserIdOrNull()
            ?: error("يجب تسجيل الدخول بحساب Google أولاً")
        val extension = fileName.substringAfterLast('.', "bin")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "bin"
        val path = "$conversationId/$userId/${UUID.randomUUID()}.$extension"
        val bucket = client.storage.from(CHAT_ATTACHMENTS_BUCKET)
        bucket.upload(path, bytes) {
            contentType = ContentType.parse(normalizedMime)
        }
        return try {
            insertMessage(conversationId, fileName.trim().ifBlank { "مرفق" }.take(180), path)
        } catch (failure: Exception) {
            runCatching { bucket.delete(path) }
            throw failure
        }
    }

    private suspend fun insertMessage(
        conversationId: String,
        body: String,
        attachmentPath: String?,
        replyToMessageId: String? = null
    ): CloudMessage {
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
                put("p_attachment_path", attachmentPath?.let(::JsonPrimitive) ?: JsonNull)
                put("p_reply_to_message_id", replyToMessageId?.let(::JsonPrimitive) ?: JsonNull)
            }
        ).decodeAs<CloudMessage>()
    }

    @Serializable
    private data class MessageDeletionRow(@SerialName("message_id") val messageId: String)

    suspend fun hideMessageForMe(messageId: String) {
        client.postgrest.rpc(
            "hide_message_for_me",
            buildJsonObject { put("p_message", JsonPrimitive(messageId)) }
        )
    }

    suspend fun editMessage(messageId: String, body: String): CloudMessage =
        client.postgrest.rpc(
            "edit_message",
            buildJsonObject {
                put("p_message", JsonPrimitive(messageId))
                put("p_body", JsonPrimitive(body.trim()))
            }
        ).decodeAs<CloudMessage>()

    suspend fun deleteMessage(messageId: String) {
        val message = client.postgrest["messages"].select {
            filter { eq("id", messageId) }
        }.decodeSingle<CloudMessage>()
        client.postgrest.rpc(
            "delete_message",
            buildJsonObject { put("p_message", JsonPrimitive(messageId)) }
        )
        message.attachmentPath?.takeIf { it.isNotBlank() }?.let { path ->
            runCatching { client.storage.from(CHAT_ATTACHMENTS_BUCKET).delete(path) }
        }
    }

    suspend fun resolveAttachmentUrl(path: String): String =
        client.storage.from(CHAT_ATTACHMENTS_BUCKET).createSignedUrl(path, 1.hours)

    suspend fun clearOfflineWarehouseDesignationsForDevice(
        workspaceId: String,
        deviceFingerprint: String
    ): Int {
        if (deviceFingerprint.isBlank()) return 0
        val registeredDevices = client.postgrest["devices"].select {
            filter {
                eq("workspace_id", workspaceId)
                eq("device_fingerprint", deviceFingerprint)
                eq("is_revoked", false)
            }
        }.decodeList<CloudDevice>()
        val userIds = registeredDevices.map { it.userId }.distinct()
        if (userIds.isEmpty()) return 0
        val cloudWarehouses = listWarehouses(workspaceId).filter { it.localId != null }
        val membershipsByWarehouse = cloudWarehouses.associate { warehouse ->
            warehouse.id to listWarehouseMemberships(warehouse.id)
        }
        userIds.forEach { userId ->
            cloudWarehouses.forEach { warehouse ->
                val membership = membershipsByWarehouse[warehouse.id].orEmpty()
                    .firstOrNull { it.userId == userId } ?: return@forEach
                setWarehousePermissions(
                    workspaceId,
                    warehouse.id,
                    userId,
                    membership.permissions,
                    makeDedicated = false
                )
            }
        }
        return userIds.size
    }

    suspend fun syncLinkedDevicePermissionsToCloud(
        workspaceId: String,
        slot: LinkedSubDevice
    ): Int {
        if (slot.boundInstallationId.isBlank()) return 0
        val registeredDevices = client.postgrest["devices"].select {
            filter {
                eq("workspace_id", workspaceId)
                eq("device_fingerprint", slot.boundInstallationId)
                eq("is_revoked", false)
            }
        }.decodeList<CloudDevice>()
        val userIds = registeredDevices.map { it.userId }.distinct()
        if (userIds.isEmpty()) return 0

        val cloudWarehouses = listWarehouses(workspaceId).filter { it.localId != null }
        val grantsByLocalId = slot.warehousePermissions.associateBy { it.warehouseId }
        userIds.forEach { userId ->
            cloudWarehouses.forEach { warehouse ->
                val localWarehouseId = warehouse.localId ?: return@forEach
                val grant = grantsByLocalId[localWarehouseId]
                val canView = !slot.isKicked &&
                    (slot.canViewAllWarehouses || grant?.canView == true)
                val permissions = buildJsonObject {
                    put("canView", canView)
                    // Warehouse-level grants are authoritative when present. Do not
                    // intersect them with legacy device-wide toggles: doing so silently
                    // revokes a grant when the old global flag was never enabled.
                    put("canInward", canView && (grant?.canInward ?: slot.canInward))
                    put("canDisburse", canView && (grant?.canDisburse ?: slot.canDisburse))
                    put("canTransfer", canView && (grant?.canTransfer == true))
                    put("canAdjust", canView && (grant?.canAdjust == true))
                    put("canReports", canView && (grant?.canReports == true))
                    put("canEdit", canView && (grant?.canEdit ?: slot.canEditDelete))
                    put("canEditInventory", canView && (grant?.canEditInventory ?: slot.canEditInventory))
                    put("canDeleteItem", canView && (grant?.canDeleteItem ?: slot.canDeleteItem))
                    put("canEditIssueVoucher", canView && slot.canEditIssueVoucher)
                    put("canDeleteIssueVoucher", canView && slot.canDeleteIssueVoucher)
                    put("canEditSupplyVoucher", canView && slot.canEditSupplyVoucher)
                    put("canDeleteSupplyVoucher", canView && slot.canDeleteSupplyVoucher)
                    put("canAddItems", canView && slot.canAddItems)
                    put("canEditCatalogItems", canView && slot.canEditCatalogItems)
                    put("canBackup", canView && slot.canBackup)
                }
                setWarehousePermissions(workspaceId, warehouse.id, userId, permissions, grant?.canWorkOffline == true && grant.canView)
            }
        }
        return userIds.size
    }

    fun observeMessages(conversationId: String): Flow<Unit> = callbackFlow {
        val channel = client.realtime.channel("cloud-chat-$conversationId")
        val inserts = channel.postgresChangeFlow<PostgresAction.Insert>(schema = "public") {
            table = "messages"
            filter("conversation_id", FilterOperator.EQ, conversationId)
        }
        val deletes = channel.postgresChangeFlow<PostgresAction.Delete>(schema = "public") {
            table = "messages"
        }
        val insertCollector = launch {
            inserts.collect { trySend(Unit).isSuccess }
        }
        val deleteCollector = launch {
            deletes.collect { trySend(Unit).isSuccess }
        }
        channel.subscribe(blockUntilSubscribed = true)
        trySend(Unit)
        awaitClose {
            insertCollector.cancel()
            deleteCollector.cancel()
            CoroutineScope(Dispatchers.IO).launch {
                runCatching { client.realtime.removeChannel(channel) }
            }
        }
    }
}
