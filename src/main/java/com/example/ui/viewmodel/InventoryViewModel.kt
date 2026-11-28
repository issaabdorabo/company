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
import com.example.data.cloud.GoogleApiHttpException
import com.example.data.cloud.GoogleOAuthHelper
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.WarehousePermission
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
import com.example.data.model.OperationAuditLog
import com.example.data.model.ItemStockInfo
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
import com.example.data.sync.CloudSyncWorker
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as InventoryApp
    private val repository = app.repository
    private val preferences = app.preferencesManager
    private val cloudBackupRepo = app.cloudBackupRepository
    private val cloudSyncEngine = app.cloudSyncEngine
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
        onExportAuditLogs = { device -> repository.getOperationLogsForDevice(device) },
        onApplyRemoteEntities = { records ->
            repository.replaceDatabaseFromCloudEntitiesSafely(records)
            preferences.incrementSyncRevision()
        },
        onRemoteOperationAlert = { subDeviceName, actorName, actionType, operationTitle, operationDetails ->
            viewModelScope.launch {
                repository.recordOperation(
                    OperationAuditLog(
                        actorName = actorName.ifBlank { subDeviceName },
                        deviceName = subDeviceName,
                        operationType = actionType.ifBlank { "GENERAL" },
                        title = operationTitle,
                        details = operationDetails
                    )
                )
            }
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

    // Cloud Backup (Google account picker + app-private Google Drive data; no email OTP or Firebase)

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

    private val _pendingConsentRequest = MutableStateFlow<PendingConsentRequest?>(null)
    val pendingConsentRequest: StateFlow<PendingConsentRequest?> = _pendingConsentRequest.asStateFlow()

    private var pendingPostConsentAction: PendingPostConsentAction? = null

    val lastCloudBackupAt: StateFlow<Long> = preferences.lastCloudBackupAtFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)
    val lastCloudBackupEmail: StateFlow<String> = preferences.lastCloudBackupEmailFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val lastCloudBackupName: StateFlow<String> = preferences.lastCloudBackupNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")
    val savedCloudEmails: StateFlow<List<String>> = preferences.savedCloudEmailsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // Stock & Inventory Data — hide warehouses that were not granted to a secondary device.
    private fun visibleWarehouseIds(allIds: List<Long>, permissions: DevicePermissions): Set<Long> =
        if (permissions.isMainDevice || permissions.canViewAllWarehouses) allIds.toSet()
        else permissions.warehousePermissions.filter { it.canView }.map { it.warehouseId }.toSet()

    val warehouses: StateFlow<List<Warehouse>> = combine(repository.allWarehouses, devicePermissions) { all, perms ->
        if (perms.isMainDevice || perms.canViewAllWarehouses) all
        else all.filter { warehouse -> perms.warehousePermissions.any { it.warehouseId == warehouse.id && it.canView } }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val stockSnapshot: StateFlow<StockSnapshot?> = combine(repository.getCombinedStockStream(), devicePermissions) { source, perms ->
        if (source == null || perms.isMainDevice || perms.canViewAllWarehouses) return@combine source
        val allowedIds = visibleWarehouseIds(source.warehouses.map { it.id }, perms)
        val visibleWarehouses = source.warehouses.filter { it.id in allowedIds }
        val visibleStocks = source.warehouseItemStocks.filter { it.warehouseId in allowedIds }
        // Do not reveal the global catalogue to a secondary device that can see only selected stores.
        val visibleItemIds = visibleStocks.map { it.itemId }.toSet()
        val visibleItems = source.items.filter { it.id in visibleItemIds }
        val visibleVouchers = source.vouchers.filter { v ->
            (v.voucher.sourceWarehouseId == null || v.voucher.sourceWarehouseId in allowedIds) &&
                (v.voucher.destinationWarehouseId == null || v.voucher.destinationWarehouseId in allowedIds)
        }
        val scopedItemStocks = visibleItems.map { item ->
            val qty = visibleStocks.filter { it.itemId == item.id }.sumOf { it.currentStock }
            val combinedMinStock = visibleStocks.filter { it.itemId == item.id }.sumOf { it.minStockLevel }
                .takeIf { it > 0.0 } ?: item.minStockLevel
            val low = qty > 0.0001 && qty <= combinedMinStock
            ItemStockInfo(item, qty, qty * item.purchasePrice, low, qty <= 0.0001)
        }
        val visibleSummaries = source.warehouseSummaries.filter { it.warehouseId in allowedIds }
        val visibleStockMap = source.stockMap.filterKeys { it.first in allowedIds }
        source.copy(
            warehouses = visibleWarehouses,
            items = visibleItems,
            vouchers = visibleVouchers,
            warehouseSummaries = visibleSummaries,
            itemStockInfos = scopedItemStocks,
            warehouseItemStocks = visibleStocks,
            lowStockItems = scopedItemStocks.filter { it.isLowStock || it.isOutOfStock },
            totalInventoryValue = scopedItemStocks.sumOf { it.totalValue },
            totalInventoryQuantity = scopedItemStocks.sumOf { it.currentStock },
            stockMap = visibleStockMap
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val items: StateFlow<List<Item>> = combine(
        repository.allItems,
        repository.allAssociations,
        warehouses,
        devicePermissions
    ) { allItems, associations, visibleWarehouses, perms ->
        if (perms.isMainDevice || perms.canViewAllWarehouses) allItems
        else {
            val visibleWarehouseIds = visibleWarehouses.map { it.id }.toSet()
            val visibleItemIds = associations.asSequence()
                .filter { !it.isRemoved && it.warehouseId in visibleWarehouseIds }
                .map { it.itemId }
                .toSet()
            allItems.filter { it.id in visibleItemIds }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val beneficiaries: StateFlow<List<Beneficiary>> = repository.allBeneficiaries
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val vouchers: StateFlow<List<VoucherWithItems>> = combine(repository.allVouchers, warehouses) { all, visible ->
        val ids = visible.map { it.id }.toSet()
        all.filter { v ->
            (v.voucher.sourceWarehouseId == null || v.voucher.sourceWarehouseId in ids) &&
                (v.voucher.destinationWarehouseId == null || v.voucher.destinationWarehouseId in ids)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val backups: StateFlow<List<BackupRecord>> = repository.allBackups
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val operationAuditLogs: StateFlow<List<OperationAuditLog>> = repository.operationAuditLogs
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
    private val subSyncMutex = Mutex()
    private var nsdDiscoveryJob: Job? = null

    init {
        startMultiDeviceCoordinator()
    }

    private fun startMultiDeviceCoordinator() {
        viewModelScope.launch {
            launch {
            var hasInitialAuditSnapshot = false
            var knownAuditKeys = emptySet<String>()
            repository.operationAuditLogs.collect { logs ->
                val currentKeys = logs.map { log ->
                    listOf(log.timestamp, log.actorName, log.deviceName, log.operationType, log.title, log.details)
                        .joinToString("|")
                }.toSet()
                if (hasInitialAuditSnapshot) {
                    logs.filter { log ->
                        val key = listOf(
                            log.timestamp, log.actorName, log.deviceName,
                            log.operationType, log.title, log.details
                        ).joinToString("|")
                        val currentDeviceName = if (isMainDevice.value) "الجهاز الرئيسي" else deviceName.value
                        key !in knownAuditKeys &&
                            log.deviceName.isNotBlank() &&
                            !log.deviceName.equals(currentDeviceName, ignoreCase = true)
                    }.forEach { log ->
                        notificationManager.notifyOperation(
                            title = "عملية جديدة من " + log.deviceName + ": " + log.title,
                            details = "بواسطة " + log.actorName + " • " + log.details,
                            isRemoteSubDeviceAlert = true
                        )
                    }
                }
                knownAuditKeys = currentKeys
                hasInitialAuditSnapshot = true
            }
            }
            combine(
                preferences.deviceRoleFlow,
                preferences.subSessionTokenFlow,
                preferences.devicePermissionsFlow
            ) { role, token, perms ->
                Triple(role, token, perms)
            }.collect { (role, token, perms) ->
                subSyncJob?.cancel()
                subSyncJob = null
                cloudSyncEngine.stopRealtimeListeners()
                nsdDiscoveryJob?.cancel()
                nsdDiscoveryJob = null
                nsdHelper.unregisterMainService()
                syncServer.stop()
                _discoveredMainDevices.value = emptyList()

                if (role == DeviceRole.MAIN) {
                    subSyncJob = launch {
                        runCatching {
                            cloudSyncEngine.ensureMainWorkspace()
                            cloudSyncEngine.syncOnce()
                            cloudSyncEngine.startRealtimeListeners(viewModelScope) { message ->
                                if (message.contains("إيقاف ارتباط") || message.contains("طرد")) {
                                    handleSubDeviceKicked(message)
                                } else {
                                    _lastSyncError.value = message
                                }
                            }
                        }.onFailure { error ->
                            _lastSyncError.value = error.localizedMessage
                                ?: "تعذر تهيئة المزامنة السحابية"
                        }
                        while (isActive) {
                            runCatching { cloudSyncEngine.heartbeat() }
                                .onFailure { error ->
                                    _lastSyncError.value = error.localizedMessage
                                        ?: "تعذر تحديث حالة الاتصال السحابي"
                                }
                            delay(30_000L)
                        }
                    }
                } else if (token.isNotBlank() && !perms.isKicked) {
                    subSyncJob = launch {
                        pullFromMainOnce(silent = true)
                        runCatching {
                            cloudSyncEngine.startRealtimeListeners(viewModelScope) { message ->
                                _lastSyncError.value = message
                            }
                        }.onFailure { error ->
                            _lastSyncError.value = error.localizedMessage
                                ?: "تعذر تشغيل مستمع المزامنة السحابية"
                        }
                        while (isActive) {
                            runCatching { cloudSyncEngine.heartbeat() }
                                .onFailure { error ->
                                    val message = error.localizedMessage
                                        ?: "تعذر تحديث حالة الاتصال السحابي"
                                    _lastSyncError.value = message
                                    if (message.contains("إيقاف ارتباط")) {
                                        handleSubDeviceKicked(message)
                                    }
                                }
                            delay(30_000L)
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
        cloudSyncEngine.stopRealtimeListeners()
        syncServer.stop()
    }

    // Granular Multi-Device Permissions. Warehouse-scoped actions require grants for every target warehouse.
    fun checkPermission(
        action: DeviceAction,
        onDenied: () -> Unit,
        warehouseIds: Set<Long> = emptySet(),
        onAllowed: () -> Unit
    ) {
        val perms = devicePermissions.value
        if (perms.isMainDevice) {
            if (warehouseIds.isEmpty()) {
                onAllowed()
                return
            }
            viewModelScope.launch {
                val linkedOwners = preferences.getLinkedSubDevicesSnapshot().filter { slot ->
                    !slot.isKicked && (
                        slot.canViewAllWarehouses ||
                            slot.warehousePermissions.any { grant ->
                                grant.warehouseId in warehouseIds && grant.canView
                            }
                        )
                }
                val offlineDevice = linkedOwners.firstOrNull { slot ->
                    !runCatching { cloudSyncEngine.isSubDeviceOnline(slot.id) }.getOrDefault(false)
                }
                if (offlineDevice != null) {
                    _userMessage.emit(
                        "لا يمكن تنفيذ العملية على هذا المخزن الآن؛ الجهاز الفرعي المرتبط به (" +
                            offlineDevice.deviceName +
                            ") غير متصل بالسحابة. أعد المحاولة بعد عودة اتصاله."
                    )
                } else {
                    onAllowed()
                }
            }
            return
        }
        if (perms.isKicked) {
            _kickedAlertMessage.value = "تم إيقاف وطرد هذا الجهاز من الجهاز الرئيسي."
            onDenied()
            return
        }

        // Backward compatibility applies only to older linked devices that have no explicit
        // warehouse grants. New devices receive warehouse edit permissions per warehouse.
        val legacyGlobalGrant = perms.canViewAllWarehouses && perms.warehousePermissions.isEmpty()
        val allowed = if (warehouseIds.isNotEmpty()) {
            warehouseIds.all { warehouseId ->
                val grant = perms.warehousePermissions.firstOrNull { it.warehouseId == warehouseId }
                if (grant == null) {
                    legacyGlobalGrant && when (action) {
                        DeviceAction.INWARD -> perms.canInward
                        DeviceAction.DISBURSEMENT, DeviceAction.DISBURSE_OR_TRANSFER, DeviceAction.TRANSFER -> perms.canDisburse
                        DeviceAction.EDIT_OR_DELETE -> perms.canEditDelete
                        DeviceAction.ADJUST_STOCK, DeviceAction.EDIT_INVENTORY, DeviceAction.EDIT_SERIALS -> perms.canEditInventory
                        DeviceAction.DELETE_ITEM -> perms.canDeleteItem
                        DeviceAction.EDIT_ISSUE_VOUCHER -> perms.canEditIssueVoucher
                        DeviceAction.DELETE_ISSUE_VOUCHER -> perms.canDeleteIssueVoucher
                        DeviceAction.EDIT_SUPPLY_VOUCHER -> perms.canEditSupplyVoucher
                        DeviceAction.DELETE_SUPPLY_VOUCHER -> perms.canDeleteSupplyVoucher
                        DeviceAction.VIEW_REPORTS -> true
                        DeviceAction.BACKUP_RESTORE -> perms.canBackup
                        DeviceAction.ADD_OR_MANAGE_CATALOG -> perms.canAddItems
                        DeviceAction.EDIT_CATALOG_ITEMS -> false
                    }
                } else when (action) {
                    DeviceAction.ADD_OR_MANAGE_CATALOG -> perms.canAddItems && grant.canView
                    DeviceAction.EDIT_CATALOG_ITEMS -> false
                    DeviceAction.INWARD -> grant.canView && grant.canInward
                    DeviceAction.DISBURSEMENT, DeviceAction.DISBURSE_OR_TRANSFER -> grant.canView && grant.canDisburse
                    DeviceAction.TRANSFER -> grant.canView && grant.canTransfer
                    DeviceAction.ADJUST_STOCK -> grant.canView && grant.canAdjust
                    DeviceAction.EDIT_OR_DELETE -> grant.canView && grant.canEdit
                    DeviceAction.EDIT_INVENTORY, DeviceAction.EDIT_SERIALS -> grant.canView && grant.canEditInventory
                    DeviceAction.DELETE_ITEM -> perms.canDeleteItem && grant.canView && grant.canDeleteItem
                    DeviceAction.EDIT_ISSUE_VOUCHER -> grant.canView && grant.canEdit && perms.canEditIssueVoucher
                    DeviceAction.DELETE_ISSUE_VOUCHER -> grant.canView && perms.canDeleteIssueVoucher
                    DeviceAction.EDIT_SUPPLY_VOUCHER -> grant.canView && grant.canEdit && perms.canEditSupplyVoucher
                    DeviceAction.DELETE_SUPPLY_VOUCHER -> grant.canView && perms.canDeleteSupplyVoucher
                    DeviceAction.VIEW_REPORTS -> grant.canView && grant.canReports
                    DeviceAction.BACKUP_RESTORE -> perms.canBackup
                }
            }
        } else when (action) {
            DeviceAction.ADD_OR_MANAGE_CATALOG -> perms.canAddItems
            DeviceAction.EDIT_CATALOG_ITEMS -> perms.canEditCatalogItems
            DeviceAction.INWARD -> perms.canInward || perms.warehousePermissions.any { it.canView && it.canInward }
            DeviceAction.DISBURSEMENT, DeviceAction.DISBURSE_OR_TRANSFER -> perms.canDisburse || perms.warehousePermissions.any { it.canView && it.canDisburse }
            DeviceAction.TRANSFER -> perms.canDisburse || perms.warehousePermissions.any { it.canView && it.canTransfer }
            DeviceAction.EDIT_OR_DELETE -> perms.canEditDelete
            // Inventory and serial edits without an explicit warehouse are allowed only for
            // legacy configurations; current UI always supplies the affected warehouse IDs.
            DeviceAction.EDIT_INVENTORY, DeviceAction.EDIT_SERIALS, DeviceAction.ADJUST_STOCK -> legacyGlobalGrant && perms.canEditInventory
            DeviceAction.DELETE_ITEM -> perms.canDeleteItem
            DeviceAction.EDIT_ISSUE_VOUCHER -> perms.canEditIssueVoucher
            DeviceAction.DELETE_ISSUE_VOUCHER -> perms.canDeleteIssueVoucher
            DeviceAction.EDIT_SUPPLY_VOUCHER -> perms.canEditSupplyVoucher
            DeviceAction.DELETE_SUPPLY_VOUCHER -> perms.canDeleteSupplyVoucher
            DeviceAction.VIEW_REPORTS -> legacyGlobalGrant || perms.warehousePermissions.any { it.canView && it.canReports }
            DeviceAction.BACKUP_RESTORE -> perms.canBackup
        }
        if (allowed) onAllowed() else onDenied()
    }

    fun checkCanEdit(onSubDeviceWarning: () -> Unit, onAllowed: () -> Unit) {
        checkPermission(DeviceAction.EDIT_OR_DELETE, onSubDeviceWarning, onAllowed = onAllowed)
    }

    private fun runAuthorizedAction(
        action: DeviceAction,
        warehouseIds: Set<Long> = emptySet(),
        deniedMessage: String = "لا تملك صلاحية تنفيذ هذه العملية",
        operation: suspend () -> Unit
    ) {
        checkPermission(
            action = action,
            warehouseIds = warehouseIds,
            onDenied = { viewModelScope.launch { _userMessage.emit(deniedMessage) } }
        ) {
            viewModelScope.launch { operation() }
        }
    }

    // Multi-Device Setup & Controls
    fun switchToMainDeviceMode() {
        viewModelScope.launch {
            _pairingError.value = null
            _lastSyncError.value = null
            subSyncJob?.cancel()
            subSyncJob = null
            val hasActivePairing = preferences.subSessionTokenFlow.first().isNotBlank()
            if (hasActivePairing) {
                repository.clearAllUserData(preserveAuditLogs = false)
            }
            preferences.clearSubPairingAndSwitchToMain()
            _userMessage.emit(
                if (hasActivePairing) "تم فك الارتباط ومسح بيانات المستخدم من التطبيق والعودة إلى الوضع الرئيسي"
                else "تم التبديل إلى وضع الجهاز الرئيسي وتشغيل خادم الربط المحلي"
            )
        }
    }

    fun switchToSubDeviceDraftMode() {
        viewModelScope.launch {
            // A main device with any registered device history must remain a main device.
            if (preferences.getLinkedSubDevicesSnapshot().isNotEmpty()) {
                _userMessage.emit("لا يمكن تحويل الجهاز الرئيسي إلى جهاز فرعي ما دامت لديه أجهزة فرعية مسجلة")
                return@launch
            }
            _pairingError.value = null
            _lastSyncError.value = null
            preferences.setDeviceRole(DeviceRole.SUB)
        }
    }

    fun refreshMainServerInfo() {
        viewModelScope.launch {
            runCatching {
                cloudSyncEngine.ensureMainWorkspace()
                cloudSyncEngine.syncOnce()
            }.onSuccess {
                _lastSyncError.value = null
                _userMessage.emit("مساحة العمل السحابية جاهزة للربط عبر الإنترنت")
            }.onFailure {
                _lastSyncError.value = it.localizedMessage ?: "تعذر الاتصال بمساحة العمل السحابية"
                _userMessage.emit(_lastSyncError.value ?: "تعذر تحديث حالة الربط")
            }
        }
    }

    fun regenerateWorkspaceCode() {
        viewModelScope.launch {
            val existingDevices = preferences.getLinkedSubDevicesSnapshot()
            if (existingDevices.isNotEmpty()) {
                _userMessage.emit("لا يمكن تغيير رمز مساحة العمل بعد تسجيل الأجهزة؛ أنشئ دعوة جديدة لكل جهاز عند الحاجة.")
                return@launch
            }
            val current = preferences.workspaceCodeFlow.first()
            if (current.isNotBlank()) {
                _userMessage.emit("رمز مساحة العمل ثابت للحفاظ على ارتباط البيانات السحابية: $current")
            } else {
                val created = preferences.ensureWorkspaceCode()
                runCatching { cloudSyncEngine.ensureMainWorkspace() }
                _userMessage.emit("تم إنشاء مساحة العمل السحابية: $created")
            }
        }
    }

    fun createSubDeviceSlot(
        deviceName: String,
        canAddItems: Boolean,
        canInward: Boolean,
        canDisburse: Boolean,
        canEditDelete: Boolean,
        canBackup: Boolean = false,
        canViewAllWarehouses: Boolean = false,
        warehousePermissions: List<WarehousePermission> = emptyList(),
        canEditInventory: Boolean = canEditDelete,
        canDeleteItem: Boolean = canEditDelete,
        canEditIssueVoucher: Boolean = canEditDelete,
        canDeleteIssueVoucher: Boolean = canEditDelete,
        canEditSupplyVoucher: Boolean = canEditDelete,
        canDeleteSupplyVoucher: Boolean = canEditDelete,
        canEditCatalogItems: Boolean = false
    ) {
        viewModelScope.launch {
            val cleanName = deviceName.trim().ifBlank { "جهاز فرعي #${linkedSubDevices.value.size + 1}" }
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            if (current.any { it.deviceName.trim().equals(cleanName, ignoreCase = true) }) {
                _userMessage.emit("اسم الجهاز ($cleanName) مستخدم بالفعل. اختر اسماً مختلفاً.")
                return@launch
            }

            val plainPassword = SecurityUtils.generateUniqueDevicePassword(10)
            val salt = SecurityUtils.generateSalt()
            val passwordHash = SecurityUtils.hashPassword(plainPassword, salt)
            val newSlot = LinkedSubDevice(
                id = UUID.randomUUID().toString(),
                deviceName = cleanName,
                passwordSalt = salt,
                passwordHash = passwordHash,
                sessionToken = UUID.randomUUID().toString(),
                boundInstallationId = "",
                isClaimed = false,
                isKicked = false,
                canAddItems = canAddItems,
                canInward = canInward,
                canDisburse = canDisburse,
                canEditDelete = canEditDelete,
                canEditInventory = canEditInventory,
                canDeleteItem = canDeleteItem,
                canEditIssueVoucher = canEditIssueVoucher,
                canDeleteIssueVoucher = canDeleteIssueVoucher,
                canEditSupplyVoucher = canEditSupplyVoucher,
                canDeleteSupplyVoucher = canDeleteSupplyVoucher,
                canEditCatalogItems = canEditCatalogItems,
                canBackup = canBackup,
                canViewAllWarehouses = canViewAllWarehouses,
                warehousePermissions = warehousePermissions,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )

            try {
                cloudSyncEngine.createPairingInvite(newSlot, plainPassword)
                current.add(0, newSlot)
                preferences.saveLinkedSubDevices(current)
                val code = preferences.ensureWorkspaceCode()
                val creds = CreatedDeviceCredentials(
                    deviceSlotId = newSlot.id,
                    deviceName = newSlot.deviceName,
                    workspaceCode = code,
                    hostAddress = "CLOUD",
                    plainPassword = plainPassword
                )
                _sessionPairingCredentials.value = _sessionPairingCredentials.value + (newSlot.id to creds)
                _createdDeviceCredentials.value = creds
                _userMessage.emit("تم إنشاء دعوة ربط سحابية صالحة لمدة 30 دقيقة للجهاز ($cleanName)")
            } catch (error: Exception) {
                _pairingError.value = error.localizedMessage
                    ?: "تعذر إنشاء دعوة الربط السحابية. تحقق من تسجيل الدخول والإنترنت وقواعد Firestore."
                _userMessage.emit(_pairingError.value ?: "تعذر إنشاء دعوة الربط")
            }
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
        canEditDelete: Boolean,
        canBackup: Boolean = false,
        canViewAllWarehouses: Boolean = false,
        warehousePermissions: List<WarehousePermission> = emptyList(),
        canEditInventory: Boolean = canEditDelete,
        canDeleteItem: Boolean = canEditDelete,
        canEditIssueVoucher: Boolean = canEditDelete,
        canDeleteIssueVoucher: Boolean = canEditDelete,
        canEditSupplyVoucher: Boolean = canEditDelete,
        canDeleteSupplyVoucher: Boolean = canEditDelete,
        canEditCatalogItems: Boolean = false
    ) {
        viewModelScope.launch {
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val idx = current.indexOfFirst { it.id == slotId }
            if (idx >= 0) {
                val updated = current[idx].copy(
                    canAddItems = canAddItems,
                    canInward = canInward,
                    canDisburse = canDisburse,
                    canEditDelete = canEditDelete,
                    canEditInventory = canEditInventory,
                    canDeleteItem = canDeleteItem,
                    canEditIssueVoucher = canEditIssueVoucher,
                    canDeleteIssueVoucher = canDeleteIssueVoucher,
                    canEditSupplyVoucher = canEditSupplyVoucher,
                    canDeleteSupplyVoucher = canDeleteSupplyVoucher,
                    canEditCatalogItems = canEditCatalogItems,
                    canBackup = canBackup,
                    canViewAllWarehouses = canViewAllWarehouses,
                    warehousePermissions = warehousePermissions,
                    updatedAt = System.currentTimeMillis()
                )
                current[idx] = updated
                preferences.saveLinkedSubDevices(current)
                runCatching { cloudSyncEngine.updatePermissions(slotId, updated.toPermissions()) }
                    .onFailure { _lastSyncError.value = it.localizedMessage ?: "تعذر إرسال الصلاحيات إلى Firestore" }
                preferences.incrementSyncRevision()
                _userMessage.emit("تم تحديث صلاحيات ${updated.deviceName} سحابياً")
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
                    sessionToken = UUID.randomUUID().toString(),
                    updatedAt = System.currentTimeMillis()
                )
                preferences.saveLinkedSubDevices(current)
                runCatching { cloudSyncEngine.setDeviceKicked(slotId, true) }
                    .onFailure { _lastSyncError.value = it.localizedMessage ?: "تعذر نشر أمر الطرد سحابياً" }
                preferences.incrementSyncRevision()
                repository.recordOperation(OperationAuditLog(
                    actorName = userName.value.ifBlank { "المسؤول" },
                    deviceName = "الجهاز الرئيسي",
                    operationType = "KICK_DEVICE",
                    title = "طرد جهاز فرعي",
                    details = "تم إيقاف الجهاز ($targetName) وإلغاء عضويته السحابية"
                ))
                notificationManager.notifyOperation("طرد جهاز فرعي", "تم إيقاف الجهاز ($targetName)", false)
                _userMessage.emit("تم إرسال أمر إيقاف ($targetName) إلى Firestore")
            }
        }
    }

    fun reactivateSubDevice(slotId: String) {
        viewModelScope.launch {
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val idx = current.indexOfFirst { it.id == slotId }
            if (idx >= 0) {
                val previous = current[idx]
                current[idx] = previous.copy(
                    isKicked = false,
                    isClaimed = false,
                    boundInstallationId = "",
                    sessionToken = UUID.randomUUID().toString(),
                    updatedAt = System.currentTimeMillis()
                )
                preferences.saveLinkedSubDevices(current)
                runCatching { cloudSyncEngine.setDeviceKicked(slotId, false) }
                    .onFailure { _lastSyncError.value = it.localizedMessage ?: "تعذر إعادة تفعيل الجهاز سحابياً" }
                preferences.incrementSyncRevision()
                _userMessage.emit("تمت إعادة تفعيل عضوية ${previous.deviceName} سحابياً. أعد الربط من الجهاز الفرعي.")
            }
        }
    }

    fun removeSubDeviceSlot(slotId: String) {
        viewModelScope.launch {
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val removed = current.firstOrNull { it.id == slotId }
            if (removed != null) {
                runCatching { cloudSyncEngine.removeDeviceSlot(slotId) }
                    .onFailure { _lastSyncError.value = it.localizedMessage ?: "تعذر إلغاء العضوية السحابية" }
                current.removeAll { it.id == slotId }
                preferences.saveLinkedSubDevices(current)
                preferences.incrementSyncRevision()
                _userMessage.emit("تم إلغاء ارتباط الجهاز (${removed.deviceName}) في السحابة")
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

            val cleanCode = workspaceCodeInput.trim().uppercase()
            val cleanPwd = passwordInput.trim()
            if (cleanCode.isBlank() || cleanPwd.isBlank()) {
                _pairingError.value = "أدخل رمز مساحة العمل وكلمة المرور الممنوحة من الجهاز الرئيسي"
                return@launch
            }

            _isPairingInProgress.value = true
            _pairingError.value = null
            try {
                val installId = preferences.getOrCreateInstallationId()
                val paired = cloudSyncEngine.joinWithInvite(cleanCode, cleanPwd, installId)
                preferences.resetPairingFailures()
                preferences.savePairedSubDevice(
                    hostUrl = "cloud://${paired.workspaceId}",
                    workspaceCode = paired.workspaceId,
                    deviceSlotId = paired.deviceSlotId,
                    deviceName = paired.deviceName,
                    sessionToken = "FIRESTORE",
                    permissions = paired.permissions,
                    appliedRevision = 1L
                )
                cloudSyncEngine.initializeNewSubDevice(paired.workspaceId)
                CloudSyncWorker.requestImmediate(getApplication())
                _lastSyncError.value = null
                _userMessage.emit("تم ربط الجهاز الفرعي (${paired.deviceName}) عبر الإنترنت ومزامنة بياناته")
            } catch (error: Exception) {
                val (attempts, lockTime) = preferences.recordPairingFailure()
                _pairingError.value = if (lockTime > 0L) {
                    "تم تجاوز 5 محاولات خاطئة. تم إيقاف المحاولات لمدة 60 ثانية."
                } else {
                    (error.localizedMessage ?: "تعذر إتمام الربط السحابي") + " (محاولة $attempts من 5)"
                }
                runCatching { preferences.clearSubPairingAndSwitchToMain() }
            } finally {
                _isPairingInProgress.value = false
            }
        }
    }

    fun deleteOperationLogs(ids: List<Long>) {
        viewModelScope.launch {
            repository.deleteOperationLogs(ids)
            _userMessage.emit("تم حذف ${ids.size} عملية من سجل العمليات")
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _isSyncingNow.value = true
            runCatching {
                cloudSyncEngine.syncOnce()
            }.onSuccess { result ->
                _lastSyncError.value = null
                preferences.updateLastSync(preferences.lastAppliedRevisionFlow.first() + 1L)
                _userMessage.emit("اكتملت المزامنة السحابية: رفع ${result.uploaded} وتحديث ${result.downloaded} سجل")
            }.onFailure { error ->
                _lastSyncError.value = error.localizedMessage ?: "تعذرت المزامنة السحابية"
                _userMessage.emit(_lastSyncError.value ?: "تعذرت المزامنة")
            }
            _isSyncingNow.value = false
        }
    }

    /** Refresh remote records and permissions when the user navigates between screens. */
    fun syncOnNavigation() {
        if (subSessionToken.value.isBlank() && !isMainDevice.value) return
        viewModelScope.launch { pullFromMainOnce(silent = true) }
    }

    

    private suspend fun pullFromMainOnce(silent: Boolean) = subSyncMutex.withLock {
        if (!silent) _isSyncingNow.value = true
        try {
            cloudSyncEngine.syncOnce()
            _lastSyncError.value = null
            preferences.updateLastSync(preferences.lastAppliedRevisionFlow.first() + 1L)
            if (!silent) _userMessage.emit("تمت المزامنة السحابية بنجاح عبر الإنترنت")
        } catch (error: Exception) {
            val message = error.localizedMessage ?: "تعذر الوصول إلى Firestore"
            if (message.contains("إيقاف ارتباط") || message.contains("غير معتمد")) {
                handleSubDeviceKicked(message)
            } else {
                _lastSyncError.value = message
                if (!silent) _userMessage.emit("تعذرت المزامنة السحابية: $message")
            }
        } finally {
            if (!silent) _isSyncingNow.value = false
        }
    }

    private suspend fun handleSubDeviceKicked(reason: String) {
        subSyncJob?.cancel()
        subSyncJob = null
        // A kicked device must discard the previous account's complete local dataset,
        // including audit logs and private attachments, as soon as the revoke is received.
        repository.clearAllUserData(preserveAuditLogs = false)
        preferences.markSubDeviceKicked()
        _kickedAlertMessage.value = "$reason\nتم مسح البيانات المتزامنة من هذا الجهاز."
    }

    fun unlinkSubDevice() {
        viewModelScope.launch {
            subSyncJob?.cancel()
            subSyncJob = null
            val name = deviceName.value.ifBlank { "الجهاز الفرعي" }
            runCatching { cloudSyncEngine.unlinkCurrentDevice() }
                .onFailure { _lastSyncError.value = it.localizedMessage ?: "تعذر تسجيل فك الارتباط سحابياً" }
            repository.recordOperation(OperationAuditLog(
                actorName = userName.value.ifBlank { name },
                deviceName = name,
                operationType = "UNLINK",
                title = "فك ارتباط الجهاز الفرعي",
                details = "قام المستخدم بفك ارتباط هذا الجهاز من مساحة العمل السحابية"
            ))
            repository.clearAllUserData(preserveAuditLogs = false)
            preferences.clearSubPairingAndSwitchToMain()
            _lastSyncError.value = null
            _userMessage.emit("تم فك الارتباط السحابي ومسح بيانات الحساب المحلية")
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
        notificationDetails: String,
        warehouseIds: Set<Long> = emptySet(),
        targetItemIds: Set<Long> = emptySet(),
        targetVoucherIds: Set<Long> = emptySet()
    ) {
        viewModelScope.launch {
            repository.recordOperation(
                OperationAuditLog(
                    actorName = userName.value.ifBlank { "مستخدم التطبيق" },
                    deviceName = if (isMainDevice.value) "الجهاز الرئيسي" else deviceName.value.ifBlank { "الجهاز الفرعي" },
                    operationType = action?.name ?: "GENERAL",
                    title = notificationTitle,
                    details = notificationDetails
                )
            )
        }
        notificationManager.notifyOperation(
            title = notificationTitle,
            details = notificationDetails,
            isRemoteSubDeviceAlert = false
        )
        propagateDataChange(
            action = action,
            operationTitle = notificationTitle,
            operationDetails = notificationDetails,
            warehouseIds = warehouseIds,
            targetItemIds = targetItemIds,
            targetVoucherIds = targetVoucherIds
        )
    }

    private fun propagateDataChange(
        action: DeviceAction? = null,
        operationTitle: String = "",
        operationDetails: String = "",
        warehouseIds: Set<Long> = emptySet(),
        targetItemIds: Set<Long> = emptySet(),
        targetVoucherIds: Set<Long> = emptySet()
    ) {
        // Room is the durable offline copy. The sync worker compares it with the last
        // acknowledged cloud baseline and sends only changed records/tombstones.
        CloudSyncWorker.requestImmediate(getApplication())
        viewModelScope.launch {
            runCatching { cloudSyncEngine.syncOnce() }
                .onSuccess { _lastSyncError.value = null }
                .onFailure { error ->
                    _lastSyncError.value = "تم حفظ التغيير محلياً، وستتم مزامنته تلقائياً عند عودة الإنترنت: ${error.localizedMessage ?: "لا يوجد اتصال"}"
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
                runAuthorizedAction(
                    action = DeviceAction.EDIT_OR_DELETE,
                    warehouseIds = setOf(warehouse.id),
                    deniedMessage = "لا تملك صلاحية تعديل هذا المخزن"
                ) {
                    repository.updateWarehouse(warehouse)
                    _userMessage.emit("تم تحديث بيانات المخزن بنجاح")
                    notifyAndPropagate(
                        action = DeviceAction.EDIT_OR_DELETE,
                        notificationTitle = "تعديل بيانات مخزن",
                        notificationDetails = "تم تحديث بيانات المخزن (${warehouse.name})",
                        warehouseIds = setOf(warehouse.id)
                    )
                    onDone()
                }
                return@launch
            }
            onDone()
        }
    }

    suspend fun checkWarehouseHasStock(warehouseId: Long): Boolean {
        return repository.warehouseHasStock(warehouseId)
    }

    fun deleteWarehouse(warehouse: Warehouse) {
        runAuthorizedAction(
            action = DeviceAction.EDIT_OR_DELETE,
            warehouseIds = setOf(warehouse.id),
            deniedMessage = "لا تملك صلاحية حذف هذا المخزن"
        ) {
            repository.deleteWarehouse(warehouse)
            _userMessage.emit("تم حذف المخزن نهائياً")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "حذف مخزن",
                notificationDetails = "تم حذف المخزن (${warehouse.name}) من النظام",
                warehouseIds = setOf(warehouse.id)
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
        runAuthorizedAction(
            action = DeviceAction.EDIT_INVENTORY,
            warehouseIds = setOf(warehouseId),
            deniedMessage = "لا تملك صلاحية إضافة أصناف إلى هذا المخزن"
        ) {
            val warehouseName = warehouses.value.find { it.id == warehouseId }?.name ?: "مخزن"
            val itemName = items.value.find { it.id == itemId }?.name ?: "صنف"
            repository.addItemDirectlyToWarehouse(warehouseId, itemId, quantity, minStockAlert)
            _userMessage.emit("تمت إضافة الصنف داخل المخزن بنجاح")
            notifyAndPropagate(
                action = DeviceAction.EDIT_INVENTORY,
                notificationTitle = "إضافة صنف لمخزن",
                notificationDetails = "تم إضافة ($itemName) إلى ($warehouseName) بكمية ${quantity.toInt()}",
                warehouseIds = setOf(warehouseId),
                targetItemIds = setOf(itemId)
            )
            onDone()
        }
    }

    fun removeItemFromWarehouse(
        warehouseId: Long,
        itemId: Long,
        onDone: () -> Unit = {}
    ) {
        runAuthorizedAction(
            action = DeviceAction.EDIT_INVENTORY,
            warehouseIds = setOf(warehouseId),
            deniedMessage = "لا تملك صلاحية حذف الأصناف من هذا المخزن"
        ) {
            val warehouseName = warehouses.value.find { it.id == warehouseId }?.name ?: "مخزن"
            val itemName = items.value.find { it.id == itemId }?.name ?: "صنف"
            repository.removeItemFromWarehouse(warehouseId, itemId)
            _userMessage.emit("تم حذف الصنف نهائياً من هذا المخزن")
            notifyAndPropagate(
                action = DeviceAction.EDIT_INVENTORY,
                notificationTitle = "حذف صنف من مخزن",
                notificationDetails = "تم إزالة ($itemName) نهائياً من ($warehouseName)",
                warehouseIds = setOf(warehouseId),
                targetItemIds = setOf(itemId)
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
        runAuthorizedAction(
            action = DeviceAction.ADJUST_STOCK,
            warehouseIds = setOf(warehouseId),
            deniedMessage = "لا تملك صلاحية تعديل حدود المخزون في هذا المخزن"
        ) {
            val itemName = items.value.find { it.id == itemId }?.name ?: "صنف"
            repository.updateWarehouseItemMinStock(warehouseId, itemId, customMinStock)
            _userMessage.emit("تم تحديث حد إعادة الطلب الخاص بالمخزن")
            notifyAndPropagate(
                action = DeviceAction.ADJUST_STOCK,
                notificationTitle = "تحديث حد إعادة الطلب",
                notificationDetails = "تم تعديل حد الطلب للصنف ($itemName) إلى ${customMinStock.toInt()}",
                warehouseIds = setOf(warehouseId),
                targetItemIds = setOf(itemId)
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
                checkPermission(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
                    onDenied = { viewModelScope.launch { _userMessage.emit("لا تملك صلاحية تعديل بيانات الصنف داخل الدليل") } }
                ) {
                    viewModelScope.launch {
                        repository.updateItem(item)
                        _userMessage.emit("تم تحديث بيانات الصنف بنجاح")
                        notifyAndPropagate(
                            action = DeviceAction.EDIT_CATALOG_ITEMS,
                            notificationTitle = "تعديل بيانات صنف داخل الدليل",
                            notificationDetails = "تم تحديث بيانات الصنف (${item.name})",
                            targetItemIds = setOf(item.id)
                        )
                        onDone()
                    }
                }
                return@launch
            }
            onDone()
        }
    }

    suspend fun checkItemHasTransactions(itemId: Long): Boolean {
        return repository.itemHasTransactions(itemId)
    }

    fun deleteItem(item: Item) {
        val itemWarehouseIds = stockSnapshot.value?.warehouseItemStocks.orEmpty().filter { it.itemId == item.id }.map { it.warehouseId }.toSet()
        runAuthorizedAction(DeviceAction.DELETE_ITEM, warehouseIds = itemWarehouseIds, deniedMessage = "لا تملك صلاحية حذف هذا الصنف من المخازن المرتبطة به") {
            repository.deleteItem(item)
            _userMessage.emit("تم حذف الصنف نهائياً")
            notifyAndPropagate(
                action = DeviceAction.DELETE_ITEM,
                notificationTitle = "حذف صنف",
                notificationDetails = "تم حذف الصنف (${item.name}) من الدليل",
                warehouseIds = itemWarehouseIds,
                targetItemIds = setOf(item.id)
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
        runAuthorizedAction(
            action = DeviceAction.ADJUST_STOCK,
            warehouseIds = setOf(warehouseId),
            deniedMessage = "لا تملك صلاحية تسوية المخزون في هذا المخزن"
        ) {
            val warehouseName = warehouses.value.find { it.id == warehouseId }?.name ?: "مخزن"
            val itemName = items.value.find { it.id == itemId }?.name ?: "صنف"
            repository.adjustWarehouseStock(warehouseId, itemId, newQuantity, reasonNote)
            _userMessage.emit("تم تعديل كمية المخزون وتوثيق السبب بنجاح")
            notifyAndPropagate(
                action = DeviceAction.ADJUST_STOCK,
                notificationTitle = "تسوية وتعديل مخزون",
                notificationDetails = "تم تعديل رصيد ($itemName) في ($warehouseName) إلى ${newQuantity.toInt()} — السبب: $reasonNote",
                warehouseIds = setOf(warehouseId),
                targetItemIds = setOf(itemId)
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

    fun updateItemStatusSafely(
        existing: ItemStatus,
        newName: String,
        warehouseId: Long? = null,
        onResult: (Boolean) -> Unit = {}
    ) {
        runAuthorizedAction(
            action = DeviceAction.EDIT_OR_DELETE,
            warehouseIds = setOfNotNull(warehouseId),
            deniedMessage = "لا تملك صلاحية تعديل حالات الأصناف في هذا المخزن"
        ) {
            val ok = repository.updateItemStatusSafely(existing, newName)
            if (ok) {
                _userMessage.emit("تم تعديل الحالة بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "تعديل حالة صنف",
                    notificationDetails = "تم تعديل الحالة من (${existing.name}) إلى (${newName.trim()})",
                    warehouseIds = setOfNotNull(warehouseId)
                )
            } else {
                _userMessage.emit("لا يمكن تعديل الحالة (${existing.name}) لوجود عمليات أو وحدات مرقمة تستخدمها!")
            }
            onResult(ok)
        }
    }

    fun deleteItemStatusSafely(
        status: ItemStatus,
        warehouseId: Long? = null,
        onResult: (Boolean) -> Unit = {}
    ) {
        runAuthorizedAction(
            action = DeviceAction.EDIT_OR_DELETE,
            warehouseIds = setOfNotNull(warehouseId),
            deniedMessage = "لا تملك صلاحية حذف حالات الأصناف في هذا المخزن"
        ) {
            val ok = repository.deleteItemStatusSafely(status)
            if (ok) {
                _userMessage.emit("تم حذف الحالة (${status.name}) بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_OR_DELETE,
                    notificationTitle = "حذف حالة صنف",
                    notificationDetails = "تم حذف الحالة (${status.name})",
                    warehouseIds = setOfNotNull(warehouseId)
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
    suspend fun getNextVoucherNumber(type: VoucherType, warehouseId: Long? = null): String {
        return repository.generateNextVoucherNumber(type, warehouseId)
    }

    // 9. Voucher Operations
    fun saveVoucher(
        voucher: Voucher,
        items: List<VoucherItem>,
        onDone: () -> Unit = {}
    ) {
        val warehouseIds = setOfNotNull(voucher.sourceWarehouseId, voucher.destinationWarehouseId)
        val action = if (voucher.id != 0L) when (voucher.voucherType) {
            VoucherType.DISBURSEMENT, VoucherType.TRANSFER -> DeviceAction.EDIT_ISSUE_VOUCHER
            VoucherType.INWARD -> DeviceAction.EDIT_SUPPLY_VOUCHER
        } else when (voucher.voucherType) {
            VoucherType.INWARD -> DeviceAction.INWARD
            VoucherType.TRANSFER -> DeviceAction.TRANSFER
            VoucherType.DISBURSEMENT -> DeviceAction.DISBURSEMENT
        }
        runAuthorizedAction(
            action = action,
            warehouseIds = warehouseIds,
            deniedMessage = "لا تملك صلاحية تنفيذ هذه العملية في المخزن المحدد"
        ) {
            val numberingWarehouseId = when (voucher.voucherType) {
                VoucherType.INWARD -> voucher.destinationWarehouseId
                VoucherType.TRANSFER, VoucherType.DISBURSEMENT -> voucher.sourceWarehouseId
            }
            val savedVoucher = if (voucher.id == 0L) {
                voucher.copy(
                    voucherNumber = repository.generateNextVoucherNumber(voucher.voucherType, numberingWarehouseId),
                    createdBy = userName.value.ifBlank { "مستخدم التطبيق" },
                    deviceName = if (isMainDevice.value) "الجهاز الرئيسي" else deviceName.value.ifBlank { "الجهاز الفرعي" },
                    numberingWarehouseId = numberingWarehouseId
                )
            } else {
                voucher.copy(
                    createdBy = voucher.createdBy.ifBlank { userName.value.ifBlank { "مستخدم التطبيق" } },
                    deviceName = voucher.deviceName.ifBlank { if (isMainDevice.value) "الجهاز الرئيسي" else deviceName.value.ifBlank { "الجهاز الفرعي" } },
                    numberingWarehouseId = voucher.numberingWarehouseId ?: numberingWarehouseId
                )
            }
            repository.saveVoucherWithItems(savedVoucher, items)
            val actionName = when (voucher.voucherType) {
                VoucherType.INWARD -> "التوريد"
                VoucherType.TRANSFER -> "التحويل المخزني"
                VoucherType.DISBURSEMENT -> "أمر الصرف"
            }
            val totalQty = items.sumOf { it.quantity }.toInt()
            val itemSummary = items.take(2).joinToString("، ") { it.itemName }
            val notifTitle = if (voucher.id == 0L) {
                "عملية $actionName (${savedVoucher.voucherNumber})"
            } else {
                "تعديل سند $actionName (${savedVoucher.voucherNumber})"
            }
            val notifDetails = "الأصناف: $itemSummary • إجمالي الكمية: $totalQty"
            _userMessage.emit("تم حفظ $actionName بنجاح")
            notifyAndPropagate(
                action = action,
                notificationTitle = notifTitle,
                notificationDetails = notifDetails,
                warehouseIds = warehouseIds,
                targetVoucherIds = if (voucher.id != 0L) setOf(voucher.id) else emptySet()
            )
            onDone()
        }
    }

    fun deleteVoucher(voucherId: Long, warehouseIds: Set<Long> = emptySet(), voucherType: VoucherType = VoucherType.INWARD) {
        val deleteAction = when (voucherType) {
            VoucherType.INWARD -> DeviceAction.DELETE_SUPPLY_VOUCHER
            VoucherType.DISBURSEMENT, VoucherType.TRANSFER -> DeviceAction.DELETE_ISSUE_VOUCHER
        }
        runAuthorizedAction(
            action = deleteAction,
            warehouseIds = warehouseIds,
            deniedMessage = "لا تملك صلاحية حذف السند في هذا المخزن"
        ) {
            repository.deleteVoucher(voucherId)
            _userMessage.emit("تم حذف السند بنجاح")
            notifyAndPropagate(
                action = deleteAction,
                notificationTitle = "حذف سند مخزني",
                notificationDetails = "تم حذف السند وعكس أرصدة المخزون المرتبطة به",
                warehouseIds = warehouseIds,
                targetVoucherIds = setOf(voucherId)
            )
        }
    }

    fun removeAttachment(voucherId: Long, warehouseIds: Set<Long> = emptySet()) {
        runAuthorizedAction(
            action = DeviceAction.EDIT_OR_DELETE,
            warehouseIds = warehouseIds,
            deniedMessage = "لا تملك صلاحية حذف المرفقات في هذا المخزن"
        ) {
            repository.removeVoucherAttachment(voucherId)
            _userMessage.emit("تم حذف المرفق بنجاح")
            notifyAndPropagate(
                action = DeviceAction.EDIT_OR_DELETE,
                notificationTitle = "حذف مرفق سند",
                notificationDetails = "تمت إزالة الملف المرفق من السند",
                warehouseIds = warehouseIds
            )
        }
    }

    // 10. Local Backups, External Reinstall Recovery & Database/Network Diagnostics
    private fun mayUseBackups(): Boolean = devicePermissions.value.isMainDevice || devicePermissions.value.canBackup

    private fun emitBackupPermissionDenied() {
        viewModelScope.launch { _userMessage.emit("صلاحية النسخ الاحتياطي والتصدير غير ممنوحة من المستخدم الرئيسي") }
    }

    fun createBackup(name: String) {
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
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
        check(mayUseBackups()) { "صلاحية تصدير JSON غير ممنوحة من المستخدم الرئيسي" }
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        val record = repository.createBackup("تصدير_$dateStr", isCloud = false, accountEmail = "")
        return record.backupJson
    }

    fun importBackupJsonFromFile(rawJson: String) {
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
        viewModelScope.launch {
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري استرداد البيانات من الملف..."
            try {
                val ok = repository.restoreBackupReplacingExisting(rawJson)
                if (ok) {
                    notifyAndPropagate(
                        action = DeviceAction.BACKUP_RESTORE,
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
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
        viewModelScope.launch {
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري استعادة النسخة المحلية..."
            try {
                val ok = repository.restoreBackupReplacingExisting(record.backupJson)
                if (ok) {
                    notifyAndPropagate(
                        action = DeviceAction.BACKUP_RESTORE,
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
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
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
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
        viewModelScope.launch {
            repository.deleteBackup(record)
            notificationManager.notifyOperation(
                title = "حذف نسخة احتياطية محلية",
                details = "تم حذف النسخة (${record.name})"
            )
            _userMessage.emit("تم حذف النسخة الاحتياطية")
        }
    }

    // 10b. Cloud Backup & Restore via Phone Google Account + Google Drive
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
                val needsFreshGoogleAuthorization = err is GoogleApiHttpException && (
                    err.statusCode == 401 ||
                        (err.statusCode == 403 && (
                            err.message.contains("scope", ignoreCase = true) ||
                                err.message.contains("permission", ignoreCase = true)
                            ))
                    )
                if (needsFreshGoogleAuthorization) {
                    // A 403 caused by a missing Drive scope can come from an older cached token.
                    // Invalidate it, then request the same least-privilege drive.appdata scope again.
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
            _cloudAuthError.value = "تم إلغاء منح صلاحية الوصول إلى Google Drive."
            return
        }
        when (action) {
            is PendingPostConsentAction.CreateBackup -> createCloudBackup(context, action.backupName)
            is PendingPostConsentAction.ListBackups -> refreshCloudBackupsFromDrive(context, action.silent)
            is PendingPostConsentAction.RenameBackup -> renameCloudBackup(context, action.item, action.newName)
            is PendingPostConsentAction.RestoreBackup -> restoreCloudBackup(context, action.item)
            is PendingPostConsentAction.DeleteBackup -> deleteCloudBackup(context, action.item)
            null -> Unit
        }
    }

    /** Link the selected on-device Google account directly. Google OAuth consent is the authorization step;
     * no email message, verification code, Gmail read access, or Gmail send access is involved.
     */
    fun linkGoogleDriveAccount(context: Context, pickedEmail: String) {
        val cleanEmail = pickedEmail.trim()
        if (cleanEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
            viewModelScope.launch {
                _userMessage.emit("تعذر اختيار حساب Google صالح. اختر حساباً صحيحاً من الهاتف.")
            }
            return
        }
        viewModelScope.launch {
            preferences.rememberSavedCloudEmail(cleanEmail)
            preferences.setVerifiedGoogleEmail(cleanEmail)
            _cloudAuthError.value = null
            _userMessage.emit("تم اختيار حساب Google. وافق على صلاحية مساحة النسخ الاحتياطي عند ظهور نافذة Google.")
            refreshCloudBackupsFromDrive(context, silent = false)
        }
    }

    fun disconnectGoogleAccount() {
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
        if (!mayUseBackups()) { if (!silent) emitBackupPermissionDenied(); return }
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
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
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
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
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
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
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
                            action = DeviceAction.BACKUP_RESTORE,
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
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
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

    private suspend fun findChangedSerialRecords(serials: List<ItemUnitSerialRecord>): List<ItemUnitSerialRecord> {
        if (serials.isEmpty()) return emptyList()
        val existingByWarehouseItem = serials.map { it.warehouseId to it.itemId }.distinct().associateWith { key ->
            repository.getSerialsList(key.first, key.second)
        }
        return serials.filter { candidate ->
            val oldRows = existingByWarehouseItem[candidate.warehouseId to candidate.itemId].orEmpty()
            val old = if (candidate.id > 0L) oldRows.firstOrNull { it.id == candidate.id }
                else oldRows.firstOrNull { it.unitIndex == candidate.unitIndex }
            old == null || old.serialNumber != candidate.serialNumber ||
                old.status != candidate.status || old.notes != candidate.notes
        }
    }

    suspend fun saveSerialsAndGet(serials: List<ItemUnitSerialRecord>): List<ItemUnitSerialRecord> {
        val changed = findChangedSerialRecords(serials)
        val saved = repository.saveSerialsAndReturn(serials)
        if (changed.isNotEmpty()) {
            notifyAndPropagate(
                action = DeviceAction.EDIT_SERIALS,
                notificationTitle = "تحديث الأرقام التسلسلية والحالات والملاحظات",
                notificationDetails = buildSerialAuditDetails(changed, "تم تعديل ${changed.size} وحدة فقط"),
                warehouseIds = changed.map { it.warehouseId }.toSet(),
                targetItemIds = changed.map { it.itemId }.toSet()
            )
        }
        return saved
    }

    fun saveSerials(serials: List<ItemUnitSerialRecord>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val changed = findChangedSerialRecords(serials)
            val saved = repository.saveSerialsAndReturn(serials)
            _userMessage.emit(if (changed.isEmpty()) "لا توجد تغييرات جديدة لحفظها" else "تم حفظ الأرقام المميزة والحالات بنجاح")
            if (changed.isNotEmpty()) {
                notifyAndPropagate(
                    action = DeviceAction.EDIT_SERIALS,
                    notificationTitle = "تحديث الأرقام التسلسلية والحالات والملاحظات",
                    notificationDetails = buildSerialAuditDetails(changed, "تم تعديل ${changed.size} وحدة فقط"),
                    warehouseIds = changed.map { it.warehouseId }.toSet(),
                    targetItemIds = changed.map { it.itemId }.toSet()
                )
            }
            onDone()
        }
    }

    private fun buildSerialAuditDetails(serials: List<ItemUnitSerialRecord>, prefix: String): String {
        val grouped = serials.groupBy { it.itemId to it.warehouseId }
        val sections = grouped.map { (key, rows) ->
            val itemName = items.value.firstOrNull { it.id == key.first }?.name ?: "صنف رقم ${key.first}"
            val warehouseName = warehouses.value.firstOrNull { it.id == key.second }?.name ?: "مخزن رقم ${key.second}"
            val entries = rows.joinToString("، ") { row ->
                "${row.serialNumber.ifBlank { "وحدة ${row.unitIndex}" }} [${row.status}]" + if (row.notes.isBlank()) "" else " (${row.notes})"
            }
            "رقم الصنف: ${key.first} | الصنف: $itemName | المخزن: $warehouseName | الأرقام/الحالات: $entries"
        }
        return (listOf(prefix) + sections).joinToString("\n")
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
    DISBURSEMENT,
    TRANSFER,
    EDIT_OR_DELETE,
    EDIT_CATALOG_ITEMS,
    EDIT_INVENTORY,
    EDIT_SERIALS,
    DELETE_ITEM,
    EDIT_ISSUE_VOUCHER,
    DELETE_ISSUE_VOUCHER,
    EDIT_SUPPLY_VOUCHER,
    DELETE_SUPPLY_VOUCHER,
    ADJUST_STOCK,
    VIEW_REPORTS,
    BACKUP_RESTORE
}

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
