package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.InventoryApp
import com.example.data.cloud.CloudBackupItem
import com.example.data.cloud.CreatedDeviceCredentials
import com.example.data.cloud.DevicePermissions
import com.example.data.cloud.DeviceRole
import com.example.data.cloud.DiscoveredMainDevice
import com.example.data.cloud.GmailOtpClient
import com.example.data.cloud.GoogleApiHttpException
import com.example.data.cloud.GoogleOAuthHelper
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.LocalServerStatus
import com.example.data.cloud.OAuthTokenResult
import com.example.data.dao.VoucherWithItems
import com.example.data.model.BackupRecord
import com.example.data.model.Beneficiary
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemStatus
import com.example.data.model.ItemUnit
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.SmallerUnit
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
import com.example.util.AppNotificationManager
import com.example.util.SecurityUtils
import kotlinx.coroutines.CancellationException
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
    private val cloudBackupRepo = app.cloudBackupRepository
    private val notificationManager = AppNotificationManager(application)

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
        },
        onRemoteOperationAlert = { subDeviceName, actorName, _, operationTitle, operationDetails ->
            val title = "إشعار من ($subDeviceName): $operationTitle"
            val actorPrefix = if (actorName.isNotBlank()) "بواسطة: $actorName • " else ""
            val details = "$actorPrefix$operationDetails"
            notificationManager.notifyOperation(
                title = title,
                details = details,
                isRemoteSubDeviceAlert = true
            )
            viewModelScope.launch {
                _userMessage.emit("🔔 $subDeviceName: $operationTitle")
            }
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

    private val _sessionPairingCredentials = MutableStateFlow<Map<String, CreatedDeviceCredentials>>(emptyMap())
    val sessionPairingCredentials: StateFlow<Map<String, CreatedDeviceCredentials>> = _sessionPairingCredentials.asStateFlow()

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

    // Cloud Backup (Google Account Picker + Email OTP Auto-Fill + Google Drive REST API v3; zero Firebase Auth)
    private val gmailOtpClient = GmailOtpClient()

    val verifiedGoogleEmail: StateFlow<String> = preferences.verifiedGoogleEmailFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    private val _cloudBackupsState = MutableStateFlow<CloudBackupUiState>(CloudBackupUiState.SignedOut)
    val cloudBackupsState: StateFlow<CloudBackupUiState> = _cloudBackupsState.asStateFlow()

    private val _isCloudBackupBusy = MutableStateFlow(false)
    val isCloudBackupBusy: StateFlow<Boolean> = _isCloudBackupBusy.asStateFlow()

    private val _cloudSyncOperationLabel = MutableStateFlow<String?>(null)
    val cloudSyncOperationLabel: StateFlow<String?> = _cloudSyncOperationLabel.asStateFlow()

    private val _cloudAuthError = MutableStateFlow<String?>(null)
    val cloudAuthError: StateFlow<String?> = _cloudAuthError.asStateFlow()

    private val _pendingOtpState = MutableStateFlow<PendingEmailOtpState?>(null)
    val pendingOtpState: StateFlow<PendingEmailOtpState?> = _pendingOtpState.asStateFlow()

    private val _pendingConsentRequest = MutableStateFlow<PendingConsentRequest?>(null)
    val pendingConsentRequest: StateFlow<PendingConsentRequest?> = _pendingConsentRequest.asStateFlow()

    private var pendingPostConsentAction: PendingPostConsentAction? = null
    private var otpPollingJob: Job? = null

    val lastCloudBackupAt: StateFlow<Long> = preferences.lastCloudBackupAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val lastCloudBackupEmail: StateFlow<String> = preferences.lastCloudBackupEmailFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val lastCloudBackupName: StateFlow<String> = preferences.lastCloudBackupNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val savedCloudEmails: StateFlow<List<String>> = preferences.savedCloudEmailsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

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

    val smallerUnits: StateFlow<List<SmallerUnit>> = repository.allSmallerUnits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val itemStatuses: StateFlow<List<ItemStatus>> = repository.allItemStatuses
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

            val creds = CreatedDeviceCredentials(
                deviceSlotId = newSlot.id,
                deviceName = newSlot.deviceName,
                workspaceCode = code,
                hostAddress = hostDisplay,
                plainPassword = plainPassword
            )
            _sessionPairingCredentials.value = _sessionPairingCredentials.value + (newSlot.id to creds)
            _createdDeviceCredentials.value = creds
            _userMessage.emit("تم إنشاء بيانات الربط والباركود للجهاز الفرعي ($cleanName)")
        }
    }

    fun showSessionCredentialsQr(slotId: String) {
        val cached = _sessionPairingCredentials.value[slotId]
        if (cached != null) {
            _createdDeviceCredentials.value = cached
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

    private fun notifyAndPropagate(
        action: DeviceAction? = null,
        notificationTitle: String,
        notificationDetails: String
    ) {
        notificationManager.notifyOperation(
            title = notificationTitle,
            details = notificationDetails,
            isRemoteSubDeviceAlert = false
        )
        propagateDataChange(
            action = action,
            operationTitle = notificationTitle,
            operationDetails = notificationDetails
        )
    }

    private fun propagateDataChange(
        action: DeviceAction? = null,
        operationTitle: String = "",
        operationDetails: String = ""
    ) {
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
                    entities = entities,
                    operationTitle = operationTitle,
                    operationDetails = operationDetails,
                    actorName = userName.value
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
                notifyAndPropagate(
                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                    notificationTitle = "إضافة مخزن جديد",
                    notificationDetails = "تم إضافة المخزن (${warehouse.name}) — أمين المخزن: ${warehouse.managerName}"
                )
            } else {
                repository.updateWarehouse(warehouse)
                _userMessage.emit("تم تحديث بيانات المخزن بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل بيانات مخزن",
                    notificationDetails = "تم تحديث بيانات المخزن (${warehouse.name})"
                )
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
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "حذف مخزن",
                notificationDetails = "تم حذف المخزن (${warehouse.name}) من النظام"
            )
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
            val warehouseName = warehouses.value.find { it.id == warehouseId }?.name ?: "مخزن"
            val itemName = items.value.find { it.id == itemId }?.name ?: "صنف"
            repository.addItemDirectlyToWarehouse(warehouseId, itemId, quantity, minStockAlert)
            _userMessage.emit("تمت إضافة الصنف داخل المخزن بنجاح")
            notifyAndPropagate(
                action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                notificationTitle = "إضافة صنف لمخزن",
                notificationDetails = "تم إضافة ($itemName) إلى ($warehouseName) بكمية ${quantity.toInt()}"
            )
            onDone()
        }
    }

    fun removeItemFromWarehouse(
        warehouseId: Long,
        itemId: Long,
        onDone: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val warehouseName = warehouses.value.find { it.id == warehouseId }?.name ?: "مخزن"
            val itemName = items.value.find { it.id == itemId }?.name ?: "صنف"
            repository.removeItemFromWarehouse(warehouseId, itemId)
            _userMessage.emit("تم حذف الصنف نهائياً من هذا المخزن")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "حذف صنف من مخزن",
                notificationDetails = "تم إزالة ($itemName) نهائياً من ($warehouseName)"
            )
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
            val itemName = items.value.find { it.id == itemId }?.name ?: "صنف"
            repository.updateWarehouseItemMinStock(warehouseId, itemId, customMinStock)
            _userMessage.emit("تم تحديث حد إعادة الطلب الخاص بالمخزن")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "تحديث حد إعادة الطلب",
                notificationDetails = "تم تعديل حد الطلب للصنف ($itemName) إلى ${customMinStock.toInt()}"
            )
            onDone()
        }
    }

    // 2. Item Operations
    fun saveItem(item: Item, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (item.id == 0L) {
                repository.insertItem(item)
                _userMessage.emit("تمت إضافة الصنف بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                    notificationTitle = "إضافة صنف جديد",
                    notificationDetails = "تم إضافة الصنف (${item.name}) بالوحدة (${item.unit})"
                )
            } else {
                repository.updateItem(item)
                _userMessage.emit("تم تحديث بيانات الصنف بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل بيانات صنف",
                    notificationDetails = "تم تحديث بيانات الصنف (${item.name})"
                )
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
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "حذف صنف",
                notificationDetails = "تم حذف الصنف (${item.name}) من الدليل"
            )
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
            val warehouseName = warehouses.value.find { it.id == warehouseId }?.name ?: "مخزن"
            val itemName = items.value.find { it.id == itemId }?.name ?: "صنف"
            repository.adjustWarehouseStock(warehouseId, itemId, newQuantity, reasonNote)
            _userMessage.emit("تم تعديل كمية المخزون وتوثيق السبب بنجاح")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "تسوية وتعديل مخزون",
                notificationDetails = "تم تعديل رصيد ($itemName) في ($warehouseName) إلى ${newQuantity.toInt()}"
            )
            onDone()
        }
    }

    // 4. Beneficiary Operations
    fun saveBeneficiary(beneficiary: Beneficiary, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (beneficiary.id == 0L) {
                val newId = repository.insertBeneficiary(beneficiary)
                _userMessage.emit("تمت إضافة الجهة المستفيدة بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                    notificationTitle = "إضافة جهة مستفيدة",
                    notificationDetails = "تمت إضافة الجهة المستفيدة (${beneficiary.name})"
                )
                newId
            } else {
                repository.updateBeneficiary(beneficiary)
                _userMessage.emit("تم تحديث بيانات الجهة المستفيدة")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل جهة مستفيدة",
                    notificationDetails = "تم تحديث بيانات (${beneficiary.name})"
                )
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
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "حذف جهة مستفيدة",
                    notificationDetails = "تم حذف (${beneficiary.name})"
                )
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
                notifyAndPropagate(
                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                    notificationTitle = "إضافة مورد جديد",
                    notificationDetails = "تمت إضافة المورد (${supplier.name})"
                )
                newId
            } else {
                repository.updateSupplier(supplier)
                _userMessage.emit("تم تحديث بيانات المورد")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تحديث بيانات مورد",
                    notificationDetails = "تم تعديل بيانات المورد (${supplier.name})"
                )
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
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "حذف مورد",
                    notificationDetails = "تم حذف المورد (${supplier.name})"
                )
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
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل اسم مورد",
                    notificationDetails = "تم تغيير اسم المورد من ($oldName) إلى (${newName.trim()})"
                )
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
                notifyAndPropagate(
                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                    notificationTitle = "إضافة مستلم / قائم بالصرف",
                    notificationDetails = "تمت إضافة (${party.name})"
                )
                newId
            } else {
                repository.updateParty(party)
                _userMessage.emit("تم تحديث البيانات")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تحديث مستلم / قائم بالصرف",
                    notificationDetails = "تم تحديث بيانات (${party.name})"
                )
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
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "حذف مستلم / قائم بالصرف",
                    notificationDetails = "تم حذف (${party.name})"
                )
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
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل اسم مستلم / قائم بالصرف",
                    notificationDetails = "تم تعديل الاسم من ($oldName) إلى (${newName.trim()})"
                )
            }
            onDone()
        }
    }

    // 7. Categories, Package Units, Smaller Units & Item Statuses Operations
    fun saveCategory(category: ItemCategory, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (category.id == 0L) {
                repository.insertCategory(category)
                notifyAndPropagate(
                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                    notificationTitle = "إضافة فئة / تصنيف",
                    notificationDetails = "تمت إضافة الفئة (${category.name})"
                )
            } else {
                repository.updateCategory(category)
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل فئة / تصنيف",
                    notificationDetails = "تم تحديث الفئة (${category.name})"
                )
            }
            _userMessage.emit("تم حفظ التصنيف")
            onDone()
        }
    }

    suspend fun checkCategoryHasOperations(categoryName: String): Boolean {
        return repository.checkCategoryHasOperations(categoryName)
    }

    fun updateCategorySafely(
        category: ItemCategory,
        newName: String,
        newNotes: String = category.notes,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val ok = repository.updateCategorySafely(category, newName, newNotes)
            if (ok) {
                _userMessage.emit("تم تعديل الفئة بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل فئة / تصنيف",
                    notificationDetails = "تم تعديل الفئة إلى (${newName.trim()})"
                )
            } else {
                _userMessage.emit("لا يمكن تعديل الفئة (${category.name}) لوجود أصناف أو عمليات مرتبطة بها!")
            }
            onResult(ok)
        }
    }

    fun deleteCategory(category: ItemCategory) {
        deleteCategorySafely(category)
    }

    fun deleteCategorySafely(category: ItemCategory, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.deleteCategorySafely(category)
            if (ok) {
                _userMessage.emit("تم حذف التصنيف بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "حذف فئة / تصنيف",
                    notificationDetails = "تم حذف الفئة (${category.name})"
                )
            } else {
                _userMessage.emit("لا يمكن حذف الفئة (${category.name}) لوجود أصناف أو عمليات مرتبطة بها!")
            }
            onResult(ok)
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
            notifyAndPropagate(
                action = if (unit.id == 0L) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_OR_DELETE,
                notificationTitle = if (unit.id == 0L) "إضافة وحدة قياس" else "تعديل وحدة قياس",
                notificationDetails = "الوحدة (${unit.name}) — ${unit.smallerUnitsCount.toInt()} ${unit.smallerUnit}"
            )
            onDone(id)
        }
    }

    fun saveUnit(unitName: String, onDone: (Long) -> Unit = {}) {
        saveUnit(ItemUnit(name = unitName), onDone)
    }

    fun updateUnitSafely(unit: ItemUnit, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.updateUnitSafely(unit)
            if (ok) {
                _userMessage.emit("تم تعديل وحدة القياس بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل وحدة قياس",
                    notificationDetails = "تم تحديث الوحدة (${unit.name})"
                )
            } else {
                _userMessage.emit("لا يمكن تعديل الوحدة لوجود أصناف أو حركات مرتبطة بها!")
            }
            onResult(ok)
        }
    }

    fun updateUnitName(unitId: Long, newName: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.updateUnitName(unitId, newName)
            _userMessage.emit("تم تعديل اسم الوحدة بنجاح")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "تعديل اسم وحدة",
                notificationDetails = "تم تعديل اسم الوحدة إلى (${newName.trim()})"
            )
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
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "حذف وحدة قياس",
                    notificationDetails = "تم حذف الوحدة (${unit.name})"
                )
            } else {
                _userMessage.emit("لا يمكن حذف الوحدة لأنها مرتبطة بأصناف أو حركات توريد وصرف سابقة!")
            }
            onResult(success)
        }
    }

    // Hierarchical & Parent-Scoped Smaller/Medium Units Operations
    fun saveHierarchicalUnit(
        existingUnit: ItemUnit?,
        largeName: String,
        hasMediumUnit: Boolean,
        mediumName: String,
        mediumPerLarge: Double,
        smallName: String,
        smallCountInParent: Double,
        smallCapacity: Double,
        onResult: (Boolean, String?) -> Unit = { _, _ -> }
    ) {
        viewModelScope.launch {
            val res = repository.saveHierarchicalUnit(
                existingUnit = existingUnit,
                largeName = largeName,
                hasMediumUnit = hasMediumUnit,
                mediumName = mediumName,
                mediumPerLarge = mediumPerLarge,
                smallName = smallName,
                smallCountInParent = smallCountInParent,
                smallCapacity = smallCapacity
            )
            res.onSuccess {
                val summary = if (hasMediumUnit) {
                    "$largeName ← $mediumName (${mediumPerLarge.toInt()}) ← $smallName (${smallCountInParent.toInt()})"
                } else {
                    "$largeName ← $smallName (${smallCountInParent.toInt()})"
                }
                _userMessage.emit("تم حفظ نظام وحدات القياس بنجاح")
                notifyAndPropagate(
                    action = if (existingUnit == null) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = if (existingUnit == null) "إضافة وحدة قياس هرمية" else "تعديل وحدة قياس",
                    notificationDetails = summary
                )
                onResult(true, null)
            }.onFailure { err ->
                val msg = err.localizedMessage ?: "تعذر حفظ وحدة القياس"
                _userMessage.emit(msg)
                onResult(false, msg)
            }
        }
    }

    fun addChildUnitToLargeUnit(
        parentUnit: ItemUnit,
        unitLevel: String,
        name: String,
        countInParent: Double,
        capacity: Double,
        mediumUnitName: String = "",
        onDone: (Long) -> Unit = {}
    ) {
        viewModelScope.launch {
            val id = repository.addChildUnitToLargeUnit(
                parentUnit = parentUnit,
                unitLevel = unitLevel,
                name = name,
                countInParent = countInParent,
                capacity = capacity,
                mediumUnitName = mediumUnitName
            )
            val levelLabel = if (unitLevel == "MEDIUM") "متوسطة" else "صغرى"
            _userMessage.emit("تمت إضافة الوحدة الـ$levelLabel ($name) تحت (${parentUnit.name}) بنجاح")
            notifyAndPropagate(
                action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                notificationTitle = "إضافة وحدة $levelLabel تابعة",
                notificationDetails = "${parentUnit.name} ← ${name.trim()} (${countInParent.toInt()})"
            )
            onDone(id)
        }
    }

    suspend fun checkChildUnitHasOperations(child: SmallerUnit): Boolean {
        return repository.checkChildUnitHasOperations(child)
    }

    fun updateChildUnitSafely(
        existing: SmallerUnit,
        newName: String,
        newCountInParent: Double,
        newCapacity: Double,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val ok = repository.updateChildUnitSafely(existing, newName, newCountInParent, newCapacity)
            if (ok) {
                val levelLabel = if (existing.unitLevel == "MEDIUM") "المتوسطة" else "الصغرى"
                _userMessage.emit("تم تعديل الوحدة $levelLabel بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل وحدة $levelLabel",
                    notificationDetails = "${existing.parentUnitName.ifBlank { "وحدة" }}: (${existing.name}) إلى (${newName.trim()})"
                )
            } else {
                _userMessage.emit("لا يمكن تعديل الوحدة (${existing.name}) تحت (${existing.parentUnitName}) لوجود أصناف أو عمليات مرتبطة بها!")
            }
            onResult(ok)
        }
    }

    fun deleteChildUnitSafely(unit: SmallerUnit, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.deleteSmallerUnitSafely(unit)
            if (ok) {
                val levelLabel = if (unit.unitLevel == "MEDIUM") "المتوسطة" else "الصغرى"
                _userMessage.emit("تم حذف الوحدة $levelLabel (${unit.name}) بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "حذف وحدة $levelLabel",
                    notificationDetails = "تم حذف (${unit.name}) التابعة لـ (${unit.parentUnitName})"
                )
            } else {
                _userMessage.emit("لا يمكن حذف الوحدة (${unit.name}) تحت (${unit.parentUnitName}) لوجود أصناف أو عمليات مرتبطة بها!")
            }
            onResult(ok)
        }
    }

    // Smaller Units Operations
    fun saveSmallerUnit(unit: SmallerUnit, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = repository.insertSmallerUnit(unit)
            _userMessage.emit("تمت إضافة الوحدة الصغرى (${unit.name}) بنجاح")
            notifyAndPropagate(
                action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                notificationTitle = "إضافة وحدة صغرى",
                notificationDetails = "تمت إضافة (${unit.name}) بسعة ${unit.capacity.toInt()}"
            )
            onDone(id)
        }
    }

    suspend fun checkSmallerUnitHasOperations(name: String): Boolean {
        return repository.checkSmallerUnitHasOperations(name)
    }

    suspend fun checkSmallerUnitUsedByPackageUnit(name: String): Boolean {
        return repository.checkSmallerUnitUsedByPackageUnit(name)
    }

    fun updateSmallerUnitSafely(
        existing: SmallerUnit,
        newName: String,
        newCapacity: Double,
        onResult: (Boolean) -> Unit = {}
    ) {
        viewModelScope.launch {
            val ok = repository.updateSmallerUnitSafely(existing, newName, newCapacity)
            if (ok) {
                _userMessage.emit("تم تعديل الوحدة الصغرى بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل وحدة صغرى",
                    notificationDetails = "تم تعديل (${existing.name}) إلى (${newName.trim()})"
                )
            } else {
                _userMessage.emit("لا يمكن تعديل الوحدة الصغرى (${existing.name}) لوجود أصناف أو عمليات مرتبطة بها!")
            }
            onResult(ok)
        }
    }

    fun deleteSmallerUnitSafely(unit: SmallerUnit, onResult: (Boolean) -> Unit = {}) {
        deleteChildUnitSafely(unit, onResult)
    }

    // Item Status Operations
    fun saveItemStatus(statusName: String, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val trimmed = statusName.trim()
            if (trimmed.isBlank()) return@launch
            val id = repository.insertItemStatus(ItemStatus(name = trimmed))
            _userMessage.emit("تمت إضافة الحالة ($trimmed) بنجاح")
            notifyAndPropagate(
                action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                notificationTitle = "إضافة حالة صنف جديدة",
                notificationDetails = "تمت إضافة الحالة ($trimmed)"
            )
            onDone(id)
        }
    }

    suspend fun checkItemStatusHasOperations(statusName: String): Boolean {
        return repository.checkItemStatusHasOperations(statusName)
    }

    fun updateItemStatusSafely(existing: ItemStatus, newName: String, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.updateItemStatusSafely(existing, newName)
            if (ok) {
                _userMessage.emit("تم تعديل الحالة بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل حالة صنف",
                    notificationDetails = "تم تعديل الحالة من (${existing.name}) إلى (${newName.trim()})"
                )
            } else {
                _userMessage.emit("لا يمكن تعديل الحالة (${existing.name}) لوجود عمليات أو وحدات مرقمة تستخدمها!")
            }
            onResult(ok)
        }
    }

    fun deleteItemStatusSafely(status: ItemStatus, onResult: (Boolean) -> Unit = {}) {
        viewModelScope.launch {
            val ok = repository.deleteItemStatusSafely(status)
            if (ok) {
                _userMessage.emit("تم حذف الحالة (${status.name}) بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "حذف حالة صنف",
                    notificationDetails = "تم حذف الحالة (${status.name})"
                )
            } else {
                _userMessage.emit("لا يمكن حذف الحالة (${status.name}) لوجود عمليات أو وحدات مرقمة تستخدمها!")
            }
            onResult(ok)
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
            val totalQty = items.sumOf { it.quantity }.toInt()
            val itemSummary = items.take(2).joinToString("، ") { it.itemName }
            val notifTitle = if (voucher.id == 0L) {
                "عملية $actionName (${voucher.voucherNumber})"
            } else {
                "تعديل سند $actionName (${voucher.voucherNumber})"
            }
            val notifDetails = "الأصناف: $itemSummary • إجمالي الكمية: $totalQty"
            _userMessage.emit("تم حفظ $actionName بنجاح")
            notifyAndPropagate(
                action = if (voucher.id != 0L) DeviceAction.EDIT_OR_DELETE else action,
                notificationTitle = notifTitle,
                notificationDetails = notifDetails
            )
            onDone()
        }
    }

    fun deleteVoucher(voucherId: Long) {
        viewModelScope.launch {
            repository.deleteVoucher(voucherId)
            _userMessage.emit("تم حذف السند بنجاح")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "حذف سند مخزني",
                notificationDetails = "تم حذف السند وعكس أرصدة المخزون المرتبطة به"
            )
        }
    }

    fun removeAttachment(voucherId: Long) {
        viewModelScope.launch {
            repository.removeVoucherAttachment(voucherId)
            _userMessage.emit("تم حذف المرفق بنجاح")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "حذف مرفق سند",
                notificationDetails = "تمت إزالة الملف المرفق من السند"
            )
        }
    }

    // 10. Local Backups, External Reinstall Recovery & Database/Network Diagnostics
    fun createBackup(name: String) {
        viewModelScope.launch {
            try {
                repository.createBackup(name, isCloud = false, accountEmail = "")
                notificationManager.notifyOperation(
                    title = "إنشاء نسخة احتياطية محلية",
                    details = "تم حفظ النسخة ($name) بنجاح"
                )
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
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري استرداد البيانات من الملف..."
            try {
                val ok = repository.restoreBackupReplacingExisting(rawJson)
                if (ok) {
                    notifyAndPropagate(
                        action = null,
                        notificationTitle = "استعادة نسخة من ملف",
                        notificationDetails = "تم استرداد كافة بيانات المخزون بنجاح"
                    )
                    _userMessage.emit("تم استرداد كافة البيانات من الملف بنجاح")
                } else {
                    _userMessage.emit("الملف المختار لا يحتوي على نسخة بيانات صالحة لمخزني الذكي")
                }
            } catch (e: Exception) {
                _userMessage.emit("تعذر استرداد الملف: ${e.localizedMessage}")
            } finally {
                _isCloudBackupBusy.value = false
                _cloudSyncOperationLabel.value = null
            }
        }
    }

    fun restoreBackup(record: BackupRecord) {
        viewModelScope.launch {
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري استعادة النسخة المحلية..."
            try {
                val ok = repository.restoreBackupReplacingExisting(record.backupJson)
                if (ok) {
                    notifyAndPropagate(
                        action = null,
                        notificationTitle = "استعادة نسخة احتياطية",
                        notificationDetails = "تمت استعادة البيانات من (${record.name})"
                    )
                    _userMessage.emit("تمت استعادة البيانات بنجاح من النسخة")
                } else {
                    _userMessage.emit("فشلت استعادة البيانات من النسخة")
                }
            } catch (e: Exception) {
                _userMessage.emit("خطأ أثناء الاستعادة: ${e.localizedMessage}")
            } finally {
                _isCloudBackupBusy.value = false
                _cloudSyncOperationLabel.value = null
            }
        }
    }

    fun renameBackup(record: BackupRecord, newName: String) {
        viewModelScope.launch {
            val cleanName = newName.trim()
            if (cleanName.isBlank()) {
                _userMessage.emit("يرجى إدخال اسم صالح للنسخة الاحتياطية")
                return@launch
            }
            try {
                repository.renameBackup(record.id, cleanName)
                _userMessage.emit("تم تعديل اسم النسخة الاحتياطية إلى ($cleanName)")
            } catch (e: Exception) {
                _userMessage.emit("تعذر تعديل الاسم: ${e.localizedMessage}")
            }
        }
    }

    fun deleteBackup(record: BackupRecord) {
        viewModelScope.launch {
            repository.deleteBackup(record)
            notificationManager.notifyOperation(
                title = "حذف نسخة احتياطية محلية",
                details = "تم حذف النسخة (${record.name})"
            )
            _userMessage.emit("تم حذف النسخة الاحتياطية")
        }
    }

    // 10b. Cloud Backup & Restore via Phone Google Account + Email OTP + Google Drive
    private suspend fun <T> executeWithGoogleToken(
        context: Context,
        email: String,
        postConsentAction: PendingPostConsentAction,
        block: suspend (accessToken: String) -> Result<T>
    ): Result<T>? {
        val firstTokenRes = GoogleOAuthHelper.fetchAccessToken(context, email)
        when (firstTokenRes) {
            is OAuthTokenResult.NeedsConsent -> {
                pendingPostConsentAction = postConsentAction
                _pendingConsentRequest.value = PendingConsentRequest(
                    intent = firstTokenRes.consentIntent,
                    email = email
                )
                return null
            }
            is OAuthTokenResult.Error -> {
                return Result.failure(IllegalStateException(firstTokenRes.message))
            }
            is OAuthTokenResult.Success -> {
                val firstCall = block(firstTokenRes.accessToken)
                val err = firstCall.exceptionOrNull()
                if (err is GoogleApiHttpException && err.statusCode == 401) {
                    GoogleOAuthHelper.clearToken(context, firstTokenRes.accessToken)
                    val retryTokenRes = GoogleOAuthHelper.fetchAccessToken(context, email)
                    return when (retryTokenRes) {
                        is OAuthTokenResult.NeedsConsent -> {
                            pendingPostConsentAction = postConsentAction
                            _pendingConsentRequest.value = PendingConsentRequest(
                                intent = retryTokenRes.consentIntent,
                                email = email
                            )
                            null
                        }
                        is OAuthTokenResult.Error -> Result.failure(IllegalStateException(retryTokenRes.message))
                        is OAuthTokenResult.Success -> block(retryTokenRes.accessToken)
                    }
                }
                return firstCall
            }
        }
    }

    fun onGoogleConsentResult(context: Context, granted: Boolean) {
        val action = pendingPostConsentAction
        pendingPostConsentAction = null
        _pendingConsentRequest.value = null
        if (!granted) {
            _isCloudBackupBusy.value = false
            _cloudSyncOperationLabel.value = null
            _pendingOtpState.value = null
            _cloudAuthError.value = "تم إلغاء منح صلاحية الوصول إلى البريد و Google Drive."
            return
        }
        when (action) {
            is PendingPostConsentAction.SendOtp -> startEmailOtpVerification(context, action.email)
            is PendingPostConsentAction.CreateBackup -> createCloudBackup(context, action.backupName)
            is PendingPostConsentAction.ListBackups -> refreshCloudBackupsFromDrive(context, action.silent)
            is PendingPostConsentAction.RenameBackup -> renameCloudBackup(context, action.item, action.newName)
            is PendingPostConsentAction.RestoreBackup -> restoreCloudBackup(context, action.item)
            is PendingPostConsentAction.DeleteBackup -> deleteCloudBackup(context, action.item)
            null -> Unit
        }
    }

    fun startEmailOtpVerification(context: Context, pickedEmail: String) {
        val cleanEmail = pickedEmail.trim()
        if (cleanEmail.isBlank() || !cleanEmail.contains("@")) {
            viewModelScope.launch {
                _userMessage.emit("يرجى اختيار حساب Google صالح من الهاتف")
            }
            return
        }
        otpPollingJob?.cancel()
        _cloudAuthError.value = null

        val otpCode = gmailOtpClient.generateRandomOtpCode()
        val refTag = gmailOtpClient.generateRefTag()
        val now = System.currentTimeMillis()
        _pendingOtpState.value = PendingEmailOtpState(
            email = cleanEmail,
            refTag = refTag,
            expectedCode = otpCode,
            sentAtMillis = now,
            isSending = true,
            isCheckingInbox = false,
            statusMessage = "جاري إرسال رمز تأكيد عشوائي عبر البريد إلى $cleanEmail..."
        )

        viewModelScope.launch {
            preferences.rememberSavedCloudEmail(cleanEmail)
            val result = executeWithGoogleToken(
                context = context,
                email = cleanEmail,
                postConsentAction = PendingPostConsentAction.SendOtp(cleanEmail)
            ) { token ->
                gmailOtpClient.sendVerificationOtpEmail(
                    accessToken = token,
                    targetEmail = cleanEmail,
                    otpCode = otpCode,
                    refTag = refTag
                )
            } ?: return@launch

            result.onSuccess {
                _pendingOtpState.value = _pendingOtpState.value?.copy(
                    isSending = false,
                    isCheckingInbox = true,
                    statusMessage = "تم إرسال رمز التأكيد إلى بريدك. جاري انتظار وصول الرسالة للتعبئة التلقائية...",
                    errorMessage = null
                )
                startOtpAutoFillPolling(context, cleanEmail, refTag)
            }.onFailure { err ->
                _pendingOtpState.value = _pendingOtpState.value?.copy(
                    isSending = false,
                    isCheckingInbox = false,
                    errorMessage = err.localizedMessage ?: "تعذر إرسال رمز التأكيد عبر البريد الإلكتروني"
                )
            }
        }
    }

    private fun startOtpAutoFillPolling(context: Context, email: String, refTag: String) {
        otpPollingJob?.cancel()
        otpPollingJob = viewModelScope.launch {
            for (attempt in 0 until 36) {
                if (!isActive) return@launch
                val current = _pendingOtpState.value ?: return@launch
                if (current.refTag != refTag || current.autoFilledCode != null) return@launch
                delay(if (attempt == 0) 1500L else 2500L)

                val tokenRes = GoogleOAuthHelper.fetchAccessToken(context, email)
                val token = (tokenRes as? OAuthTokenResult.Success)?.accessToken ?: continue
                val pollRes = gmailOtpClient.pollIncomingOtpCode(
                    accessToken = token,
                    refTag = refTag,
                    expectedEmail = email
                )
                val detected = pollRes.getOrNull()
                if (!detected.isNullOrBlank()) {
                    _pendingOtpState.value = _pendingOtpState.value?.copy(
                        autoFilledCode = detected,
                        isCheckingInbox = false,
                        statusMessage = "تم استلام الرسالة وتعبئة رمز التأكيد تلقائياً ✓",
                        errorMessage = null
                    )
                    return@launch
                }
            }
            _pendingOtpState.value = _pendingOtpState.value?.let { st ->
                if (st.refTag == refTag && st.autoFilledCode == null) {
                    st.copy(
                        isCheckingInbox = false,
                        statusMessage = "تحقق من بريدك وأدخل الرمز المرسل، أو اضغط «فحص البريد الآن»."
                    )
                } else {
                    st
                }
            }
        }
    }

    fun checkOtpInboxNow(context: Context) {
        val current = _pendingOtpState.value ?: return
        if (current.isSending) return
        viewModelScope.launch {
            _pendingOtpState.value = _pendingOtpState.value?.copy(
                isCheckingInbox = true,
                errorMessage = null,
                statusMessage = "جاري فحص البريد الوارد بحثاً عن رمز التأكيد..."
            )
            val tokenRes = GoogleOAuthHelper.fetchAccessToken(context, current.email)
            when (tokenRes) {
                is OAuthTokenResult.Success -> {
                    val pollRes = gmailOtpClient.pollIncomingOtpCode(
                        accessToken = tokenRes.accessToken,
                        refTag = current.refTag,
                        expectedEmail = current.email
                    )
                    val detected = pollRes.getOrNull()
                    if (!detected.isNullOrBlank()) {
                        otpPollingJob?.cancel()
                        _pendingOtpState.value = _pendingOtpState.value?.copy(
                            autoFilledCode = detected,
                            isCheckingInbox = false,
                            statusMessage = "تم استلام الرسالة وتعبئة رمز التأكيد تلقائياً ✓",
                            errorMessage = null
                        )
                    } else {
                        _pendingOtpState.value = _pendingOtpState.value?.copy(
                            isCheckingInbox = false,
                            statusMessage = "لم تصل الرسالة للبريد الوارد بعد، حاول بعد لحظات أو أدخل الرمز يدوياً."
                        )
                    }
                }
                is OAuthTokenResult.Error -> {
                    _pendingOtpState.value = _pendingOtpState.value?.copy(
                        isCheckingInbox = false,
                        errorMessage = tokenRes.message
                    )
                }
                is OAuthTokenResult.NeedsConsent -> {
                    pendingPostConsentAction = PendingPostConsentAction.SendOtp(current.email)
                    _pendingConsentRequest.value = PendingConsentRequest(tokenRes.consentIntent, current.email)
                }
            }
        }
    }

    fun verifyEmailOtpCode(context: Context, enteredCode: String) {
        val current = _pendingOtpState.value ?: return
        val cleanCode = enteredCode.trim()
        if (cleanCode.length != 6 || cleanCode != current.expectedCode) {
            _pendingOtpState.value = current.copy(
                errorMessage = "رمز التأكيد غير صحيح. تأكد من الرمز العشوائي المرسل إلى (${current.email})."
            )
            return
        }
        otpPollingJob?.cancel()
        _pendingOtpState.value = null
        viewModelScope.launch {
            preferences.setVerifiedGoogleEmail(current.email)
            _cloudAuthError.value = null
            _userMessage.emit("تم توثيق وإضافة حساب (${current.email}) بنجاح ✓")
            refreshCloudBackupsFromDrive(context, silent = true)
        }
    }

    fun dismissOtpDialog() {
        otpPollingJob?.cancel()
        _pendingOtpState.value = null
    }

    fun disconnectGoogleAccount() {
        otpPollingJob?.cancel()
        _pendingOtpState.value = null
        viewModelScope.launch {
            preferences.clearVerifiedGoogleEmail()
            _cloudBackupsState.value = CloudBackupUiState.SignedOut
            _cloudAuthError.value = null
            _userMessage.emit("تم فصل حساب Google عن المزامنة السحابية")
        }
    }

    private suspend fun syncLastUploadedMetadata(accountEmail: String, list: List<CloudBackupItem>) {
        val latest = list.firstOrNull() ?: return
        val tsMillis = maxOf(latest.createdAtMillis, latest.updatedAtMillis)
        val resolvedEmail = latest.userEmail.ifBlank { accountEmail }
        if (tsMillis > lastCloudBackupAt.value || lastCloudBackupEmail.value.isBlank()) {
            preferences.recordCloudBackupSuccess(
                email = resolvedEmail,
                backupName = latest.name,
                timestampMillis = if (tsMillis > 0L) tsMillis else System.currentTimeMillis()
            )
        }
    }

    fun refreshCloudBackupsFromDrive(context: Context, silent: Boolean = false) {
        val email = verifiedGoogleEmail.value.trim()
        if (email.isBlank()) {
            _cloudBackupsState.value = CloudBackupUiState.SignedOut
            return
        }
        viewModelScope.launch {
            if (!silent) {
                _cloudBackupsState.value = CloudBackupUiState.Loading
                _isCloudBackupBusy.value = true
                _cloudSyncOperationLabel.value = "جاري جلب النسخ الاحتياطية من Google Drive..."
                _cloudAuthError.value = null
            } else if (_cloudBackupsState.value !is CloudBackupUiState.Ready) {
                _cloudBackupsState.value = CloudBackupUiState.Loading
            }
            try {
                val res = executeWithGoogleToken(
                    context = context,
                    email = email,
                    postConsentAction = PendingPostConsentAction.ListBackups(email, silent)
                ) { token ->
                    cloudBackupRepo.fetchCloudBackupsFromDrive(token, email)
                } ?: return@launch

                res.onSuccess { list ->
                    _cloudBackupsState.value = CloudBackupUiState.Ready(list)
                    syncLastUploadedMetadata(email, list)
                    if (!silent) {
                        _userMessage.emit(
                            if (list.isEmpty()) "لا توجد نسخ احتياطية مرفوعة للحساب ($email) بعد"
                            else "تم عرض ${list.size} نسخة احتياطية من حساب ($email)"
                        )
                    }
                }.onFailure { err ->
                    val msg = err.localizedMessage ?: "تعذر جلب النسخ السحابية من Google Drive"
                    _cloudBackupsState.value = CloudBackupUiState.Error(msg)
                    if (!silent) {
                        _cloudAuthError.value = msg
                    }
                }
            } finally {
                if (!silent) {
                    _isCloudBackupBusy.value = false
                    _cloudSyncOperationLabel.value = null
                }
            }
        }
    }

    fun createCloudBackup(context: Context, name: String) {
        val email = verifiedGoogleEmail.value.trim()
        if (email.isBlank()) {
            _cloudAuthError.value = "يرجى إضافة وتأكيد حساب Google أولاً لرفع النسخة السحابية."
            return
        }
        viewModelScope.launch {
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري رفع النسخة السحابية إلى Google Drive ($email)..."
            _cloudAuthError.value = null
            try {
                val cleanName = name.trim().ifBlank { "نسخة_سحابية" }
                val snapshot = repository.buildCloudBackupSnapshot(cleanName, email)
                val currentOrg = preferences.orgNameFlow.first()

                val res = executeWithGoogleToken(
                    context = context,
                    email = email,
                    postConsentAction = PendingPostConsentAction.CreateBackup(email, cleanName)
                ) { token ->
                    cloudBackupRepo.createCloudBackup(
                        accessToken = token,
                        userEmail = email,
                        name = cleanName,
                        orgName = currentOrg,
                        warehouseCount = snapshot.warehouseCount,
                        itemCount = snapshot.itemCount,
                        voucherCount = snapshot.voucherCount,
                        backupJson = snapshot.backupJson
                    )
                } ?: return@launch

                res.onSuccess { created ->
                    preferences.recordCloudBackupSuccess(
                        email = email,
                        backupName = cleanName,
                        timestampMillis = System.currentTimeMillis()
                    )
                    val currentList = (_cloudBackupsState.value as? CloudBackupUiState.Ready)?.backups.orEmpty()
                    _cloudBackupsState.value = CloudBackupUiState.Ready(listOf(created) + currentList.filterNot { it.id == created.id })
                    notificationManager.notifyOperation(
                        title = "رفع نسخة احتياطية سحابية",
                        details = "تم حفظ النسخة ($cleanName) على Google Drive للحساب ($email)"
                    )
                    _userMessage.emit("تم رفع النسخة السحابية ($cleanName) بنجاح على حساب $email")
                }.onFailure { err ->
                    _cloudAuthError.value = err.localizedMessage ?: "تعذر رفع النسخة السحابية"
                }
            } finally {
                _isCloudBackupBusy.value = false
                _cloudSyncOperationLabel.value = null
            }
        }
    }

    fun renameCloudBackup(context: Context, item: CloudBackupItem, newName: String) {
        val email = verifiedGoogleEmail.value.trim()
        if (email.isBlank()) return
        val cleanName = newName.trim()
        if (cleanName.isBlank()) {
            viewModelScope.launch { _userMessage.emit("يرجى إدخال اسم صالح للنسخة السحابية") }
            return
        }
        viewModelScope.launch {
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري تعديل اسم النسخة السحابية..."
            _cloudAuthError.value = null
            try {
                val res = executeWithGoogleToken(
                    context = context,
                    email = email,
                    postConsentAction = PendingPostConsentAction.RenameBackup(email, item, cleanName)
                ) { token ->
                    cloudBackupRepo.renameCloudBackup(token, item.id, cleanName)
                } ?: return@launch

                res.onSuccess {
                    if (lastCloudBackupName.value == item.name) {
                        preferences.recordCloudBackupSuccess(
                            email = email,
                            backupName = cleanName,
                            timestampMillis = lastCloudBackupAt.value.takeIf { it > 0L } ?: System.currentTimeMillis()
                        )
                    }
                    refreshCloudBackupsFromDrive(context, silent = true)
                    _userMessage.emit("تم تعديل اسم النسخة السحابية إلى ($cleanName)")
                }.onFailure { err ->
                    _cloudAuthError.value = err.localizedMessage ?: "تعذر تعديل اسم النسخة السحابية"
                }
            } finally {
                _isCloudBackupBusy.value = false
                _cloudSyncOperationLabel.value = null
            }
        }
    }

    fun restoreCloudBackup(context: Context, item: CloudBackupItem) {
        val email = verifiedGoogleEmail.value.trim()
        if (email.isBlank()) {
            _cloudAuthError.value = "يرجى إضافة وتأكيد حساب Google أولاً."
            return
        }
        viewModelScope.launch {
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري تنزيل واستعادة النسخة (${item.name})..."
            _cloudAuthError.value = null
            try {
                val jsonRes = if (item.backupJson.isNotBlank()) {
                    Result.success(item.backupJson)
                } else {
                    executeWithGoogleToken(
                        context = context,
                        email = email,
                        postConsentAction = PendingPostConsentAction.RestoreBackup(email, item)
                    ) { token ->
                        cloudBackupRepo.downloadBackupJson(token, item.id)
                    } ?: return@launch
                }

                jsonRes.onSuccess { rawJson ->
                    val ok = repository.restoreBackupReplacingExisting(rawJson)
                    if (ok) {
                        notifyAndPropagate(
                            action = null,
                            notificationTitle = "استعادة نسخة سحابية",
                            notificationDetails = "تمت استعادة (${item.name}) بالكامل"
                        )
                        _userMessage.emit("تمت استعادة النسخة السحابية (${item.name}) بالكامل ✓")
                    } else {
                        _userMessage.emit("تعذر قراءة محتوى النسخة السحابية")
                    }
                }.onFailure { err ->
                    _cloudAuthError.value = err.localizedMessage ?: "تعذر تنزيل النسخة السحابية"
                }
            } catch (e: Exception) {
                _userMessage.emit("خطأ أثناء استعادة النسخة السحابية: ${e.localizedMessage}")
            } finally {
                _isCloudBackupBusy.value = false
                _cloudSyncOperationLabel.value = null
            }
        }
    }

    fun deleteCloudBackup(context: Context, item: CloudBackupItem) {
        val email = verifiedGoogleEmail.value.trim()
        if (email.isBlank()) return
        viewModelScope.launch {
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري حذف النسخة من Google Drive..."
            _cloudAuthError.value = null
            try {
                val res = executeWithGoogleToken(
                    context = context,
                    email = email,
                    postConsentAction = PendingPostConsentAction.DeleteBackup(email, item)
                ) { token ->
                    cloudBackupRepo.deleteCloudBackup(token, item.id)
                } ?: return@launch

                res.onSuccess {
                    val currentList = (_cloudBackupsState.value as? CloudBackupUiState.Ready)?.backups.orEmpty()
                    _cloudBackupsState.value = CloudBackupUiState.Ready(currentList.filterNot { it.id == item.id })
                    notificationManager.notifyOperation(
                        title = "حذف نسخة سحابية",
                        details = "تم حذف النسخة السحابية (${item.name}) نهائياً"
                    )
                    _userMessage.emit("تم حذف النسخة السحابية (${item.name}) بنجاح")
                }.onFailure { err ->
                    _cloudAuthError.value = err.localizedMessage ?: "تعذر حذف النسخة السحابية"
                }
            } finally {
                _isCloudBackupBusy.value = false
                _cloudSyncOperationLabel.value = null
            }
        }
    }

    private sealed interface PendingPostConsentAction {
        data class SendOtp(val email: String) : PendingPostConsentAction
        data class CreateBackup(val email: String, val backupName: String) : PendingPostConsentAction
        data class ListBackups(val email: String, val silent: Boolean) : PendingPostConsentAction
        data class RenameBackup(val email: String, val item: CloudBackupItem, val newName: String) : PendingPostConsentAction
        data class RestoreBackup(val email: String, val item: CloudBackupItem) : PendingPostConsentAction
        data class DeleteBackup(val email: String, val item: CloudBackupItem) : PendingPostConsentAction
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
        notifyAndPropagate(
            action = DeviceAction.EDIT_OR_DELETE,
            notificationTitle = "تحديث الأرقام التسلسلية والحالات",
            notificationDetails = "تم حفظ ${saved.size} وحدة مرقمة في السجل"
        )
        return saved
    }

    fun saveSerials(serials: List<ItemUnitSerialRecord>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val saved = repository.saveSerialsAndReturn(serials)
            _userMessage.emit("تم حفظ الأرقام المميزة والحالات بنجاح")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "حفظ الأرقام التسلسلية والحالات",
                notificationDetails = "تم تحديث ${saved.size} وحدة مرقمة"
            )
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

data class PendingEmailOtpState(
    val email: String,
    val refTag: String,
    val expectedCode: String,
    val sentAtMillis: Long,
    val autoFilledCode: String? = null,
    val isSending: Boolean = false,
    val isCheckingInbox: Boolean = false,
    val statusMessage: String? = null,
    val errorMessage: String? = null
)

data class PendingConsentRequest(
    val intent: Intent,
    val email: String
)

sealed interface CloudBackupUiState {
    data object SignedOut : CloudBackupUiState
    data object Loading : CloudBackupUiState
    data class Ready(val backups: List<CloudBackupItem>) : CloudBackupUiState
    data class Error(val message: String) : CloudBackupUiState
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
