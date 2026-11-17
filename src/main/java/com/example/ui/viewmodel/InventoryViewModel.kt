package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.InventoryApp
import com.example.data.cloud.CreatedDeviceCredentials
import com.example.data.cloud.DevicePermissions
import com.example.data.cloud.DeviceRole
import com.example.data.cloud.DiscoveredMainDevice
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.LocalServerStatus
import com.example.data.dao.VoucherWithItems
import com.example.data.model.BackupRecord
import com.example.data.model.Beneficiary
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemUnit
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.Supplier
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseParty
import com.example.data.repository.StockSnapshot
import com.example.data.sync.LocalNetworkSyncClient
import com.example.data.sync.LocalNetworkSyncServer
import com.example.data.sync.NsdDiscoveryHelper
import com.example.data.sync.SyncApiException
import com.example.ui.theme.AppTheme
import com.example.util.SecurityUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as InventoryApp
    private val repository = app.repository
    private val preferences = app.preferencesManager

    private val syncClient = LocalNetworkSyncClient()
    private val nsdHelper = NsdDiscoveryHelper(application)

    private val syncServer = LocalNetworkSyncServer(
        scope = viewModelScope,
        getWorkspaceCode = { preferences.ensureWorkspaceCode() },
        getOrgName = { preferences.orgNameFlow.first() },
        getSyncVersion = { preferences.syncRevisionFlow.first() },
        getLinkedDevices = { preferences.getLinkedSubDevicesSnapshot() },
        onSaveLinkedDevices = { devices -> preferences.saveLinkedSubDevices(devices) },
        onExportEntities = { repository.exportAllEntitiesForCloud() },
        onApplyRemoteEntities = { records ->
            repository.replaceDatabaseFromCloudEntitiesSafely(records)
            preferences.incrementSyncRevision()
        }
    )

    // Preferences & Theme
    val theme: StateFlow<AppTheme> = preferences.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.LIGHT)

    val userName: StateFlow<String> = preferences.userNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "عيسى عبدربه")

    val orgName: StateFlow<String> = preferences.orgNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "المؤسسة العامة للتوريد والمخازن")

    // Multi-Device States (Zero Google Auth / Zero API Key / Zero Fingerprint)
    val deviceRole: StateFlow<DeviceRole> = preferences.deviceRoleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeviceRole.MAIN)

    val isMainDevice: StateFlow<Boolean> = preferences.isMainDeviceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val devicePermissions: StateFlow<DevicePermissions> = preferences.devicePermissionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DevicePermissions())

    val workspaceCode: StateFlow<String> = preferences.workspaceCodeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val mainHostUrl: StateFlow<String> = preferences.mainHostUrlFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val subSessionToken: StateFlow<String> = preferences.subSessionTokenFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val deviceName: StateFlow<String> = preferences.deviceNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val linkedSubDevices: StateFlow<List<LinkedSubDevice>> = preferences.linkedSubDevicesFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val lastSyncAt: StateFlow<Long> = preferences.lastSyncAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val pairingLockedUntil: StateFlow<Long> = preferences.pairingLockedUntilFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val isSubConnected: StateFlow<Boolean> = combine(
        deviceRole,
        subSessionToken,
        devicePermissions
    ) { role, token, perms ->
        role == DeviceRole.SUB && token.isNotBlank() && !perms.isKicked
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val serverStatus: StateFlow<LocalServerStatus> = syncServer.status

    private val _discoveredMainDevices = MutableStateFlow<List<DiscoveredMainDevice>>(emptyList())
    val discoveredMainDevices: StateFlow<List<DiscoveredMainDevice>> = _discoveredMainDevices.asStateFlow()

    private val _createdDeviceCredentials = MutableStateFlow<CreatedDeviceCredentials?>(null)
    val createdDeviceCredentials: StateFlow<CreatedDeviceCredentials?> = _createdDeviceCredentials.asStateFlow()

    private val _isPairingInProgress = MutableStateFlow(false)
    val isPairingInProgress: StateFlow<Boolean> = _isPairingInProgress.asStateFlow()

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

    private val _isSyncingNow = MutableStateFlow(false)
    val isSyncingNow: StateFlow<Boolean> = _isSyncingNow.asStateFlow()

    private val _lastSyncError = MutableStateFlow<String?>(null)
    val lastSyncError: StateFlow<String?> = _lastSyncError.asStateFlow()

    private val _kickedAlertMessage = MutableStateFlow<String?>(null)
    val kickedAlertMessage: StateFlow<String?> = _kickedAlertMessage.asStateFlow()

    private val _diagnosticsReport = MutableStateFlow<DiagnosticsReport?>(null)
    val diagnosticsReport: StateFlow<DiagnosticsReport?> = _diagnosticsReport.asStateFlow()

    private val _isRunningDiagnostics = MutableStateFlow(false)
    val isRunningDiagnostics: StateFlow<Boolean> = _isRunningDiagnostics.asStateFlow()

    // Stock & Inventory Data
    val stockSnapshot: StateFlow<StockSnapshot?> = repository.getCombinedStockStream()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val warehouses: StateFlow<List<Warehouse>> = repository.allWarehouses
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val items: StateFlow<List<Item>> = repository.allItems
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val beneficiaries: StateFlow<List<Beneficiary>> = repository.allBeneficiaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vouchers: StateFlow<List<VoucherWithItems>> = repository.allVouchers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backups: StateFlow<List<BackupRecord>> = repository.allBackups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val suppliers: StateFlow<List<Supplier>> = repository.allSuppliers
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val parties: StateFlow<List<WarehouseParty>> = repository.allParties
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val categories: StateFlow<List<ItemCategory>> = repository.allCategories
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val units: StateFlow<List<ItemUnit>> = repository.allUnits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Feedback Messages
    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private var subSyncJob: Job? = null
    private var nsdDiscoveryJob: Job? = null

    init {
        startMultiDeviceCoordinator()
    }

    private fun startMultiDeviceCoordinator() {
        viewModelScope.launch {
            combine(
                preferences.deviceRoleFlow,
                preferences.mainHostUrlFlow,
                preferences.subSessionTokenFlow,
                preferences.devicePermissionsFlow
            ) { role, host, token, perms ->
                CoordinatorState(role, host, token, perms)
            }.collect { state ->
                if (state.role == DeviceRole.MAIN) {
                    subSyncJob?.cancel()
                    subSyncJob = null
                    nsdDiscoveryJob?.cancel()
                    nsdDiscoveryJob = null
                    _discoveredMainDevices.value = emptyList()

                    val code = preferences.ensureWorkspaceCode()
                    val port = syncServer.start(LocalNetworkSyncServer.DEFAULT_PORT)
                    if (port > 0) {
                        nsdHelper.registerMainService(port, code)
                    }
                } else {
                    syncServer.stop()
                    nsdHelper.unregisterMainService()

                    if (state.subToken.isBlank() || state.permissions.isKicked) {
                        subSyncJob?.cancel()
                        subSyncJob = null
                        if (nsdDiscoveryJob?.isActive != true) {
                            nsdDiscoveryJob = launch {
                                nsdHelper.observeDiscoveredMainDevices().collect { found ->
                                    _discoveredMainDevices.value = found
                                }
                            }
                        }
                    } else {
                        nsdDiscoveryJob?.cancel()
                        nsdDiscoveryJob = null
                        if (subSyncJob?.isActive != true) {
                            subSyncJob = launch {
                                while (isActive) {
                                    pullFromMainOnce(silent = true)
                                    delay(2500L)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        subSyncJob?.cancel()
        nsdDiscoveryJob?.cancel()
        nsdHelper.unregisterMainService()
        syncServer.stop()
    }

    // Granular Multi-Device Permissions (No Google Auth / No Fingerprint)
    fun checkPermission(action: DeviceAction, onDenied: () -> Unit, onAllowed: () -> Unit) {
        val perms = devicePermissions.value
        if (perms.isMainDevice) {
            onAllowed()
            return
        }
        if (perms.isKicked) {
            _kickedAlertMessage.value = "تم إيقاف وطرد هذا الجهاز من الجهاز الرئيسي."
            onDenied()
            return
        }
        val allowed = when (action) {
            DeviceAction.ADD_OR_MANAGE_CATALOG -> perms.canAddItems
            DeviceAction.INWARD -> perms.canInward
            DeviceAction.DISBURSE_OR_TRANSFER -> perms.canDisburse
            DeviceAction.EDIT_OR_DELETE -> perms.canEditDelete
        }
        if (allowed) onAllowed() else onDenied()
    }

    fun checkCanEdit(onSubDeviceWarning: () -> Unit, onAllowed: () -> Unit) {
        checkPermission(DeviceAction.EDIT_OR_DELETE, onSubDeviceWarning, onAllowed)
    }

    // Multi-Device Setup & Controls
    fun switchToMainDeviceMode() {
        viewModelScope.launch {
            _pairingError.value = null
            _lastSyncError.value = null
            preferences.clearSubPairingAndSwitchToMain()
            _userMessage.emit("تم التبديل إلى وضع الجهاز الرئيسي وتشغيل خادم الربط المحلي")
        }
    }

    fun switchToSubDeviceDraftMode() {
        viewModelScope.launch {
            _pairingError.value = null
            _lastSyncError.value = null
            preferences.setDeviceRole(DeviceRole.SUB)
        }
    }

    fun refreshMainServerInfo() {
        viewModelScope.launch {
            val code = preferences.ensureWorkspaceCode()
            val port = syncServer.start(LocalNetworkSyncServer.DEFAULT_PORT)
            syncServer.refreshIpAddresses()
            if (port > 0) {
                nsdHelper.registerMainService(port, code)
            }
        }
    }

    fun regenerateWorkspaceCode() {
        viewModelScope.launch {
            val newCode = preferences.regenerateWorkspaceCode()
            syncServer.refreshIpAddresses()
            val port = syncServer.actualPort
            if (port > 0) {
                nsdHelper.registerMainService(port, newCode)
            }
            _userMessage.emit("تم إنشاء رمز قاعدة بيانات جديد: $newCode")
        }
    }

    fun createSubDeviceSlot(
        deviceName: String,
        canAddItems: Boolean,
        canInward: Boolean,
        canDisburse: Boolean,
        canEditDelete: Boolean
    ) {
        viewModelScope.launch {
            val cleanName = deviceName.trim().ifBlank { "جهاز فرعي #${linkedSubDevices.value.size + 1}" }
            val code = preferences.ensureWorkspaceCode()
            val port = syncServer.start(LocalNetworkSyncServer.DEFAULT_PORT)
            if (port > 0) {
                nsdHelper.registerMainService(port, code)
            }
            val ips = LocalNetworkSyncServer.getLocalLanIpAddresses()
            val hostDisplay = (ips.firstOrNull() ?: "192.168.43.1") + ":" + (if (port > 0) port else LocalNetworkSyncServer.DEFAULT_PORT)

            val plainPassword = SecurityUtils.generateUniqueDevicePassword(10)
            val salt = SecurityUtils.generateSalt()
            val passwordHash = SecurityUtils.hashPassword(plainPassword, salt)
            val sessionToken = UUID.randomUUID().toString()
            val now = System.currentTimeMillis()

            val newSlot = LinkedSubDevice(
                id = UUID.randomUUID().toString(),
                deviceName = cleanName,
                passwordSalt = salt,
                passwordHash = passwordHash,
                sessionToken = sessionToken,
                boundInstallationId = "",
                isClaimed = false,
                isKicked = false,
                canAddItems = canAddItems,
                canInward = canInward,
                canDisburse = canDisburse,
                canEditDelete = canEditDelete,
                createdAt = now,
                updatedAt = now,
                lastSeenAt = 0L
            )

            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            current.add(0, newSlot)
            preferences.saveLinkedSubDevices(current)

            _createdDeviceCredentials.value = CreatedDeviceCredentials(
                deviceSlotId = newSlot.id,
                deviceName = newSlot.deviceName,
                workspaceCode = code,
                hostAddress = hostDisplay,
                plainPassword = plainPassword
            )
            _userMessage.emit("تم إنشاء بيانات الربط للجهاز الفرعي ($cleanName)")
        }
    }

    fun dismissCreatedDeviceCredentials() {
        _createdDeviceCredentials.value = null
    }

    fun updateSubDevicePermissions(
        slotId: String,
        canAddItems: Boolean,
        canInward: Boolean,
        canDisburse: Boolean,
        canEditDelete: Boolean
    ) {
        viewModelScope.launch {
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val idx = current.indexOfFirst { it.id == slotId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(
                    canAddItems = canAddItems,
                    canInward = canInward,
                    canDisburse = canDisburse,
                    canEditDelete = canEditDelete,
                    updatedAt = System.currentTimeMillis()
                )
                preferences.saveLinkedSubDevices(current)
                preferences.incrementSyncRevision()
                _userMessage.emit("تم تحديث صلاحيات ${current[idx].deviceName}")
            }
        }
    }

    fun kickSubDevice(slotId: String) {
        viewModelScope.launch {
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val idx = current.indexOfFirst { it.id == slotId }
            if (idx >= 0) {
                val targetName = current[idx].deviceName
                current[idx] = current[idx].copy(
                    isKicked = true,
                    sessionToken = UUID.randomUUID().toString(), // invalidate existing token
                    updatedAt = System.currentTimeMillis()
                )
                preferences.saveLinkedSubDevices(current)
                preferences.incrementSyncRevision()
                _userMessage.emit("تم طرد ($targetName) وإلغاء ارتباطه فوراً")
            }
        }
    }

    fun removeSubDeviceSlot(slotId: String) {
        viewModelScope.launch {
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val removed = current.firstOrNull { it.id == slotId }
            if (removed != null) {
                current.removeAll { it.id == slotId }
                preferences.saveLinkedSubDevices(current)
                preferences.incrementSyncRevision()
                _userMessage.emit("تم حذف سجل الجهاز (${removed.deviceName})")
            }
        }
    }

    fun pairWithMainDevice(
        hostAddress: String,
        workspaceCodeInput: String,
        passwordInput: String
    ) {
        viewModelScope.launch {
            val lockedUntil = preferences.pairingLockedUntilFlow.first()
            val now = System.currentTimeMillis()
            if (lockedUntil > now) {
                val secs = ((lockedUntil - now) / 1000L).coerceAtLeast(1L)
                _pairingError.value = "تم قفل المحاولات مؤقتاً لمدة $secs ثانية بسبب تكرار المحاولات الخاطئة"
                return@launch
            }

            val cleanHost = hostAddress.trim()
            val cleanCode = workspaceCodeInput.trim().uppercase()
            val cleanPwd = passwordInput.trim()

            if (cleanHost.isBlank() || cleanCode.isBlank() || cleanPwd.isBlank()) {
                _pairingError.value = "يرجى إدخال عنوان الجهاز الرئيسي ورمز القاعدة وكلمة المرور المنفردة"
                return@launch
            }

            _isPairingInProgress.value = true
            _pairingError.value = null
            try {
                val installId = preferences.getOrCreateInstallationId()
                val normalizedUrl = syncClient.normalizeBaseUrl(cleanHost)
                val result = syncClient.pairWithMainDevice(
                    rawHost = normalizedUrl,
                    workspaceCode = cleanCode,
                    password = cleanPwd,
                    installationId = installId
                )

                result.onSuccess { paired ->
                    preferences.resetPairingFailures()
                    if (paired.entities.isNotEmpty()) {
                        repository.replaceDatabaseFromCloudEntitiesSafely(paired.entities)
                    } else {
                        repository.clearAllSyncedData()
                    }
                    preferences.savePairedSubDevice(
                        hostUrl = normalizedUrl,
                        workspaceCode = paired.workspaceCode,
                        deviceSlotId = paired.deviceSlotId,
                        deviceName = paired.deviceName,
                        sessionToken = paired.sessionToken,
                        permissions = paired.permissions,
                        appliedRevision = paired.syncVersion
                    )
                    _lastSyncError.value = null
                    _userMessage.emit("تم ربط الجهاز الفرعي (${paired.deviceName}) ومزامنة البيانات بنجاح")
                }.onFailure { err ->
                    val apiErr = err as? SyncApiException
                    if (apiErr?.errorCode == "INVALID_PASSWORD" || apiErr?.errorCode == "INVALID_WORKSPACE") {
                        val (attempts, lockTime) = preferences.recordPairingFailure()
                        _pairingError.value = if (lockTime > 0L) {
                            "تم تجاوز 5 محاولات خاطئة. تم إيقاف المحاولات لمدة 60 ثانية."
                        } else {
                            "${apiErr.message} (محاولة $attempts من 5)"
                        }
                    } else {
                        _pairingError.value = apiErr?.message
                            ?: "تعذر الاتصال بالجهاز الرئيسي. تأكد أن الجهازين على نفس شبكة Wi-Fi أو نقطة الاتصال وأن العنوان صحيح."
                    }
                }
            } finally {
                _isPairingInProgress.value = false
            }
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            if (isMainDevice.value) {
                preferences.incrementSyncRevision()
                refreshMainServerInfo()
                _userMessage.emit("تم تحديث حالة خادم الربط المحلي وجاهزية المزامنة")
            } else {
                pullFromMainOnce(silent = false)
            }
        }
    }

    private suspend fun pullFromMainOnce(silent: Boolean) {
        val host = preferences.mainHostUrlFlow.first()
        val token = preferences.subSessionTokenFlow.first()
        if (host.isBlank() || token.isBlank()) return

        if (!silent) _isSyncingNow.value = true
        try {
            val result = syncClient.fetchSyncSnapshot(host, token)
            result.onSuccess { snapshot ->
                _lastSyncError.value = null
                if (snapshot.isKicked) {
                    handleSubDeviceKicked("تم طرد هذا الجهاز وإلغاء ارتباطه من الجهاز الرئيسي.")
                    return@onSuccess
                }

                preferences.updateSubDevicePermissions(snapshot.permissions)
                val lastApplied = preferences.lastAppliedRevisionFlow.first()
                if (snapshot.syncVersion != lastApplied) {
                    if (snapshot.entities.isNotEmpty()) {
                        repository.replaceDatabaseFromCloudEntitiesSafely(snapshot.entities)
                    } else {
                        repository.clearAllSyncedData()
                    }
                    preferences.updateLastSync(snapshot.syncVersion)
                } else {
                    preferences.updateLastSync(lastApplied)
                }

                if (!silent) {
                    _userMessage.emit("تمت المزامنة مع الجهاز الرئيسي بنجاح")
                }
            }.onFailure { err ->
                val apiErr = err as? SyncApiException
                if (apiErr?.errorCode == "KICKED") {
                    handleSubDeviceKicked(apiErr.message ?: "تم طرد هذا الجهاز وإلغاء ارتباطه من الجهاز الرئيسي.")
                } else {
                    _lastSyncError.value = "بانتظار توفر الجهاز الرئيسي على الشبكة المحلية"
                    if (!silent) {
                        _userMessage.emit("تعذر الوصول للجهاز الرئيسي حالياً. تأكد من الاتصال بنفس الشبكة.")
                    }
                }
            }
        } finally {
            if (!silent) _isSyncingNow.value = false
        }
    }

    private suspend fun handleSubDeviceKicked(reason: String) {
        subSyncJob?.cancel()
        subSyncJob = null
        repository.clearAllSyncedData()
        preferences.markSubDeviceKicked()
        _kickedAlertMessage.value = "$reason\nتم مسح البيانات المتزامنة من هذا الجهاز."
    }

    fun unlinkSubDevice() {
        viewModelScope.launch {
            subSyncJob?.cancel()
            subSyncJob = null
            preferences.clearSubPairingAndSwitchToMain()
            _lastSyncError.value = null
            _userMessage.emit("تم فك الارتباط والعودة إلى الوضع الرئيسي")
        }
    }

    fun dismissKickedAlert() {
        viewModelScope.launch {
            _kickedAlertMessage.value = null
            preferences.clearSubPairingAndSwitchToMain()
        }
    }

    private fun propagateDataChange(action: DeviceAction? = null) {
        viewModelScope.launch {
            if (isMainDevice.value) {
                preferences.incrementSyncRevision()
            } else {
                val host = preferences.mainHostUrlFlow.first()
                val token = preferences.subSessionTokenFlow.first()
                if (host.isBlank() || token.isBlank()) return@launch
                val entities = repository.exportAllEntitiesForCloud()
                val res = syncClient.pushEntitiesToMain(
                    rawHost = host,
                    sessionToken = token,
                    actionType = action?.name ?: "GENERAL",
                    entities = entities
                )
                res.onSuccess { newRev ->
                    preferences.updateLastSync(newRev)
                    _lastSyncError.value = null
                }.onFailure { err ->
                    val apiErr = err as? SyncApiException
                    when (apiErr?.errorCode) {
                        "KICKED" -> handleSubDeviceKicked(
                            apiErr.message ?: "تم طرد هذا الجهاز من الجهاز الرئيسي."
                        )
                        "PERMISSION_DENIED" -> {
                            _userMessage.emit(apiErr.message ?: "لا تملك صلاحية تنفيذ هذا التعديل")
                            pullFromMainOnce(silent = true)
                        }
                        else -> {
                            _lastSyncError.value = "تم الحفظ محلياً وبانتظار عودة الاتصال بالجهاز الرئيسي"
                        }
                    }
                }
            }
        }
    }

    // 1. Warehouse Operations
    fun saveWarehouse(warehouse: Warehouse, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (warehouse.id == 0L) {
                repository.insertWarehouse(warehouse)
                _userMessage.emit("تمت إضافة المخزن بنجاح")
                propagateDataChange(DeviceAction.ADD_OR_MANAGE_CATALOG)
            } else {
                repository.updateWarehouse(warehouse)
                _userMessage.emit("تم تحديث بيانات المخزن بنجاح")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            }
            onDone()
        }
    }

    suspend fun checkWarehouseHasStock(warehouseId: Long): Boolean {
        return repository.warehouseHasStock(warehouseId)
    }

    fun deleteWarehouse(warehouse: Warehouse) {
        viewModelScope.launch {
            repository.deleteWarehouse(warehouse)
            _userMessage.emit("تم حذف المخزن نهائياً")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
        }
    }

    fun addItemDirectlyToWarehouse(
        warehouseId: Long,
        itemId: Long,
        quantity: Double,
        minStockAlert: Double,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            repository.addItemDirectlyToWarehouse(warehouseId, itemId, quantity, minStockAlert)
            _userMessage.emit("تمت إضافة الصنف داخل المخزن بنجاح")
            propagateDataChange(DeviceAction.ADD_OR_MANAGE_CATALOG)
            onDone()
        }
    }

    fun removeItemFromWarehouse(
        warehouseId: Long,
        itemId: Long,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.removeItemFromWarehouse(warehouseId, itemId)
            _userMessage.emit("تم حذف الصنف نهائياً من هذا المخزن")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            onDone()
        }
    }

    fun updateWarehouseItemMinStock(
        warehouseId: Long,
        itemId: Long,
        customMinStock: Double,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.updateWarehouseItemMinStock(warehouseId, itemId, customMinStock)
            _userMessage.emit("تم تحديث حد إعادة الطلب الخاص بالمخزن")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            onDone()
        }
    }

    // 2. Item Operations
    fun saveItem(item: Item, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (item.id == 0L) {
                repository.insertItem(item)
                _userMessage.emit("تمت إضافة الصنف بنجاح")
                propagateDataChange(DeviceAction.ADD_OR_MANAGE_CATALOG)
            } else {
                repository.updateItem(item)
                _userMessage.emit("تم تحديث بيانات الصنف بنجاح")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            }
            onDone()
        }
    }

    suspend fun checkItemHasTransactions(itemId: Long): Boolean {
        return repository.itemHasTransactions(itemId)
    }

    fun deleteItem(item: Item) {
        viewModelScope.launch {
            repository.deleteItem(item)
            _userMessage.emit("تم حذف الصنف نهائياً")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
        }
    }

    // 3. Stock Adjustment inside Warehouse
    fun adjustWarehouseStock(
        warehouseId: Long,
        itemId: Long,
        newQuantity: Double,
        reasonNote: String,
        onDone: () -> Unit
    ) {
        viewModelScope.launch {
            repository.adjustWarehouseStock(warehouseId, itemId, newQuantity, reasonNote)
            _userMessage.emit("تم تعديل كمية المخزون وتوثيق السبب بنجاح")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            onDone()
        }
    }

    // 4. Beneficiary Operations
    fun saveBeneficiary(beneficiary: Beneficiary, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (beneficiary.id == 0L) {
                val newId = repository.insertBeneficiary(beneficiary)
                _userMessage.emit("تمت إضافة الجهة المستفيدة بنجاح")
                propagateDataChange(DeviceAction.ADD_OR_MANAGE_CATALOG)
                newId
            } else {
                repository.updateBeneficiary(beneficiary)
                _userMessage.emit("تم تحديث بيانات الجهة المستفيدة")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
                beneficiary.id
            }
            onDone(id)
        }
    }

    suspend fun checkBeneficiaryHasTransactions(name: String, id: Long): Boolean {
        return repository.checkBeneficiaryHasTransactions(name, id)
    }

    fun deleteBeneficiary(beneficiary: Beneficiary, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = repository.deleteBeneficiarySafely(beneficiary)
            if (success) {
                _userMessage.emit("تم حذف الجهة المستفيدة بنجاح")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            } else {
                _userMessage.emit("لا يمكن حذف الجهة المستفيدة لوجود حركات صرف سابقة مرتبطة بها!")
            }
            onResult(success)
        }
    }

    // 5. Supplier Operations
    fun saveSupplier(supplier: Supplier, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (supplier.id == 0L) {
                val newId = repository.insertSupplier(supplier)
                _userMessage.emit("تمت إضافة المورد بنجاح")
                propagateDataChange(DeviceAction.ADD_OR_MANAGE_CATALOG)
                newId
            } else {
                repository.updateSupplier(supplier)
                _userMessage.emit("تم تحديث بيانات المورد")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
                supplier.id
            }
            onDone(id)
        }
    }

    suspend fun checkSupplierHasTransactions(supplierName: String): Boolean {
        return repository.checkSupplierHasTransactions(supplierName)
    }

    fun deleteSupplier(supplier: Supplier, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = repository.deleteSupplierSafely(supplier)
            if (success) {
                _userMessage.emit("تم حذف المورد بنجاح")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            } else {
                _userMessage.emit("لا يمكن حذف المورد لوجود عمليات سابقة مرتبطة به!")
            }
            onResult(success)
        }
    }

    fun updateSupplierName(oldName: String, newName: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val supp = suppliers.value.find { it.name == oldName }
            if (supp != null) {
                repository.updateSupplier(supp.copy(name = newName.trim()))
                _userMessage.emit("تم تعديل اسم المورد بنجاح")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            }
            onDone()
        }
    }

    // 6. WarehouseParty Operations (Receivers / Disbursers)
    fun saveParty(party: WarehouseParty, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (party.id == 0L) {
                val newId = repository.insertParty(party)
                _userMessage.emit("تمت إضافة الاسم بنجاح")
                propagateDataChange(DeviceAction.ADD_OR_MANAGE_CATALOG)
                newId
            } else {
                repository.updateParty(party)
                _userMessage.emit("تم تحديث البيانات")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
                party.id
            }
            onDone(id)
        }
    }

    suspend fun checkPartyHasTransactions(name: String): Boolean {
        return repository.checkPartyHasTransactions(name)
    }

    fun deleteParty(party: WarehouseParty, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = repository.deletePartySafely(party)
            if (success) {
                _userMessage.emit("تم الحذف بنجاح")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            } else {
                _userMessage.emit("لا يمكن الحذف لوجود عمليات سابقة مرتبطة بالاسم!")
            }
            onResult(success)
        }
    }

    fun updatePartyName(oldName: String, newName: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val pty = parties.value.find { it.name == oldName }
            if (pty != null) {
                repository.updateParty(pty.copy(name = newName.trim()))
                _userMessage.emit("تم تعديل الاسم بنجاح")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            }
            onDone()
        }
    }

    // 7. Categories & Units Operations
    fun saveCategory(category: ItemCategory, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (category.id == 0L) {
                repository.insertCategory(category)
                propagateDataChange(DeviceAction.ADD_OR_MANAGE_CATALOG)
            } else {
                repository.updateCategory(category)
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            }
            _userMessage.emit("تم حفظ التصنيف")
            onDone()
        }
    }

    fun deleteCategory(category: ItemCategory) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            _userMessage.emit("تم حذف التصنيف")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
        }
    }

    fun saveUnit(unit: ItemUnit, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (unit.id == 0L) {
                repository.insertUnit(unit)
            } else {
                repository.updateUnit(unit)
                unit.id
            }
            _userMessage.emit("تم حفظ وحدة القياس بنجاح")
            propagateDataChange(if (unit.id == 0L) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_OR_DELETE)
            onDone(id)
        }
    }

    fun saveUnit(unitName: String, onDone: (Long) -> Unit = {}) {
        saveUnit(ItemUnit(name = unitName), onDone)
    }

    fun updateUnitName(unitId: Long, newName: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.updateUnitName(unitId, newName)
            _userMessage.emit("تم تعديل اسم الوحدة بنجاح")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            onDone()
        }
    }

    suspend fun checkUnitHasTransactions(unitName: String): Boolean {
        return repository.checkUnitHasTransactions(unitName)
    }

    fun deleteUnit(unit: ItemUnit, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val success = repository.deleteUnitSafely(unit)
            if (success) {
                _userMessage.emit("تم حذف الوحدة بنجاح")
                propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            } else {
                _userMessage.emit("لا يمكن حذف الوحدة لأنها مرتبطة بأصناف أو حركات توريد وصرف سابقة!")
            }
            onResult(success)
        }
    }

    suspend fun validateInwardVoucherUpdate(
        voucherId: Long,
        destWarehouseId: Long,
        items: List<VoucherItem>
    ): Pair<Boolean, String?> {
        return repository.validateInwardVoucherUpdate(voucherId, destWarehouseId, items)
    }

    // 8. Sequential Voucher Number Generator
    suspend fun getNextVoucherNumber(type: VoucherType): String {
        return repository.generateNextVoucherNumber(type)
    }

    // 9. Voucher Operations
    fun saveVoucher(
        voucher: Voucher,
        items: List<VoucherItem>,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            repository.saveVoucherWithItems(voucher, items)
            val actionName = when (voucher.voucherType) {
                VoucherType.INWARD -> "التوريد"
                VoucherType.TRANSFER -> "التحويل المخزني"
                VoucherType.DISBURSEMENT -> "أمر الصرف"
            }
            val action = when (voucher.voucherType) {
                VoucherType.INWARD -> DeviceAction.INWARD
                VoucherType.TRANSFER, VoucherType.DISBURSEMENT -> DeviceAction.DISBURSE_OR_TRANSFER
            }
            _userMessage.emit("تم حفظ $actionName بنجاح")
            propagateDataChange(if (voucher.id != 0L) DeviceAction.EDIT_OR_DELETE else action)
            onDone()
        }
    }

    fun deleteVoucher(voucherId: Long) {
        viewModelScope.launch {
            repository.deleteVoucher(voucherId)
            _userMessage.emit("تم حذف السند بنجاح")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
        }
    }

    fun removeAttachment(voucherId: Long) {
        viewModelScope.launch {
            repository.removeVoucherAttachment(voucherId)
            _userMessage.emit("تم حذف المرفق بنجاح")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
        }
    }

    // 10. Local Backups, External Reinstall Recovery & Database/Network Diagnostics
    fun createBackup(name: String) {
        viewModelScope.launch {
            try {
                repository.createBackup(name, isCloud = false, accountEmail = "")
                _userMessage.emit("تم إنشاء وحفظ النسخة الاحتياطية على الهاتف بنجاح")
            } catch (e: Exception) {
                _userMessage.emit("تعذر إنشاء النسخة: ${e.localizedMessage}")
            }
        }
    }

    suspend fun exportBackupJsonForFile(): String {
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val record = repository.createBackup("تصدير_$dateStr", isCloud = false, accountEmail = "")
        return record.backupJson
    }

    fun importBackupJsonFromFile(rawJson: String) {
        viewModelScope.launch {
            try {
                val ok = repository.restoreBackupReplacingExisting(rawJson)
                if (ok) {
                    propagateDataChange()
                    _userMessage.emit("تم استرداد كافة البيانات من الملف بنجاح")
                } else {
                    _userMessage.emit("الملف المختار لا يحتوي على نسخة بيانات صالحة لمخزني الذكي")
                }
            } catch (e: Exception) {
                _userMessage.emit("تعذر استرداد الملف: ${e.localizedMessage}")
            }
        }
    }

    fun restoreBackup(record: BackupRecord) {
        viewModelScope.launch {
            try {
                val ok = repository.restoreBackupReplacingExisting(record.backupJson)
                if (ok) {
                    propagateDataChange()
                    _userMessage.emit("تمت استعادة البيانات بنجاح من النسخة")
                } else {
                    _userMessage.emit("فشلت استعادة البيانات من النسخة")
                }
            } catch (e: Exception) {
                _userMessage.emit("خطأ أثناء الاستعادة: ${e.localizedMessage}")
            }
        }
    }

    fun deleteBackup(record: BackupRecord) {
        viewModelScope.launch {
            repository.deleteBackup(record)
            _userMessage.emit("تم حذف النسخة الاحتياطية")
        }
    }

    fun runLocalDatabaseCheck() {
        viewModelScope.launch {
            _isRunningDiagnostics.value = true
            try {
                val checks = mutableListOf<DiagnosticCheckItem>()
                val localResult = runCatching { repository.getLocalDiagnosticsCounts() }
                localResult.onSuccess { counts ->
                    checks.add(
                        DiagnosticCheckItem(
                            title = "فحص قاعدة البيانات المحلية (Room)",
                            passed = true,
                            detail = "سليمة • مخازن: ${counts.warehouses} | أصناف: ${counts.items} | سندات: ${counts.vouchers} | وحدات متاحة: ${counts.activeSerials} (منصرف: ${counts.disbursedSerials})",
                            status = DiagnosticStatus.PASS
                        )
                    )
                }.onFailure { err ->
                    checks.add(
                        DiagnosticCheckItem(
                            title = "فحص قاعدة البيانات المحلية (Room)",
                            passed = false,
                            detail = "خطأ في الفحص المحلي: ${err.localizedMessage}",
                            status = DiagnosticStatus.FAIL
                        )
                    )
                }

                if (isMainDevice.value) {
                    val srv = serverStatus.value
                    val activeDevices = linkedSubDevices.value.count { !it.isKicked }
                    checks.add(
                        DiagnosticCheckItem(
                            title = "خادم الربط المباشر بين الأجهزة",
                            passed = srv.isRunning,
                            detail = if (srv.isRunning) {
                                "نشط على العنوان (${srv.primaryAddress}) • رمز القاعدة: ${srv.workspaceCode} • أجهزة مسجلة: $activeDevices"
                            } else {
                                "متوقف مؤقتاً — اضغط تحديث خادم الربط أو فعّل شبكة Wi-Fi / نقطة الاتصال"
                            },
                            status = if (srv.isRunning) DiagnosticStatus.PASS else DiagnosticStatus.WARNING
                        )
                    )
                } else {
                    val connected = isSubConnected.value
                    val host = mainHostUrl.value
                    val pingOk = if (host.isNotBlank()) syncClient.pingMainDevice(host).isSuccess else false
                    checks.add(
                        DiagnosticCheckItem(
                            title = "اتصال الجهاز الفرعي بالجهاز الرئيسي",
                            passed = connected && pingOk,
                            detail = when {
                                connected && pingOk -> "متصل مباشرة بالجهاز الرئيسي ($host) والمزامنة فعالة"
                                connected -> "مرتبط بالجهاز ($host) لكنه غير متاح حالياً على الشبكة المحلية"
                                else -> "غير مرتبط بعد — أدخل بيانات الربط من قسم ربط الأجهزة أعلاه"
                            },
                            status = if (connected && pingOk) DiagnosticStatus.PASS else DiagnosticStatus.WARNING
                        )
                    )
                }

                _diagnosticsReport.value = DiagnosticsReport(
                    checkedAtMillis = System.currentTimeMillis(),
                    checks = checks
                )
            } finally {
                _isRunningDiagnostics.value = false
            }
        }
    }

    // 11. Settings Actions
    fun setTheme(theme: AppTheme) {
        viewModelScope.launch { preferences.setTheme(theme) }
    }

    fun setUserName(name: String) {
        viewModelScope.launch { preferences.setUserName(name) }
    }

    fun setOrgName(name: String) {
        viewModelScope.launch { preferences.setOrgName(name) }
    }

    // Item Unit Serial Tracking
    fun getSerialsForWarehouseItem(warehouseId: Long, itemId: Long): kotlinx.coroutines.flow.Flow<List<ItemUnitSerialRecord>> {
        return repository.getSerialsForWarehouseItem(warehouseId, itemId)
    }

    suspend fun getSerialsList(warehouseId: Long, itemId: Long): List<ItemUnitSerialRecord> {
        return repository.getSerialsList(warehouseId, itemId)
    }

    suspend fun getNextStartUnitIndex(warehouseId: Long, itemId: Long): Int {
        return repository.getNextStartUnitIndex(warehouseId, itemId)
    }

    suspend fun saveSerialsAndGet(serials: List<ItemUnitSerialRecord>): List<ItemUnitSerialRecord> {
        val saved = repository.saveSerialsAndReturn(serials)
        propagateDataChange(DeviceAction.EDIT_OR_DELETE)
        return saved
    }

    fun saveSerials(serials: List<ItemUnitSerialRecord>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.saveSerialsAndReturn(serials)
            _userMessage.emit("تم حفظ الأرقام المميزة والحالات بنجاح")
            propagateDataChange(DeviceAction.EDIT_OR_DELETE)
            onDone()
        }
    }

    suspend fun getAllSerialsForWarehouse(warehouseId: Long): List<ItemUnitSerialRecord> {
        return repository.getAllSerialsForWarehouse(warehouseId)
    }

    private data class CoordinatorState(
        val role: DeviceRole,
        val mainHostUrl: String,
        val subToken: String,
        val permissions: DevicePermissions
    )
}

enum class DiagnosticStatus {
    PASS,
    INFO,
    WARNING,
    FAIL
}

data class DiagnosticCheckItem(
    val title: String,
    val passed: Boolean,
    val detail: String,
    val status: DiagnosticStatus = if (passed) DiagnosticStatus.PASS else DiagnosticStatus.WARNING
)

data class DiagnosticsReport(
    val checkedAtMillis: Long,
    val checks: List<DiagnosticCheckItem>
)

enum class DeviceAction {
    ADD_OR_MANAGE_CATALOG,
    INWARD,
    DISBURSE_OR_TRANSFER,
    EDIT_OR_DELETE
}

class InventoryViewModelFactory(private val application: Application) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(InventoryViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return InventoryViewModel(application) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
