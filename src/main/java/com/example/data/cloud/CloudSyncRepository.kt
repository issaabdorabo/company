package com.example.data.cloud

import android.content.Context
import com.example.R
import com.example.util.OperationType
import com.example.util.SecurityUtils
import com.example.util.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import java.util.UUID

class CloudSyncRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = Firebase.auth
    )

    private fun requireUid(): String {
        return auth.currentUser?.uid
            ?: throw IllegalStateException("يجب تسجيل الدخول بحساب Google أولاً قبل المزامنة السحابية.")
    }

    /**
     * Finds all workspaces owned by the signed-in user (used on fresh install/reinstall recovery
     * or Main Device initialization).
     */
    suspend fun findOwnerWorkspaces(): Result<List<CloudWorkspace>> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val path = "workspaces"
        return try {
            val snap = db.collection(path)
                .whereEqualTo("ownerUid", uid)
                .get()
                .await()
            val list = snap.toObjects(CloudWorkspace::class.java)
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    /**
     * Creates a new Main Device workspace or returns the existing primary workspace owned by this user.
     */
    suspend fun ensureOwnerWorkspace(
        orgName: String,
        installationId: String
    ): Result<CloudWorkspace> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val existingResult = findOwnerWorkspaces()
        val existingList = existingResult.getOrNull() ?: emptyList()
        if (existingList.isNotEmpty()) {
            return Result.success(existingList.first())
        }

        val workspaceId = "ws_${UUID.randomUUID().toString().replace("-", "").take(16)}"
        val workspaceCode = SecurityUtils.generateWorkspaceCode()
        val safeOrgName = orgName.trim().ifBlank { "المؤسسة العامة للتوريد والمخازن" }.take(160)
        val docRef = db.collection("workspaces").document(workspaceId)
        val payload = mapOf(
            "ownerUid" to uid,
            "workspaceCode" to workspaceCode,
            "orgName" to safeOrgName,
            "syncVersion" to 1L,
            "updatedByUid" to uid,
            "updatedByDeviceId" to installationId.ifBlank { "main_device" }.take(128),
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        return try {
            docRef.set(payload).await()
            val saved = docRef.get().await().toObject(CloudWorkspace::class.java)
                ?: CloudWorkspace(
                    id = workspaceId,
                    ownerUid = uid,
                    workspaceCode = workspaceCode,
                    orgName = safeOrgName,
                    syncVersion = 1L,
                    updatedByUid = uid,
                    updatedByDeviceId = installationId
                )
            Result.success(saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docRef.path)
            Result.failure(e)
        }
    }

    /**
     * Main Device creates an authorized Sub-Device slot protected by a unique per-device password.
     * The plaintext password is returned once for admin handoff, while only salted SHA-256 hash
     * is persisted on the device slot document.
     */
    suspend fun createSubDeviceSlot(
        workspaceId: String,
        workspaceCode: String,
        deviceName: String,
        canAddItems: Boolean = false,
        canInward: Boolean = false,
        canDisburse: Boolean = false,
        canEditDelete: Boolean = false
    ): Result<CreatedDeviceCredentials> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanName = deviceName.trim().ifBlank { "جهاز فرعي" }.take(80)
        val plainPassword = SecurityUtils.generateUniqueDevicePassword(10)
        val salt = SecurityUtils.generateSalt()
        val passwordHash = SecurityUtils.hashPassword(plainPassword, salt)
        val inviteHash = SecurityUtils.computeInviteHash(workspaceCode, plainPassword)
        val deviceSlotId = "dev_${UUID.randomUUID().toString().replace("-", "").take(16)}"

        val deviceRef = db.collection("workspaces").document(workspaceId)
            .collection("devices").document(deviceSlotId)
        val inviteRef = db.collection("pairing_invites").document(inviteHash)

        val devicePayload = mapOf(
            "workspaceId" to workspaceId,
            "ownerUid" to uid,
            "deviceName" to cleanName,
            "passwordSalt" to salt,
            "passwordHash" to passwordHash,
            "inviteHash" to inviteHash,
            "boundUid" to "",
            "boundInstallationId" to "",
            "isClaimed" to false,
            "isKicked" to false,
            "canAddItems" to canAddItems,
            "canInward" to canInward,
            "canDisburse" to canDisburse,
            "canEditDelete" to canEditDelete,
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )
        val invitePayload = mapOf(
            "workspaceId" to workspaceId,
            "workspaceCode" to workspaceCode.trim().uppercase(),
            "ownerUid" to uid,
            "deviceSlotId" to deviceSlotId,
            "deviceName" to cleanName,
            "canAddItems" to canAddItems,
            "canInward" to canInward,
            "canDisburse" to canDisburse,
            "canEditDelete" to canEditDelete,
            "isUsed" to false,
            "isRevoked" to false,
            "boundUid" to "",
            "boundInstallationId" to "",
            "createdAt" to FieldValue.serverTimestamp(),
            "updatedAt" to FieldValue.serverTimestamp()
        )

        return try {
            val batch = db.batch()
            batch.set(deviceRef, devicePayload)
            batch.set(inviteRef, invitePayload)
            batch.commit().await()
            Result.success(
                CreatedDeviceCredentials(
                    deviceSlotId = deviceSlotId,
                    deviceName = cleanName,
                    workspaceCode = workspaceCode.trim().uppercase(),
                    plainPassword = plainPassword
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, deviceRef.path)
            Result.failure(e)
        }
    }

    /**
     * Links a Sub Device using Workspace Code + Unique Device Password and binds the slot
     * to this device's installationId + Google UID so credentials cannot be hijacked elsewhere.
     */
    suspend fun pairSubDevice(
        workspaceCode: String,
        plainPassword: String,
        installationId: String
    ): Result<PairingResult> {
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val cleanCode = workspaceCode.trim().uppercase()
        val cleanPwd = plainPassword.trim()
        if (cleanCode.isBlank() || cleanPwd.isBlank()) {
            return Result.failure(IllegalArgumentException("يرجى إدخال رمز القاعدة السحابية وكلمة المرور الخاصة بالجهاز"))
        }

        val inviteHash = SecurityUtils.computeInviteHash(cleanCode, cleanPwd)
        val inviteRef = db.collection("pairing_invites").document(inviteHash)

        return try {
            val inviteSnap = inviteRef.get().await()
            if (!inviteSnap.exists()) {
                return Result.failure(SecurityException("رمز الربط أو كلمة مرور الجهاز غير صحيحة"))
            }
            val invite = inviteSnap.toObject(PairingInvite::class.java)
                ?: return Result.failure(SecurityException("تعذر قراءة بيانات الربط"))

            if (invite.isRevoked) {
                return Result.failure(SecurityException("تم إلغاء صلاحية كلمة المرور أو طرد هذا الجهاز من قبل الجهاز الرئيسي"))
            }

            if (invite.isUsed && (invite.boundUid != uid || invite.boundInstallationId != installationId)) {
                return Result.failure(SecurityException("كلمة المرور هذه مستخدمة ومقيدة بجهاز آخر ولا يمكن مشاركتها"))
            }

            val workspaceId = invite.workspaceId
            val deviceSlotId = invite.deviceSlotId
            val memberRef = db.collection("workspaces").document(workspaceId)
                .collection("members").document(uid)
            val deviceRef = db.collection("workspaces").document(workspaceId)
                .collection("devices").document(deviceSlotId)

            // Verify member status if previously joined
            val existingMember = try {
                memberRef.get().await().toObject(MemberStatus::class.java)
            } catch (_: Exception) {
                null
            }

            if (existingMember != null && existingMember.isKicked) {
                return Result.failure(SecurityException("تم طرد هذا الحساب مسبقاً من قاعدة العمل"))
            }

            val perms: DevicePermissions
            if (existingMember != null && existingMember.isActive) {
                perms = DevicePermissions(
                    isMainDevice = false,
                    canAddItems = existingMember.canAddItems,
                    canInward = existingMember.canInward,
                    canDisburse = existingMember.canDisburse,
                    canEditDelete = existingMember.canEditDelete,
                    isKicked = false
                )
            } else {
                val batch = db.batch()
                batch.update(
                    inviteRef,
                    mapOf(
                        "isUsed" to true,
                        "boundUid" to uid,
                        "boundInstallationId" to installationId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.set(
                    memberRef,
                    mapOf(
                        "uid" to uid,
                        "workspaceId" to workspaceId,
                        "ownerUid" to invite.ownerUid,
                        "deviceSlotId" to deviceSlotId,
                        "deviceName" to invite.deviceName,
                        "inviteHash" to inviteHash,
                        "boundInstallationId" to installationId,
                        "isActive" to true,
                        "isKicked" to false,
                        "canAddItems" to invite.canAddItems,
                        "canInward" to invite.canInward,
                        "canDisburse" to invite.canDisburse,
                        "canEditDelete" to invite.canEditDelete,
                        "joinedAt" to FieldValue.serverTimestamp(),
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.update(
                    deviceRef,
                    mapOf(
                        "isClaimed" to true,
                        "boundUid" to uid,
                        "boundInstallationId" to installationId,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                batch.commit().await()

                perms = DevicePermissions(
                    isMainDevice = false,
                    canAddItems = invite.canAddItems,
                    canInward = invite.canInward,
                    canDisburse = invite.canDisburse,
                    canEditDelete = invite.canEditDelete,
                    isKicked = false
                )
            }

            val wsSnap = db.collection("workspaces").document(workspaceId).get().await()
            val ws = wsSnap.toObject(CloudWorkspace::class.java)

            Result.success(
                PairingResult(
                    workspaceId = workspaceId,
                    workspaceCode = invite.workspaceCode,
                    orgName = ws?.orgName ?: "قاعدة بيانات موحدة",
                    deviceSlotId = deviceSlotId,
                    deviceName = invite.deviceName,
                    permissions = perms
                )
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, inviteRef.path)
            Result.failure(e)
        }
    }

    /**
     * Real-time list of linked sub-devices for Main Device admin panel.
     */
    fun observeLinkedDevices(workspaceId: String): Flow<List<LinkedSubDevice>> = flow {
        val uid = requireUid()
        val path = "workspaces/$workspaceId/devices"
        emitAll(
            db.collection("workspaces").document(workspaceId).collection("devices")
                .whereEqualTo("ownerUid", uid)
                .snapshots()
                .map { snapshot ->
                    snapshot.toObjects(LinkedSubDevice::class.java)
                        .sortedByDescending { it.createdAt?.seconds ?: 0L }
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Main Device updates granular permissions for a Sub Device.
     */
    suspend fun updateSubDevicePermissions(
        workspaceId: String,
        device: LinkedSubDevice,
        canAddItems: Boolean,
        canInward: Boolean,
        canDisburse: Boolean,
        canEditDelete: Boolean
    ): Result<Unit> {
        try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val deviceRef = db.collection("workspaces").document(workspaceId)
            .collection("devices").document(device.id)
        val inviteRef = db.collection("pairing_invites").document(device.inviteHash)
        return try {
            val batch = db.batch()
            batch.update(
                deviceRef,
                mapOf(
                    "canAddItems" to canAddItems,
                    "canInward" to canInward,
                    "canDisburse" to canDisburse,
                    "canEditDelete" to canEditDelete,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                inviteRef,
                mapOf(
                    "canAddItems" to canAddItems,
                    "canInward" to canInward,
                    "canDisburse" to canDisburse,
                    "canEditDelete" to canEditDelete,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (device.boundUid.isNotBlank()) {
                val memberRef = db.collection("workspaces").document(workspaceId)
                    .collection("members").document(device.boundUid)
                batch.update(
                    memberRef,
                    mapOf(
                        "canAddItems" to canAddItems,
                        "canInward" to canInward,
                        "canDisburse" to canDisburse,
                        "canEditDelete" to canEditDelete,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, deviceRef.path)
            Result.failure(e)
        }
    }

    /**
     * Main Device kicks/disconnects a Sub Device immediately and revokes its password invite.
     */
    suspend fun kickSubDevice(workspaceId: String, device: LinkedSubDevice): Result<Unit> {
        try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val deviceRef = db.collection("workspaces").document(workspaceId)
            .collection("devices").document(device.id)
        val inviteRef = db.collection("pairing_invites").document(device.inviteHash)
        return try {
            val batch = db.batch()
            batch.update(
                deviceRef,
                mapOf(
                    "isKicked" to true,
                    "canAddItems" to false,
                    "canInward" to false,
                    "canDisburse" to false,
                    "canEditDelete" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            batch.update(
                inviteRef,
                mapOf(
                    "isRevoked" to true,
                    "canAddItems" to false,
                    "canInward" to false,
                    "canDisburse" to false,
                    "canEditDelete" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            )
            if (device.boundUid.isNotBlank()) {
                val memberRef = db.collection("workspaces").document(workspaceId)
                    .collection("members").document(device.boundUid)
                batch.update(
                    memberRef,
                    mapOf(
                        "isActive" to false,
                        "isKicked" to true,
                        "canAddItems" to false,
                        "canInward" to false,
                        "canDisburse" to false,
                        "canEditDelete" to false,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, deviceRef.path)
            Result.failure(e)
        }
    }

    /**
     * Permanently removes a kicked or unused sub-device record from the Main Device list.
     */
    suspend fun deleteSubDeviceSlot(workspaceId: String, device: LinkedSubDevice): Result<Unit> {
        try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val deviceRef = db.collection("workspaces").document(workspaceId)
            .collection("devices").document(device.id)
        val inviteRef = db.collection("pairing_invites").document(device.inviteHash)
        return try {
            val batch = db.batch()
            batch.delete(deviceRef)
            batch.delete(inviteRef)
            if (device.boundUid.isNotBlank()) {
                val memberRef = db.collection("workspaces").document(workspaceId)
                    .collection("members").document(device.boundUid)
                batch.delete(memberRef)
            }
            batch.commit().await()
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, deviceRef.path)
            Result.failure(e)
        }
    }

    /**
     * Real-time listener on Sub Device for instant permission updates and kick enforcement.
     */
    fun observeMyMemberStatus(workspaceId: String): Flow<MemberStatus?> = flow {
        val uid = requireUid()
        val path = "workspaces/$workspaceId/members/$uid"
        emitAll(
            db.collection("workspaces").document(workspaceId)
                .collection("members").document(uid)
                .snapshots()
                .map { snapshot ->
                    if (snapshot.exists()) snapshot.toObject(MemberStatus::class.java) else null
                }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.GET, path)
                    }
                    throw error
                }
        )
    }

    /**
     * Real-time stream of synchronized inventory entities under workspaces/{workspaceId}/entities.
     */
    fun observeWorkspaceEntities(workspaceId: String): Flow<List<CloudEntityRecord>> = flow {
        val uid = requireUid()
        val path = "workspaces/$workspaceId/entities"
        emitAll(
            db.collection("workspaces").document(workspaceId).collection("entities")
                .snapshots()
                .map { snapshot -> snapshot.toObjects(CloudEntityRecord::class.java) }
                .catch { error ->
                    val firestoreError = generateSequence(error) { it.cause }
                        .filterIsInstance<FirebaseFirestoreException>()
                        .firstOrNull()
                    if (firestoreError == null && error is CancellationException) throw error
                    val exceptionToLog = firestoreError ?: (error as? Exception)
                    if (exceptionToLog != null && auth.currentUser?.uid == uid) {
                        handleFirestoreError(exceptionToLog, OperationType.LIST, path)
                    }
                    throw error
                }
        )
    }

    /**
     * One-shot fetch of all entities in the workspace for cloud recovery after reinstall.
     */
    suspend fun fetchAllWorkspaceEntities(workspaceId: String): Result<List<CloudEntityRecord>> {
        try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val path = "workspaces/$workspaceId/entities"
        return try {
            val snap = db.collection("workspaces").document(workspaceId)
                .collection("entities").get().await()
            Result.success(snap.toObjects(CloudEntityRecord::class.java))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, path)
            Result.failure(e)
        }
    }

    /**
     * Batch upserts inventory entity documents to Firestore (chunked to stay within 450 ops/batch).
     */
    suspend fun pushEntityRecords(
        workspaceId: String,
        records: List<CloudEntityUpsert>
    ): Result<Unit> {
        if (records.isEmpty()) return Result.success(Unit)
        val uid = try {
            requireUid()
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val entitiesCol = db.collection("workspaces").document(workspaceId).collection("entities")
        return try {
            records.chunked(400).forEach { chunk ->
                val batch = db.batch()
                chunk.forEach { item ->
                    val docRef = entitiesCol.document(item.docId)
                    val cleanPayload = item.payload.filterValues { it != null }
                    batch.set(
                        docRef,
                        mapOf(
                            "workspaceId" to workspaceId,
                            "entityType" to item.entityType,
                            "localId" to item.localId,
                            "payload" to cleanPayload,
                            "updatedByUid" to uid,
                            "isDeleted" to item.isDeleted,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                    )
                }
                batch.commit().await()
            }
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, entitiesCol.path)
            Result.failure(e)
        }
    }
}

data class CloudEntityUpsert(
    val docId: String,
    val entityType: String,
    val localId: Long,
    val payload: Map<String, Any?>,
    val isDeleted: Boolean = false
)
