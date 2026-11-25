package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.InventoryApp
import com.example.data.cloud.CloudBackupItem
import com.example.data.cloud.CloudCollaborationRepository
import com.example.data.cloud.CloudOperationAuditEntry
import com.example.data.cloud.CloudUserNotification
import com.example.data.cloud.CreatedDeviceCredentials
import com.example.data.cloud.DevicePermissions
import com.example.data.cloud.DeviceRole
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
import com.example.data.model.PendingSyncOperation
import com.example.data.model.CloudPendingSyncOperation
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
import com.example.data.sync.LocalSyncJson
import com.example.data.sync.LocalNetworkSyncServer
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as InventoryApp
    private val repository = app.repository
    private val preferences = app.preferencesManager
    private val cloudBackupRepo = app.cloudBackupRepository
    private val cloudCollaborationRepo = CloudCollaborationRepository()
    private val notificationManager = AppNotificationManager(application)

    private val authorizedActionRunning = AtomicBoolean(false)
    private val _isOperationRunning = MutableStateFlow(false)
    val isOperationRunning: StateFlow<Boolean> = _isOperationRunning.asStateFlow()

    private val syncClient = LocalNetworkSyncClient()

    private val syncServer = LocalNetworkSyncServer(
        scope = viewModelScope,
        getWorkspaceCode = { preferences.ensureWorkspaceCode() },
        getOrgName = { preferences.orgNameFlow.first() },
        getSyncVersion = { preferences.syncRevisionFlow.first() },
        getLinkedDevices = { preferences.getLinkedSubDevicesSnapshot() },
        onSaveLinkedDevices = { devices ->
            val previousById = preferences.getLinkedSubDevicesSnapshot().associateBy { it.id }
            val newlyBoundDevices = devices.filter { slot ->
                slot.boundInstallationId.isNotBlank() &&
                    previousById[slot.id]?.boundInstallationId != slot.boundInstallationId
            }
            preferences.saveLinkedSubDevices(devices)
            newlyBoundDevices.forEach { newlyBound ->
                viewModelScope.launch {
                    val workspaceId = preferences.cloudWorkspaceIdFlow.first()
                    if (workspaceId.isNotBlank()) {
                        runCatching {
                            cloudCollaborationRepo.syncLinkedDevicePermissionsToCloud(
                                workspaceId,
                                newlyBound
                            )
                        }
                    }
                }
            }
            val newlyUnlinkedDevices = devices.filter { slot ->
                slot.wasUnlinked && previousById[slot.id]?.wasUnlinked != true
            }
            newlyUnlinkedDevices.forEach { unlinked ->
                val oldFingerprint = previousById[unlinked.id]?.boundInstallationId.orEmpty()
                if (oldFingerprint.isNotBlank()) {
                    viewModelScope.launch {
                        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
                        if (workspaceId.isNotBlank()) {
                            runCatching {
                                cloudCollaborationRepo.clearOfflineWarehouseDesignationsForDevice(
                                    workspaceId,
                                    oldFingerprint
                                )
                            }
                        }
                    }
                }
            }
        },
        onExportEntities = { repository.exportAllEntitiesForCloud() },
        onExportAuditLogs = { deviceName -> repository.getOperationLogsForSyncToDevice(deviceName) },
        onApplyRemoteEntities = { records ->
            // Do not acknowledge a sub-device operation or advance the shared revision
            // until Room has committed the merged snapshot successfully.
            repository.replaceDatabaseFromCloudEntitiesSafely(records).getOrThrow()
            preferences.incrementSyncRevision()
        },
        hasAppliedSyncOperation = { operationId -> repository.hasAppliedSyncOperation(operationId) },
        onMarkSyncOperationApplied = { operationId -> repository.markSyncOperationApplied(operationId) },
        onRemoteOperationAlert = { subDeviceName, actorName, actionType, operationTitle, operationDetails ->
            // Persist the incoming operation in history before the server acknowledges it.
            repository.recordOperation(
                OperationAuditLog(
                    actorName = actorName.ifBlank { subDeviceName },
                    deviceName = subDeviceName,
                    operationType = actionType.ifBlank { "GENERAL" },
                    title = operationTitle,
                    details = operationDetails
                )
            )
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
    private var cloudOutboxJob: Job? = null
    private val subSyncMutex = Mutex()
    private val pendingPushMutex = Mutex()
    private val cloudPushMutex = Mutex()

    private val cloudAuthorizationEnabled = MutableStateFlow(false)

    init {
        viewModelScope.launch {
            combine(
                preferences.cloudWorkspaceIdFlow,
                preferences.cloudDeviceIdFlow
            ) { workspaceId, deviceId ->
                workspaceId.isNotBlank() && deviceId.isNotBlank()
            }.collect { cloudAuthorizationEnabled.value = it }
        }
        startMultiDeviceCoordinator()
        startCloudOutboxCoordinator()
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

                    val code = preferences.ensureWorkspaceCode()
                    val port = syncServer.start(LocalNetworkSyncServer.DEFAULT_PORT)
                } else {
                    syncServer.stop()
                    if (state.subToken.isBlank() || state.permissions.isKicked) {
                        subSyncJob?.cancel()
                        subSyncJob = null
                    } else {
                        if (subSyncJob?.isActive != true) {
                            subSyncJob = launch {
                                while (isActive) {
                                    try {
                                        pullFromMainOnce(silent = true)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        // A failed background sync must never terminate the app process.
                                        _lastSyncError.value = "تعذر تحديث بيانات الجهاز حالياً"
                                    }
                                    delay(3000L)
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
        cloudOutboxJob?.cancel()
        syncServer.stop()
    }

    private fun linkedSubDevicesForWarehouse(warehouseId: Long): List<LinkedSubDevice> =
        linkedSubDevices.value.filter { slot ->
            !slot.isKicked && !slot.wasUnlinked &&
                (slot.warehousePermissions.any { it.warehouseId == warehouseId && it.canView } ||
                    (slot.canViewAllWarehouses && slot.warehousePermissions.isEmpty()))
        }

    private fun isAssignedSubDeviceOnline(warehouseId: Long): Boolean {
        val now = System.currentTimeMillis()
        return linkedSubDevicesForWarehouse(warehouseId).any { slot ->
            slot.isClaimed && now - slot.lastSeenAt in 0L..15_000L
        }
    }

    private fun hasValidatedInternetConnection(): Boolean {
        val connectivity = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = connectivity.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
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
            val assignedWarehouses = warehouseIds.filter { linkedSubDevicesForWarehouse(it).isNotEmpty() }
            val offlineDesignatedDeviceActive = assignedWarehouses.any { warehouseId ->
                val now = System.currentTimeMillis()
                linkedSubDevicesForWarehouse(warehouseId).any { slot ->
                    slot.warehousePermissions.any {
                        it.warehouseId == warehouseId && it.canView && it.canWorkOffline
                    } && slot.isClaimed && now - slot.lastSeenAt in 0L..15_000L
                }
            }
            val assignedWarehouseUnavailable = assignedWarehouses.isNotEmpty() &&
                (!hasValidatedInternetConnection() ||
                    assignedWarehouses.any { !isAssignedSubDeviceOnline(it) } ||
                    offlineDesignatedDeviceActive)
            if (assignedWarehouseUnavailable) {
                viewModelScope.launch {
                    _userMessage.emit(
                        if (offlineDesignatedDeviceActive) {
                            "هذا المخزن مخصص لجهاز فرعي محدد للعمل دون اتصال؛ لا يمكن تنفيذ عمليات متزامنة من الجهاز الرئيسي."
                        } else {
                            "لا يمكن تنفيذ العملية على هذا المخزن إلا عند توفر إنترنت موثّق وظهور الجهاز الفرعي المرتبط به متصلاً."
                        }
                    )
                }
                onDenied()
                return
            }
            onAllowed()
            return
        }
        if (perms.isKicked) {
            _kickedAlertMessage.value = "تم إيقاف وطرد هذا الجهاز من الجهاز الرئيسي."
            onDenied()
            return
        }

        // For a cloud-registered device, Supabase is the authoritative permission source.
        // runAuthorizedAction performs the server-side permission check before each write.
        if (cloudAuthorizationEnabled.value) {
            onAllowed()
            return
        }

        // Backward compatibility applies only to older linked devices that have no explicit
        // warehouse grants. New devices receive warehouse edit permissions per warehouse.
        val globalWarehouseGrant = perms.canViewAllWarehouses
        val legacyGlobalGrant = globalWarehouseGrant && perms.warehousePermissions.isEmpty()
        val allowed = if (warehouseIds.isNotEmpty()) {
            warehouseIds.all { warehouseId ->
                val grant = perms.warehousePermissions.firstOrNull { it.warehouseId == warehouseId }
                if (grant == null) {
                    globalWarehouseGrant && when (action) {
                        DeviceAction.INWARD -> perms.canInward
                        DeviceAction.DISBURSEMENT, DeviceAction.DISBURSE_OR_TRANSFER -> perms.canDisburse
                        DeviceAction.TRANSFER -> false
                        DeviceAction.EDIT_OR_DELETE -> perms.canEditDelete
                        DeviceAction.ADJUST_STOCK -> false
                        DeviceAction.EDIT_INVENTORY, DeviceAction.EDIT_SERIALS -> perms.canEditInventory
                        DeviceAction.DELETE_ITEM -> perms.canDeleteItem
                        DeviceAction.EDIT_ISSUE_VOUCHER -> perms.canEditIssueVoucher
                        DeviceAction.DELETE_ISSUE_VOUCHER -> perms.canDeleteIssueVoucher
                        DeviceAction.EDIT_SUPPLY_VOUCHER -> perms.canEditSupplyVoucher
                        DeviceAction.DELETE_SUPPLY_VOUCHER -> perms.canDeleteSupplyVoucher
                        DeviceAction.VIEW_REPORTS -> false
                        DeviceAction.BACKUP_RESTORE -> perms.canBackup
                        DeviceAction.ADD_OR_MANAGE_CATALOG -> perms.canAddItems
                        DeviceAction.EDIT_CATALOG_ITEMS -> perms.canEditCatalogItems
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
            DeviceAction.EDIT_INVENTORY, DeviceAction.EDIT_SERIALS -> legacyGlobalGrant && perms.canEditInventory
            DeviceAction.ADJUST_STOCK -> false
            DeviceAction.DELETE_ITEM -> perms.canDeleteItem
            DeviceAction.EDIT_ISSUE_VOUCHER -> perms.canEditIssueVoucher
            DeviceAction.DELETE_ISSUE_VOUCHER -> perms.canDeleteIssueVoucher
            DeviceAction.EDIT_SUPPLY_VOUCHER -> perms.canEditSupplyVoucher
            DeviceAction.DELETE_SUPPLY_VOUCHER -> perms.canDeleteSupplyVoucher
            DeviceAction.VIEW_REPORTS -> legacyGlobalGrant || perms.warehousePermissions.any { it.canView && it.canReports }
            DeviceAction.BACKUP_RESTORE -> perms.canBackup
        }
        val offlineAllowed = hasValidatedInternetConnection() ||
            (isSubConnected.value && hasCachedOfflinePermission(action, warehouseIds))
        if (allowed && offlineAllowed) onAllowed() else onDenied()
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
            viewModelScope.launch {
                if (!authorizedActionRunning.compareAndSet(false, true)) {
                    _userMessage.emit("هناك عملية قيد التنفيذ؛ انتظر اكتمالها قبل تكرار الضغط.")
                    return@launch
                }
                _isOperationRunning.value = true
                try {
                    validateCloudWarehousePermission(action, warehouseIds)
                    operation()
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (error: Exception) {
                    val detail = error.localizedMessage ?: error.javaClass.simpleName
                    _userMessage.emit("تعذر تنفيذ العملية: $detail")
                } finally {
                    _isOperationRunning.value = false
                    authorizedActionRunning.set(false)
                }
            }
        }
    }

    private fun offlineWarehouseConflict(
        grants: List<WarehousePermission>,
        existingDevices: List<LinkedSubDevice>,
        excludingSlotId: String?
    ): String? {
        val requested = grants.filter { it.canWorkOffline && it.canView }
        val devices = existingDevices.filter {
            it.id != excludingSlotId && !it.isKicked && !it.wasUnlinked
        }
        val conflict = requested.firstNotNullOfOrNull { grant ->
            devices.firstOrNull { slot ->
                slot.warehousePermissions.any { it.warehouseId == grant.warehouseId && it.canWorkOffline && it.canView }
            }?.let { slot -> grant.warehouseId to slot.deviceName }
        }
        return conflict?.let { (warehouseId, deviceName) ->
            "المخزن رقم $warehouseId مخصص بالفعل للعمل دون اتصال للجهاز ($deviceName). لا يمكن تعيين أكثر من جهاز واحد للمخزن."
        }
    }

    private fun hasCachedOfflinePermission(action: DeviceAction, requestedIds: Set<Long>): Boolean {
        val permissions = devicePermissions.value
        val candidates = permissions.warehousePermissions.filter { grant ->
            grant.canView && grant.canWorkOffline &&
                (requestedIds.isEmpty() || grant.warehouseId in requestedIds)
        }
        if (candidates.isEmpty()) return false
        fun WarehousePermission.allows(): Boolean = when (action) {
            DeviceAction.INWARD -> canInward
            DeviceAction.DISBURSEMENT, DeviceAction.DISBURSE_OR_TRANSFER -> canDisburse
            DeviceAction.TRANSFER -> canTransfer
            DeviceAction.ADJUST_STOCK -> canAdjust
            DeviceAction.EDIT_INVENTORY, DeviceAction.EDIT_SERIALS -> canEditInventory
            DeviceAction.DELETE_ITEM -> canDeleteItem && permissions.canDeleteItem
            DeviceAction.EDIT_OR_DELETE -> canEdit
            DeviceAction.VIEW_REPORTS -> canReports
            DeviceAction.EDIT_ISSUE_VOUCHER -> canEdit && permissions.canEditIssueVoucher
            DeviceAction.DELETE_ISSUE_VOUCHER -> permissions.canDeleteIssueVoucher
            DeviceAction.EDIT_SUPPLY_VOUCHER -> canEdit && permissions.canEditSupplyVoucher
            DeviceAction.DELETE_SUPPLY_VOUCHER -> permissions.canDeleteSupplyVoucher
            DeviceAction.ADD_OR_MANAGE_CATALOG -> permissions.canAddItems
            DeviceAction.EDIT_CATALOG_ITEMS -> permissions.canEditCatalogItems
            DeviceAction.BACKUP_RESTORE -> permissions.canBackup
        }
        return if (requestedIds.isEmpty()) candidates.any { it.allows() }
        else requestedIds.all { id -> candidates.any { it.warehouseId == id && it.allows() } }
    }

    private suspend fun resolveCloudWarehouseMap(workspaceId: String): Map<Long, String> {
        val workspace = cloudCollaborationRepo.getWorkspace(workspaceId)
        val currentUserId = cloudCollaborationRepo.currentUserIdOrNull()
            ?: error("يجب تسجيل الدخول إلى حساب Google")
        val localWarehouses = repository.allWarehouses.first()
        if (workspace.ownerUserId == currentUserId) {
            localWarehouses.forEach { warehouse ->
                cloudCollaborationRepo.ensureCloudWarehouse(workspaceId, warehouse)
            }
        }
        return cloudCollaborationRepo.listWarehouses(workspaceId)
            .mapNotNull { remote -> remote.localId?.let { it to remote.id } }
            .toMap()
    }

    private suspend fun effectiveCloudWarehouseIds(
        workspaceId: String,
        action: DeviceAction?,
        requestedIds: Set<Long>
    ): Set<Long> {
        if (requestedIds.isNotEmpty()) return requestedIds
        if (action !in setOf(
                DeviceAction.ADD_OR_MANAGE_CATALOG,
                DeviceAction.EDIT_CATALOG_ITEMS,
                DeviceAction.BACKUP_RESTORE
            )
        ) {
            return requestedIds
        }
        val userId = cloudCollaborationRepo.currentUserIdOrNull()
            ?: error("يجب تسجيل الدخول إلى حساب Google")
        val workspace = cloudCollaborationRepo.getWorkspace(workspaceId)
        if (workspace.ownerUserId == userId) return emptySet()

        val requiredPermission = when (action) {
            DeviceAction.EDIT_CATALOG_ITEMS -> "canEditCatalogItems"
            DeviceAction.BACKUP_RESTORE -> "canBackup"
            else -> "canAddItems"
        }
        val remoteWarehouses = cloudCollaborationRepo.listWarehouses(workspaceId)
        return remoteWarehouses.mapNotNull { warehouse ->
            val hasGrant = cloudCollaborationRepo.listWarehouseMemberships(warehouse.id)
                .any { grant ->
                    grant.userId == userId && grant.permissions[requiredPermission]?.toString() == "true"
                }
            if (hasGrant) warehouse.localId else null
        }.toSet()
    }

    private suspend fun validateCloudWarehousePermission(
        action: DeviceAction,
        localWarehouseIds: Set<Long>
    ) {
        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
        if (workspaceId.isBlank()) return
        if (!hasValidatedInternetConnection()) {
            if (devicePermissions.value.isMainDevice) {
                val canUseUnassignedWarehouseOffline = localWarehouseIds.isNotEmpty() &&
                    localWarehouseIds.all { linkedSubDevicesForWarehouse(it).isEmpty() }
                val isOwnerGlobalAction = localWarehouseIds.isEmpty() && action in setOf(
                    DeviceAction.ADD_OR_MANAGE_CATALOG,
                    DeviceAction.EDIT_CATALOG_ITEMS,
                    DeviceAction.BACKUP_RESTORE
                )
                if (canUseUnassignedWarehouseOffline || isOwnerGlobalAction) return
            } else if (isSubConnected.value && hasCachedOfflinePermission(action, localWarehouseIds)) {
                return
            }
            error("لا يمكن تنفيذ هذه العملية دون اتصال؛ هذا المخزن غير مخصص للعمل دون اتصال على هذا الجهاز.")
        }
        val deviceId = preferences.cloudDeviceIdFlow.first()
        if (deviceId.isBlank()) error("لم يتم تسجيل هذا الجهاز في مساحة العمل السحابية بعد")
        val effectiveIds = effectiveCloudWarehouseIds(workspaceId, action, localWarehouseIds)
        if ((action == DeviceAction.ADD_OR_MANAGE_CATALOG || action == DeviceAction.EDIT_CATALOG_ITEMS) &&
            !isMainDevice.value && effectiveIds.isEmpty()
        ) {
            error("لم تُمنح لهذا الحساب صلاحية إضافة أو تعديل دليل الأصناف في أي مخزن")
        }
        val cloudWarehouseMap = resolveCloudWarehouseMap(workspaceId)
        val cloudWarehouseIds = effectiveIds.map { localId ->
            cloudWarehouseMap[localId] ?: error("المخزن المحلي رقم $localId غير مربوط بمساحة العمل السحابية")
        }.toSet()
        val allowed = try {
            cloudCollaborationRepo.checkCloudWarehouseAccess(
                workspaceId = workspaceId,
                deviceId = deviceId,
                operationType = action.name,
                warehouseIds = cloudWarehouseIds
            )
        } catch (exception: Exception) {
            val detail = exception.localizedMessage.orEmpty()
            when {
                detail.contains("WAREHOUSE_LOCKED_BY_ASSIGNED_USER", ignoreCase = true) ->
                    throw IllegalStateException("الجهاز الفرعي المخصص لهذا المخزن يستخدمه حالياً؛ انتظر حتى ينتهي أو ألغِ تخصيصه.", exception)
                detail.contains("WAREHOUSE_ACCESS_DENIED", ignoreCase = true) ->
                    throw IllegalStateException("لا تملك الصلاحية المطلوبة لتنفيذ هذه العملية في المخزن المحدد.", exception)
                else -> throw exception
            }
        }
        if (!allowed) error("CLOUD_PERMISSION_DENIED")
    }

    // Multi-Device Setup & Controls
    private suspend fun requireMainDeviceForDeviceManagement(): Boolean {
        if (preferences.deviceRoleFlow.first() == DeviceRole.MAIN) return true
        _userMessage.emit("إدارة وربط الأجهزة الفرعية متاحان للجهاز الرئيسي فقط. لا يمكن للجهاز الفرعي إنشاء أجهزة تابعة أو إدارتها.")
        return false
    }

    fun switchToMainDeviceMode() {
        viewModelScope.launch {
            if (preferences.subSessionTokenFlow.first().isNotBlank()) {
                _userMessage.emit("هذا الجهاز مرتبط بالفعل. استخدم خيار فك الارتباط أولاً قبل تحويله إلى جهاز رئيسي.")
                return@launch
            }
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
            if (!requireMainDeviceForDeviceManagement()) return@launch
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
            if (!requireMainDeviceForDeviceManagement()) return@launch
            val code = preferences.ensureWorkspaceCode()
            val port = syncServer.start(LocalNetworkSyncServer.DEFAULT_PORT)
            syncServer.refreshIpAddresses()
        }
    }

    fun regenerateWorkspaceCode() {
        viewModelScope.launch {
            if (!requireMainDeviceForDeviceManagement()) return@launch
            val newCode = preferences.regenerateWorkspaceCode()
            syncServer.refreshIpAddresses()
            val port = syncServer.actualPort
            _userMessage.emit("تم إنشاء رمز قاعدة بيانات جديد: $newCode")
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
            if (!requireMainDeviceForDeviceManagement()) return@launch
            val cleanName = deviceName.trim().ifBlank { "جهاز فرعي #${linkedSubDevices.value.size + 1}" }
            val currentDevices = preferences.getLinkedSubDevicesSnapshot()
            val offlineConflict = offlineWarehouseConflict(warehousePermissions, currentDevices, excludingSlotId = null)
            if (offlineConflict != null) {
                _userMessage.emit(offlineConflict)
                return@launch
            }
            if (currentDevices.any { it.deviceName.trim().equals(cleanName, ignoreCase = true) }) {
                _userMessage.emit("اسم الجهاز ($cleanName) مستخدم بالفعل. اختر اسماً مختلفاً.")
                return@launch
            }
            val code = preferences.ensureWorkspaceCode()
            val port = syncServer.start(LocalNetworkSyncServer.DEFAULT_PORT)
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
        if (!isMainDevice.value) return
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
            if (!requireMainDeviceForDeviceManagement()) return@launch
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val idx = current.indexOfFirst { it.id == slotId }
            if (idx >= 0) {
                val offlineConflict = offlineWarehouseConflict(warehousePermissions, current, excludingSlotId = slotId)
                if (offlineConflict != null) {
                    _userMessage.emit(offlineConflict)
                    return@launch
                }
                current[idx] = current[idx].copy(
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
                preferences.saveLinkedSubDevices(current)
                preferences.incrementSyncRevision()

                val updatedSlot = current[idx]
                val workspaceId = preferences.cloudWorkspaceIdFlow.first()
                val cloudSync = if (
                    workspaceId.isNotBlank() && updatedSlot.boundInstallationId.isNotBlank()
                ) {
                    runCatching {
                        cloudCollaborationRepo.syncLinkedDevicePermissionsToCloud(
                            workspaceId,
                            updatedSlot
                        )
                    }
                } else {
                    Result.success(0)
                }

                when {
                    cloudSync.isFailure -> _userMessage.emit(
                        "تم تحديث الصلاحيات محلياً، لكن تعذرت مزامنتها سحابياً: " +
                            (cloudSync.exceptionOrNull()?.localizedMessage ?: "تحقق من الاتصال")
                    )
                    cloudSync.getOrDefault(0) > 0 -> _userMessage.emit(
                        "تم تحديث صلاحيات ${updatedSlot.deviceName} ومزامنتها مع التعاون السحابي."
                    )
                    else -> _userMessage.emit(
                        "تم تحديث صلاحيات ${updatedSlot.deviceName} محلياً. سجّل المستخدم الدخول بحساب Google على الجهاز أولاً، ثم اضغط مزامنة الصلاحيات من شاشة الأجهزة."
                    )
                }
            }
        }
    }

    fun syncLinkedSubDevicesPermissionsToCloud() {
        viewModelScope.launch {
            if (!requireMainDeviceForDeviceManagement()) return@launch
            val workspaceId = preferences.cloudWorkspaceIdFlow.first()
            if (workspaceId.isBlank()) {
                _userMessage.emit("اربط حساب Google بالجهاز الرئيسي أولاً لتفعيل مزامنة الصلاحيات.")
                return@launch
            }

            val slots = preferences.getLinkedSubDevicesSnapshot()
                .filter { it.boundInstallationId.isNotBlank() && !it.isKicked }
            if (slots.isEmpty()) {
                _userMessage.emit("لا توجد أجهزة فرعية مرتبطة وجاهزة لمزامنة الصلاحيات.")
                return@launch
            }

            var syncedAccounts = 0
            var failedDevices = 0
            slots.forEach { slot ->
                val syncResult = runCatching {
                    cloudCollaborationRepo.syncLinkedDevicePermissionsToCloud(workspaceId, slot)
                }
                if (syncResult.isSuccess) {
                    syncedAccounts += syncResult.getOrDefault(0)
                } else {
                    failedDevices++
                }
            }

            _userMessage.emit(
                when {
                    syncedAccounts > 0 ->
                        "تمت مزامنة صلاحيات $syncedAccounts حساباً سحابياً من شاشة الأجهزة."
                    failedDevices > 0 ->
                        "تعذرت مزامنة الصلاحيات. تحقق من اتصال الإنترنت وحساب Google الرئيسي."
                    else ->
                        "لم يظهر أي حساب سحابي مطابق لبصمات الأجهزة المرتبطة. سجّل المستخدمون الدخول إلى التعاون السحابي من أجهزتهم أولاً."
                }
            )
        }
    }

    fun kickSubDevice(slotId: String) {
        viewModelScope.launch {
            if (!requireMainDeviceForDeviceManagement()) return@launch
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val idx = current.indexOfFirst { it.id == slotId }
            if (idx >= 0) {
                val previous = current[idx]
                val targetName = previous.deviceName
                current[idx] = previous.copy(
                    isKicked = true,
                    warehousePermissions = previous.warehousePermissions.map { it.copy(canWorkOffline = false) },
                    sessionToken = UUID.randomUUID().toString(), // invalidate existing token
                    updatedAt = System.currentTimeMillis()
                )
                preferences.saveLinkedSubDevices(current)
                preferences.incrementSyncRevision()
                val workspaceId = preferences.cloudWorkspaceIdFlow.first()
                if (workspaceId.isNotBlank() && previous.boundInstallationId.isNotBlank()) {
                    runCatching {
                        cloudCollaborationRepo.syncLinkedDevicePermissionsToCloud(
                            workspaceId,
                            current[idx]
                        )
                    }
                }
                repository.recordOperation(OperationAuditLog(actorName = userName.value.ifBlank { "المسؤول" }, deviceName = "الجهاز الرئيسي", operationType = "KICK_DEVICE", title = "طرد جهاز فرعي", details = "تم طرد الجهاز ($targetName) وإبطال رمز جلسته. سيُمسح محتواه عند أول اتصال يصل إليه أمر الإيقاف"))
                notificationManager.notifyOperation("طرد جهاز فرعي", "تم طرد الجهاز ($targetName)", false)
                _userMessage.emit("تم إبطال جلسة ($targetName). سيُمسح محتواه تلقائياً فور وصول أمر الإيقاف إليه عبر الاتصال")
            }
        }
    }

    /** Re-enable the device while issuing a fresh one-time pairing password. */
    fun reactivateSubDevice(slotId: String) {
        viewModelScope.launch {
            if (!requireMainDeviceForDeviceManagement()) return@launch
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val idx = current.indexOfFirst { it.id == slotId }
            if (idx < 0) return@launch

            val previous = current[idx]
            val workspaceCode = preferences.ensureWorkspaceCode()
            val port = syncServer.start(LocalNetworkSyncServer.DEFAULT_PORT)
            val hostAddress = LocalNetworkSyncServer.getLocalLanIpAddresses().firstOrNull() ?: "192.168.43.1"
            val hostDisplay = "$hostAddress:${if (port > 0) port else LocalNetworkSyncServer.DEFAULT_PORT}"
            val oneTimePassword = SecurityUtils.generateUniqueDevicePassword(10)
            val passwordSalt = SecurityUtils.generateSalt()
            val passwordHash = SecurityUtils.hashPassword(oneTimePassword, passwordSalt)

            current[idx] = previous.copy(
                passwordSalt = passwordSalt,
                passwordHash = passwordHash,
                isKicked = false,
                isClaimed = false,
                boundInstallationId = "",
                sessionToken = UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis()
            )
            preferences.saveLinkedSubDevices(current)
            preferences.incrementSyncRevision()

            val credentials = CreatedDeviceCredentials(
                deviceSlotId = previous.id,
                deviceName = previous.deviceName,
                workspaceCode = workspaceCode,
                hostAddress = hostDisplay,
                plainPassword = oneTimePassword
            )
            _sessionPairingCredentials.value = _sessionPairingCredentials.value + (previous.id to credentials)
            _createdDeviceCredentials.value = credentials
            repository.recordOperation(
                OperationAuditLog(
                    actorName = userName.value.ifBlank { "المسؤول" },
                    deviceName = "الجهاز الرئيسي",
                    operationType = "REACTIVATE_DEVICE",
                    title = "إعادة تفعيل جهاز فرعي",
                    details = "تمت إعادة تفعيل الجهاز (${previous.deviceName}) وإصدار كلمة مرور جديدة للربط مع الاحتفاظ بصلاحياته."
                )
            )
            _userMessage.emit("تمت إعادة تفعيل ${previous.deviceName}. تظهر الآن كلمة مرور جديدة في نافذة بيانات الربط؛ لن تعمل كلمة المرور القديمة.")
        }
    }

    fun removeSubDeviceSlot(slotId: String) {
        viewModelScope.launch {
            if (!requireMainDeviceForDeviceManagement()) return@launch
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val removed = current.firstOrNull { it.id == slotId }
            if (removed != null) {
                current.removeAll { it.id == slotId }
                preferences.saveLinkedSubDevices(current)
                preferences.incrementSyncRevision()
                val workspaceId = preferences.cloudWorkspaceIdFlow.first()
                if (workspaceId.isNotBlank() && removed.boundInstallationId.isNotBlank()) {
                    runCatching {
                        cloudCollaborationRepo.clearOfflineWarehouseDesignationsForDevice(
                            workspaceId,
                            removed.boundInstallationId
                        )
                    }
                }
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
            if (preferences.deviceRoleFlow.first() != DeviceRole.SUB) {
                _pairingError.value = "لا يمكن ربط هذا الجهاز كفرعي قبل تحويله إلى وضع الجهاز الفرعي."
                return@launch
            }
            if (preferences.subSessionTokenFlow.first().isNotBlank()) {
                _pairingError.value = "هذا الجهاز مرتبط بالفعل بجهاز رئيسي."
                return@launch
            }
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
                    repository.importOperationLogsFromMain(paired.auditLogs)
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
                            ?: "تعذر الاتصال بالجهاز الرئيسي. تأكد من صحة العنوان وأن الجهازين على شبكة Wi-Fi نفسها أو نقطة الاتصال نفسها. قد يكون السبب ضعف الشبكة أو انقطاعاً مؤقتاً؛ أعد المحاولة بعد استقرار الاتصال."
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // Keep the app alive if importing the initial snapshot or saving the pairing state fails.
                val detail = error.localizedMessage?.take(120).orEmpty()
                _pairingError.value = if (detail.isBlank()) {
                    "تم الاتصال لكن تعذر إكمال حفظ بيانات الربط. أعد المحاولة."
                } else {
                    "تم الاتصال لكن تعذر إكمال حفظ بيانات الربط: $detail"
                }
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
            if (isMainDevice.value) {
                preferences.incrementSyncRevision()
                refreshMainServerInfo()
                _userMessage.emit("تم تحديث حالة خادم الربط المحلي وجاهزية المزامنة")
            } else {
                pullFromMainOnce(silent = false)
            }
        }
    }

    /** Called by the navigation shell so revocation/permission changes are checked on every screen change. */
    fun syncOnNavigation() {
        if (isMainDevice.value || !isSubConnected.value) return
        viewModelScope.launch { pullFromMainOnce(silent = true) }
    }

    private suspend fun pullFromMainOnce(silent: Boolean): Unit = subSyncMutex.withLock {
        if (!silent) _isSyncingNow.value = true
        try {
            val host = preferences.mainHostUrlFlow.first()
            val token = preferences.subSessionTokenFlow.first()
            if (host.isBlank() || token.isBlank()) {
                _lastSyncError.value = "بيانات اتصال الجهاز الرئيسي غير مكتملة"
                return@withLock
            }

            // Send locally queued changes before pulling the main snapshot. Pulling first can
            // overwrite an offline voucher that has not yet reached the main device.
            if (!flushPendingSubDeviceSyncOperations()) {
                if (!silent) _userMessage.emit("توجد عمليات محفوظة بانتظار المزامنة؛ لم يتم استبدال البيانات المحلية.")
                return@withLock
            }

            val result = syncClient.fetchSyncSnapshot(host, token)
            result.onSuccess { snapshot ->
                _lastSyncError.value = null
                if (snapshot.isKicked) {
                    handleSubDeviceKicked("تم طرد هذا الجهاز وإلغاء ارتباطه من الجهاز الرئيسي.")
                    return@onSuccess
                }

                preferences.updateSubDevicePermissions(snapshot.permissions)
                // History is synchronized independently of the inventory revision and deduplicated
                // by the repository, so late audit writes are not missed.
                repository.importOperationLogsFromMain(snapshot.auditLogs)
                val lastApplied = preferences.lastAppliedRevisionFlow.first()
                if (snapshot.syncVersion != lastApplied) {
                    if (snapshot.entities.isEmpty()) {
                        val localEntities = repository.exportAllEntitiesForCloud()
                        if (localEntities.isNotEmpty()) {
                            // An empty remote response can be transient or incomplete. Never erase
                            // an existing warehouse database from an empty snapshot.
                            _lastSyncError.value = "وصلت لقطة مزامنة فارغة رغم وجود بيانات محلية؛ تم الاحتفاظ بالبيانات وإعادة المحاولة لاحقاً."
                            return@onSuccess
                        }
                    } else {
                        // Advance the revision only after the database replacement succeeds.
                        // The previous code ignored Result.failure and marked the snapshot applied,
                        // leaving the device on stale/partial inventory indefinitely.
                        repository.replaceDatabaseFromCloudEntitiesSafely(snapshot.entities).getOrThrow()
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
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Covers DataStore, JSON, Room transaction and permission-update failures as well
            // as HTTP failures so no background sync exception can crash the app.
            _lastSyncError.value = "تعذر تحديث بيانات الجهاز: " + (error.localizedMessage?.take(100).orEmpty())
            if (!silent) {
                _userMessage.emit("تعذر إكمال المزامنة. سيحاول التطبيق مرة أخرى تلقائياً.")
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
            val host = preferences.mainHostUrlFlow.first()
            val token = preferences.subSessionTokenFlow.first()
            val name = deviceName.value.ifBlank { "الجهاز الفرعي" }
            if (host.isNotBlank() && token.isNotBlank()) {
                val remote = syncClient.unlinkFromMain(host, token)
                if (remote.isFailure) {
                    _userMessage.emit("تعذر إبلاغ الجهاز الرئيسي بفك الارتباط الآن؛ سيتم حفظ سجل العملية محلياً")
                }
            }
            repository.recordOperation(OperationAuditLog(
                actorName = userName.value.ifBlank { name },
                deviceName = name,
                operationType = "UNLINK",
                title = "فك ارتباط الجهاز الفرعي",
                details = "قام المستخدم بفك ارتباط هذا الجهاز من الجهاز الرئيسي"
            ))
            repository.clearAllUserData(preserveAuditLogs = false)
            preferences.clearSubPairingAndSwitchToMain()
            _lastSyncError.value = null
            _userMessage.emit("تم فك الارتباط ومسح بيانات الحساب وسجل العمليات المحلي؛ سيُستعاد سجل هذا الجهاز عند إعادة ربطه بالجهاز الرئيسي")
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
        propagateCloudDataChange(
            action = action,
            operationTitle = notificationTitle,
            operationDetails = notificationDetails,
            warehouseIds = warehouseIds
        )
    }

    private fun propagateCloudDataChange(
        action: DeviceAction? = null,
        operationTitle: String,
        operationDetails: String,
        warehouseIds: Set<Long>
    ) {
        viewModelScope.launch {
            val workspaceId = preferences.cloudWorkspaceIdFlow.first()
            if (workspaceId.isBlank()) return@launch
            try {
                val effectiveIds = effectiveCloudWarehouseIds(workspaceId, action, warehouseIds)
                if ((action == DeviceAction.ADD_OR_MANAGE_CATALOG || action == DeviceAction.EDIT_CATALOG_ITEMS) &&
                    !isMainDevice.value && effectiveIds.isEmpty()
                ) {
                    _userMessage.emit("لم تُمنح لهذا الحساب صلاحية تعديل دليل الأصناف في أي مخزن.")
                    return@launch
                }
                val operation = CloudPendingSyncOperation(
                    operationId = UUID.randomUUID().toString(),
                    actionType = action?.name ?: "GENERAL",
                    operationTitle = operationTitle,
                    operationDetails = operationDetails,
                    actorName = userName.value,
                    warehouseIdsJson = JSONArray(effectiveIds.toList()).toString(),
                    entitiesJson = LocalSyncJson.upsertsToJsonArray(repository.exportAllEntitiesForCloud()).toString()
                )
                repository.enqueueCloudPendingSyncOperation(operation)
                if (!flushCloudPendingSyncOperations()) {
                    _lastSyncError.value = "تم حفظ العملية في طابور السحابة؛ ستُرفع عند عودة الاتصال."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _lastSyncError.value = "تعذر حفظ العملية في طابور السحابة: ${error.localizedMessage ?: "خطأ غير معروف"}"
            }
        }
    }

    private fun startCloudOutboxCoordinator() {
        cloudOutboxJob?.cancel()
        cloudOutboxJob = viewModelScope.launch {
            var lastOfflinePermissionSyncAt = 0L
            while (isActive) {
                runCatching { flushCloudPendingSyncOperations() }
                runCatching { pollCloudIncomingOperations() }
                val now = System.currentTimeMillis()
                if (now - lastOfflinePermissionSyncAt >= 60_000L) {
                    runCatching { syncOfflineDesignationsQuietly() }
                    lastOfflinePermissionSyncAt = now
                }
                delay(15_000L)
            }
        }
    }

    private suspend fun syncOfflineDesignationsQuietly() {
        if (!isMainDevice.value) return
        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
        if (workspaceId.isBlank()) return
        val slots = preferences.getLinkedSubDevicesSnapshot().filter { slot ->
            slot.boundInstallationId.isNotBlank() &&
                !slot.wasUnlinked &&
                (slot.isKicked || slot.warehousePermissions.any { it.canWorkOffline && it.canView })
        }
        slots.forEach { slot ->
            runCatching {
                cloudCollaborationRepo.syncLinkedDevicePermissionsToCloud(workspaceId, slot)
            }.onFailure { error ->
                _lastSyncError.value = "تعذرت مزامنة تعيين العمل دون اتصال للجهاز ${slot.deviceName}: ${error.localizedMessage ?: "تحقق من الاتصال"}"
            }
        }
    }

    private suspend fun pollCloudIncomingOperations() {
        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
        if (workspaceId.isBlank() || cloudCollaborationRepo.currentUserIdOrNull().isNullOrBlank()) return

        // Keep the local Room database in sync even when the collaboration screen is not open.
        runCatching {
            val snapshot = cloudCollaborationRepo.fetchCloudEntitySnapshotVersioned(workspaceId)
            val savedSignature = preferences.cloudSnapshotSignatureFlow.first()
            // A LAN-paired sub-device receives the authoritative database from its main
            // device. Applying a cloud snapshot here can roll back a newer LAN operation
            // with an older cloud revision, which caused stock entries to appear then vanish.
            if (!isSubConnected.value &&
                snapshot.records.isNotEmpty() &&
                savedSignature.isNotBlank() &&
                snapshot.updatedAtSignature != savedSignature
            ) {
                if (repository.getPendingSyncOperations().isEmpty() &&
                    repository.getCloudPendingSyncOperations().isEmpty()
                ) {
                    repository.replaceDatabaseFromCloudEntitiesSafely(snapshot.records).getOrThrow()
                    preferences.saveCloudSnapshotSignature(snapshot.updatedAtSignature)
                    // The main device must publish cloud-originated changes to LAN sub-devices too.
                    if (isMainDevice.value) preferences.incrementSyncRevision()
                } else {
                    _lastSyncError.value = "توجد عمليات محلية معلقة؛ تم تأجيل استبدال البيانات حتى لا تضيع."
                }
            }
        }.onFailure { failure ->
            _lastSyncError.value = "تعذر تحديث العمليات السحابية: " + (failure.localizedMessage ?: failure.javaClass.simpleName)
        }

        val notifications = cloudCollaborationRepo.listMyOperationNotifications(workspaceId)
        if (notifications.isEmpty()) return

        val auditEntries = runCatching {
            cloudCollaborationRepo.listAllowedOperationAuditEntries(workspaceId)
        }.getOrDefault(emptyList())
        val auditByOperationId = auditEntries
            .filter { !it.operationId.isNullOrBlank() }
            .associateBy { it.operationId!! }
        val membersByUserId = runCatching {
            cloudCollaborationRepo.listWorkspaceMembers(workspaceId)
                .associateBy { it.userId }
        }.getOrDefault(emptyMap())
        val ownerUserId = runCatching {
            cloudCollaborationRepo.getWorkspace(workspaceId).ownerUserId
        }.getOrNull()

        notifications.forEach { notification ->
            if (preferences.hasSeenCloudNotification(notification.id)) return@forEach

            val audit = notification.relatedOperationId?.let(auditByOperationId::get)
            // Keep the notification pending until its RLS-authorized audit row is available;
            // this prevents a transient audit-query failure from losing the history entry.
            if (!notification.relatedOperationId.isNullOrBlank() && audit == null) return@forEach
            val member = audit?.let { membersByUserId[it.actorUserId] }
            val actorName = member?.displayName?.takeIf { it.isNotBlank() }
                ?: member?.email?.takeIf { it.isNotBlank() }
                ?: if (audit?.actorUserId == ownerUserId) "المستخدم الرئيسي" else "عضو مساحة العمل"
            val sourceDevice = audit?.deviceName?.takeIf { it.isNotBlank() } ?: "جهاز غير محدد"
            val operationTitle = audit?.title?.takeIf { it.isNotBlank() }
                ?: notification.title.ifBlank { "عملية مخزنية" }
            val operationDetails = audit?.details?.takeIf { it.isNotBlank() }
                ?: notification.body
            val operationType = audit?.operationType?.takeIf { it.isNotBlank() }
                ?: notification.notificationType

            if (audit != null) {
                repository.recordOperation(
                    OperationAuditLog(
                        timestamp = parseCloudAuditTimestamp(audit.createdAt),
                        actorName = actorName,
                        deviceName = "$sourceDevice • سحابي",
                        operationType = operationType,
                        title = operationTitle,
                        details = operationDetails
                    )
                )
            }

            notificationManager.notifyOperation(
                title = "عملية على مخزن مرتبط: $operationTitle",
                details = "نفّذها: $actorName • الجهاز: $sourceDevice\n$operationDetails",
                isRemoteSubDeviceAlert = true
            )
            preferences.markCloudNotificationSeen(notification.id)
        }
    }

    private fun parseCloudAuditTimestamp(value: String): Long {
        val patterns = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'"
        )
        for (pattern in patterns) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).parse(value)?.time
            }.getOrNull()
            if (parsed != null) return parsed
        }
        return System.currentTimeMillis()
    }

    suspend fun flushPendingOperationsBeforeCollaboration() {
        val localPendingOk = if (!isMainDevice.value && isSubConnected.value) {
            flushPendingSubDeviceSyncOperations()
        } else true
        val cloudPendingOk = flushCloudPendingSyncOperations()
        if (!localPendingOk || !cloudPendingOk) {
            _lastSyncError.value = "توجد عمليات محفوظة محلياً لم تكتمل مزامنتها بعد؛ ستبقى محفوظة حتى نجاح الرفع."
        }
    }

    private suspend fun flushCloudPendingSyncOperations(): Boolean = cloudPushMutex.withLock {
        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
        val deviceId = preferences.cloudDeviceIdFlow.first()
        if (workspaceId.isBlank()) return@withLock true
        if (deviceId.isBlank() || cloudCollaborationRepo.currentUserIdOrNull().isNullOrBlank()) {
            return@withLock false
        }

        val pending = repository.getCloudPendingSyncOperations()
        if (pending.isEmpty()) return@withLock true

        val cloudWorkspace = cloudCollaborationRepo.getWorkspace(workspaceId)
        val isOwner = cloudWorkspace.ownerUserId == cloudCollaborationRepo.currentUserIdOrNull()
        val warehouseMap = resolveCloudWarehouseMap(workspaceId)

        for (operation in pending) {
            val localWarehouseIds = longIdsFromJson(operation.warehouseIdsJson)
            val missing = localWarehouseIds.filterNot { warehouseMap.containsKey(it) }
            if (missing.isNotEmpty()) {
                repository.recordCloudPendingSyncFailure(
                    operation.operationId,
                    "المخازن التالية غير مربوطة سحابياً: ${missing.joinToString()}"
                )
                _lastSyncError.value = "تعذر رفع عملية سحابية لعدم ربط أحد المخازن."
                return@withLock false
            }

            val entities = runCatching {
                LocalSyncJson.upsertsFromJsonArray(JSONArray(operation.entitiesJson))
            }.getOrElse { error ->
                repository.recordCloudPendingSyncFailure(
                    operation.operationId,
                    "تعذر قراءة لقطة العملية: ${error.localizedMessage}"
                )
                return@withLock false
            }
            val cloudWarehouseIds = localWarehouseIds.map { warehouseMap.getValue(it) }.toSet()
            val records = cloudCollaborationRepo.encodeCloudEntityRecords(
                entities = entities,
                cloudWarehouseIdByLocalId = warehouseMap,
                targetWarehouseLocalIds = localWarehouseIds,
                includeGlobalRecords = isOwner ||
                    operation.actionType == DeviceAction.ADD_OR_MANAGE_CATALOG.name ||
                    operation.actionType == DeviceAction.EDIT_CATALOG_ITEMS.name
            )

            val result = runCatching {
                cloudCollaborationRepo.pushCloudSnapshot(
                    workspaceId = workspaceId,
                    deviceId = deviceId,
                    clientOperationId = operation.operationId,
                    operationType = operation.actionType,
                    warehouseIds = cloudWarehouseIds,
                    title = operation.operationTitle,
                    details = operation.operationDetails,
                    records = records
                )
            }
            result.onSuccess {
                repository.removeCloudPendingSyncOperation(operation.operationId)
                _lastSyncError.value = null
            }.onFailure { error ->
                repository.recordCloudPendingSyncFailure(
                    operation.operationId,
                    error.localizedMessage ?: error.javaClass.simpleName
                )
                _lastSyncError.value = error.localizedMessage ?: "تعذر رفع عملية إلى السحابة"
                return@withLock false
            }
        }
        true
    }

    private fun propagateDataChange(
        action: DeviceAction? = null,
        operationTitle: String = "",
        operationDetails: String = "",
        warehouseIds: Set<Long> = emptySet(),
        targetItemIds: Set<Long> = emptySet(),
        targetVoucherIds: Set<Long> = emptySet()
    ) {
        viewModelScope.launch {
            if (isMainDevice.value) {
                preferences.incrementSyncRevision()
                return@launch
            }

            try {
                // Persist the exact post-operation snapshot and metadata before attempting any
                // network request. A process death or lost response will not lose the operation.
                val operation = PendingSyncOperation(
                    operationId = UUID.randomUUID().toString(),
                    actionType = action?.name ?: "GENERAL",
                    operationTitle = operationTitle,
                    operationDetails = operationDetails,
                    actorName = userName.value,
                    warehouseIdsJson = JSONArray(warehouseIds.toList()).toString(),
                    targetItemIdsJson = JSONArray(targetItemIds.toList()).toString(),
                    targetVoucherIdsJson = JSONArray(targetVoucherIds.toList()).toString(),
                    entitiesJson = LocalSyncJson.upsertsToJsonArray(repository.exportAllEntitiesForCloud()).toString()
                )
                repository.enqueuePendingSyncOperation(operation)

                val host = preferences.mainHostUrlFlow.first()
                val token = preferences.subSessionTokenFlow.first()
                if (host.isBlank() || token.isBlank() || !flushPendingSubDeviceSyncOperations()) {
                    _lastSyncError.value = "تم حفظ العملية في قائمة المزامنة وستُرسل عند توفر اتصال الجهاز الرئيسي."
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _lastSyncError.value = "تم حفظ البيانات محلياً، وتعذر تجهيز المزامنة: ${error.localizedMessage ?: "خطأ غير معروف"}"
            }
        }
    }

    private fun longIdsFromJson(json: String): Set<Long> = runCatching {
        val array = JSONArray(json)
        buildSet {
            for (index in 0 until array.length()) {
                array.optLong(index, 0L).takeIf { it > 0L }?.let { add(it) }
            }
        }
    }.getOrDefault(emptySet())

    private suspend fun flushPendingSubDeviceSyncOperations(): Boolean = pendingPushMutex.withLock {
        val host = preferences.mainHostUrlFlow.first()
        val token = preferences.subSessionTokenFlow.first()
        if (host.isBlank() || token.isBlank()) return@withLock false

        val pending = repository.getPendingSyncOperations()
        for (operation in pending) {
            val entities = runCatching {
                LocalSyncJson.upsertsFromJsonArray(JSONArray(operation.entitiesJson))
            }.getOrElse { error ->
                repository.recordPendingSyncFailure(operation.operationId, "تعذر قراءة بيانات العملية: ${error.localizedMessage}")
                _lastSyncError.value = "توجد عملية محفوظة لا يمكن قراءة بياناتها؛ لم يتم تجاوزها."
                return@withLock false
            }

            val result = syncClient.pushEntitiesToMain(
                rawHost = host,
                sessionToken = token,
                actionType = operation.actionType,
                entities = entities,
                operationTitle = operation.operationTitle,
                operationDetails = operation.operationDetails,
                actorName = operation.actorName,
                warehouseIds = longIdsFromJson(operation.warehouseIdsJson),
                targetItemIds = longIdsFromJson(operation.targetItemIdsJson),
                targetVoucherIds = longIdsFromJson(operation.targetVoucherIdsJson),
                clientOperationId = operation.operationId
            )

            if (result.isSuccess) {
                val newRevision = result.getOrThrow()
                repository.removePendingSyncOperation(operation.operationId)
                preferences.updateLastSync(newRevision)
                _lastSyncError.value = null
            } else {
                val error = result.exceptionOrNull() ?: IllegalStateException("فشل غير معروف في المزامنة")
                repository.recordPendingSyncFailure(operation.operationId, error.localizedMessage ?: error.javaClass.simpleName)
                val apiError = error as? SyncApiException
                when (apiError?.errorCode) {
                    "KICKED" -> handleSubDeviceKicked(
                        apiError.message ?: "تم إيقاف هذا الجهاز من الجهاز الرئيسي."
                    )
                    "PERMISSION_DENIED" -> _userMessage.emit(
                        apiError.message ?: "رفض الجهاز الرئيسي العملية بسبب الصلاحيات؛ بقيت محفوظة في قائمة المزامنة."
                    )
                }
                _lastSyncError.value = error.localizedMessage ?: "تعذر إرسال العمليات المحفوظة"
                return@withLock false
            }
        }
        true
    }

    // 1. Warehouse Operations
    fun saveWarehouse(warehouse: Warehouse, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (warehouse.id == 0L) {
                runAuthorizedAction(
                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                    deniedMessage = "لا تملك صلاحية إضافة مخزن جديد"
                ) {
                    repository.insertWarehouse(warehouse)
                    _userMessage.emit("تمت إضافة المخزن بنجاح")
                    notifyAndPropagate(
                        action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                        notificationTitle = "إضافة مخزن جديد",
                        notificationDetails = "تم إضافة المخزن (${warehouse.name}) — أمين المخزن: ${warehouse.managerName}"
                    )
                    onDone()
                }
                return@launch
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
        val action = if (item.id == 0L) {
            DeviceAction.ADD_OR_MANAGE_CATALOG
        } else {
            DeviceAction.EDIT_CATALOG_ITEMS
        }
        runAuthorizedAction(
            action = action,
            deniedMessage = "لا تملك صلاحية إضافة أو تعديل دليل الأصناف"
        ) {
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
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
                    notificationTitle = "تعديل بيانات صنف داخل الدليل",
                    notificationDetails = "تم تحديث بيانات الصنف (${item.name})",
                    targetItemIds = setOf(item.id)
                )
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
        runAuthorizedAction(action = if (beneficiary.id == 0L) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية إضافة أو تعديل الجهات المستفيدة") {
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
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية حذف الجهات المستفيدة") {
            val success = repository.deleteBeneficiarySafely(beneficiary)
            if (success) {
                _userMessage.emit("تم حذف الجهة المستفيدة بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = if (supplier.id == 0L) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية إضافة أو تعديل الموردين") {
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
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية حذف الموردين") {
            val success = repository.deleteSupplierSafely(supplier)
            if (success) {
                _userMessage.emit("تم حذف المورد بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية تعديل أسماء الموردين") {
            val supp = suppliers.value.find { it.name == oldName }
            if (supp != null) {
                repository.updateSupplier(supp.copy(name = newName.trim()))
                _userMessage.emit("تم تعديل اسم المورد بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
                    notificationTitle = "تعديل اسم مورد",
                    notificationDetails = "تم تغيير اسم المورد من ($oldName) إلى (${newName.trim()})"
                )
            }
            onDone()
        }
    }

    // 6. WarehouseParty Operations (Receivers / Disbursers)
    fun saveParty(party: WarehouseParty, onDone: (Long) -> Unit = {}) {
        runAuthorizedAction(action = if (party.id == 0L) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية إضافة أو تعديل الأسماء") {
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
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية حذف الأسماء") {
            val success = repository.deletePartySafely(party)
            if (success) {
                _userMessage.emit("تم الحذف بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية تعديل الأسماء") {
            val pty = parties.value.find { it.name == oldName }
            if (pty != null) {
                repository.updateParty(pty.copy(name = newName.trim()))
                _userMessage.emit("تم تعديل الاسم بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
                    notificationTitle = "تعديل اسم مستلم / قائم بالصرف",
                    notificationDetails = "تم تعديل الاسم من ($oldName) إلى (${newName.trim()})"
                )
            }
            onDone()
        }
    }

    // 7. Categories, Package Units, Smaller Units & Item Statuses Operations
    fun saveCategory(category: ItemCategory, onDone: () -> Unit = {}) {
        runAuthorizedAction(action = if (category.id == 0L) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية إضافة أو تعديل التصنيفات") {
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
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية تعديل التصنيفات") {
            val ok = repository.updateCategorySafely(category, newName, newNotes)
            if (ok) {
                _userMessage.emit("تم تعديل الفئة بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية حذف التصنيفات") {
            val ok = repository.deleteCategorySafely(category)
            if (ok) {
                _userMessage.emit("تم حذف التصنيف بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = if (unit.id == 0L) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية إضافة أو تعديل وحدات القياس") {
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية تعديل وحدات القياس") {
            val ok = repository.updateUnitSafely(unit)
            if (ok) {
                _userMessage.emit("تم تعديل وحدة القياس بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية تعديل أسماء وحدات القياس") {
            repository.updateUnitName(unitId, newName)
            _userMessage.emit("تم تعديل اسم الوحدة بنجاح")
            notifyAndPropagate(
                action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية حذف وحدات القياس") {
            val success = repository.deleteUnitSafely(unit)
            if (success) {
                _userMessage.emit("تم حذف الوحدة بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = if (existingUnit == null) DeviceAction.ADD_OR_MANAGE_CATALOG else DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية إضافة أو تعديل وحدات القياس") {
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
        runAuthorizedAction(action = DeviceAction.ADD_OR_MANAGE_CATALOG, deniedMessage = "لا تملك صلاحية إضافة وحدة تابعة") {
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية تعديل الوحدات التابعة") {
            val ok = repository.updateChildUnitSafely(existing, newName, newCountInParent, newCapacity)
            if (ok) {
                val levelLabel = if (existing.unitLevel == "MEDIUM") "المتوسطة" else "الصغرى"
                _userMessage.emit("تم تعديل الوحدة $levelLabel بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.ADD_OR_MANAGE_CATALOG, deniedMessage = "لا تملك صلاحية إضافة وحدة صغرى") {
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
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "لا تملك صلاحية تعديل الوحدات الصغرى") {
            val ok = repository.updateSmallerUnitSafely(existing, newName, newCapacity)
            if (ok) {
                _userMessage.emit("تم تعديل الوحدة الصغرى بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
        runAuthorizedAction(action = DeviceAction.ADD_OR_MANAGE_CATALOG, deniedMessage = "لا تملك صلاحية إضافة حالات الأصناف") {
            val trimmed = statusName.trim()
            if (trimmed.isBlank()) return@runAuthorizedAction
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
            action = DeviceAction.EDIT_CATALOG_ITEMS,
            warehouseIds = setOfNotNull(warehouseId),
            deniedMessage = "لا تملك صلاحية تعديل حالات الأصناف في هذا المخزن"
        ) {
            val ok = repository.updateItemStatusSafely(existing, newName)
            if (ok) {
                _userMessage.emit("تم تعديل الحالة بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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
            action = DeviceAction.EDIT_CATALOG_ITEMS,
            warehouseIds = setOfNotNull(warehouseId),
            deniedMessage = "لا تملك صلاحية حذف حالات الأصناف في هذا المخزن"
        ) {
            val ok = repository.deleteItemStatusSafely(status)
            if (ok) {
                _userMessage.emit("تم حذف الحالة (${status.name}) بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_CATALOG_ITEMS,
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


    private fun mayUseBackups(): Boolean = devicePermissions.value.isMainDevice || devicePermissions.value.canBackup

    private fun emitBackupPermissionDenied() {
        viewModelScope.launch { _userMessage.emit("صلاحية النسخ الاحتياطي والتصدير غير ممنوحة من المستخدم الرئيسي") }
    }

    private suspend fun attachDeviceBackupMetadata(record: BackupRecord): BackupRecord {
        val metadata = org.json.JSONObject().put(
            "linkedSubDevices",
            org.json.JSONArray(preferences.exportLinkedDevicesBackupJson())
        )
        return repository.attachBackupMetadata(record, metadata)
    }

    private suspend fun restoreDeviceBackupMetadata(rawJson: String) {
        val backup = org.json.JSONObject(rawJson)
        val devices = backup.optJSONArray("linkedSubDevices") ?: return
        if (preferences.isMainDeviceFlow.first()) {
            preferences.restoreLinkedDevicesBackupJson(devices.toString())
        }
    }

    fun createBackup(name: String) {
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
        viewModelScope.launch {
            try {
                val record = repository.createBackup(name, isCloud = false, accountEmail = "")
                attachDeviceBackupMetadata(record)
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
        return attachDeviceBackupMetadata(record).backupJson
    }

    fun importBackupJsonFromFile(rawJson: String) {
        if (!mayUseBackups()) { emitBackupPermissionDenied(); return }
        viewModelScope.launch {
            _isCloudBackupBusy.value = true
            _cloudSyncOperationLabel.value = "جاري استرداد البيانات من الملف..."
            try {
                val ok = repository.restoreBackupReplacingExisting(rawJson)
                if (ok) {
                    restoreDeviceBackupMetadata(rawJson)
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
                    restoreDeviceBackupMetadata(record.backupJson)
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
                val rawSnapshot = repository.buildCloudBackupSnapshot(cleanName, email)
                val snapshot = attachDeviceBackupMetadata(rawSnapshot)
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
                        restoreDeviceBackupMetadata(rawJson)
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
                            detail = "تعذر فحص قاعدة البيانات المحلية. تحقق من سلامة البيانات ثم أعد الفحص.",
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
        if (changed.isEmpty()) return serials
        try {
            if (cloudAuthorizationEnabled.value) {
                validateCloudWarehousePermission(
                    DeviceAction.EDIT_SERIALS,
                    changed.map { it.warehouseId }.toSet()
                )
            }
            val saved = repository.saveSerialsAndReturn(serials)
            notifyAndPropagate(
                action = DeviceAction.EDIT_SERIALS,
                notificationTitle = "تحديث الأرقام التسلسلية والحالات والملاحظات",
                notificationDetails = buildSerialAuditDetails(changed, "تم تعديل ${changed.size} وحدة فقط"),
                warehouseIds = changed.map { it.warehouseId }.toSet(),
                targetItemIds = changed.map { it.itemId }.toSet()
            )
            return saved
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            _userMessage.emit("تعذر حفظ الترقيم: " + (failure.localizedMessage ?: failure.javaClass.simpleName))
            throw failure
        }
    }

    fun saveSerials(serials: List<ItemUnitSerialRecord>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            val changed = findChangedSerialRecords(serials)
            if (changed.isEmpty()) {
                _userMessage.emit("لا توجد تغييرات جديدة لحفظها")
                onDone()
                return@launch
            }
            runAuthorizedAction(
                action = DeviceAction.EDIT_SERIALS,
                warehouseIds = changed.map { it.warehouseId }.toSet(),
                deniedMessage = "لا تملك صلاحية تحديث الأرقام التسلسلية أو الحالات أو الملاحظات في هذا المخزن"
            ) {
                repository.saveSerialsAndReturn(serials)
                _userMessage.emit("تم حفظ الأرقام المميزة والحالات والملاحظات بنجاح")
                notifyAndPropagate(
                    action = DeviceAction.EDIT_SERIALS,
                    notificationTitle = "تحديث الأرقام التسلسلية والحالات والملاحظات",
                    notificationDetails = buildSerialAuditDetails(changed, "تم تعديل ${changed.size} وحدة فقط"),
                    warehouseIds = changed.map { it.warehouseId }.toSet(),
                    targetItemIds = changed.map { it.itemId }.toSet()
                )
                onDone()
            }
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
