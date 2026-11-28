package com.example.data.sync

import android.content.Context
import android.net.ConnectivityManager
import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
import com.example.data.cloud.DevicePermissions
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.WarehousePermission
import com.example.data.model.OperationAuditLog
import com.example.data.preferences.UserPreferencesManager
import com.example.data.repository.InventoryRepository
import com.example.util.SecurityUtils
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Date

data class CloudPairingInfo(
    val workspaceId: String,
    val deviceSlotId: String,
    val deviceName: String,
    val permissions: DevicePermissions
)

data class CloudSyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val auditLogs: Int,
    val serverTimestamp: Long
)

/** Firestore transport for multi-device collaboration. Room remains the operational database. */
class FirebaseCloudSyncEngine(
    context: Context,
    private val repository: InventoryRepository,
    private val preferences: UserPreferencesManager,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance(),
    private val firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
) {
    private val appContext = context.applicationContext
    private val syncMutex = Mutex()
    private val listenerRegistrations = mutableListOf<ListenerRegistration>()

    private fun workspaceRef(workspaceId: String) =
        firestore.collection("workspaces").document(workspaceId)

    private fun stateFile(workspaceId: String, installationId: String): File {
        val key = sha256("$workspaceId:$installationId").take(24)
        return File(appContext.noBackupFilesDir, "cloud-sync-$key.json")
    }

    private data class SyncState(val hashes: MutableMap<String, String> = linkedMapOf())

    private fun readState(file: File): SyncState {
        if (!file.exists()) return SyncState()
        return runCatching {
            val json = JSONObject(file.readText())
            val hashesJson = json.optJSONObject("hashes") ?: JSONObject()
            val hashes = linkedMapOf<String, String>()
            val keys = hashesJson.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                hashes[key] = hashesJson.optString(key)
            }
            SyncState(hashes)
        }.getOrDefault(SyncState())
    }

    private fun writeState(file: File, state: SyncState) {
        val hashes = JSONObject()
        state.hashes.forEach { (key, value) -> hashes.put(key, value) }
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(JSONObject().put("hashes", hashes).toString())
        if (!tmp.renameTo(file)) {
            file.writeText(tmp.readText())
            tmp.delete()
        }
    }

    private fun sha256(value: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun canonical(value: Any?): String = when (value) {
        null -> "null"
        is Map<*, *> -> value.entries
            .filter { it.key is String }
            .sortedBy { it.key as String }
            .joinToString(prefix = "{", postfix = "}") { (k, v) -> "${k}:${canonical(v)}" }
        is List<*> -> value.joinToString(prefix = "[", postfix = "]") { canonical(it) }
        is Number -> value.toString()
        is Boolean -> value.toString()
        else -> value.toString()
    }

    private fun signature(record: CloudEntityUpsert): String =
        sha256("${record.entityType}|${record.localId}|${record.isDeleted}|${canonical(record.payload)}")

    private fun signature(record: CloudEntityRecord): String =
        sha256("${record.entityType}|${record.localId}|${record.isDeleted}|${canonical(record.payload)}")

    private fun recordFromDocument(id: String, data: Map<String, Any?>): CloudEntityRecord {
        @Suppress("UNCHECKED_CAST")
        val payload = data["payload"] as? Map<String, Any?> ?: emptyMap()
        return CloudEntityRecord(
            id = id,
            workspaceId = data["workspaceId"] as? String ?: "",
            entityType = data["entityType"] as? String ?: "",
            localId = (data["localId"] as? Number)?.toLong() ?: 0L,
            payload = payload,
            updatedByUid = data["updatedByUid"] as? String ?: "",
            isDeleted = data["isDeleted"] as? Boolean ?: false
        )
    }

    private fun recordToMap(
        workspaceId: String,
        record: CloudEntityRecord,
        uid: String,
        installationId: String
    ): Map<String, Any?> = mapOf(
        "workspaceId" to workspaceId,
        "entityType" to record.entityType,
        "localId" to record.localId,
        "payload" to record.payload,
        "updatedByUid" to uid,
        "updatedByDeviceId" to installationId,
        "isDeleted" to record.isDeleted,
        "updatedAt" to FieldValue.serverTimestamp()
    )

    private fun localToRecord(record: CloudEntityUpsert, workspaceId: String, uid: String) =
        CloudEntityRecord(
            id = record.docId,
            workspaceId = workspaceId,
            entityType = record.entityType,
            localId = record.localId,
            payload = record.payload,
            updatedByUid = uid,
            isDeleted = record.isDeleted
        )

    private suspend fun requireUserUid(): String =
        auth.currentUser?.uid ?: throw IllegalStateException("يجب تسجيل الدخول بحساب Google أولاً")

    suspend fun ensureMainWorkspace(): String {
        val uid = requireUserUid()
        val workspaceId = preferences.ensureWorkspaceCode()
        val installId = preferences.getOrCreateInstallationId()
        val ref = workspaceRef(workspaceId)
        val existing = ref.get().await()
        val orgName = preferences.orgNameFlow.first()

        if (!existing.exists()) {
            ref.set(
                mapOf(
                    "workspaceId" to workspaceId,
                    "ownerUid" to uid,
                    "mainInstallationId" to installId,
                    "orgName" to orgName,
                    "schemaVersion" to 1,
                    "createdAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            val owner = existing.getString("ownerUid").orEmpty()
            check(owner == uid) {
                "رمز مساحة العمل مرتبط بحساب Google آخر. لا يمكن استخدام هذا الحساب كمالك."
            }
            ref.set(
                mapOf(
                    "mainInstallationId" to installId,
                    "orgName" to orgName,
                    "updatedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        }

        ref.collection("members").document(uid).set(
            mapOf(
                "uid" to uid,
                "role" to "MAIN",
                "status" to "ACTIVE",
                "deviceName" to "الجهاز الرئيسي",
                "installationId" to installId,
                "online" to true,
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
        return workspaceId
    }

    suspend fun createPairingInvite(slot: LinkedSubDevice, plainPassword: String): String {
        val uid = requireUserUid()
        val workspaceId = ensureMainWorkspace()
        val inviteHash = SecurityUtils.computeInviteHash(workspaceId, plainPassword)
        val expiry = Date(System.currentTimeMillis() + 30L * 60L * 1000L)
        workspaceRef(workspaceId).collection("pairingInvites").document(inviteHash).set(
            mapOf(
                "workspaceId" to workspaceId,
                "ownerUid" to uid,
                "deviceSlotId" to slot.id,
                "deviceName" to slot.deviceName,
                "passwordSalt" to slot.passwordSalt,
                "passwordHash" to slot.passwordHash,
                "permissions" to permissionsMap(slot.toPermissions()),
                "status" to "PENDING",
                "expiresAt" to Timestamp(expiry),
                "createdAt" to FieldValue.serverTimestamp()
            )
        ).await()
        return inviteHash
    }

    suspend fun joinWithInvite(
        workspaceIdInput: String,
        plainPassword: String,
        installationId: String
    ): CloudPairingInfo {
        val uid = requireUserUid()
        val workspaceId = workspaceIdInput.trim().uppercase()
        require(workspaceId.isNotBlank() && plainPassword.isNotBlank()) {
            "أدخل رمز مساحة العمل وكلمة المرور"
        }
        val inviteHash = SecurityUtils.computeInviteHash(workspaceId, plainPassword)
        val ref = workspaceRef(workspaceId)
        val inviteRef = ref.collection("pairingInvites").document(inviteHash)
        val invite = inviteRef.get().await()
        if (!invite.exists()) throw IllegalArgumentException("رمز الربط أو كلمة المرور غير صحيحة")
        val status = invite.getString("status").orEmpty()
        val expiry = invite.getTimestamp("expiresAt")?.toDate()?.time ?: 0L
        val claimedBy = invite.getString("claimedByUid").orEmpty()
        if (expiry < System.currentTimeMillis()) {
            throw IllegalArgumentException("انتهت صلاحية رمز الربط. اطلب رمزاً جديداً من الجهاز الرئيسي.")
        }
        if (status != "PENDING" && !(status == "CLAIMED" && claimedBy == uid)) {
            throw IllegalArgumentException("تم استخدام رمز الربط أو إلغاؤه. اطلب رمزاً جديداً.")
        }
        val salt = invite.getString("passwordSalt").orEmpty()
        val expected = invite.getString("passwordHash").orEmpty()
        if (!SecurityUtils.verifyPassword(plainPassword, salt, expected)) {
            throw IllegalArgumentException("كلمة المرور غير صحيحة")
        }
        val slotId = invite.getString("deviceSlotId").orEmpty()
        val deviceName = invite.getString("deviceName").orEmpty()
        val permissionData = invite.get("permissions") as? Map<*, *> ?: emptyMap<String, Any?>()
        val permissions = permissionsFromMap(permissionData)

        val memberRef = ref.collection("members").document(uid)
        val existingMember = memberRef.get().await()
        if (!existingMember.exists()) {
            memberRef.set(
                mapOf(
                    "uid" to uid,
                    "role" to "SUB",
                    "status" to "ACTIVE",
                    "deviceSlotId" to slotId,
                    "deviceName" to deviceName,
                    "installationId" to installationId,
                    "inviteHash" to inviteHash,
                    "permissions" to permissionsMap(permissions),
                    "online" to true,
                    "lastSeenAt" to FieldValue.serverTimestamp(),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        } else {
            check(existingMember.getString("status") == "ACTIVE" &&
                existingMember.getString("deviceSlotId") == slotId) {
                "هذا الحساب مرتبط بجهاز موقوف أو مختلف. اطلب من الرئيسي إعادة تفعيل الجهاز أولاً."
            }
            memberRef.update(
                "online", true,
                "installationId", installationId,
                "lastSeenAt", FieldValue.serverTimestamp(),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
        }
        if (status == "PENDING") {
            inviteRef.set(
                mapOf(
                    "status" to "CLAIMED",
                    "claimedByUid" to uid,
                    "claimedAt" to FieldValue.serverTimestamp()
                ),
                SetOptions.merge()
            ).await()
        }
        return CloudPairingInfo(workspaceId, slotId, deviceName, permissions)
    }

    suspend fun initializeNewSubDevice(workspaceId: String) = syncMutex.withLock {
        val uid = requireUserUid()
        val member = workspaceRef(workspaceId).collection("members").document(uid).get().await()
        check(member.exists() && member.getString("status") == "ACTIVE") {
            "الجهاز غير معتمد في مساحة العمل"
        }
        val remote = workspaceRef(workspaceId).collection("records").get().await()
            .documents.map { recordFromDocument(it.id, it.data ?: emptyMap()) }
        val localBeforePairing = repository.exportAllEntitiesForCloud()
        val tombstonedIds = remote.filter { it.isDeleted }.map { it.id }.toSet()
        val mergedForPairing = (localBeforePairing
            .filterNot { it.docId in tombstonedIds }
            .associateBy { it.docId } +
            remote.filterNot { it.isDeleted }.associateBy { it.id }).values.toList()
        if (mergedForPairing.isNotEmpty()) {
            val result = repository.replaceDatabaseFromCloudEntitiesSafely(mergedForPairing)
            check(result.isSuccess) { result.exceptionOrNull()?.message ?: "تعذر دمج بيانات المخزون" }
        }
        syncAuditLogs(workspaceId)
        val installId = preferences.getOrCreateInstallationId()
        val file = stateFile(workspaceId, installId)
        val state = SyncState()
        repository.exportAllEntitiesForCloud().forEach { state.hashes[it.docId] = signature(it) }
        remote.filter { it.isDeleted }.forEach { state.hashes[it.id] = signature(it) }
        writeState(file, state)
        markPresence(workspaceId, uid, installId)
    }

    suspend fun updatePermissions(slotId: String, permissions: DevicePermissions) {
        val workspaceId = preferences.ensureWorkspaceCode()
        val ownerUid = requireUserUid()
        val ref = workspaceRef(workspaceId)
        val workspace = ref.get().await()
        check(workspace.getString("ownerUid") == ownerUid) { "تحديث الصلاحيات متاح للمالك فقط" }
        val matches = ref.collection("members").whereEqualTo("deviceSlotId", slotId).get().await()
        matches.documents.forEach { doc ->
            doc.reference.update(
                mapOf(
                    "permissions" to permissionsMap(permissions),
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
        val pendingInvites = ref.collection("pairingInvites")
            .whereEqualTo("deviceSlotId", slotId)
            .whereEqualTo("status", "PENDING")
            .get().await()
        pendingInvites.documents.forEach { doc ->
            doc.reference.update(
                "permissions", permissionsMap(permissions),
                "updatedAt", FieldValue.serverTimestamp()
            ).await()
        }
    }

    suspend fun setDeviceKicked(slotId: String, kicked: Boolean) {
        val workspaceId = preferences.ensureWorkspaceCode()
        val ownerUid = requireUserUid()
        val ref = workspaceRef(workspaceId)
        check(ref.get().await().getString("ownerUid") == ownerUid) { "إجراء الأجهزة متاح للمالك فقط" }
        val matches = ref.collection("members").whereEqualTo("deviceSlotId", slotId).get().await()
        matches.documents.forEach { doc ->
            doc.reference.update(
                mapOf(
                    "status" to if (kicked) "KICKED" else "ACTIVE",
                    "online" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun removeDeviceSlot(slotId: String) {
        val workspaceId = preferences.ensureWorkspaceCode()
        val ref = workspaceRef(workspaceId)
        val matches = ref.collection("members").whereEqualTo("deviceSlotId", slotId).get().await()
        matches.documents.forEach { doc ->
            doc.reference.update(
                "status", "REVOKED", "online", false, "updatedAt", FieldValue.serverTimestamp()
            ).await()
        }
    }

    suspend fun unlinkCurrentDevice() {
        val uid = requireUserUid()
        val workspaceId = preferences.workspaceCodeFlow.first()
        if (workspaceId.isBlank()) return
        val memberRef = workspaceRef(workspaceId).collection("members").document(uid)
        val member = memberRef.get().await()
        if (member.exists() && member.getString("role") == "SUB") {
            memberRef.update(
                mapOf(
                    "status" to "UNLINKED",
                    "online" to false,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
            ).await()
        }
    }

    suspend fun isSubDeviceOnline(slotId: String, freshForMillis: Long = 90_000L): Boolean {
        val workspaceId = preferences.workspaceCodeFlow.first()
        if (workspaceId.isBlank()) return false
        val members = workspaceRef(workspaceId).collection("members")
            .whereEqualTo("deviceSlotId", slotId)
            .whereEqualTo("status", "ACTIVE")
            .get().await()
        val lastSeen = members.documents.firstOrNull()?.getTimestamp("lastSeenAt")?.toDate()?.time ?: 0L
        return lastSeen > 0L && System.currentTimeMillis() - lastSeen <= freshForMillis
    }

    suspend fun heartbeat() {
        val uid = requireUserUid()
        val role = preferences.deviceRoleFlow.first()
        val workspaceId = preferences.workspaceCodeFlow.first().ifBlank { preferences.ensureWorkspaceCode() }
        check(workspaceId.isNotBlank()) { "لا توجد مساحة عمل سحابية مرتبطة بهذا الجهاز" }
        val memberRef = workspaceRef(workspaceId).collection("members").document(uid)
        if (role.name != "MAIN") {
            val member = memberRef.get().await()
            if (!member.exists()) throw IllegalStateException("عضوية هذا الجهاز غير مسجلة في السحابة")
            val status = member.getString("status").orEmpty()
            if (status != "ACTIVE") {
                if (status == "KICKED" || status == "REVOKED" || status == "UNLINKED") {
                    repository.clearAllUserData(preserveAuditLogs = false)
                    preferences.markSubDeviceKicked()
                    throw IllegalStateException("تم إيقاف ارتباط هذا الجهاز من المستخدم الرئيسي")
                }
                throw IllegalStateException("حالة ارتباط هذا الجهاز غير نشطة")
            }
            val permissions = permissionsFromMap(
                member.get("permissions") as? Map<*, *> ?: emptyMap<String, Any?>()
            )
            preferences.updateSubDevicePermissions(permissions)
        }
        markPresence(workspaceId, uid, preferences.getOrCreateInstallationId())
    }

    suspend fun startRealtimeListeners(
        scope: CoroutineScope,
        onError: (String) -> Unit
    ) {
        stopRealtimeListeners()
        val uid = requireUserUid()
        val role = preferences.deviceRoleFlow.first()
        val workspaceId = if (role.name == "MAIN") ensureMainWorkspace() else preferences.workspaceCodeFlow.first()
        check(workspaceId.isNotBlank()) { "لا توجد مساحة عمل سحابية مرتبطة بهذا الجهاز" }
        val workspace = workspaceRef(workspaceId)
        val lastSecurityState = linkedMapOf<String, String>()

        fun requestSync(reason: String) {
            scope.launch {
                runCatching { syncOnce() }
                    .onFailure { error ->
                        val message = error.localizedMessage ?: "فشلت المزامنة السحابية"
                        onError(reason + ": " + message)
                    }
            }
        }

        listenerRegistrations += workspace.collection("records")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error.localizedMessage ?: "انقطع مستمع بيانات المخزون")
                } else if (snapshot != null && snapshot.documentChanges.isNotEmpty()) {
                    requestSync("تحديث بيانات المخزون")
                }
            }

        listenerRegistrations += workspace.collection("auditLogs")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error.localizedMessage ?: "انقطع مستمع سجل العمليات")
                } else if (snapshot != null && snapshot.documentChanges.isNotEmpty()) {
                    requestSync("تحديث سجل العمليات")
                }
            }

        listenerRegistrations += workspace.collection("members").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    onError(error.localizedMessage ?: "تعذر متابعة صلاحيات الجهاز")
                } else if (snapshot != null && snapshot.exists()) {
                    val status = snapshot.getString("status").orEmpty()
                    val permissions = snapshot.get("permissions")
                    val securityState = sha256(status + "|" + canonical(permissions))
                    val previous = lastSecurityState.put("member", securityState)
                    if (previous != null && previous != securityState) {
                        requestSync("تحديث الصلاحيات أو حالة الجهاز")
                    }
                }
            }
    }

    fun stopRealtimeListeners() {
        listenerRegistrations.forEach { registration -> registration.remove() }
        listenerRegistrations.clear()
    }

    suspend fun syncOnce(): CloudSyncResult = syncMutex.withLock {
        if (!hasNetworkConnection()) {
            throw IllegalStateException("لا يوجد اتصال بالإنترنت حالياً. تم الاحتفاظ بالتعديلات محلياً وستعاد المحاولة تلقائياً.")
        }
        val uid = requireUserUid()
        val role = preferences.deviceRoleFlow.first()
        val workspaceId = if (role.name == "MAIN") ensureMainWorkspace() else preferences.workspaceCodeFlow.first()
        check(workspaceId.isNotBlank()) { "لا توجد مساحة عمل سحابية مرتبطة بهذا الجهاز" }
        val installationId = preferences.getOrCreateInstallationId()
        val workspace = workspaceRef(workspaceId)
        val memberRef = workspace.collection("members").document(uid)

        if (role.name != "MAIN") {
            val member = memberRef.get().await()
            if (!member.exists()) {
                throw IllegalStateException("عضوية هذا الجهاز غير مسجلة في مساحة العمل السحابية. أعد ربط الجهاز باستخدام رمز دعوة جديد.")
            }
            val status = member.getString("status").orEmpty()
            if (status == "KICKED" || status == "REVOKED" || status == "UNLINKED") {
                repository.clearAllUserData(preserveAuditLogs = false)
                preferences.markSubDeviceKicked()
                throw IllegalStateException("تم إيقاف ارتباط هذا الجهاز من المستخدم الرئيسي")
            }
            check(status == "ACTIVE") { "حالة ارتباط هذا الجهاز غير نشطة في السحابة" }
            val permissions = permissionsFromMap(
                member.get("permissions") as? Map<*, *> ?: emptyMap<String, Any?>()
            )
            preferences.updateSubDevicePermissions(permissions)
        }

        markPresence(workspaceId, uid, installationId)
        refreshMainDevicePresence(workspaceId, role.name == "MAIN")

        val recordsRef = workspace.collection("records")
        val remoteDocs = recordsRef.get().await().documents
        val remoteById = remoteDocs.associate { doc ->
            doc.id to recordFromDocument(doc.id, doc.data ?: emptyMap())
        }
        val localById = repository.exportAllEntitiesForCloud().associateBy { it.docId }
        val file = stateFile(workspaceId, installationId)
        val baseline = readState(file)
        val merged = linkedMapOf<String, CloudEntityRecord>()
        val writes = mutableListOf<CloudEntityRecord>()
        val allIds = (baseline.hashes.keys + localById.keys + remoteById.keys).toSortedSet()

        for (id in allIds) {
            val local = localById[id]
            val remote = remoteById[id]
            val baseHash = baseline.hashes[id]
            val localHash = local?.let(::signature)
            val remoteHash = remote?.let(::signature)
            val localDeleted = local == null && baseHash != null
            val localChanged = local != null && (baseHash == null || localHash != baseHash)
            val remoteChanged = remote != null && remoteHash != baseHash

            val chosen = when {
                localDeleted -> CloudEntityRecord(
                    id = id,
                    workspaceId = workspaceId,
                    entityType = remote?.entityType ?: "unknown",
                    localId = remote?.localId ?: 0L,
                    payload = emptyMap(),
                    updatedByUid = uid,
                    isDeleted = true
                )
                localChanged -> localToRecord(local!!, workspaceId, uid)
                remoteChanged -> remote
                local != null -> localToRecord(local, workspaceId, uid)
                else -> remote
            }

            if (chosen != null) {
                merged[id] = chosen
                if (remote == null || signature(remote) != signature(chosen)) writes.add(chosen)
            }
        }

        var uploaded = 0
        writes.chunked(450).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { record ->
                batch.set(
                    recordsRef.document(record.id),
                    recordToMap(workspaceId, record, uid, installationId)
                )
            }
            batch.commit().await()
            uploaded += chunk.size
        }

        val mergedList = merged.values.toList()
        val needsLocalApply = mergedList.size != localById.size ||
            mergedList.any { rec -> localById[rec.id]?.let(::signature) != signature(rec) }
        if (needsLocalApply) {
            val result = repository.replaceDatabaseFromCloudEntitiesSafely(mergedList)
            check(result.isSuccess) {
                result.exceptionOrNull()?.message ?: "تعذر تطبيق التغييرات السحابية على قاعدة البيانات المحلية"
            }
        }

        baseline.hashes.clear()
        val actualLocalAfterMerge = repository.exportAllEntitiesForCloud().associateBy { it.docId }
        mergedList.forEach { record ->
            baseline.hashes[record.id] = if (record.isDeleted) {
                signature(record)
            } else {
                actualLocalAfterMerge[record.id]?.let(::signature) ?: signature(record)
            }
        }
        writeState(file, baseline)

        val auditCount = syncAuditLogs(workspaceId)
        CloudSyncResult(
            uploaded = uploaded,
            downloaded = mergedList.count { rec ->
                val local = localById[rec.id]
                rec.isDeleted || local?.let(::signature) != signature(rec)
            },
            auditLogs = auditCount,
            serverTimestamp = System.currentTimeMillis()
        )
    }

    private fun hasNetworkConnection(): Boolean {
        val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return true
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private suspend fun markPresence(workspaceId: String, uid: String, installationId: String) {
        workspaceRef(workspaceId).collection("members").document(uid).set(
            mapOf(
                "uid" to uid,
                "installationId" to installationId,
                "online" to true,
                "lastSeenAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            ),
            SetOptions.merge()
        ).await()
    }

    private suspend fun refreshMainDevicePresence(workspaceId: String, isMain: Boolean) {
        if (!isMain) return
        val members = workspaceRef(workspaceId).collection("members").get().await()
        val lastSeenBySlot = members.documents.mapNotNull { doc ->
            val slotId = doc.getString("deviceSlotId") ?: return@mapNotNull null
            val timestamp = doc.getTimestamp("lastSeenAt")?.toDate()?.time ?: 0L
            slotId to timestamp
        }.toMap()
        val current = preferences.getLinkedSubDevicesSnapshot()
        if (current.isNotEmpty()) {
            preferences.saveLinkedSubDevices(
                current.map { slot ->
                    val seen = lastSeenBySlot[slot.id]
                    if (seen != null) slot.copy(isClaimed = true, lastSeenAt = seen) else slot
                }
            )
        }
    }

    private suspend fun syncAuditLogs(workspaceId: String): Int {
        val auditRef = workspaceRef(workspaceId).collection("auditLogs")
        val localLogs = repository.operationAuditLogs.first()
        val localIds = localLogs.map(::auditDocumentId).toSet()
        val remoteSnapshot = auditRef.get().await()
        val remoteIds = remoteSnapshot.documents.map { it.id }.toSet()
        val newLogs = localLogs.filter { auditDocumentId(it) !in remoteIds }

        newLogs.chunked(450).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { log ->
                val docId = auditDocumentId(log)
                batch.set(
                    auditRef.document(docId),
                    mapOf(
                        "timestamp" to log.timestamp,
                        "actorName" to log.actorName,
                        "deviceName" to log.deviceName,
                        "operationType" to log.operationType,
                        "title" to log.title,
                        "details" to log.details,
                        "createdAt" to FieldValue.serverTimestamp()
                    )
                )
            }
            batch.commit().await()
        }

        val incoming = remoteSnapshot.documents.mapNotNull { doc ->
            if (doc.id in localIds) return@mapNotNull null
            val data = doc.data
            val timestamp = (data["timestamp"] as? Number)?.toLong() ?: return@mapNotNull null
            OperationAuditLog(
                timestamp = timestamp,
                actorName = data["actorName"] as? String ?: "",
                deviceName = data["deviceName"] as? String ?: "",
                operationType = data["operationType"] as? String ?: "GENERAL",
                title = data["title"] as? String ?: "",
                details = data["details"] as? String ?: ""
            )
        }
        if (incoming.isNotEmpty()) repository.importOperationLogsFromMain(incoming)
        return incoming.size + newLogs.size
    }

    private fun auditDocumentId(log: OperationAuditLog): String =
        sha256(
            listOf(
                log.timestamp, log.actorName, log.deviceName, log.operationType, log.title, log.details
            ).joinToString("|")
        )

    private fun permissionsMap(p: DevicePermissions): Map<String, Any?> = mapOf(
        "isMainDevice" to p.isMainDevice,
        "canAddItems" to p.canAddItems,
        "canInward" to p.canInward,
        "canDisburse" to p.canDisburse,
        "canEditDelete" to p.canEditDelete,
        "canEditInventory" to p.canEditInventory,
        "canDeleteItem" to p.canDeleteItem,
        "canEditIssueVoucher" to p.canEditIssueVoucher,
        "canDeleteIssueVoucher" to p.canDeleteIssueVoucher,
        "canEditSupplyVoucher" to p.canEditSupplyVoucher,
        "canDeleteSupplyVoucher" to p.canDeleteSupplyVoucher,
        "canEditCatalogItems" to p.canEditCatalogItems,
        "isKicked" to p.isKicked,
        "canBackup" to p.canBackup,
        "canViewAllWarehouses" to p.canViewAllWarehouses,
        "warehousePermissions" to p.warehousePermissions.map { w ->
            mapOf(
                "warehouseId" to w.warehouseId,
                "canView" to w.canView,
                "canInward" to w.canInward,
                "canDisburse" to w.canDisburse,
                "canTransfer" to w.canTransfer,
                "canAdjust" to w.canAdjust,
                "canReports" to w.canReports,
                "canEdit" to w.canEdit,
                "canEditInventory" to w.canEditInventory,
                "canDeleteItem" to w.canDeleteItem
            )
        }
    )

    private fun permissionsFromMap(raw: Map<*, *>): DevicePermissions {
        fun bool(key: String, default: Boolean = false) = raw[key] as? Boolean ?: default
        val grants = (raw["warehousePermissions"] as? List<*>)?.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            fun g(key: String, default: Boolean = false) = m[key] as? Boolean ?: default
            WarehousePermission(
                warehouseId = (m["warehouseId"] as? Number)?.toLong() ?: 0L,
                canView = g("canView", true),
                canInward = g("canInward"),
                canDisburse = g("canDisburse"),
                canTransfer = g("canTransfer"),
                canAdjust = g("canAdjust"),
                canReports = g("canReports"),
                canEdit = g("canEdit"),
                canEditInventory = g("canEditInventory", g("canEdit")),
                canDeleteItem = g("canDeleteItem", g("canEdit"))
            )
        } ?: emptyList()
        return DevicePermissions(
            isMainDevice = false,
            canAddItems = bool("canAddItems"),
            canInward = bool("canInward"),
            canDisburse = bool("canDisburse"),
            canEditDelete = bool("canEditDelete"),
            canEditInventory = bool("canEditInventory"),
            canDeleteItem = bool("canDeleteItem"),
            canEditIssueVoucher = bool("canEditIssueVoucher"),
            canDeleteIssueVoucher = bool("canDeleteIssueVoucher"),
            canEditSupplyVoucher = bool("canEditSupplyVoucher"),
            canDeleteSupplyVoucher = bool("canDeleteSupplyVoucher"),
            canEditCatalogItems = bool("canEditCatalogItems"),
            isKicked = bool("isKicked"),
            canBackup = bool("canBackup"),
            canViewAllWarehouses = bool("canViewAllWarehouses"),
            warehousePermissions = grants
        )
    }
}
