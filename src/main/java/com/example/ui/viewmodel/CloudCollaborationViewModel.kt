package com.example.ui.viewmodel

import android.app.Application
import android.content.Intent
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.InventoryApp
import com.example.service.CloudPresenceService
import com.example.data.cloud.CloudCollaborationRepository
import com.example.data.cloud.CloudConversation
import com.example.data.cloud.CloudDevice
import com.example.data.cloud.CloudMessage
import com.example.data.cloud.CloudRegisteredUser
import com.example.data.cloud.CloudWarehouse
import com.example.data.cloud.CloudWarehouseMembership
import com.example.data.cloud.CloudWorkspace
import com.example.data.cloud.CloudWorkspaceMember
import com.example.data.cloud.CloudEntitySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CloudCollaborationViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = (application as InventoryApp).preferencesManager
    private val repository = CloudCollaborationRepository()
    private val localInventoryRepository = (application as InventoryApp).repository

    private val _userEmail = MutableStateFlow("")
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _currentUserId = MutableStateFlow("")
    val currentUserId: StateFlow<String> = _currentUserId.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _workspaces = MutableStateFlow<List<CloudWorkspace>>(emptyList())
    val workspaces: StateFlow<List<CloudWorkspace>> = _workspaces.asStateFlow()

    private val _canCreateFirstWorkspace = MutableStateFlow(false)
    val canCreateFirstWorkspace: StateFlow<Boolean> = _canCreateFirstWorkspace.asStateFlow()

    private val _activeWorkspace = MutableStateFlow<CloudWorkspace?>(null)
    val activeWorkspace: StateFlow<CloudWorkspace?> = _activeWorkspace.asStateFlow()

    private val _members = MutableStateFlow<List<CloudWorkspaceMember>>(emptyList())
    val members: StateFlow<List<CloudWorkspaceMember>> = _members.asStateFlow()

    private val _devices = MutableStateFlow<List<CloudDevice>>(emptyList())
    val devices: StateFlow<List<CloudDevice>> = _devices.asStateFlow()

    private val _registeredUsers = MutableStateFlow<List<CloudRegisteredUser>>(emptyList())
    val registeredUsers: StateFlow<List<CloudRegisteredUser>> = _registeredUsers.asStateFlow()

    private val _warehouses = MutableStateFlow<List<CloudWarehouse>>(emptyList())
    val warehouses: StateFlow<List<CloudWarehouse>> = _warehouses.asStateFlow()

    private val _warehouseMemberships = MutableStateFlow<Map<String, List<CloudWarehouseMembership>>>(emptyMap())
    val warehouseMemberships: StateFlow<Map<String, List<CloudWarehouseMembership>>> = _warehouseMemberships.asStateFlow()

    private val _conversations = MutableStateFlow<List<CloudConversation>>(emptyList())
    val conversations: StateFlow<List<CloudConversation>> = _conversations.asStateFlow()

    private val _selectedConversation = MutableStateFlow<CloudConversation?>(null)
    val selectedConversation: StateFlow<CloudConversation?> = _selectedConversation.asStateFlow()

    private val _messages = MutableStateFlow<List<CloudMessage>>(emptyList())
    val messages: StateFlow<List<CloudMessage>> = _messages.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy: StateFlow<Boolean> = _isBusy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice.asStateFlow()

    private var heartbeatJob: Job? = null
    private var messagesRealtimeJob: Job? = null

    fun refresh() {
        if (_isBusy.value) return
        viewModelScope.launch {
            if (_isBusy.value) return@launch
            _isBusy.value = true
            _error.value = null
            try {
                val uid = repository.currentUserIdOrNull()
                _isAuthenticated.value = !uid.isNullOrBlank()
                _currentUserId.value = uid.orEmpty()
                _userEmail.value = repository.currentUserEmailOrNull().orEmpty()
                if (uid.isNullOrBlank()) {
                    _workspaces.value = emptyList()
                    _canCreateFirstWorkspace.value = false
                    _activeWorkspace.value = null
                    _currentUserId.value = ""
                    _members.value = emptyList()
                    _devices.value = emptyList()
                    _registeredUsers.value = emptyList()
                    stopCloudPresenceService()
                    _warehouses.value = emptyList()
                    _conversations.value = emptyList()
                    _notice.value = "اربط حساب Google من الإعدادات أولاً لتفعيل التعاون السحابي."
                    return@launch
                }

                // Clear an old sign-in warning once the current Supabase session is valid.
                _notice.value = null
                val available = repository.listMyWorkspaces()
                _workspaces.value = available
                if (available.isEmpty()) {
                    _devices.value = emptyList()
                    stopCloudPresenceService()
                    _canCreateFirstWorkspace.value = repository.canCreateFirstWorkspace()
                    _activeWorkspace.value = null
                    _members.value = emptyList()
                    _warehouses.value = emptyList()
                    _conversations.value = emptyList()
                    _notice.value = if (_canCreateFirstWorkspace.value) {
                        "هذه أول مرة يتم فيها إعداد التطبيق السحابي. أنشئ مساحة العمل الرئيسية مرة واحدة."
                    } else {
                        "مساحة العمل الرئيسية موجودة بالفعل. اطلب من المسؤول إضافة بريد Google الخاص بك؛ لا تنشئ مساحة أخرى."
                    }
                    return@launch
                }

                _canCreateFirstWorkspace.value = false
                // The app uses one shared workspace. Prefer the owner's workspace if
                // legacy memberships are returned while an older client is still active.
                val savedId = preferences.cloudWorkspaceIdFlow.first()
                val selected = available.firstOrNull { it.id == savedId }
                    ?: available.firstOrNull { it.ownerUserId == uid }
                    ?: available.first()
                if (selected.id != savedId) preferences.saveCloudWorkspace(selected.id, selected.name)
                _activeWorkspace.value = selected
                loadWorkspaceContent(selected.id)
                ensureCloudDeviceRegistered(selected)
                bootstrapCloudWorkspace(selected)
                // Bootstrap can create or refresh cloud memberships and conversations.
                // Reload once afterwards so the owner immediately sees every active member,
                // direct conversation, and warehouse group without leaving this screen.
                loadWorkspaceContent(selected.id)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                _error.value = humanError(failure)
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun createWorkspace(name: String) {
        viewModelScope.launch {
            runCloudAction("تعذر إنشاء مساحة العمل") {
                val workspace = repository.createWorkspace(name)
                preferences.saveCloudWorkspace(workspace.id, workspace.name)
                _activeWorkspace.value = workspace
                _canCreateFirstWorkspace.value = false
                refreshAfterWorkspaceChange(workspace)
                _notice.value = "تم إعداد مساحة العمل الرئيسية المشتركة بنجاح."
            }
        }
    }

    fun selectWorkspace(workspace: CloudWorkspace) {
        viewModelScope.launch {
            runCloudAction("تعذر تبديل مساحة العمل") {
                heartbeatJob?.cancel()
                messagesRealtimeJob?.cancel()
                _selectedConversation.value = null
                _messages.value = emptyList()
                preferences.saveCloudWorkspace(workspace.id, workspace.name)
                _activeWorkspace.value = workspace
                loadWorkspaceContent(workspace.id)
                ensureCloudDeviceRegistered(workspace)
                bootstrapCloudWorkspace(workspace)
                _notice.value = "تم التبديل إلى مساحة العمل: ${workspace.name}"
            }
        }
    }

    fun registerCurrentDevice() {
        viewModelScope.launch {
            runCloudAction("تعذر تسجيل هذا الجهاز") {
                val workspace = _activeWorkspace.value ?: error("اختر مساحة عمل أولاً")
                ensureCloudDeviceRegistered(workspace, force = true)
                _notice.value = "تم تسجيل هذا الجهاز في مساحة العمل السحابية."
            }
        }
    }

    fun addMember(email: String, displayName: String, role: String) {
        viewModelScope.launch {
            runCloudAction("تعذرت إضافة المستخدم") {
                val workspace = _activeWorkspace.value ?: error("لا توجد مساحة عمل محددة")
                repository.addMemberByEmail(workspace.id, email, role, displayName)
                _members.value = repository.listWorkspaceMembers(workspace.id)
                _registeredUsers.value = repository.listRegisteredWorkspaceUsers(workspace.id)
                _notice.value = "تمت إضافة الحساب إلى مساحة العمل. امنحه صلاحيات المخازن من تبويب الصلاحيات."
            }
        }
    }

    fun startDirectConversation(member: CloudWorkspaceMember) {
        viewModelScope.launch {
            runCloudAction("تعذر فتح المحادثة") {
                val workspace = _activeWorkspace.value ?: error("لا توجد مساحة عمل محددة")
                val conversationId = repository.createDirectConversation(workspace.id, member.userId)
                openConversationInternal(conversationId)
                _notice.value = "تم فتح المحادثة المباشرة مع ${member.displayName.ifBlank { member.email }}."
            }
        }
    }

    fun startWarehouseConversation(warehouse: CloudWarehouse) {
        viewModelScope.launch {
            runCloudAction("تعذر إنشاء محادثة المخزن") {
                val workspace = _activeWorkspace.value ?: error("لا توجد مساحة عمل محددة")
                val conversationId = repository.createWarehouseConversation(
                    workspace.id, warehouse.id, "محادثة ${warehouse.name}"
                )
                openConversationInternal(conversationId)
                _notice.value = "تم إنشاء محادثة مخزن ${warehouse.name}."
            }
        }
    }

    fun openConversation(conversation: CloudConversation) {
        viewModelScope.launch {
            runCloudAction("تعذر تحميل المحادثة") {
                openConversationInternal(conversation.id)
            }
        }
    }

    fun closeConversation() {
        messagesRealtimeJob?.cancel()
        messagesRealtimeJob = null
        _selectedConversation.value = null
        _messages.value = emptyList()
    }

    fun sendMessage(body: String, replyToMessageId: String? = null) {
        viewModelScope.launch {
            val conversation = _selectedConversation.value ?: return@launch
            runCloudAction("تعذر إرسال الرسالة") {
                val sent = repository.sendMessage(conversation.id, body, replyToMessageId)
                _messages.value = (_messages.value.filterNot { it.id == sent.id } + sent)
                    .sortedBy { it.createdAt }
            }
        }
    }

    fun sendAttachment(fileName: String, mimeType: String, bytes: ByteArray) {
        viewModelScope.launch {
            val conversation = _selectedConversation.value ?: return@launch
            runCloudAction("تعذر إرسال المرفق") {
                val sent = repository.sendAttachment(conversation.id, fileName, mimeType, bytes)
                _messages.value = (_messages.value.filterNot { it.id == sent.id } + sent)
                    .sortedBy { it.createdAt }
            }
        }
    }

    fun hideMessageForMe(messageId: String) {
        viewModelScope.launch {
            runCloudAction("تعذر إخفاء الرسالة") {
                repository.hideMessageForMe(messageId)
                _messages.value = _messages.value.filterNot { it.id == messageId }
            }
        }
    }

    fun hideMessagesForMe(messageIds: List<String>) {
        val uniqueIds = messageIds.distinct().filter { it.isNotBlank() }
        if (uniqueIds.isEmpty()) return
        viewModelScope.launch {
            runCloudAction("تعذر إخفاء الرسائل المحددة") {
                uniqueIds.forEach { repository.hideMessageForMe(it) }
                _messages.value = _messages.value.filterNot { it.id in uniqueIds.toSet() }
            }
        }
    }

    fun editMessage(messageId: String, body: String) {
        viewModelScope.launch {
            runCloudAction("تعذر تعديل الرسالة") {
                val updated = repository.editMessage(messageId, body)
                _messages.value = _messages.value.map { if (it.id == updated.id) updated else it }
            }
        }
    }

    fun deleteMessage(messageId: String) {
        viewModelScope.launch {
            runCloudAction("تعذر حذف الرسالة نهائياً") {
                repository.deleteMessage(messageId)
                _messages.value = _messages.value.filterNot { it.id == messageId }
            }
        }
    }

    suspend fun resolveAttachmentUrl(path: String): String =
        repository.resolveAttachmentUrl(path)

    fun dismissNotice() {
        _notice.value = null
        _error.value = null
    }

    private suspend fun refreshAfterWorkspaceChange(workspace: CloudWorkspace) {
        loadWorkspaceContent(workspace.id)
        ensureCloudDeviceRegistered(workspace, force = true)
        bootstrapCloudWorkspace(workspace)
        _workspaces.value = repository.listMyWorkspaces()
    }

    private suspend fun bootstrapCloudWorkspace(workspace: CloudWorkspace) {
        val deviceId = preferences.cloudDeviceIdFlow.first()
        if (deviceId.isBlank()) error("تعذر تسجيل الجهاز في مساحة العمل السحابية")

        val userId = repository.currentUserIdOrNull()
            ?: error("انتهت جلسة حساب Google، أعد تسجيل الدخول")
        val localWarehouses = localInventoryRepository.allWarehouses.first()

        if (workspace.ownerUserId == userId) {
            localWarehouses.forEach { warehouse ->
                repository.ensureCloudWarehouse(workspace.id, warehouse)
            }
            _warehouses.value = repository.listWarehouses(workspace.id)

            val existingSnapshot = repository.fetchCloudEntitySnapshotVersioned(workspace.id)
            if (existingSnapshot.records.isEmpty()) {
                _notice.value = "مساحة العمل جاهزة، ولم تُرفع بيانات المخازن بعد. راجع البيانات ثم اضغط (رفع بيانات هذا الجهاز) لبدء النسخ السحابي."
            } else {
                val savedSignature = preferences.cloudSnapshotSignatureFlow.first()
                if (savedSignature.isBlank() && localWarehouses.isEmpty()) {
                    applyCloudSnapshot(existingSnapshot, workspace.id, force = false)
                }
            }
        } else {
            val cloudSnapshot = repository.fetchCloudEntitySnapshotVersioned(workspace.id)
            val savedSignature = preferences.cloudSnapshotSignatureFlow.first()
            val localCounts = localInventoryRepository.getLocalDiagnosticsCounts()
            if (savedSignature.isBlank() && localCounts.warehouses == 0 && cloudSnapshot.records.isNotEmpty()) {
                applyCloudSnapshot(cloudSnapshot, workspace.id, force = false)
            } else if (cloudSnapshot.records.isEmpty()) {
                _notice.value = "تم ربط الحساب، لكن لم تُرفع بيانات المخازن إلى مساحة العمل بعد."
            }
        }
    }

    private suspend fun applyCloudSnapshot(
        snapshot: CloudEntitySnapshot,
        workspaceId: String,
        force: Boolean
    ) {
        if (snapshot.records.isEmpty()) return
        val lanPending = localInventoryRepository.getPendingSyncOperations()
        val cloudPending = localInventoryRepository.getCloudPendingSyncOperations()
        if (!force && (lanPending.isNotEmpty() || cloudPending.isNotEmpty())) {
            _notice.value = "توجد عمليات محلية لم تُرفع بعد؛ تم تأجيل استبدال البيانات حتى لا تضيع."
            return
        }
        val result = localInventoryRepository.replaceDatabaseFromCloudEntitiesSafely(snapshot.records)
        result.getOrElse { throw it }
        preferences.saveCloudSnapshotSignature(snapshot.updatedAtSignature)
        _notice.value = "تم تنزيل بيانات مساحة العمل من السحابة بنجاح."
    }

    fun uploadLocalInventoryToCloud() {
        viewModelScope.launch {
            runCloudAction("تعذر رفع بيانات المخازن") {
                val workspace = _activeWorkspace.value ?: error("لا توجد مساحة عمل محددة")
                val userId = repository.currentUserIdOrNull()
                    ?: error("أعد تسجيل الدخول بحساب Google")
                if (workspace.ownerUserId != userId) error("رفع قاعدة المخازن بالكامل متاح للمستخدم الرئيسي فقط")

                val deviceId = preferences.cloudDeviceIdFlow.first()
                if (deviceId.isBlank()) error("هذا الجهاز غير مسجل في مساحة العمل")
                val localWarehouses = localInventoryRepository.allWarehouses.first()
                if (localWarehouses.isEmpty()) error("لا توجد مخازن محلية لرفعها؛ لم يتم تغيير البيانات السحابية.")
                localWarehouses.forEach { warehouse ->
                    repository.ensureCloudWarehouse(workspace.id, warehouse)
                }
                _warehouses.value = repository.listWarehouses(workspace.id)
                val warehouseMap = _warehouses.value.mapNotNull { remote ->
                    remote.localId?.let { localId -> localId to remote.id }
                }.toMap()
                val targetLocalIds = localWarehouses.map { it.id }.toSet()
                val entities = localInventoryRepository.exportAllEntitiesForCloud()
                val encoded = repository.encodeCloudEntityRecords(
                    entities = entities,
                    cloudWarehouseIdByLocalId = warehouseMap,
                    targetWarehouseLocalIds = targetLocalIds,
                    includeGlobalRecords = true
                )
                val replacedCount = repository.replaceCloudWorkspaceSnapshot(workspace.id, deviceId, encoded)
                val seeded = repository.fetchCloudEntitySnapshotVersioned(workspace.id)
                preferences.saveCloudSnapshotSignature(seeded.updatedAtSignature)
                preferences.incrementSyncRevision()
                _notice.value = "تم تحديث بيانات السحابة بالكامل: $replacedCount سجلاً. تمت إزالة السجلات القديمة والمحذوفة من النسخة السابقة."
            }
        }
    }

    fun downloadCloudWorkspaceData() {
        viewModelScope.launch {
            runCloudAction("تعذر تنزيل بيانات مساحة العمل") {
                val workspace = _activeWorkspace.value ?: error("لا توجد مساحة عمل محددة")
                val snapshot = repository.fetchCloudEntitySnapshotVersioned(workspace.id)
                if (snapshot.records.isEmpty()) error("لا توجد بيانات سحابية لهذه المساحة بعد")
                applyCloudSnapshot(snapshot, workspace.id, force = true)
            }
        }
    }

    private suspend fun refreshCloudSnapshotIfChanged(workspaceId: String) {
        val snapshot = repository.fetchCloudEntitySnapshotVersioned(workspaceId)
        if (snapshot.records.isEmpty()) return
        val savedSignature = preferences.cloudSnapshotSignatureFlow.first()
        if (snapshot.updatedAtSignature == savedSignature) return
        if (savedSignature.isBlank()) return // Initial import is explicit unless the local database is empty.
        applyCloudSnapshot(snapshot, workspaceId, force = false)
    }

    private suspend fun loadWorkspaceContent(workspaceId: String) {
        _members.value = repository.listWorkspaceMembers(workspaceId)
        _devices.value = runCatching { repository.listWorkspaceDevices(workspaceId) }.getOrDefault(emptyList())
        _registeredUsers.value = if (_activeWorkspace.value?.ownerUserId == repository.currentUserIdOrNull()) {
            repository.listRegisteredWorkspaceUsers(workspaceId)
        } else emptyList()
        val allWarehouses = repository.listWarehouses(workspaceId)
        val ownerId = _activeWorkspace.value?.ownerUserId.orEmpty()
        val currentUserId = repository.currentUserIdOrNull().orEmpty()
        val membershipsByWarehouse = allWarehouses.associate { warehouse ->
            warehouse.id to repository.listWarehouseMemberships(warehouse.id)
        }
        _warehouses.value = if (currentUserId == ownerId) allWarehouses else allWarehouses.filter { warehouse ->
            membershipsByWarehouse[warehouse.id].orEmpty().any { grant ->
                grant.userId == currentUserId && grant.permissions["canView"]?.toString() == "true"
            }
        }
        _warehouseMemberships.value = membershipsByWarehouse.filterKeys { id ->
            _warehouses.value.any { it.id == id }
        }
        var visibleConversations = repository.listConversations(workspaceId, ownerId)
        if (currentUserId == ownerId) {
            _members.value.filter { it.userId != ownerId && it.isActive }.forEach { member ->
                // One unavailable/legacy member must not prevent other direct chats
                // from appearing in the owner's conversation list.
                runCatching { repository.createDirectConversation(workspaceId, member.userId) }
            }
            _warehouses.value.forEach { warehouse ->
                val assignedUsers = membershipsByWarehouse[warehouse.id].orEmpty()
                    .filter { it.permissions["canView"]?.toString() == "true" }
                val hasGroup = visibleConversations.any {
                    it.conversationType == "WAREHOUSE_GROUP" && it.warehouseId == warehouse.id
                }
                if (assignedUsers.size > 1 && !hasGroup) {
                    repository.createWarehouseConversation(workspaceId, warehouse.id, "مجموعة " + warehouse.name)
                }
            }
            visibleConversations = repository.listConversations(workspaceId, ownerId)
        }
        _conversations.value = visibleConversations
    }

    private suspend fun ensureCloudDeviceRegistered(workspace: CloudWorkspace, force: Boolean = false) {
        val existing = preferences.cloudDeviceIdFlow.first()
        if (!force && existing.isNotBlank()) {
            startCloudPresenceService()
            heartbeatJob?.cancel()
            heartbeatJob = viewModelScope.launch {
                while (true) {
                    runCatching { repository.heartbeat(existing) }
                    runCatching { _devices.value = repository.listWorkspaceDevices(workspace.id) }
                    runCatching { refreshCloudSnapshotIfChanged(workspace.id) }
                    delay(20_000L)
                }
            }
            return
        }

        val fingerprint = preferences.getOrCreateInstallationId()
        val deviceName = preferences.deviceNameFlow.first()
            .ifBlank { "Android ${Build.MODEL.orEmpty().take(40)}" }
        val deviceId = repository.registerDevice(workspace.id, deviceName, fingerprint)
        preferences.saveCloudDeviceId(deviceId)
        startCloudPresenceService()
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                runCatching { repository.heartbeat(deviceId) }
                runCatching { _devices.value = repository.listWorkspaceDevices(workspace.id) }
                runCatching { refreshCloudSnapshotIfChanged(workspace.id) }
                delay(20_000L)
            }
        }
    }

    private fun startCloudPresenceService() {
        val context = getApplication<Application>()
        runCatching { ContextCompat.startForegroundService(context, Intent(context, CloudPresenceService::class.java)) }
    }

    private fun stopCloudPresenceService() {
        val context = getApplication<Application>()
        runCatching { context.stopService(Intent(context, CloudPresenceService::class.java)) }
    }

    private suspend fun openConversationInternal(conversationId: String) {
        val workspace = _activeWorkspace.value ?: error("لا توجد مساحة عمل محددة")
        val conversation = repository.listConversations(workspace.id, workspace.ownerUserId)
            .firstOrNull { it.id == conversationId }
            ?: error("هذه المحادثة غير متاحة لهذا الحساب")
        messagesRealtimeJob?.cancel()
        _selectedConversation.value = conversation
        _messages.value = emptyList()
        messagesRealtimeJob = viewModelScope.launch {
            val realtimeJob = launch {
                while (isActive) {
                    try {
                        repository.observeMessages(conversationId).collect {
                            runCatching { _messages.value = repository.listMessages(conversationId) }
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        delay(1_500L)
                    }
                    delay(1_500L)
                }
            }
            try {
                while (isActive) {
                    runCatching { _messages.value = repository.listMessages(conversationId) }
                    delay(2_500L)
                }
            } finally {
                realtimeJob.cancel()
            }
        }
        _conversations.value = repository.listConversations(workspace.id, workspace.ownerUserId)
    }

    private suspend fun runCloudAction(prefix: String, action: suspend () -> Unit) {
        // Ignore rapid repeated taps while a previous network request is still active.
        if (_isBusy.value) return
        _isBusy.value = true
        _error.value = null
        try {
            action()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _error.value = "$prefix: ${humanError(failure)}"
        } finally {
            _isBusy.value = false
        }
    }

    private fun humanError(error: Throwable): String {
        val raw = error.message ?: error.javaClass.simpleName
        return when {
            raw.contains("USER_NOT_REGISTERED", true) ->
                "هذا الحساب لم يسجل الدخول إلى التطبيق بعد. سيظهر تلقائياً بعد تسجيل الدخول بحساب Google."
            raw.contains("CANNOT_ADD_SELF", true) ->
                "لا يمكنك إضافة حسابك إلى مساحة العمل مرة أخرى."
            raw.contains("OWNER_REQUIRED", true) ->
                "هذه العملية متاحة للمستخدم الرئيسي المسؤول عن مساحة العمل فقط."
            raw.contains("WORKSPACE_ALREADY_EXISTS_INVITE_REQUIRED", true) ->
                "مساحة العمل الرئيسية موجودة بالفعل. اطلب من المسؤول إضافتك بدلاً من إنشاء مساحة جديدة."
            raw.contains("WAREHOUSE_ACCESS_DENIED", true) ->
                "لا يملك الحساب صلاحية الوصول إلى المخزن المحدد."
            raw.contains("DEVICE_REVOKED", true) ->
                "تم إلغاء تسجيل هذا الجهاز. راجع المسؤول لإعادة السماح به."
            raw.contains("NOT_WORKSPACE_MEMBER", true) ->
                "هذا الحساب ليس عضواً نشطاً في مساحة العمل."
            raw.contains("WAREHOUSE_LOCKED_BY_ASSIGNED_USER", true) ->
                "المخزن تحت إدارة المستخدم الفرعي المتصل حالياً."
            raw.contains("JWT", true) || raw.contains("TOKEN", true) || raw.contains("AUTH", true) ->
                "انتهت جلسة تسجيل الدخول أو تعذر التحقق منها. أعد ربط حساب Google ثم حاول مرة أخرى."
            raw.contains("TIMEOUT", true) || raw.contains("TIMED OUT", true) ->
                "انتهت مهلة الاتصال بالخادم. تحقق من الإنترنت ثم أعد المحاولة."
            raw.contains("UNKNOWNHOST", true) || raw.contains("UNABLE TO RESOLVE", true) ||
                raw.contains("NETWORK", true) || raw.contains("CONNECTION", true) ->
                "تعذر الاتصال بالإنترنت أو بالخادم. تحقق من الشبكة ثم حاول مرة أخرى."
            raw.contains("403", true) || raw.contains("FORBIDDEN", true) ->
                "رفض الخادم العملية بسبب عدم كفاية الصلاحيات."
            raw.contains("404", true) || raw.contains("NOT FOUND", true) ->
                "تعذر العثور على البيانات المطلوبة على الخادم."
            raw.contains("DUPLICATE", true) || raw.contains("UNIQUE", true) ->
                "هذه البيانات مسجلة مسبقاً."
            raw.contains("SERIAL", true) ->
                "تعذر حفظ أو تحميل الأرقام التسلسلية. تحقق من البيانات والصلاحيات."
            else -> "تعذر إتمام العملية. تحقق من اتصال الإنترنت والصلاحيات ثم حاول مرة أخرى."
        }
    }

    override fun onCleared() {
        heartbeatJob?.cancel()
        messagesRealtimeJob?.cancel()
        super.onCleared()
    }
}

class CloudCollaborationViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CloudCollaborationViewModel::class.java)) {
            return CloudCollaborationViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
