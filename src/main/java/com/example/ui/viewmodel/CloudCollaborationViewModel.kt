package com.example.ui.viewmodel

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.InventoryApp
import com.example.data.cloud.CloudCollaborationRepository
import com.example.data.cloud.CloudConversation
import com.example.data.cloud.CloudMessage
import com.example.data.cloud.CloudWarehouse
import com.example.data.cloud.CloudWarehouseMembership
import com.example.data.cloud.CloudWorkspace
import com.example.data.cloud.CloudWorkspaceMember
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class CloudCollaborationViewModel(application: Application) : AndroidViewModel(application) {
    private val preferences = (application as InventoryApp).preferencesManager
    private val repository = CloudCollaborationRepository()

    private val _userEmail = MutableStateFlow("")
    val userEmail: StateFlow<String> = _userEmail.asStateFlow()

    private val _isAuthenticated = MutableStateFlow(false)
    val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

    private val _workspaces = MutableStateFlow<List<CloudWorkspace>>(emptyList())
    val workspaces: StateFlow<List<CloudWorkspace>> = _workspaces.asStateFlow()

    private val _activeWorkspace = MutableStateFlow<CloudWorkspace?>(null)
    val activeWorkspace: StateFlow<CloudWorkspace?> = _activeWorkspace.asStateFlow()

    private val _members = MutableStateFlow<List<CloudWorkspaceMember>>(emptyList())
    val members: StateFlow<List<CloudWorkspaceMember>> = _members.asStateFlow()

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
        viewModelScope.launch {
            _isBusy.value = true
            _error.value = null
            try {
                val uid = repository.currentUserIdOrNull()
                _isAuthenticated.value = !uid.isNullOrBlank()
                _userEmail.value = repository.currentUserEmailOrNull().orEmpty()
                if (uid.isNullOrBlank()) {
                    _workspaces.value = emptyList()
                    _activeWorkspace.value = null
                    _members.value = emptyList()
                    _warehouses.value = emptyList()
                    _conversations.value = emptyList()
                    _notice.value = "اربط حساب Google من الإعدادات أولاً لتفعيل التعاون السحابي."
                    return@launch
                }

                val available = repository.listMyWorkspaces()
                _workspaces.value = available
                if (available.isEmpty()) {
                    _activeWorkspace.value = null
                    _members.value = emptyList()
                    _warehouses.value = emptyList()
                    _conversations.value = emptyList()
                    _notice.value = "لا توجد مساحة عمل مرتبطة بهذا الحساب. أنشئ مساحة جديدة أو اطلب من المسؤول إضافتك ببريد حساب Google."
                    return@launch
                }

                val savedId = preferences.cloudWorkspaceIdFlow.first()
                val selected = available.firstOrNull { it.id == savedId } ?: available.first()
                if (selected.id != savedId) preferences.saveCloudWorkspace(selected.id, selected.name)
                _activeWorkspace.value = selected
                loadWorkspaceContent(selected.id)
                ensureCloudDeviceRegistered(selected)
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
                refreshAfterWorkspaceChange(workspace)
                _notice.value = "تم إنشاء مساحة العمل السحابية بنجاح."
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
                _notice.value = "تمت إضافة الحساب إلى مساحة العمل. امنحه صلاحيات المخازن من تبويب الصلاحيات."
            }
        }
    }

    fun saveWarehousePermissions(
        warehouseId: String,
        userId: String,
        canView: Boolean,
        canInward: Boolean,
        canDisburse: Boolean,
        canTransfer: Boolean,
        canAdjust: Boolean,
        canReports: Boolean,
        canEdit: Boolean,
        canEditInventory: Boolean,
        canDeleteItem: Boolean,
        makeDedicated: Boolean
    ) {
        viewModelScope.launch {
            runCloudAction("تعذر حفظ صلاحيات المخزن") {
                val workspace = _activeWorkspace.value ?: error("لا توجد مساحة عمل محددة")
                val permissions = buildJsonObject {
                    put("canView", canView)
                    put("canInward", canInward)
                    put("canDisburse", canDisburse)
                    put("canTransfer", canTransfer)
                    put("canAdjust", canAdjust)
                    put("canReports", canReports)
                    put("canEdit", canEdit)
                    put("canEditInventory", canEditInventory)
                    put("canDeleteItem", canDeleteItem)
                }
                repository.setWarehousePermissions(
                    workspace.id, warehouseId, userId, permissions, makeDedicated
                )
                val updated = _warehouseMemberships.value.toMutableMap()
                updated[warehouseId] = repository.listWarehouseMemberships(warehouseId)
                _warehouseMemberships.value = updated
                _notice.value = "تم حفظ صلاحيات المستخدم لهذا المخزن على الخادم."
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

    fun sendMessage(body: String) {
        viewModelScope.launch {
            val conversation = _selectedConversation.value ?: return@launch
            runCloudAction("تعذر إرسال الرسالة") {
                val sent = repository.sendMessage(conversation.id, body)
                _messages.value = (_messages.value.filterNot { it.id == sent.id } + sent)
                    .sortedBy { it.createdAt }
            }
        }
    }

    fun dismissNotice() {
        _notice.value = null
        _error.value = null
    }

    private suspend fun refreshAfterWorkspaceChange(workspace: CloudWorkspace) {
        loadWorkspaceContent(workspace.id)
        ensureCloudDeviceRegistered(workspace, force = true)
        _workspaces.value = repository.listMyWorkspaces()
    }

    private suspend fun loadWorkspaceContent(workspaceId: String) {
        _members.value = repository.listWorkspaceMembers(workspaceId)
        _warehouses.value = repository.listWarehouses(workspaceId)
        val membershipMap = LinkedHashMap<String, List<CloudWarehouseMembership>>()
        _warehouses.value.forEach { warehouse ->
            membershipMap[warehouse.id] = repository.listWarehouseMemberships(warehouse.id)
        }
        _warehouseMemberships.value = membershipMap
        _conversations.value = repository.listConversations(workspaceId)
    }

    private suspend fun ensureCloudDeviceRegistered(workspace: CloudWorkspace, force: Boolean = false) {
        val existing = preferences.cloudDeviceIdFlow.first()
        if (!force && existing.isNotBlank()) {
            heartbeatJob?.cancel()
            heartbeatJob = viewModelScope.launch {
                while (true) {
                    runCatching { repository.heartbeat(existing) }
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
        heartbeatJob?.cancel()
        heartbeatJob = viewModelScope.launch {
            while (true) {
                runCatching { repository.heartbeat(deviceId) }
                delay(20_000L)
            }
        }
    }

    private suspend fun openConversationInternal(conversationId: String) {
        val workspace = _activeWorkspace.value ?: error("لا توجد مساحة عمل محددة")
        val conversation = repository.listConversations(workspace.id)
            .firstOrNull { it.id == conversationId }
            ?: error("هذه المحادثة غير متاحة لهذا الحساب")
        messagesRealtimeJob?.cancel()
        _selectedConversation.value = conversation
        _messages.value = repository.listMessages(conversationId)
        messagesRealtimeJob = viewModelScope.launch {
            repository.observeMessages(conversationId).collect {
                runCatching {
                    _messages.value = repository.listMessages(conversationId)
                }
            }
        }
        _conversations.value = repository.listConversations(workspace.id)
    }

    private suspend fun runCloudAction(prefix: String, action: suspend () -> Unit) {
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
                "هذا البريد لم يسجل الدخول إلى التطبيق بعد. اطلب من المستخدم ربط حساب Google أولاً."
            raw.contains("OWNER_REQUIRED", true) ->
                "هذه العملية متاحة للمستخدم الرئيسي المسؤول عن مساحة العمل فقط."
            raw.contains("WAREHOUSE_ACCESS_DENIED", true) ->
                "لا يملك الحساب صلاحية الوصول إلى المخزن المحدد."
            raw.contains("DEVICE_REVOKED", true) ->
                "تم إلغاء تسجيل هذا الجهاز. راجع المسؤول لإعادة السماح به."
            raw.contains("NOT_WORKSPACE_MEMBER", true) ->
                "هذا الحساب ليس عضواً نشطاً في مساحة العمل."
            raw.contains("WAREHOUSE_LOCKED_BY_ASSIGNED_USER", true) ->
                "المخزن تحت إدارة المستخدم الفرعي المتصل حالياً."
            else -> raw.take(240)
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
