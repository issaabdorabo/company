package com.example.data.cloud

import android.util.Base64
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

import com.example.data.sync.LocalSyncJson
import com.example.data.model.Warehouse
import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
import org.json.JSONObject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
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
 * Collaboration identity, metadata, pairing and chat are stored in Firebase Realtime Database.
 */
class CloudCollaborationRepository {

    private val firebaseRoot get() = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference

    private suspend fun readFirebase(ref: com.google.firebase.database.DatabaseReference) =
        withContext(Dispatchers.IO) { Tasks.await(ref.get()) }

    private fun com.google.firebase.database.DataSnapshot.textValue(key: String): String =
        child(key).getValue(String::class.java).orEmpty()

    private fun com.google.firebase.database.DataSnapshot.boolValue(key: String, default: Boolean = false): Boolean =
        child(key).getValue(Boolean::class.java) ?: default

    private fun com.google.firebase.database.DataSnapshot.longValue(key: String, default: Long = 0L): Long =
        child(key).getValue(Long::class.java) ?: default

    private fun com.google.firebase.database.DataSnapshot.asCloudWorkspace(): CloudWorkspace? {
        val idValue = key ?: return null
        return CloudWorkspace(idValue, textValue("name"), textValue("ownerUserId"), longValue("createdAt").toString())
    }

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
        val ref = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
            .child("workspaceEvents").child(workspaceId).child(deviceId)
        val listener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                val raw = snapshot.child("envelope").getValue(String::class.java) ?: return
                val envelope = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
                val decoded = secrets.firstNotNullOfOrNull { secret ->
                    decryptInventoryEnvelope(workspaceId, deviceId, secret, envelope)?.let { secret to it }
                } ?: return
                val authenticatedEvent = buildJsonObject {
                    decoded.second.forEach { (key, value) -> put(key, value) }
                    put("_transportSecret", decoded.first)
                }
                trySend(authenticatedEvent)
                snapshot.ref.removeValue()
            }
            override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) = Unit
            override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                close(error.toException())
            }
        }
        ref.addChildEventListener(listener)
        awaitClose { ref.removeEventListener(listener) }
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
    ) = withContext(Dispatchers.IO) {
        val envelope = encryptInventoryEnvelope(workspaceId, recipientDeviceId, transportSecret, payload)
        val ref = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
            .child("workspaceEvents").child(workspaceId).child(recipientDeviceId).push()
        Tasks.await(ref.setValue(mapOf(
            "envelope" to envelope.toString(),
            "createdAt" to ServerValue.TIMESTAMP
        )))
    }

    /**
     * Deliver an encrypted request through Firebase RTDB and wait for its matching ACK.
     * This transport works across independent internet connections; it does not use LAN,
     * IP addresses, Wi-Fi discovery, or a phone hotspot.
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
        val root = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference
            .child("workspaceEvents").child(workspaceId)
        val senderRef = root.child(senderDeviceId)
        val targetRef = root.child(targetDeviceId).push()
        val ack = CompletableDeferred<JsonObject>()
        val listener = object : com.google.firebase.database.ChildEventListener {
            override fun onChildAdded(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) {
                val raw = snapshot.child("envelope").getValue(String::class.java) ?: return
                val envelope = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return
                val response = decryptInventoryEnvelope(workspaceId, senderDeviceId, transportSecret, envelope) ?: return
                val responseId = response["operationId"]?.jsonPrimitive?.contentOrNull
                val type = response["type"]?.jsonPrimitive?.contentOrNull
                val responderId = response["senderDeviceId"]?.jsonPrimitive?.contentOrNull
                val senderMatches = expectedSenderDeviceId == null || responderId == expectedSenderDeviceId
                if (type == "ACK" && responseId == operationId && senderMatches) {
                    ack.complete(response)
                    snapshot.ref.removeValue()
                }
            }
            override fun onChildChanged(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) = Unit
            override fun onChildRemoved(snapshot: com.google.firebase.database.DataSnapshot) = Unit
            override fun onChildMoved(snapshot: com.google.firebase.database.DataSnapshot, previousChildName: String?) = Unit
            override fun onCancelled(error: com.google.firebase.database.DatabaseError) {
                ack.completeExceptionally(error.toException())
            }
        }
        senderRef.addChildEventListener(listener)
        try {
            val envelope = encryptInventoryEnvelope(workspaceId, targetDeviceId, transportSecret, payload)
            withContext(Dispatchers.IO) {
                Tasks.await(targetRef.setValue(mapOf(
                    "envelope" to envelope.toString(),
                    "createdAt" to ServerValue.TIMESTAMP
                )))
            }
            withTimeout(timeoutMillis) { ack.await() }
        } finally {
            senderRef.removeEventListener(listener)
            withContext(Dispatchers.IO) { runCatching { Tasks.await(targetRef.removeValue()) } }
        }
    }

    fun currentUserIdOrNull(): String? = FirebaseAuthClient.currentUserIdOrNull()

    fun currentUserEmailOrNull(): String? = FirebaseAuthClient.currentUserEmailOrNull()

    suspend fun listMyWorkspaces(): List<CloudWorkspace> = withContext(Dispatchers.IO) {
        val user = FirebaseAuthClient.auth.currentUser ?: error("يجب ربط حساب Google أولاً")
        val email = user.email?.trim()?.lowercase().orEmpty()
        val inviteKey = firebaseInviteKey(email)
        val root = firebaseRoot
        val invite = Tasks.await(root.child("emailInvites").child(inviteKey).get())
        if (invite.child("active").getValue(Boolean::class.java) == true &&
            invite.child("expiresAt").getValue(Long::class.java).let { (it ?: 0L) > System.currentTimeMillis() }) {
            val workspaceId = invite.textValue("workspaceId")
            if (workspaceId.isNotBlank()) {
                val member = root.child("workspaces").child(workspaceId).child("members").child(user.uid)
                if (!Tasks.await(member.get()).exists()) {
                    Tasks.await(member.setValue(mapOf(
                        "userId" to user.uid, "email" to email,
                        "displayName" to invite.textValue("displayName").ifBlank { user.displayName.orEmpty() },
                        "role" to "SUBUSER", "active" to true, "inviteKey" to inviteKey,
                        "createdAt" to ServerValue.TIMESTAMP
                    )))
                }
                Tasks.await(root.child("userWorkspaces").child(user.uid).child(workspaceId).setValue(true))
            }
        }
        val index = Tasks.await(root.child("userWorkspaces").child(user.uid).get())
        index.children.mapNotNull { entry ->
            val workspaceId = entry.key ?: return@mapNotNull null
            val ws = Tasks.await(root.child("workspaces").child(workspaceId).get())
            val isOwner = ws.textValue("ownerUserId") == user.uid
            val isMember = ws.child("members").child(user.uid).boolValue("active")
            if (ws.exists() && (isOwner || isMember)) ws.asCloudWorkspace() else null
        }
    }

    suspend fun canCreateFirstWorkspace(): Boolean = listMyWorkspaces().isEmpty()

    suspend fun createWorkspace(name: String): CloudWorkspace = withContext(Dispatchers.IO) {
        val user = FirebaseAuthClient.auth.currentUser ?: error("يجب تسجيل الدخول بحساب Google")
        val cleanName = name.trim()
        require(cleanName.length in 2..100) { "اسم مساحة العمل يجب أن يكون بين حرفين و100 حرف" }
        require(listMyWorkspaces().isEmpty()) { "WORKSPACE_ALREADY_EXISTS_INVITE_REQUIRED" }
        val ref = firebaseRoot.child("workspaces").push()
        val id = ref.key ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        Tasks.await(ref.setValue(mapOf(
            "name" to cleanName, "ownerUserId" to user.uid, "createdAt" to now,
            "members" to mapOf(user.uid to mapOf(
                "userId" to user.uid, "email" to (user.email ?: "").lowercase(),
                "displayName" to (user.displayName ?: ""), "role" to "OWNER",
                "active" to true, "createdAt" to now
            )),
            "devices" to emptyMap<String, Any>(), "warehouses" to emptyMap<String, Any>(),
            "warehouseMemberships" to emptyMap<String, Any>(), "conversations" to emptyMap<String, Any>()
        )))
        Tasks.await(firebaseRoot.child("userWorkspaces").child(user.uid).child(id).setValue(true))
        CloudWorkspace(id, cleanName, user.uid, now.toString())
    }

    suspend fun getWorkspace(workspaceId: String): CloudWorkspace {
        val snap = readFirebase(firebaseRoot.child("workspaces").child(workspaceId))
        return snap.asCloudWorkspace() ?: error("مساحة العمل غير موجودة")
    }

    suspend fun registerDevice(workspaceId: String, deviceName: String, stableFingerprint: String): String = withContext(Dispatchers.IO) {
        val user = FirebaseAuthClient.auth.currentUser ?: error("جلسة Firebase غير متاحة")
        val devicesRef = firebaseRoot.child("workspaces").child(workspaceId).child("devices")
        val all = Tasks.await(devicesRef.get())
        val existing = all.children.firstOrNull {
            it.textValue("userId") == user.uid && it.textValue("deviceFingerprint") == stableFingerprint && !it.boolValue("isRevoked")
        }
        val id = existing?.key ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        Tasks.await(devicesRef.child(id).updateChildren(mapOf(
            "workspaceId" to workspaceId, "userId" to user.uid,
            "deviceName" to deviceName.trim().ifBlank { "جهاز Android" },
            "deviceFingerprint" to stableFingerprint, "isRevoked" to false,
            "appState" to "FOREGROUND", "lastSeenAt" to now,
            "createdAt" to (existing?.longValue("createdAt") ?: now)
        )))
        id
    }

    suspend fun listWorkspaceDevices(workspaceId: String): List<CloudDevice> {
        val snap = readFirebase(firebaseRoot.child("workspaces").child(workspaceId).child("devices"))
        return snap.children.mapNotNull { d ->
            val id = d.key ?: return@mapNotNull null
            CloudDevice(id, workspaceId, d.textValue("userId"), d.textValue("deviceName"),
                d.textValue("deviceFingerprint"), d.boolValue("isRevoked"),
                d.longValue("lastSeenAt").takeIf { it > 0 }?.toString(), d.longValue("createdAt").toString())
        }.filterNot { it.isRevoked }.sortedBy { it.deviceName.lowercase() }
    }

    // Inventory records remain local on each device; Google Drive is the only full backup.
    suspend fun replaceCloudWorkspaceSnapshot(workspaceId: String, deviceId: String, records: JsonArray): Int = 0

    suspend fun heartbeat(deviceId: String, appState: String = "FOREGROUND", workspaceId: String? = null) = withContext(Dispatchers.IO) {
        val user = FirebaseAuthClient.auth.currentUser ?: error("جلسة Firebase غير متاحة")
        val activeWorkspaceId = workspaceId?.takeIf { it.isNotBlank() } ?: deviceWorkspaceId(deviceId)
        Tasks.await(firebaseRoot.child("workspaces").child(activeWorkspaceId).child("devices").child(deviceId)
            .updateChildren(mapOf("userId" to user.uid, "isRevoked" to false,
                "lastSeenAt" to ServerValue.TIMESTAMP, "appState" to appState)))
        Tasks.await(firebaseRoot.child("users").child(user.uid).updateChildren(mapOf("lastSeenAt" to ServerValue.TIMESTAMP)))
    }

    private suspend fun deviceWorkspaceId(deviceId: String): String {
        for (workspace in listMyWorkspaces()) {
            if (readFirebase(firebaseRoot.child("workspaces").child(workspace.id).child("devices").child(deviceId)).exists()) {
                return workspace.id
            }
        }
        error("تعذر تحديد مساحة عمل الجهاز")
    }

    /**
     * Firebase stores only collaboration identities, permissions and chat metadata.
     * Operational details, stock and vouchers remain in the local Room database.
     */
    suspend fun ensureCloudWarehouse(workspaceId: String, localId: Long, name: String): String = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val ref = firebaseRoot.child("workspaces").child(workspaceId)
        val ws = Tasks.await(ref.get())
        require(ws.textValue("ownerUserId") == uid || ws.child("members").child(uid).boolValue("active")) { "NOT_WORKSPACE_MEMBER" }
        val existing = Tasks.await(ref.child("warehouses").get()).children.firstOrNull { it.longValue("localId") == localId }
        val id = existing?.key ?: UUID.randomUUID().toString()
        Tasks.await(ref.child("warehouses").child(id).updateChildren(mapOf(
            "warehouseId" to id, "workspaceId" to workspaceId, "localId" to localId,
            "name" to name.trim(), "updatedAt" to ServerValue.TIMESTAMP
        )))
        id
    }

    suspend fun ensureCloudWarehouse(workspaceId: String, warehouse: Warehouse): String =
        ensureCloudWarehouse(workspaceId, warehouse.id, warehouse.name)

    suspend fun seedWorkspaceSnapshot(workspaceId: String, deviceId: String, records: JsonArray): Int = 0

    suspend fun pushCloudSnapshot(
        workspaceId: String, deviceId: String, clientOperationId: String,
        operationType: String, warehouseIds: Set<String>, title: String, details: String, records: JsonArray
    ): String = clientOperationId

    suspend fun checkCloudWarehouseAccess(
        workspaceId: String, deviceId: String, operationType: String, warehouseIds: Set<String>
    ): Boolean = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: return@withContext false
        val ws = Tasks.await(firebaseRoot.child("workspaces").child(workspaceId).get())
        if (ws.textValue("ownerUserId") == uid) return@withContext true
        val device = ws.child("devices").child(deviceId)
        if (!device.exists() || device.textValue("userId") != uid || device.boolValue("isRevoked")) return@withContext false
        val permissionKey = when (operationType.uppercase()) {
            "INWARD", "SUPPLY", "CREATE_SUPPLY" -> "canInward"
            "DISBURSEMENT", "OUTWARD", "ISSUE", "CREATE_ISSUE" -> "canDisburse"
            "TRANSFER" -> "canTransfer"
            "ADJUST", "ADJUSTMENT" -> "canAdjust"
            "REPORT", "REPORTS" -> "canReports"
            "EDIT", "UPDATE" -> "canEdit"
            "DELETE" -> "canDeleteItem"
            else -> ""
        }
        warehouseIds.all { warehouseId ->
            val grant = ws.child("warehouseMemberships").child(warehouseId).child(uid)
            if (!grant.exists()) return@all false
            val raw = grant.textValue("permissionsJson")
            val perms = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: return@all false
            val canView = perms["canView"]?.jsonPrimitive?.contentOrNull == "true"
            val allowed = permissionKey.isBlank() || perms[permissionKey]?.jsonPrimitive?.contentOrNull == "true"
            canView && allowed
        }
    }

    suspend fun fetchCloudEntitySnapshot(workspaceId: String): List<CloudEntityRecord> =
        fetchCloudEntitySnapshotVersioned(workspaceId).records

    suspend fun fetchCloudEntitySnapshotVersioned(workspaceId: String): CloudEntitySnapshot =
        CloudEntitySnapshot(emptyList(), "")

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

    suspend fun listWorkspaceMembers(workspaceId: String): List<CloudWorkspaceMember> {
        val snap = readFirebase(firebaseRoot.child("workspaces").child(workspaceId).child("members"))
        return snap.children.mapNotNull { m ->
            val uid = m.key ?: return@mapNotNull null
            CloudWorkspaceMember(workspaceId, uid, m.textValue("role"),
                m.textValue("displayName"), m.textValue("email"), m.boolValue("active"))
        }
    }

    /**
     * RLS restricts this result to notifications addressed to the signed-in user.
     * The workspace filter keeps the polling query scoped to the active collaboration space.
     */
    suspend fun listMyOperationNotifications(workspaceId: String): List<CloudUserNotification> {
        val uid = currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val snap = readFirebase(firebaseRoot.child("workspaces").child(workspaceId).child("notifications").child(uid))
        return snap.children.mapNotNull { n ->
            val id = n.key ?: return@mapNotNull null
            CloudUserNotification(id, workspaceId, uid, n.textValue("notificationType"),
                n.textValue("title"), n.textValue("body"), n.textValue("relatedOperationId").ifBlank { null },
                n.textValue("readAt").ifBlank { null }, n.textValue("createdAt"))
        }.sortedBy { it.createdAt }
    }

    /**
     * Database RLS limits audit entries to the workspace owner or users assigned to
     * the warehouses referenced by each operation.
     */
    suspend fun listAllowedOperationAuditEntries(workspaceId: String): List<CloudOperationAuditEntry> {
        val snap = readFirebase(firebaseRoot.child("workspaces").child(workspaceId).child("operationAudit"))
        return snap.children.mapNotNull { n ->
            val id = n.key ?: return@mapNotNull null
            CloudOperationAuditEntry(id, workspaceId, n.textValue("operationId").ifBlank { null },
                n.textValue("actorUserId"), n.textValue("deviceName"), n.textValue("operationType"),
                n.textValue("title"), n.textValue("details"), n.textValue("createdAt"))
        }.sortedBy { it.createdAt }
    }

    suspend fun listRegisteredWorkspaceUsers(workspaceId: String): List<CloudRegisteredUser> =
        listWorkspaceMembers(workspaceId).map { CloudRegisteredUser(it.userId, it.email, it.displayName, "") }

    suspend fun addMemberByEmail(
        workspaceId: String, email: String, role: String = "SUBUSER", displayName: String = ""
    ): String = withContext(Dispatchers.IO) {
        val owner = FirebaseAuthClient.auth.currentUser ?: error("يجب تسجيل الدخول")
        val workspace = Tasks.await(firebaseRoot.child("workspaces").child(workspaceId).get())
        require(workspace.textValue("ownerUserId") == owner.uid) { "OWNER_REQUIRED" }
        val normalized = email.trim().lowercase()
        require(normalized.contains("@")) { "أدخل بريداً إلكترونياً صحيحاً" }
        require(role == "SUBUSER") { "دور الدعوة غير مدعوم" }
        val key = firebaseInviteKey(normalized)
        val expiry = System.currentTimeMillis() + 30L * 24L * 60L * 60L * 1000L
        Tasks.await(firebaseRoot.child("emailInvites").child(key).setValue(mapOf(
            "email" to normalized, "workspaceId" to workspaceId, "role" to role,
            "active" to true, "displayName" to displayName.trim(),
            "createdAt" to ServerValue.TIMESTAMP, "expiresAt" to expiry, "createdBy" to owner.uid
        )))
        key
    }

    /**
     * Firebase metadata storage for workspace warehouses and member permissions.
     * Inventory rows and operation payloads are deliberately excluded.
     */
    suspend fun listWarehouses(workspaceId: String): List<CloudWarehouse> {
        val snap = readFirebase(firebaseRoot.child("workspaces").child(workspaceId).child("warehouses"))
        return snap.children.mapNotNull { w ->
            val id = w.key ?: return@mapNotNull null
            CloudWarehouse(id, workspaceId, w.longValue("localId").takeIf { it >= 0 }, w.textValue("name"), w.longValue("updatedAt").toString())
        }.sortedBy { it.name.lowercase() }
    }

    suspend fun listWarehouseMemberships(warehouseId: String): List<CloudWarehouseMembership> {
        for (workspace in listMyWorkspaces()) {
            val grants = readFirebase(firebaseRoot.child("workspaces").child(workspace.id)
                .child("warehouseMemberships").child(warehouseId))
            if (!grants.exists()) continue
            return grants.children.mapNotNull { grant ->
                val uid = grant.key ?: return@mapNotNull null
                val raw = grant.textValue("permissionsJson")
                val permissions = runCatching { Json.parseToJsonElement(raw) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
                CloudWarehouseMembership(warehouseId, uid, permissions)
            }
        }
        return emptyList()
    }

    suspend fun setWarehousePermissions(
        workspaceId: String, warehouseId: String, userId: String,
        permissions: JsonObject, makeDedicated: Boolean
    ) = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val ws = firebaseRoot.child("workspaces").child(workspaceId)
        val ownerId = Tasks.await(ws.child("ownerUserId").get()).getValue(String::class.java)
        require(ownerId == uid) { "OWNER_REQUIRED" }
        Tasks.await(ws.child("warehouseMemberships").child(warehouseId).child(userId).updateChildren(mapOf(
            "warehouseId" to warehouseId, "userId" to userId,
            "permissionsJson" to permissions.toString(), "makeDedicated" to makeDedicated,
            "updatedAt" to ServerValue.TIMESTAMP
        )))
    }

    suspend fun listConversations(workspaceId: String, ownerUserId: String): List<CloudConversation> {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val root = firebaseRoot.child("workspaces").child(workspaceId)
        val snap = readFirebase(root.child("conversations"))
        return snap.children.mapNotNull { c ->
            val participants = c.child("participants")
            if (participants.child(uid).getValue(Boolean::class.java) != true && uid != ownerUserId) return@mapNotNull null
            val id = c.key ?: return@mapNotNull null
            val peerId = participants.children.mapNotNull { it.key }.firstOrNull { it != uid }.orEmpty()
            val peer = readFirebase(root.child("members").child(peerId))
            val peerName = peer.textValue("displayName").ifBlank { peer.textValue("email") }
            CloudConversation(id, workspaceId, c.textValue("conversationType"),
                c.textValue("title").ifBlank { peerName }, c.textValue("warehouseId").ifBlank { null },
                c.textValue("createdBy"), c.textValue("createdAt"), peerName, peer.textValue("deviceName"))
        }.sortedByDescending { it.createdAt }
    }

    suspend fun createDirectConversation(workspaceId: String, otherUserId: String): String = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        require(uid != otherUserId) { "CANNOT_ADD_SELF" }
        val pair = listOf(uid, otherUserId).sorted()
        val digest = MessageDigest.getInstance("SHA-256").digest(pair.joinToString(":").toByteArray())
            .joinToString("") { "%02x".format(it) }.take(32)
        val id = "direct_" + digest
        val ref = firebaseRoot.child("workspaces").child(workspaceId).child("conversations").child(id)
        if (!Tasks.await(ref.get()).exists()) Tasks.await(ref.setValue(mapOf(
            "id" to id, "workspaceId" to workspaceId, "conversationType" to "DIRECT",
            "createdBy" to uid, "createdAt" to System.currentTimeMillis().toString(),
            "participants" to pair.associateWith { true }
        )))
        id
    }

    suspend fun createWarehouseConversation(
        workspaceId: String, warehouseId: String, title: String
    ): String = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val ref = firebaseRoot.child("workspaces").child(workspaceId)
        val grants = Tasks.await(ref.child("warehouseMemberships").child(warehouseId).get())
        val participants = mutableMapOf<String, Boolean>(uid to true)
        grants.children.forEach { grant -> participants[grant.key ?: return@forEach] = true }
        val convRef = ref.child("conversations").push()
        val id = convRef.key ?: UUID.randomUUID().toString()
        Tasks.await(convRef.setValue(mapOf("id" to id, "workspaceId" to workspaceId,
            "conversationType" to "WAREHOUSE_GROUP", "title" to title, "warehouseId" to warehouseId,
            "createdBy" to uid, "createdAt" to System.currentTimeMillis().toString(),
            "participants" to participants)))
        id
    }

    suspend fun listMessages(conversationId: String): List<CloudMessage> = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val root = firebaseRoot
        val workspaces = listMyWorkspaces()
        val hidden = Tasks.await(root.child("users").child(uid).child("hiddenMessages").get())
            .children.mapNotNull { it.key }.toSet()
        val messages = mutableListOf<CloudMessage>()
        for (workspace in workspaces) {
            val workspaceSnap = Tasks.await(root.child("workspaces").child(workspace.id).get())
            val conv = workspaceSnap.child("conversations").child(conversationId)
            if (!conv.exists()) continue
            require(conv.child("participants").child(uid).getValue(Boolean::class.java) == true ||
                workspace.ownerUserId == uid) { "هذه المحادثة غير متاحة لهذا الحساب" }
            for (m in conv.child("messages").children) {
                val id = m.key ?: continue
                if (id in hidden) continue
                messages += CloudMessage(id, conversationId, m.textValue("senderUserId"),
                    m.textValue("clientMessageId"), m.textValue("body"),
                    m.textValue("attachmentPath").ifBlank { null }, m.textValue("createdAt"),
                    m.textValue("editedAt").ifBlank { null }, m.textValue("replyToMessageId").ifBlank { null })
            }
        }
        messages.sortedBy { it.createdAt }
    }

    suspend fun sendMessage(conversationId: String, body: String, replyToMessageId: String? = null): CloudMessage =
        insertMessage(conversationId, body.trim(), null, replyToMessageId)

    suspend fun sendAttachment(conversationId: String, fileName: String, mimeType: String, bytes: ByteArray): CloudMessage {
        require(bytes.isNotEmpty()) { "الملف فارغ" }
        require(bytes.size <= 20 * 1024 * 1024) { "حجم المرفق يتجاوز 20 ميجابايت" }
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val ext = fileName.substringAfterLast('.', "bin").lowercase().takeIf { it.matches(Regex("[a-z0-9]{1,10}")) } ?: "bin"
        val path = "chat-attachments/" + conversationId + "/" + uid + "/" + UUID.randomUUID().toString() + "." + ext
        val ref = FirebaseStorage.getInstance().reference.child(path)
        withContext(Dispatchers.IO) {
            Tasks.await(ref.putBytes(bytes, com.google.firebase.storage.StorageMetadata.Builder().setContentType(mimeType).build()))
        }
        return try { insertMessage(conversationId, fileName.trim().ifBlank { "مرفق" }.take(180), path) }
        catch (e: Exception) { runCatching { withContext(Dispatchers.IO) { Tasks.await(ref.delete()) } }; throw e }
    }

    private suspend fun insertMessage(
        conversationId: String, body: String, attachmentPath: String?, replyToMessageId: String? = null
    ): CloudMessage = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val clean = body.trim()
        require(clean.isNotBlank() && clean.length <= 10_000) { "الرسالة فارغة أو أطول من الحد المسموح" }
        val workspaces = listMyWorkspaces()
        for (ws in workspaces) {
            val wsSnap = Tasks.await(firebaseRoot.child("workspaces").child(ws.id).get())
            val conv = wsSnap.child("conversations").child(conversationId)
            if (!conv.exists()) continue
            require(conv.child("participants").child(uid).getValue(Boolean::class.java) == true ||
                ws.ownerUserId == uid) { "هذه المحادثة غير متاحة" }
            val ref = firebaseRoot.child("workspaces").child(ws.id).child("conversations")
                .child(conversationId).child("messages").push()
            val id = ref.key ?: UUID.randomUUID().toString()
            val now = System.currentTimeMillis().toString()
            val clientId = UUID.randomUUID().toString()
            Tasks.await(ref.setValue(mapOf("id" to id, "conversationId" to conversationId,
                "senderUserId" to uid, "clientMessageId" to clientId, "body" to clean,
                "attachmentPath" to attachmentPath, "createdAt" to now, "replyToMessageId" to replyToMessageId)))
            return@withContext CloudMessage(id, conversationId, uid, clientId, clean, attachmentPath, now, null, replyToMessageId)
        }
        error("المحادثة غير موجودة")
    }

    @Serializable
    private data class MessageDeletionRow(val messageId: String)

    suspend fun hideMessageForMe(messageId: String) = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        Tasks.await(firebaseRoot.child("users").child(uid).child("hiddenMessages").child(messageId).setValue(true))
    }

    suspend fun editMessage(messageId: String, body: String): CloudMessage = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val clean = body.trim()
        require(clean.isNotBlank() && clean.length <= 10_000) { "نص الرسالة غير صالح" }
        val workspaces = listMyWorkspaces()
        for (ws in workspaces) {
          val wsSnap = Tasks.await(firebaseRoot.child("workspaces").child(ws.id).get())
          for (conv in wsSnap.child("conversations").children) {
            val m = conv.child("messages").child(messageId)
            if (!m.exists()) continue
            require(m.textValue("senderUserId") == uid) { "يمكن لمُرسل الرسالة تعديلها فقط" }
            val now = System.currentTimeMillis().toString()
            val ref = firebaseRoot.child("workspaces").child(ws.id).child("conversations")
                .child(conv.key!!).child("messages").child(messageId)
            Tasks.await(ref.updateChildren(mapOf("body" to clean, "editedAt" to now)))
            return@withContext CloudMessage(messageId, conv.key!!, uid, m.textValue("clientMessageId"),
                clean, m.textValue("attachmentPath").ifBlank { null }, m.textValue("createdAt"), now,
                m.textValue("replyToMessageId").ifBlank { null })
          }
        }
        error("الرسالة غير موجودة")
    }

    suspend fun deleteMessage(messageId: String) = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: error("يجب تسجيل الدخول")
        val workspaces = listMyWorkspaces()
        for (ws in workspaces) {
            val wsSnap = Tasks.await(firebaseRoot.child("workspaces").child(ws.id).get())
            for (conv in wsSnap.child("conversations").children) {
                val m = conv.child("messages").child(messageId)
                if (!m.exists()) continue
                require(m.textValue("senderUserId") == uid || ws.ownerUserId == uid) { "لا تملك صلاحية حذف هذه الرسالة" }
                val path = m.textValue("attachmentPath")
                Tasks.await(firebaseRoot.child("workspaces").child(ws.id).child("conversations")
                    .child(conv.key!!).child("messages").child(messageId).removeValue())
                if (path.isNotBlank()) runCatching { Tasks.await(FirebaseStorage.getInstance().reference.child(path).delete()) }
                return@withContext
            }
        }
        error("الرسالة غير موجودة")
    }

    suspend fun resolveAttachmentUrl(path: String): String = withContext(Dispatchers.IO) {
        Tasks.await(FirebaseStorage.getInstance().reference.child(path).downloadUrl).toString()
    }

    suspend fun clearOfflineWarehouseDesignationsForDevice(
        workspaceId: String, deviceFingerprint: String
    ): Int {
        if (deviceFingerprint.isBlank()) return 0
        val devices = listWorkspaceDevices(workspaceId).filter { it.deviceFingerprint == deviceFingerprint }
        val userIds = devices.map { it.userId }.distinct()
        val warehouses = listWarehouses(workspaceId)
        userIds.forEach { userId ->
            warehouses.forEach { warehouse ->
                val grant = listWarehouseMemberships(warehouse.id).firstOrNull { it.userId == userId } ?: return@forEach
                val changed = buildJsonObject {
                    grant.permissions.forEach { (key, value) -> put(key, value) }
                    put("canWorkOffline", false)
                }
                setWarehousePermissions(workspaceId, warehouse.id, userId, changed, false)
            }
        }
        userIds.size
    }

    suspend fun syncLinkedDevicePermissionsToCloud(workspaceId: String, slot: LinkedSubDevice): Int {
        if (slot.boundInstallationId.isBlank()) return 0
        val devices = listWorkspaceDevices(workspaceId).filter {
            it.deviceFingerprint == slot.boundInstallationId && !it.isRevoked
        }
        val userIds = devices.map { it.userId }.distinct()
        if (userIds.isEmpty()) return 0
        val warehouses = listWarehouses(workspaceId).filter { it.localId != null }
        val grants = slot.warehousePermissions.associateBy { it.warehouseId }
        userIds.forEach { userId ->
            warehouses.forEach { warehouse ->
                val localId = warehouse.localId ?: return@forEach
                val grant = grants[localId]
                val canView = !slot.isKicked && (slot.canViewAllWarehouses || grant?.canView == true)
                val permissions = buildJsonObject {
                    put("canView", canView)
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
                    put("canWorkOffline", canView && grant?.canWorkOffline == true)
                }
                setWarehousePermissions(workspaceId, warehouse.id, userId, permissions, grant?.canWorkOffline == true && canView)
            }
        }
        userIds.size
    }

    fun observeMessages(conversationId: String): Flow<Unit> = flow { emit(Unit) }
}
