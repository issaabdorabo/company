package com.example.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.InventoryApp
import com.example.data.cloud.CloudBackupItem
import com.example.data.cloud.CloudCollaborationRepository
import com.example.data.cloud.CloudOperationAuditEntry
import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
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
import com.example.data.model.ItemSerialSubUnit
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
import com.example.util.QrPairingHelper
import com.example.util.QrPairingPayload
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
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private data class CloudPairingSnapshot(
        val revision: Long,
        val entityChunks: List<List<CloudEntityUpsert>>,
        val auditLogs: List<OperationAuditLog>,
        val deviceName: String,
        val permissions: DevicePermissions
    )

    private val pairingSnapshots = ConcurrentHashMap<String, CloudPairingSnapshot>()
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
    private val connectivityManager = app.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            flushPendingQueuesWhenInternetReturns()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                flushPendingQueuesWhenInternetReturns()
            }
        }
    }

    private fun flushPendingQueuesWhenInternetReturns() {
        viewModelScope.launch {
            delay(1_000L)
            if (hasValidatedInternetConnection()) {
                if (cloudAuthorizationEnabled.value) {
                    if (isMainDevice.value) runCatching { flushCloudPendingSyncOperations() }
                    else if (isSubConnected.value) runCatching { flushPendingSubDeviceSyncOperations() }
                } else if (!isMainDevice.value && isSubConnected.value) {
                    runCatching { flushPendingSubDeviceSyncOperations() }
                }
            }
        }
    }

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

    // Latest cloud heartbeat timestamps keyed by the linked installation fingerprint.
    private val _cloudDeviceLastSeenAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val cloudDeviceLastSeenAt: StateFlow<Map<String, Long>> = _cloudDeviceLastSeenAt.asStateFlow()

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

    val itemSerialSubUnits: StateFlow<List<ItemSerialSubUnit>> = repository.allItemSerialSubUnits
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    // UI Feedback Messages
    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    private var subSyncJob: Job? = null
    private var cloudOutboxJob: Job? = null
    private var cloudRealtimeJob: Job? = null
    private var cloudRealtimeWorkspaceId: String = ""
    private val subSyncMutex = Mutex()
    private val pendingPushMutex = Mutex()
    private val cloudPushMutex = Mutex()

    private val cloudAuthorizationEnabled = MutableStateFlow(false)
    val isCloudSyncEnabled: StateFlow<Boolean> = cloudAuthorizationEnabled.asStateFlow()

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
        runCatching { connectivityManager?.registerDefaultNetworkCallback(connectivityCallback) }
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

                    // Device linking and sync are internet-only; never start a LAN listener.
                    syncServer.stop()
                } else {
                    syncServer.stop()
                    if (state.subToken.isBlank() || state.permissions.isKicked) {
                        subSyncJob?.cancel()
                        subSyncJob = null
                    } else {
                        if (subSyncJob?.isActive != true) {
                            subSyncJob = launch {
                                while (isActive) {
                                    if (cloudAuthorizationEnabled.value) {
                                        delay(15_000L)
                                    } else {
                                        _lastSyncError.value = "المزامنة المحلية عبر Wi-Fi ونقطة الاتصال متوقفة. سجّل الجهاز في التعاون السحابي للمزامنة عبر الإنترنت."
                                        delay(15_000L)
                                    }
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
        // The Realtime coordinator belongs to InventoryApp.applicationScope and deliberately
        // survives Activity/ViewModel destruction while the foreground service keeps the process alive.
        runCatching { connectivityManager?.unregisterNetworkCallback(connectivityCallback) }
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
            val cloudLastSeen = _cloudDeviceLastSeenAt.value[slot.boundInstallationId] ?: 0L
            val effectiveLastSeen = if (cloudAuthorizationEnabled.value) cloudLastSeen else slot.lastSeenAt
            slot.isClaimed && slot.boundInstallationId.isNotBlank() &&
                now - effectiveLastSeen in 0L..45_000L
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
            val assignedWarehouseUnavailable = assignedWarehouses.isNotEmpty() &&
                (!hasValidatedInternetConnection() ||
                    assignedWarehouses.any { !isAssignedSubDeviceOnline(it) })
            if (assignedWarehouseUnavailable) {
                viewModelScope.launch {
                    _userMessage.emit(
                        "لا يمكن تنفيذ العملية على المخزن الفرعي. يجب أن يكون الجهاز الفرعي متصلاً فعلياً بالإنترنت."
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
                    if (isMainDevice.value && warehouseIds.isNotEmpty()) {
                        awaitLinkedSubDevicesReady(action, warehouseIds)
                    }
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
                else "تم التبديل إلى وضع الجهاز الرئيسي. الربط بين الأجهزة يتم عبر الإنترنت فقط."
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

    @Deprecated("LAN pairing is disabled; cloud QR pairing is required.")
    fun refreshMainServerInfo() {
        syncServer.stop()
        viewModelScope.launch {
            _userMessage.emit("الربط المحلي متوقف. استخدم باركود الربط عبر الإنترنت من شاشة الأجهزة.")
        }
    }

    @Deprecated("LAN workspace codes are no longer used.")
    fun regenerateWorkspaceCode() {
        syncServer.stop()
        viewModelScope.launch {
            _userMessage.emit("رموز الربط المحلية متوقفة. استخدم باركود التعاون السحابي.")
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
            // Cloud-only pairing: never issue LAN/IP or hotspot credentials.
            if (!cloudAuthorizationEnabled.value) {
                _userMessage.emit("لإنشاء باركود ربط عبر الإنترنت، سجّل دخول الجهاز الرئيسي في مساحة التعاون السحابي أولاً. الربط المحلي ونقطة الاتصال غير مستخدمين.")
                return@launch
            }
            val cloudWorkspaceId = preferences.cloudWorkspaceIdFlow.first()
            val mainCloudDeviceId = preferences.cloudDeviceIdFlow.first()
            if (cloudWorkspaceId.isBlank() || mainCloudDeviceId.isBlank()) {
                _userMessage.emit("تعذر إنشاء باركود الإنترنت: مساحة التعاون أو تسجيل الجهاز الرئيسي غير مكتمل.")
                return@launch
            }
            val cloudPairingReady = true
            val code = "CLOUD-" + cloudWorkspaceId.take(8)
            val hostDisplay = ""
            val plainPassword = ""
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
                lastSeenAt = 0L,
                pairingExpiresAt = now + 10L * 60L * 1000L
            )

            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            current.add(0, newSlot)
            preferences.saveLinkedSubDevices(current)
            if (cloudPairingReady) {
                // Re-open the main-device Realtime subscription immediately so the new QR
                // can be scanned without waiting for the periodic heartbeat cycle.
                startCloudOutboxCoordinator()
            }

            val creds = CreatedDeviceCredentials(
                deviceSlotId = newSlot.id,
                deviceName = newSlot.deviceName,
                workspaceCode = code,
                hostAddress = hostDisplay,
                plainPassword = plainPassword,
                cloudWorkspaceId = if (cloudPairingReady) cloudWorkspaceId else "",
                mainCloudDeviceId = if (cloudPairingReady) mainCloudDeviceId else "",
                sessionToken = if (cloudPairingReady) sessionToken else ""
            )
            _sessionPairingCredentials.value = _sessionPairingCredentials.value + (newSlot.id to creds)
            _createdDeviceCredentials.value = creds
            _userMessage.emit("تم إنشاء بيانات الربط والباركود للجهاز الفرعي ($cleanName)")
        }
    }

    fun showSessionCredentialsQr(slotId: String) {
        if (!isMainDevice.value) return
        viewModelScope.launch {
            var slot = preferences.getLinkedSubDevicesSnapshot().firstOrNull {
                it.id == slotId && !it.isClaimed && !it.isKicked && !it.wasUnlinked
            } ?: run {
                _userMessage.emit("رمز ربط هذا الجهاز غير متاح أو تم استخدامه بالفعل.")
                return@launch
            }
            if (slot.pairingExpiresAt <= System.currentTimeMillis()) {
                slot = slot.copy(
                    sessionToken = UUID.randomUUID().toString(),
                    pairingExpiresAt = System.currentTimeMillis() + 10L * 60L * 1000L,
                    updatedAt = System.currentTimeMillis()
                )
                val slots = preferences.getLinkedSubDevicesSnapshot().map {
                    if (it.id == slotId) slot else it
                }
                preferences.saveLinkedSubDevices(slots)
            }
            val workspaceId = preferences.cloudWorkspaceIdFlow.first()
            val mainDeviceId = preferences.cloudDeviceIdFlow.first()
            if (!cloudAuthorizationEnabled.value || workspaceId.isBlank() || mainDeviceId.isBlank() || slot.sessionToken.isBlank()) {
                _userMessage.emit("تعذر استعادة رمز الربط. افتح التعاون السحابي وتأكد من تسجيل الجهاز الرئيسي.")
                return@launch
            }
            require(slot.pairingExpiresAt > System.currentTimeMillis()) { "انتهت صلاحية رمز الربط. أعد إصدار الرمز." }
            // Refresh the Firebase listener whenever this QR token is shown or re-issued.
            startCloudOutboxCoordinator()
            val credentials = CreatedDeviceCredentials(
                deviceSlotId = slot.id,
                deviceName = slot.deviceName,
                workspaceCode = "CLOUD-" + workspaceId.take(8),
                hostAddress = "",
                plainPassword = "",
                cloudWorkspaceId = workspaceId,
                mainCloudDeviceId = mainDeviceId,
                sessionToken = slot.sessionToken
            )
            _sessionPairingCredentials.value = _sessionPairingCredentials.value + (slotId to credentials)
            _createdDeviceCredentials.value = credentials
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
            if (!cloudAuthorizationEnabled.value) {
                _userMessage.emit("سجّل الجهاز الرئيسي في مساحة التعاون السحابي لإعادة إصدار باركود الإنترنت.")
                return@launch
            }
            val workspaceId = preferences.cloudWorkspaceIdFlow.first()
            val mainDeviceId = preferences.cloudDeviceIdFlow.first()
            if (workspaceId.isBlank() || mainDeviceId.isBlank()) {
                _userMessage.emit("تعذر إعادة الربط: مساحة التعاون السحابي غير جاهزة.")
                return@launch
            }
            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val idx = current.indexOfFirst { it.id == slotId }
            if (idx < 0) return@launch
            val previous = current[idx]
            val newSecret = UUID.randomUUID().toString()
            current[idx] = previous.copy(
                isKicked = false,
                isClaimed = false,
                wasUnlinked = false,
                boundInstallationId = "",
                sessionToken = newSecret,
                updatedAt = System.currentTimeMillis()
            )
            preferences.saveLinkedSubDevices(current)
            startCloudOutboxCoordinator()
            val credentials = CreatedDeviceCredentials(
                deviceSlotId = previous.id,
                deviceName = previous.deviceName,
                workspaceCode = "CLOUD-" + workspaceId.take(8),
                hostAddress = "",
                plainPassword = "",
                cloudWorkspaceId = workspaceId,
                mainCloudDeviceId = mainDeviceId,
                sessionToken = newSecret
            )
            _sessionPairingCredentials.value = _sessionPairingCredentials.value + (previous.id to credentials)
            _createdDeviceCredentials.value = credentials
            repository.recordOperation(
                OperationAuditLog(
                    actorName = userName.value.ifBlank { "المسؤول" },
                    deviceName = "الجهاز الرئيسي",
                    operationType = "REACTIVATE_DEVICE",
                    title = "إعادة تفعيل جهاز فرعي",
                    details = "تمت إعادة تفعيل الجهاز (" + previous.deviceName + ") وإصدار باركود ربط عبر الإنترنت."
                )
            )
            _userMessage.emit("تمت إعادة تفعيل " + previous.deviceName + ". امسح باركود الإنترنت الجديد من الجهاز الفرعي.")
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

    fun pairWithCloudMainDevice(payload: QrPairingPayload) {
        viewModelScope.launch {
            if (preferences.deviceRoleFlow.first() != DeviceRole.SUB) {
                _pairingError.value = "حوّل هذا الجهاز إلى وضع الجهاز الفرعي قبل مسح رمز الربط."
                return@launch
            }
            if (preferences.subSessionTokenFlow.first().isNotBlank()) {
                _pairingError.value = "هذا الجهاز مرتبط بالفعل بجهاز رئيسي."
                return@launch
            }
            val lockedUntil = preferences.pairingLockedUntilFlow.first()
            if (lockedUntil > System.currentTimeMillis()) {
                _pairingError.value = "محاولات الربط مقفلة مؤقتاً؛ حاول بعد انتهاء مدة القفل."
                return@launch
            }
            _isPairingInProgress.value = true
            _pairingError.value = null
            try {
                require(payload.isCloudPairing) { "رمز الربط السحابي غير مكتمل" }
                val userId = cloudCollaborationRepo.currentUserIdOrNull()
                    ?: error("سجّل الدخول بحساب الجهاز الفرعي في شاشة التعاون السحابي أولاً")
                // Resolve the email invitation before checking membership. This is required
                // when the sub-user scans the QR directly from Device Management without
                // first opening the cloud workspace screen.
                val availableWorkspaces = cloudCollaborationRepo.listMyWorkspaces()
                val invitedWorkspace = availableWorkspaces.firstOrNull { it.id == payload.cloudWorkspaceId }
                    ?: error("لم تتم إضافة بريد حساب الجهاز الفرعي إلى مساحة العمل الرئيسية بعد")
                val workspace = cloudCollaborationRepo.getWorkspace(invitedWorkspace.id)
                require(workspace.ownerUserId != userId) {
                    "استخدم حساباً فرعياً مختلفاً عن حساب الجهاز الرئيسي"
                }
                require(cloudCollaborationRepo.listWorkspaceMembers(payload.cloudWorkspaceId)
                    .any { it.userId == userId && it.isActive }) {
                    "تعذر تفعيل دعوة هذا الحساب في مساحة عمل الجهاز الرئيسي. تحقق من البريد المدعو."
                }

                val savedWorkspaceId = preferences.cloudWorkspaceIdFlow.first()
                if (savedWorkspaceId != payload.cloudWorkspaceId) {
                    preferences.saveCloudWorkspace(workspace.id, workspace.name)
                }
                val fingerprint = preferences.getOrCreateInstallationId()
                val existingDeviceId = preferences.cloudDeviceIdFlow.first()
                val registeredDevices = cloudCollaborationRepo.listWorkspaceDevices(payload.cloudWorkspaceId)
                val validSavedDevice = registeredDevices.firstOrNull {
                    it.id == existingDeviceId && it.userId == userId &&
                        it.deviceFingerprint == fingerprint && !it.isRevoked
                }
                val existingDevice = registeredDevices.firstOrNull {
                    it.userId == userId && it.deviceFingerprint == fingerprint && !it.isRevoked
                }
                val registeredDeviceId = (validSavedDevice?.id ?: existingDevice?.id ?: cloudCollaborationRepo.registerDevice(
                    payload.cloudWorkspaceId,
                    preferences.deviceNameFlow.first().ifBlank { "Android " + android.os.Build.MODEL.orEmpty().take(40) },
                    fingerprint
                )).also { preferences.saveCloudDeviceId(it) }

                val claimOperationId = UUID.randomUUID().toString()
                val claim = buildJsonObject {
                    put("type", "PAIR_CLAIM")
                    put("operationId", claimOperationId)
                    put("slotId", payload.deviceSlotId)
                    put("senderDeviceId", registeredDeviceId)
                    put("senderUserId", userId)
                    put("targetDeviceId", payload.mainCloudDeviceId)
                }
                val claimAck = cloudCollaborationRepo.sendWorkspaceEventAndAwaitAck(
                    payload.cloudWorkspaceId,
                    payload.mainCloudDeviceId,
                    claim,
                    transportSecret = payload.sessionToken,
                    expectedSenderDeviceId = payload.mainCloudDeviceId,
                    timeoutMillis = 45_000L
                )
                require(claimAck["success"]?.jsonPrimitive?.contentOrNull == "true") {
                    claimAck["error"]?.jsonPrimitive?.contentOrNull ?: "لم يعتمد الجهاز الرئيسي طلب الربط"
                }
                val permissionsRaw = claimAck["permissions"]?.jsonPrimitive?.contentOrNull
                    ?: error("لم تصل صلاحيات الجهاز الفرعي من الجهاز الرئيسي")
                val permissions = LocalSyncJson.permissionsFromJson(JSONObject(permissionsRaw))
                val deviceName = claimAck["deviceName"]?.jsonPrimitive?.contentOrNull
                    .orEmpty().ifBlank { "جهاز فرعي" }
                val totalChunks = claimAck["totalChunks"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                    ?: error("لم تصل معلومات أجزاء النسخة الأولية")
                require(totalChunks > 0 && totalChunks <= 100_000) { "عدد أجزاء النسخة الأولية غير صالح" }
                val snapshotRevision = claimAck["snapshotRevision"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                    ?: error("لم يصل رقم إصدار النسخة الأولية")

                val allEntities = JSONArray()
                var auditLogs = JSONArray()
                for (chunkIndex in 0 until totalChunks) {
                    val requestId = UUID.randomUUID().toString()
                    val request = buildJsonObject {
                        put("type", "INITIAL_SYNC_REQUEST")
                        put("operationId", requestId)
                        put("slotId", payload.deviceSlotId)
                        put("senderDeviceId", registeredDeviceId)
                        put("senderUserId", userId)
                        put("targetDeviceId", payload.mainCloudDeviceId)
                        put("chunkIndex", chunkIndex)
                        put("snapshotRevision", snapshotRevision)
                    }
                    val chunkAck = cloudCollaborationRepo.sendWorkspaceEventAndAwaitAck(
                        payload.cloudWorkspaceId,
                        payload.mainCloudDeviceId,
                        request,
                        transportSecret = payload.sessionToken,
                        expectedSenderDeviceId = payload.mainCloudDeviceId,
                        timeoutMillis = 45_000L
                    )
                    require(chunkAck["success"]?.jsonPrimitive?.contentOrNull == "true") {
                        chunkAck["error"]?.jsonPrimitive?.contentOrNull ?: "تعذر استلام جزء من بيانات المخزون"
                    }
                    val chunkJson = chunkAck["entitiesChunk"]?.jsonPrimitive?.contentOrNull
                        ?: error("وصل جزء مزامنة فارغ أو غير صالح")
                    val chunkArray = JSONArray(chunkJson)
                    for (index in 0 until chunkArray.length()) allEntities.put(chunkArray.get(index))
                    if (chunkIndex == totalChunks - 1) {
                        auditLogs = JSONArray(chunkAck["auditLogs"]?.jsonPrimitive?.contentOrNull ?: "[]")
                    }
                }

                val upserts = LocalSyncJson.upsertsFromJsonArray(allEntities)
                if (upserts.isEmpty()) {
                    repository.clearAllSyncedData()
                } else {
                    val records = upserts.map { entity ->
                        CloudEntityRecord(
                            id = entity.docId,
                            workspaceId = payload.cloudWorkspaceId,
                            entityType = entity.entityType,
                            localId = entity.localId,
                            payload = entity.payload,
                            isDeleted = entity.isDeleted
                        )
                    }
                    repository.replaceDatabaseFromCloudEntitiesSafely(records).getOrThrow()
                }
                repository.importOperationLogsFromMain(LocalSyncJson.auditLogsFromJsonArray(auditLogs))
                preferences.savePairedSubDevice(
                    hostUrl = "",
                    workspaceCode = "CLOUD-" + payload.cloudWorkspaceId.take(8),
                    deviceSlotId = payload.deviceSlotId,
                    deviceName = deviceName,
                    sessionToken = payload.sessionToken,
                    permissions = permissions,
                    appliedRevision = snapshotRevision
                )
                preferences.resetPairingFailures()
                // Reconnect the sub-device's private Realtime listener immediately after
                // its shared pairing secret has been persisted.
                startCloudOutboxCoordinator()
                val completeOperationId = UUID.randomUUID().toString()
                runCatching {
                    cloudCollaborationRepo.sendWorkspaceEventAndAwaitAck(
                        payload.cloudWorkspaceId,
                        payload.mainCloudDeviceId,
                        buildJsonObject {
                            put("type", "PAIR_COMPLETE")
                            put("operationId", completeOperationId)
                            put("slotId", payload.deviceSlotId)
                            put("senderDeviceId", registeredDeviceId)
                            put("senderUserId", userId)
                            put("targetDeviceId", payload.mainCloudDeviceId)
                        },
                        transportSecret = payload.sessionToken,
                        expectedSenderDeviceId = payload.mainCloudDeviceId,
                        timeoutMillis = 20_000L
                    )
                }
                _lastSyncError.value = null
                _userMessage.emit("تم ربط الجهاز عبر الإنترنت واستلام بيانات المخزون وسجل العمليات.")
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                val detail = failure.localizedMessage ?: "تعذر إكمال الربط عبر الإنترنت"
                val lock = preferences.recordPairingFailure()
                _pairingError.value = if (lock.second > System.currentTimeMillis()) {
                    "تم قفل محاولات الربط مؤقتاً بسبب تكرار المحاولات. $detail"
                } else detail
            } finally {
                _isPairingInProgress.value = false
            }
        }
    }

    @Deprecated("LAN/hotspot pairing is disabled. Scan the cloud QR code instead.")
    fun pairWithMainDevice(
        hostAddress: String,
        workspaceCodeInput: String,
        passwordInput: String
    ) {
        _pairingError.value = "الربط المحلي وعبر نقطة الاتصال متوقف. امسح باركود الربط السحابي من الجهاز الرئيسي بعد تسجيل حساب الجهاز الفرعي."
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
            try {
                if (isMainDevice.value) {
                    if (cloudAuthorizationEnabled.value) {
                        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
                        val deviceId = preferences.cloudDeviceIdFlow.first()
                        if (deviceId.isNotBlank()) cloudCollaborationRepo.heartbeat(deviceId)
                        val devices = cloudCollaborationRepo.listWorkspaceDevices(workspaceId)
                        _cloudDeviceLastSeenAt.value = devices.mapNotNull { device ->
                            val seenAt = device.lastSeenAt?.let { value ->
                                runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()
                            }
                            if (seenAt != null && device.deviceFingerprint.isNotBlank()) device.deviceFingerprint to seenAt else null
                        }.toMap()
                        val received = requestSubDeviceSync()
                        val delivered = flushCloudPendingSyncOperations()
                        if (!received || !delivered) {
                            _userMessage.emit("اكتملت محاولة المزامنة؛ بعض الأجهزة غير متصلة أو لديها عمليات تنتظر التأكيد.")
                        } else {
                            _userMessage.emit("تمت مزامنة العمليات في الاتجاهين بين الجهاز الرئيسي والأجهزة الفرعية.")
                        }
                    } else {
                        _lastSyncError.value = "المزامنة عبر الشبكة المحلية متوقفة. سجّل الدخول في التعاون السحابي لاستخدام الإنترنت."
                        _userMessage.emit(_lastSyncError.value.orEmpty())
                    }
                    _userMessage.emit("اكتملت محاولة المزامنة وتحديث حالة الأجهزة.")
                } else if (cloudAuthorizationEnabled.value) {
                    val cloudDeviceId = preferences.cloudDeviceIdFlow.first()
                    if (cloudDeviceId.isNotBlank()) cloudCollaborationRepo.heartbeat(cloudDeviceId)
                    val delivered = flushPendingSubDeviceSyncOperations()
                    if (!delivered) _userMessage.emit("توجد عمليات محلية تنتظر تأكيد وصولها إلى الجهاز الرئيسي.")
                    else _userMessage.emit("تم تحديث حالة الاتصال وإرسال العمليات المعلقة عبر الإنترنت.")
                } else {
                    _lastSyncError.value = "المزامنة عبر Wi-Fi ونقطة الاتصال متوقفة. سجّل الدخول في التعاون السحابي."
                    _userMessage.emit(_lastSyncError.value.orEmpty())
                }
            } finally {
                _isSyncingNow.value = false
            }
        }
    }

    private suspend fun requestSubDeviceSync(): Boolean {
        if (!isMainDevice.value || !cloudAuthorizationEnabled.value) return true
        if (!hasValidatedInternetConnection()) return false
        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
        val senderDeviceId = preferences.cloudDeviceIdFlow.first()
        val senderUserId = cloudCollaborationRepo.currentUserIdOrNull() ?: return false
        if (workspaceId.isBlank() || senderDeviceId.isBlank()) return false
        val slots = preferences.getLinkedSubDevicesSnapshot().filter {
            it.isClaimed && !it.isKicked && !it.wasUnlinked && it.sessionToken.isNotBlank()
        }
        if (slots.isEmpty()) return true
        val cloudDevices = runCatching { cloudCollaborationRepo.listWorkspaceDevices(workspaceId) }
            .getOrElse { return false }
        var allDelivered = true
        for (slot in slots) {
            val remoteDevice = cloudDevices.firstOrNull {
                it.deviceFingerprint == slot.boundInstallationId && !it.isRevoked
            }
            val lastSeen = _cloudDeviceLastSeenAt.value[slot.boundInstallationId] ?: 0L
            if (remoteDevice == null || System.currentTimeMillis() - lastSeen !in 0L..45_000L) {
                allDelivered = false
                continue
            }
            val operationId = UUID.randomUUID().toString()
            val request = buildJsonObject {
                put("type", "SYNC_REQUEST")
                put("operationId", operationId)
                put("senderDeviceId", senderDeviceId)
                put("senderUserId", senderUserId)
                put("targetDeviceId", remoteDevice.id)
            }
            val ack = runCatching {
                cloudCollaborationRepo.sendWorkspaceEventAndAwaitAck(
                    workspaceId,
                    remoteDevice.id,
                    request,
                    transportSecret = slot.sessionToken,
                    expectedSenderDeviceId = remoteDevice.id,
                    timeoutMillis = 45_000L
                )
            }.getOrNull()
            if (ack?.get("success")?.jsonPrimitive?.contentOrNull != "true") {
                allDelivered = false
                ack?.get("error")?.jsonPrimitive?.contentOrNull?.let { _lastSyncError.value = it }
            }
        }
        return allDelivered
    }

    /** Called by the navigation shell so revocation/permission changes are checked on every screen change. */
    fun syncOnNavigation() {
        if (isMainDevice.value || !isSubConnected.value) return
        viewModelScope.launch {
            if (cloudAuthorizationEnabled.value) {
                val cloudDeviceId = preferences.cloudDeviceIdFlow.first()
                if (cloudDeviceId.isNotBlank()) runCatching { cloudCollaborationRepo.heartbeat(cloudDeviceId) }
                if (hasValidatedInternetConnection()) runCatching { flushPendingSubDeviceSyncOperations() }
            } else {
                _lastSyncError.value = "المزامنة المحلية عبر Wi-Fi ونقطة الاتصال متوقفة. سجّل الجهاز في التعاون السحابي."
            }
        }
    }

    @Deprecated("LAN synchronization is disabled; cloud Realtime is the only supported transport.")
    private suspend fun pullFromMainOnce(silent: Boolean): Unit {
        _lastSyncError.value = "المزامنة المحلية عبر Wi-Fi ونقطة الاتصال متوقفة. استخدم التعاون السحابي عبر الإنترنت."
        if (!silent) _userMessage.emit(_lastSyncError.value.orEmpty())
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
            // Do not fall back to a LAN request when unlinking legacy pairings.
            // Cloud membership/device revocation is managed by the collaboration flow.
            if (!cloudAuthorizationEnabled.value && host.isNotBlank() && token.isNotBlank()) {
                _userMessage.emit("تم إيقاف طلبات فك الارتباط عبر الشبكة المحلية؛ أدر إلغاء الجهاز من مساحة التعاون السحابي.")
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
    }

    private suspend fun awaitLinkedSubDevicesReady(
        action: DeviceAction,
        warehouseIds: Set<Long>
    ) {
        val slots = preferences.getLinkedSubDevicesSnapshot().filter { slot ->
            !slot.isKicked && !slot.wasUnlinked &&
                warehouseIds.any { warehouseId ->
                    slot.canViewAllWarehouses ||
                        slot.warehousePermissions.any { it.warehouseId == warehouseId && it.canView }
                }
        }
        if (slots.isEmpty()) return
        require(cloudAuthorizationEnabled.value) {
            "هذا المخزن مخصص لجهاز فرعي. سجّل الجهازين في التعاون السحابي؛ الربط المحلي أو نقطة الاتصال غير مدعومين."
        }
        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
        require(workspaceId.isNotBlank()) { "مساحة التعاون السحابي غير جاهزة لهذا المخزن." }
        require(hasValidatedInternetConnection()) { "يلزم اتصال إنترنت موثّق قبل تنفيذ عملية على مخزن فرعي" }
        val senderDeviceId = preferences.cloudDeviceIdFlow.first()
        val senderUserId = cloudCollaborationRepo.currentUserIdOrNull()
            ?: error("انتهت جلسة حساب Google")
        val cloudDevices = cloudCollaborationRepo.listWorkspaceDevices(workspaceId)
        for (slot in slots) {
            require(slot.isClaimed) {
                "المخزن مخصص للجهاز (" + slot.deviceName + ") لكنه لم يُربط بعد."
            }
            val assignedWarehouseIds = warehouseIds.filter { warehouseId ->
                slot.canViewAllWarehouses ||
                    slot.warehousePermissions.any { it.warehouseId == warehouseId && it.canView }
            }.toSet()
            require(assignedWarehouseIds.isNotEmpty()) {
                "لم يتم تحديد مخزن صالح للجهاز الفرعي (" + slot.deviceName + ")"
            }
            // Online main-device operations require the assigned sub-device to ACK below;
            // the separate canWorkOffline permission is only for offline work on the sub-device.
            val slotWarehouseIds = assignedWarehouseIds
            val target = cloudDevices.firstOrNull {
                it.deviceFingerprint == slot.boundInstallationId && !it.isRevoked
            } ?: error("تعذر العثور على اتصال الجهاز الفرعي (${slot.deviceName})")
            val operationId = UUID.randomUUID().toString()
            val event = buildJsonObject {
                put("type", "PREPARE_MAIN_OPERATION")
                put("operationId", operationId)
                put("senderDeviceId", senderDeviceId)
                put("senderUserId", senderUserId)
                put("targetDeviceId", target.id)
                put("actionType", action.name)
                put("warehouseIds", JSONArray(slotWarehouseIds.toList()).toString())
            }
            val ack = cloudCollaborationRepo.sendWorkspaceEventAndAwaitAck(
                workspaceId, target.id, event, transportSecret = slot.sessionToken, expectedSenderDeviceId = target.id
            )
            val accepted = ack["success"]?.jsonPrimitive?.contentOrNull == "true"
            if (!accepted) {
                error(ack["error"]?.jsonPrimitive?.contentOrNull ?: "لم يؤكد الجهاز الفرعي جاهزيته لاستلام العملية")
            }
        }
    }

    private fun operationEntitiesForScope(
        all: List<com.example.data.cloud.CloudEntityUpsert>,
        actionType: String,
        warehouseIds: Set<Long>,
        targetItemIds: Set<Long>,
        targetVoucherIds: Set<Long>
    ): List<com.example.data.cloud.CloudEntityUpsert> {
        fun Long?.matches(ids: Set<Long>) = this != null && (ids.isEmpty() || this in ids)
        fun Map<String, Any?>.number(key: String): Long? = when (val value = this[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
        val catalogAction = actionType in setOf(
            DeviceAction.ADD_OR_MANAGE_CATALOG.name,
            DeviceAction.EDIT_CATALOG_ITEMS.name
        )
        val relatedVouchers = all.filter { entity ->
            entity.entityType == "voucher" &&
                (entity.localId in targetVoucherIds ||
                    entity.payload.number("sourceWarehouseId")?.let { it in warehouseIds } == true ||
                    entity.payload.number("destinationWarehouseId")?.let { it in warehouseIds } == true)
        }.map { it.localId }.toSet() + targetVoucherIds

        val selected = all.filter { entity ->
            when (entity.entityType) {
                "warehouse" -> entity.localId in warehouseIds
                "voucher" -> entity.localId in relatedVouchers
                "voucher_item" -> entity.payload.number("voucherId")?.let { it in relatedVouchers } == true
                "serial" -> entity.payload.number("warehouseId")?.let { it in warehouseIds } == true &&
                    entity.payload.number("itemId").matches(targetItemIds)
                "adjustment" -> entity.payload.number("warehouseId")?.let { it in warehouseIds } == true &&
                    entity.payload.number("itemId").matches(targetItemIds)
                "association" -> entity.payload.number("warehouseId")?.let { it in warehouseIds } == true &&
                    entity.payload.number("itemId").matches(targetItemIds)
                "item" -> entity.localId in targetItemIds || catalogAction
                "item_status", "serial_sub_unit", "unit", "smaller_unit", "category",
                "supplier", "beneficiary", "party" -> catalogAction
                else -> false
            }
        }.toMutableList()

        if (actionType == DeviceAction.DELETE_ITEM.name) {
            targetItemIds.forEach { id ->
                selected += com.example.data.cloud.CloudEntityUpsert(
                    docId = "item_$id", entityType = "item", localId = id,
                    payload = emptyMap(), isDeleted = true
                )
            }
        }
        if (actionType == DeviceAction.DELETE_ISSUE_VOUCHER.name ||
            actionType == DeviceAction.DELETE_SUPPLY_VOUCHER.name
        ) {
            targetVoucherIds.forEach { id ->
                selected += com.example.data.cloud.CloudEntityUpsert(
                    docId = "voucher_$id", entityType = "voucher", localId = id,
                    payload = emptyMap(), isDeleted = true
                )
            }
        }
        return selected.distinctBy { it.docId }
    }

    private suspend fun sendCloudAck(
        workspaceId: String,
        operationId: String,
        success: Boolean,
        recipientDeviceId: String,
        transportSecret: String,
        error: String = "",
        extra: JsonObject = buildJsonObject { }
    ) {
        require(transportSecret.isNotBlank()) { "مفتاح الربط المشترك غير متوفر لإرسال تأكيد العملية" }
        val senderDeviceId = preferences.cloudDeviceIdFlow.first()
        cloudCollaborationRepo.broadcastWorkspaceEvent(
            workspaceId,
            recipientDeviceId,
            buildJsonObject {
                put("type", "ACK")
                put("operationId", operationId)
                put("success", success)
                put("error", error)
                put("senderDeviceId", senderDeviceId)
                extra.forEach { (key, value) -> put(key, value) }
            },
            transportSecret = transportSecret
        )
    }

    private suspend fun handleCloudWorkspaceEvent(workspaceId: String, event: JsonObject) {
        val type = event["type"]?.jsonPrimitive?.contentOrNull ?: return
        when (type) {
            "SUB_OPERATION" -> if (isMainDevice.value) handleCloudSubOperation(workspaceId, event)
            "PAIR_CLAIM" -> if (isMainDevice.value) handleCloudPairClaim(workspaceId, event)
            "INITIAL_SYNC_REQUEST" -> if (isMainDevice.value) handleCloudInitialSyncRequest(workspaceId, event)
            "PAIR_COMPLETE" -> if (isMainDevice.value) handleCloudPairComplete(workspaceId, event)
            "MAIN_OPERATION", "PREPARE_MAIN_OPERATION", "SYNC_REQUEST" ->
                if (!isMainDevice.value) handleCloudMainCommand(workspaceId, event, type)
        }
    }

    private fun chunkInitialCloudEntities(entities: List<CloudEntityUpsert>): List<List<CloudEntityUpsert>> {
        val maxPlaintextBytes = 110 * 1024
        val chunks = mutableListOf<List<CloudEntityUpsert>>()
        var current = mutableListOf<CloudEntityUpsert>()
        var currentBytes = 2
        entities.sortedBy { it.docId }.forEach { entity ->
            val entityBytes = LocalSyncJson.upsertsToJsonArray(listOf(entity))
                .toString().toByteArray(Charsets.UTF_8).size
            require(entityBytes + 2 < maxPlaintextBytes) {
                "يوجد سجل مخزني أكبر من الحد الآمن لنقله عبر قناة المزامنة"
            }
            if (current.isNotEmpty() && currentBytes + entityBytes + 1 > maxPlaintextBytes) {
                chunks += current.toList()
                current = mutableListOf()
                currentBytes = 2
            }
            current += entity
            currentBytes += entityBytes + 1
        }
        if (current.isNotEmpty() || chunks.isEmpty()) chunks += current.toList()
        return chunks
    }

    private suspend fun handleCloudPairClaim(workspaceId: String, event: JsonObject) {
        val operationId = event["operationId"]?.jsonPrimitive?.contentOrNull ?: return
        val slotId = event["slotId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderDeviceId = event["senderDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderUserId = event["senderUserId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val targetDeviceId = event["targetDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var transportSecret = ""
        try {
            val mainDeviceId = preferences.cloudDeviceIdFlow.first()
            require(targetDeviceId == mainDeviceId) { "طلب الربط موجّه إلى جهاز رئيسي آخر" }
            val workspace = cloudCollaborationRepo.getWorkspace(workspaceId)
            require(workspace.ownerUserId == cloudCollaborationRepo.currentUserIdOrNull()) {
                "اعتماد الربط متاح للمستخدم الرئيسي فقط"
            }
            val devices = cloudCollaborationRepo.listWorkspaceDevices(workspaceId)
            val remoteDevice = devices.firstOrNull {
                it.id == senderDeviceId && it.userId == senderUserId && !it.isRevoked
            } ?: error("حساب الجهاز الفرعي غير مسجل في مساحة العمل")
            require(senderUserId != workspace.ownerUserId) { "يجب أن يستخدم الجهاز الفرعي حساباً مختلفاً عن حساب الرئيسي" }
            cloudCollaborationRepo.listWorkspaceMembers(workspaceId)
                .firstOrNull { it.userId == senderUserId && it.isActive }
                ?: error("يجب إضافة حساب الجهاز الفرعي إلى مساحة التعاون أولاً")

            val current = preferences.getLinkedSubDevicesSnapshot().toMutableList()
            val slotIndex = current.indexOfFirst { it.id == slotId }
            require(slotIndex >= 0) { "رمز ربط الجهاز غير موجود أو تم حذفه" }
            val slot = current[slotIndex]
            require(slot.pairingExpiresAt > System.currentTimeMillis()) { "انتهت صلاحية رمز الربط. اطلب من الجهاز الرئيسي إصدار رمز جديد." }
            transportSecret = slot.sessionToken
            require(event["_transportSecret"]?.jsonPrimitive?.contentOrNull == transportSecret) {
                "رمز QR لا يطابق خانة الجهاز المطلوبة"
            }
            require(transportSecret.isNotBlank() && !slot.isKicked && !slot.wasUnlinked) {
                "رمز الربط ملغى أو غير صالح"
            }
            require(!slot.isClaimed || slot.boundInstallationId == remoteDevice.deviceFingerprint) {
                "تم استخدام رمز الربط على جهاز آخر بالفعل"
            }
            val now = System.currentTimeMillis()
            val claimedSlot = slot.copy(
                isClaimed = true,
                wasUnlinked = false,
                boundInstallationId = remoteDevice.deviceFingerprint,
                updatedAt = now,
                lastSeenAt = now
            )
            current[slotIndex] = claimedSlot
            preferences.saveLinkedSubDevices(current)
            cloudCollaborationRepo.syncLinkedDevicePermissionsToCloud(workspaceId, claimedSlot)

            val revision = preferences.syncRevisionFlow.first()
            val chunks = chunkInitialCloudEntities(repository.exportAllEntitiesForCloud())
            val logs = repository.getOperationLogsForSyncToDevice(claimedSlot.deviceName)
            pairingSnapshots[slotId] = CloudPairingSnapshot(
                revision = revision,
                entityChunks = chunks,
                auditLogs = logs,
                deviceName = claimedSlot.deviceName,
                permissions = claimedSlot.toPermissions()
            )
            sendCloudAck(
                workspaceId = workspaceId,
                operationId = operationId,
                success = true,
                recipientDeviceId = senderDeviceId,
                transportSecret = transportSecret,
                extra = buildJsonObject {
                    put("deviceName", claimedSlot.deviceName)
                    put("permissions", LocalSyncJson.permissionsToJson(claimedSlot.toPermissions()).toString())
                    put("totalChunks", chunks.size)
                    put("snapshotRevision", revision)
                }
            )
            _userMessage.emit("تم ربط الجهاز الفرعي (" + claimedSlot.deviceName + ") عبر الإنترنت")
        } catch (failure: Exception) {
            if (transportSecret.isNotBlank()) {
                runCatching {
                    sendCloudAck(
                        workspaceId, operationId, false, senderDeviceId, transportSecret,
                        failure.localizedMessage ?: "تعذر اعتماد ربط الجهاز الفرعي"
                    )
                }
            }
        }
    }

    private suspend fun handleCloudInitialSyncRequest(workspaceId: String, event: JsonObject) {
        val operationId = event["operationId"]?.jsonPrimitive?.contentOrNull ?: return
        val slotId = event["slotId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderDeviceId = event["senderDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderUserId = event["senderUserId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val targetDeviceId = event["targetDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var transportSecret = ""
        try {
            require(targetDeviceId == preferences.cloudDeviceIdFlow.first()) {
                "طلب المزامنة الأولية موجّه إلى جهاز رئيسي آخر"
            }
            val remoteDevice = cloudCollaborationRepo.listWorkspaceDevices(workspaceId).firstOrNull {
                it.id == senderDeviceId && it.userId == senderUserId && !it.isRevoked
            } ?: error("الجهاز الفرعي غير مسجل أو تم إيقافه")
            val slot = preferences.getLinkedSubDevicesSnapshot().firstOrNull {
                it.id == slotId && it.isClaimed && !it.isKicked && !it.wasUnlinked &&
                    it.boundInstallationId == remoteDevice.deviceFingerprint
            } ?: error("الجهاز غير مرتبط بهذا الحساب")
            transportSecret = slot.sessionToken
            require(event["_transportSecret"]?.jsonPrimitive?.contentOrNull == transportSecret) {
                "قناة المزامنة لا تطابق سر ربط الجهاز الفرعي"
            }
            val snapshot = pairingSnapshots[slotId] ?: error("انتهت جلسة نقل النسخة الأولية؛ أعد مسح رمز الربط")
            val requestedRevision = event["snapshotRevision"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
            require(requestedRevision == snapshot.revision &&
                preferences.syncRevisionFlow.first() == snapshot.revision
            ) { "تغيرت بيانات المخزون أثناء الربط؛ أعد محاولة المزامنة الأولية" }
            val chunkIndex = event["chunkIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: error("رقم جزء المزامنة غير صالح")
            require(chunkIndex in snapshot.entityChunks.indices) { "جزء المزامنة المطلوب غير موجود" }
            val isLastChunk = chunkIndex == snapshot.entityChunks.lastIndex
            val extra = buildJsonObject {
                put("chunkIndex", chunkIndex)
                put("totalChunks", snapshot.entityChunks.size)
                put("entitiesChunk", LocalSyncJson.upsertsToJsonArray(snapshot.entityChunks[chunkIndex]).toString())
                if (isLastChunk) {
                    put("auditLogs", LocalSyncJson.auditLogsToJsonArray(snapshot.auditLogs).toString())
                    put("deviceName", snapshot.deviceName)
                }
            }
            sendCloudAck(
                workspaceId, operationId, true, senderDeviceId, transportSecret, extra = extra
            )
        } catch (failure: Exception) {
            if (transportSecret.isNotBlank()) {
                runCatching {
                    sendCloudAck(
                        workspaceId, operationId, false, senderDeviceId, transportSecret,
                        failure.localizedMessage ?: "تعذر إرسال جزء من المزامنة الأولية"
                    )
                }
            }
        }
    }

    private suspend fun handleCloudPairComplete(workspaceId: String, event: JsonObject) {
        val operationId = event["operationId"]?.jsonPrimitive?.contentOrNull ?: return
        val slotId = event["slotId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderDeviceId = event["senderDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderUserId = event["senderUserId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val targetDeviceId = event["targetDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var transportSecret = ""
        try {
            require(targetDeviceId == preferences.cloudDeviceIdFlow.first()) {
                "تأكيد الربط موجّه إلى جهاز رئيسي آخر"
            }
            val remoteDevice = cloudCollaborationRepo.listWorkspaceDevices(workspaceId).firstOrNull {
                it.id == senderDeviceId && it.userId == senderUserId && !it.isRevoked
            } ?: error("الجهاز الفرعي غير مسجل أو تم إيقافه")
            val slot = preferences.getLinkedSubDevicesSnapshot().firstOrNull {
                it.id == slotId && it.isClaimed && !it.isKicked && !it.wasUnlinked &&
                    it.boundInstallationId == remoteDevice.deviceFingerprint
            } ?: error("الجهاز غير مرتبط بهذا الحساب")
            transportSecret = slot.sessionToken
            require(event["_transportSecret"]?.jsonPrimitive?.contentOrNull == transportSecret) {
                "قناة إكمال الربط لا تطابق سر الجهاز"
            }
            pairingSnapshots.remove(slotId)
            sendCloudAck(workspaceId, operationId, true, senderDeviceId, transportSecret)
        } catch (failure: Exception) {
            if (transportSecret.isNotBlank()) {
                runCatching {
                    sendCloudAck(
                        workspaceId, operationId, false, senderDeviceId, transportSecret,
                        failure.localizedMessage ?: "تعذر تأكيد اكتمال الربط"
                    )
                }
            }
        }
    }

    private suspend fun handleCloudSubOperation(workspaceId: String, event: JsonObject) {
        val operationId = event["operationId"]?.jsonPrimitive?.contentOrNull ?: return
        val senderDeviceId = event["senderDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderUserId = event["senderUserId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val operationBody = event["operation"]?.jsonPrimitive?.contentOrNull.orEmpty()
        var transportSecret = ""
        try {
            val remoteDevice = cloudCollaborationRepo.listWorkspaceDevices(workspaceId)
                .firstOrNull { it.id == senderDeviceId && !it.isRevoked && it.userId == senderUserId }
                ?: error("الجهاز المرسل غير مسجل أو تم إيقافه")
            val slot = preferences.getLinkedSubDevicesSnapshot().firstOrNull {
                it.isClaimed && !it.isKicked && !it.wasUnlinked &&
                    it.boundInstallationId == remoteDevice.deviceFingerprint
            } ?: error("لا يوجد جهاز فرعي مرتبط مطابق لهذا الحساب")
            transportSecret = slot.sessionToken
            require(transportSecret.isNotBlank()) { "مفتاح الربط المشترك لهذا الجهاز غير متوفر" }
            require(event["_transportSecret"]?.jsonPrimitive?.contentOrNull == transportSecret) {
                "قناة الحدث لا تطابق سر ربط الجهاز الفرعي"
            }
            val targetDeviceId = event["targetDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            require(targetDeviceId == preferences.cloudDeviceIdFlow.first()) { "العملية ليست موجهة إلى الجهاز الرئيسي الحالي" }
            syncServer.applyCloudOperation(slot.sessionToken, operationBody)
            sendCloudAck(
                workspaceId, operationId, true,
                recipientDeviceId = senderDeviceId,
                transportSecret = transportSecret
            )
        } catch (failure: Exception) {
            if (transportSecret.isNotBlank()) {
                sendCloudAck(
                    workspaceId,
                    operationId,
                    false,
                    recipientDeviceId = senderDeviceId,
                    transportSecret = transportSecret,
                    error = failure.localizedMessage ?: "تعذر تطبيق العملية على الجهاز الرئيسي"
                )
            }
        }
    }

    private suspend fun handleCloudMainCommand(
        workspaceId: String,
        event: JsonObject,
        type: String
    ) {
        val operationId = event["operationId"]?.jsonPrimitive?.contentOrNull ?: return
        val targetDeviceId = event["targetDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderUserId = event["senderUserId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val senderDeviceId = event["senderDeviceId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val myDeviceId = preferences.cloudDeviceIdFlow.first()
        val transportSecret = preferences.subSessionTokenFlow.first()
        if (transportSecret.isBlank()) return
        try {
            require(event["_transportSecret"]?.jsonPrimitive?.contentOrNull == transportSecret) {
                "قناة الحدث لا تطابق سر ربط هذا الجهاز"
            }
            if (targetDeviceId != myDeviceId) return
            val workspace = cloudCollaborationRepo.getWorkspace(workspaceId)
            require(senderUserId == workspace.ownerUserId) { "العملية الواردة ليست من المستخدم الرئيسي" }
            cloudCollaborationRepo.listWorkspaceDevices(workspaceId).firstOrNull {
                it.id == senderDeviceId && it.userId == workspace.ownerUserId && !it.isRevoked
            } ?: error("جهاز المستخدم الرئيسي غير مسجل أو تم إيقافه")
            if (type == "SYNC_REQUEST") {
                require(flushPendingSubDeviceOperationsOverCloud()) {
                    "توجد عمليات محلية لم يتمكن الجهاز الرئيسي من استلامها بعد"
                }
                sendCloudAck(
                    workspaceId, operationId, true,
                    recipientDeviceId = senderDeviceId,
                    transportSecret = transportSecret
                )
                return
            }
            if (type == "PREPARE_MAIN_OPERATION") {
                val warehouseIds = longIdsFromJson(event["warehouseIds"]?.jsonPrimitive?.contentOrNull.orEmpty())
                require(flushPendingSubDeviceOperationsOverCloud()) {
                    "توجد عمليات غير متزامنة محفوظة محلياً؛ تعذر تأكيد جاهزية الجهاز"
                }
                val permissions = devicePermissions.value
                require(warehouseIds.all { id ->
                    permissions.canViewAllWarehouses ||
                        permissions.warehousePermissions.any { it.warehouseId == id && it.canView }
                }) { "الجهاز غير مخول للوصول إلى أحد المخازن المستهدفة" }
                sendCloudAck(
                    workspaceId, operationId, true,
                    recipientDeviceId = senderDeviceId,
                    transportSecret = transportSecret
                )
                return
            }

            val operationBody = event["operation"]?.jsonPrimitive?.contentOrNull.orEmpty()
            applyMainOperationLocally(operationId, operationBody, workspaceId)
            sendCloudAck(
                workspaceId, operationId, true,
                recipientDeviceId = senderDeviceId,
                transportSecret = transportSecret
            )
        } catch (failure: Exception) {
            sendCloudAck(
                workspaceId,
                operationId,
                false,
                recipientDeviceId = senderDeviceId,
                transportSecret = transportSecret,
                error = failure.localizedMessage ?: "تعذر تطبيق العملية على الجهاز الفرعي"
            )
        }
    }

    private suspend fun applyMainOperationLocally(
        operationId: String,
        rawBody: String,
        workspaceId: String
    ) {
        if (repository.hasAppliedSyncOperation(operationId)) return
        val body = JSONObject(rawBody)
        val actionType = body.optString("actionType", "GENERAL")
        val warehouseIds = longIdsFromJson(body.optJSONArray("warehouseIds")?.toString().orEmpty())
        val permissions = devicePermissions.value
        require(warehouseIds.all { id ->
            permissions.canViewAllWarehouses ||
                permissions.warehousePermissions.any { it.warehouseId == id && it.canView }
        }) { "لا تملك صلاحية عرض أحد المخازن المستهدفة" }

        val incoming = LocalSyncJson.upsertsFromJsonArray(body.optJSONArray("entities"))
        val merged = repository.exportAllEntitiesForCloud().associateBy { it.docId }.toMutableMap()
        incoming.forEach { entity ->
            if (entity.isDeleted) merged.remove(entity.docId) else merged[entity.docId] = entity
        }
        val targetItems = longIdsFromJson(body.optJSONArray("targetItemIds")?.toString().orEmpty())
        val targetVouchers = longIdsFromJson(body.optJSONArray("targetVoucherIds")?.toString().orEmpty())
        if (actionType == DeviceAction.DELETE_ITEM.name) {
            merged.entries.removeAll { (_, entity) ->
                (entity.entityType == "item" && entity.localId in targetItems) ||
                    (entity.entityType == "serial" && (entity.payload["itemId"] as? Number)?.toLong() in targetItems) ||
                    (entity.entityType == "association" && (entity.payload["itemId"] as? Number)?.toLong() in targetItems)
            }
        }
        if (actionType == DeviceAction.DELETE_ISSUE_VOUCHER.name ||
            actionType == DeviceAction.DELETE_SUPPLY_VOUCHER.name
        ) {
            merged.entries.removeAll { (_, entity) ->
                (entity.entityType == "voucher" && entity.localId in targetVouchers) ||
                    (entity.entityType == "voucher_item" &&
                        (entity.payload["voucherId"] as? Number)?.toLong() in targetVouchers)
            }
        }

        val records = merged.values.map { entity ->
            CloudEntityRecord(
                id = entity.docId,
                workspaceId = workspaceId,
                entityType = entity.entityType,
                localId = entity.localId,
                payload = entity.payload
            )
        }
        repository.replaceDatabaseFromCloudEntitiesSafely(records).getOrThrow()
        val operationTitle = body.optString("operationTitle", "عملية من الجهاز الرئيسي")
        val operationDetails = body.optString("operationDetails", "")
        val actorName = body.optString("actorName", "المستخدم الرئيسي")
        repository.recordOperation(
            OperationAuditLog(
                actorName = actorName,
                deviceName = "الجهاز الرئيسي • سحابي",
                operationType = actionType,
                title = operationTitle,
                details = operationDetails
            )
        )
        notificationManager.notifyOperation(
            title = "عملية من المستخدم الرئيسي: $operationTitle",
            details = "بواسطة: $actorName • $operationDetails",
            isRemoteSubDeviceAlert = true
        )
        repository.markSyncOperationApplied(operationId)
    }

    private fun startCloudOutboxCoordinator() {
        cloudOutboxJob?.cancel()
        val coordinatorJob = app.applicationScope.launch {
            var lastOfflinePermissionSyncAt = 0L
            while (isActive) {
                val cloudDeviceId = preferences.cloudDeviceIdFlow.first()
                val cloudWorkspaceId = preferences.cloudWorkspaceIdFlow.first()
                if (cloudAuthorizationEnabled.value && cloudDeviceId.isNotBlank() && cloudWorkspaceId.isNotBlank()) {
                    if (isMainDevice.value) syncServer.stop()
                    val transportSecrets = if (isMainDevice.value) {
                        preferences.getLinkedSubDevicesSnapshot()
                            .filter { !it.isKicked && !it.wasUnlinked && it.sessionToken.isNotBlank() }
                            .map { it.sessionToken }
                            .distinct()
                    } else {
                        listOf(preferences.subSessionTokenFlow.first()).filter { it.isNotBlank() }
                    }
                    val subscriptionKey = "$cloudWorkspaceId|${transportSecrets.sorted().joinToString("|")}"
                    if (cloudRealtimeWorkspaceId != subscriptionKey) {
                        cloudRealtimeJob?.cancel()
                        cloudRealtimeJob = null
                        cloudRealtimeWorkspaceId = subscriptionKey
                    }
                    if (transportSecrets.isNotEmpty() && cloudRealtimeJob?.isActive != true) {
                        cloudRealtimeJob = launch {
                            runCatching {
                                cloudCollaborationRepo.observeWorkspaceEvents(
                                    cloudWorkspaceId,
                                    cloudDeviceId,
                                    transportSecrets
                                ).collect { event ->
                                    runCatching { handleCloudWorkspaceEvent(cloudWorkspaceId, event) }
                                        .onFailure { error ->
                                            _lastSyncError.value = error.localizedMessage ?: "تعذر معالجة عملية تعاون واردة"
                                        }
                                }
                            }.onFailure { error ->
                                _lastSyncError.value = "تعذر فتح قناة التعاون المشفر: ${error.localizedMessage ?: "تحقق من الاتصال"}"
                            }
                        }
                    } else if (transportSecrets.isEmpty()) {
                        cloudRealtimeJob?.cancel()
                        cloudRealtimeJob = null
                    }
                    runCatching { cloudCollaborationRepo.heartbeat(cloudDeviceId) }
                        .onFailure { error ->
                            _lastSyncError.value = "تعذر تحديث اتصال الجهاز السحابي: ${error.localizedMessage ?: "تحقق من الإنترنت"}"
                        }
                    runCatching { cloudCollaborationRepo.listWorkspaceDevices(cloudWorkspaceId) }
                        .onSuccess { devices ->
                            _cloudDeviceLastSeenAt.value = devices.mapNotNull { device ->
                                val seenAt = device.lastSeenAt?.let { value ->
                                    runCatching {
                                        java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli()
                                    }.getOrNull()
                                }
                                if (seenAt != null && device.deviceFingerprint.isNotBlank()) {
                                    device.deviceFingerprint to seenAt
                                } else null
                            }.toMap()
                        }
                    if (hasValidatedInternetConnection()) {
                        if (isMainDevice.value) runCatching { flushCloudPendingSyncOperations() }
                        else runCatching { flushPendingSubDeviceSyncOperations() }
                    }
                } else {
                    cloudRealtimeJob?.cancel()
                    cloudRealtimeJob = null
                    cloudRealtimeWorkspaceId = ""
                }
                val now = System.currentTimeMillis()
                if (now - lastOfflinePermissionSyncAt >= 60_000L) {
                    runCatching { syncOfflineDesignationsQuietly() }
                    lastOfflinePermissionSyncAt = now
                }
                delay(15_000L)
            }
        }
        cloudOutboxJob = coordinatorJob
        app.replaceCloudCoordinator(this, coordinatorJob)
    }

    private suspend fun flushCloudPendingSyncOperations(): Boolean = cloudPushMutex.withLock {
        if (!isMainDevice.value || !cloudAuthorizationEnabled.value) return@withLock true
        if (!hasValidatedInternetConnection()) return@withLock false
        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
        val senderDeviceId = preferences.cloudDeviceIdFlow.first()
        val senderUserId = cloudCollaborationRepo.currentUserIdOrNull() ?: return@withLock false
        if (workspaceId.isBlank() || senderDeviceId.isBlank()) return@withLock false

        val pending = repository.getCloudPendingSyncOperations()
        if (pending.isEmpty()) return@withLock true
        val slots = preferences.getLinkedSubDevicesSnapshot().filter {
            it.isClaimed && !it.isKicked && !it.wasUnlinked && it.boundInstallationId.isNotBlank()
        }
        val cloudDevices = runCatching { cloudCollaborationRepo.listWorkspaceDevices(workspaceId) }
            .getOrElse { return@withLock false }

        for (operation in pending) {
            val data = runCatching { JSONObject(operation.entitiesJson) }.getOrElse {
                repository.recordCloudPendingSyncFailure(operation.operationId, "بيانات العملية المحلية غير صالحة")
                return@withLock false
            }
            val warehouseIds = longIdsFromJson(operation.warehouseIdsJson)
            val targetItemIds = longIdsFromJson(data.optJSONArray("targetItemIds")?.toString().orEmpty())
            val targetVoucherIds = longIdsFromJson(data.optJSONArray("targetVoucherIds")?.toString().orEmpty())
            val targetSlots = slots.filter { slot ->
                if (warehouseIds.isEmpty()) {
                    if (operation.actionType == DeviceAction.EDIT_CATALOG_ITEMS.name) slot.canEditCatalogItems
                    else slot.canAddItems
                } else {
                    warehouseIds.any { warehouseId ->
                        slot.canViewAllWarehouses ||
                            slot.warehousePermissions.any { it.warehouseId == warehouseId && it.canView }
                    }
                }
            }
            if (targetSlots.isEmpty()) {
                repository.removeCloudPendingSyncOperation(operation.operationId)
                continue
            }

            for (slot in targetSlots) {
                val slotWarehouseIds = if (warehouseIds.isEmpty()) emptySet() else warehouseIds.filter { warehouseId ->
                    slot.canViewAllWarehouses ||
                        slot.warehousePermissions.any { it.warehouseId == warehouseId && it.canView }
                }.toSet()
                val remoteDevice = cloudDevices.firstOrNull {
                    it.deviceFingerprint == slot.boundInstallationId && !it.isRevoked
                } ?: run {
                    repository.recordCloudPendingSyncFailure(
                        operation.operationId,
                        "الجهاز الفرعي " + slot.deviceName + " غير مسجل في التعاون السحابي"
                    )
                    return@withLock false
                }
                // The awaited private-channel ACK below is the authoritative live-online
                // proof. Do not reject a reachable device using a stale heartbeat snapshot.
                val scopedEntities = operationEntitiesForScope(
                    all = repository.exportAllEntitiesForCloud(),
                    actionType = operation.actionType,
                    warehouseIds = slotWarehouseIds,
                    targetItemIds = targetItemIds,
                    targetVoucherIds = targetVoucherIds
                )
                val body = JSONObject()
                    .put("clientOperationId", operation.operationId)
                    .put("actionType", operation.actionType)
                    .put("operationTitle", operation.operationTitle)
                    .put("operationDetails", operation.operationDetails)
                    .put("actorName", operation.actorName)
                    .put("warehouseIds", JSONArray(slotWarehouseIds.toList()))
                    .put("targetItemIds", JSONArray(targetItemIds.toList()))
                    .put("targetVoucherIds", JSONArray(targetVoucherIds.toList()))
                    .put("entities", LocalSyncJson.upsertsToJsonArray(scopedEntities))

                val event = buildJsonObject {
                    put("type", "MAIN_OPERATION")
                    put("operationId", operation.operationId)
                    put("senderDeviceId", senderDeviceId)
                    put("senderUserId", senderUserId)
                    put("targetDeviceId", remoteDevice.id)
                    put("operation", body.toString())
                }
                val ack = runCatching {
                    cloudCollaborationRepo.sendWorkspaceEventAndAwaitAck(
                        workspaceId, remoteDevice.id, event,
                        transportSecret = slot.sessionToken,
                        expectedSenderDeviceId = remoteDevice.id
                    )
                }.getOrElse { failure ->
                    repository.recordCloudPendingSyncFailure(
                        operation.operationId,
                        failure.localizedMessage ?: "انقطع الاتصال قبل تأكيد الجهاز الفرعي"
                    )
                    return@withLock false
                }
                if (ack["success"]?.jsonPrimitive?.contentOrNull != "true") {
                    repository.recordCloudPendingSyncFailure(
                        operation.operationId,
                        ack["error"]?.jsonPrimitive?.contentOrNull ?: "لم يؤكد الجهاز الفرعي تطبيق العملية"
                    )
                    return@withLock false
                }
            }
            repository.removeCloudPendingSyncOperation(operation.operationId)
        }
        true
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

    suspend fun flushPendingOperationsBeforeCollaboration() {
        val localPendingOk = if (!isMainDevice.value && isSubConnected.value) {
            flushPendingSubDeviceSyncOperations()
        } else true
        if (!localPendingOk) {
            _lastSyncError.value = "توجد عمليات محفوظة محلياً على هذا الجهاز لم تنتقل بعد إلى الجهاز الرئيسي. ستبقى محفوظة محلياً."
        }
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
            val actionType = action?.name ?: "GENERAL"
            val scopedEntities = runCatching {
                operationEntitiesForScope(
                    all = repository.exportAllEntitiesForCloud(),
                    actionType = actionType,
                    warehouseIds = warehouseIds,
                    targetItemIds = targetItemIds,
                    targetVoucherIds = targetVoucherIds
                )
            }.getOrElse { error ->
                _lastSyncError.value = "تعذر تجهيز بيانات العملية للمزامنة: ${error.localizedMessage}"
                return@launch
            }

            if (isMainDevice.value) {
                preferences.incrementSyncRevision()
                if (cloudAuthorizationEnabled.value && (warehouseIds.isNotEmpty() || targetItemIds.isNotEmpty())) {
                    val operationId = UUID.randomUUID().toString()
                    val data = JSONObject()
                        .put("records", LocalSyncJson.upsertsToJsonArray(scopedEntities))
                        .put("targetItemIds", JSONArray(targetItemIds.toList()))
                        .put("targetVoucherIds", JSONArray(targetVoucherIds.toList()))
                    repository.enqueueCloudPendingSyncOperation(
                        CloudPendingSyncOperation(
                            operationId = operationId,
                            actionType = actionType,
                            operationTitle = operationTitle,
                            operationDetails = operationDetails,
                            actorName = userName.value,
                            warehouseIdsJson = JSONArray(warehouseIds.toList()).toString(),
                            entitiesJson = data.toString()
                        )
                    )
                    if (!flushCloudPendingSyncOperations()) {
                        _lastSyncError.value = "تم حفظ العملية على الجهاز الرئيسي، وتنتظر تأكيد وصولها إلى الجهاز الفرعي."
                    }
                }
                return@launch
            }

            try {
                val operation = PendingSyncOperation(
                    operationId = UUID.randomUUID().toString(),
                    actionType = actionType,
                    operationTitle = operationTitle,
                    operationDetails = operationDetails,
                    actorName = userName.value,
                    warehouseIdsJson = JSONArray(warehouseIds.toList()).toString(),
                    targetItemIdsJson = JSONArray(targetItemIds.toList()).toString(),
                    targetVoucherIdsJson = JSONArray(targetVoucherIds.toList()).toString(),
                    entitiesJson = LocalSyncJson.upsertsToJsonArray(scopedEntities).toString()
                )
                repository.enqueuePendingSyncOperation(operation)

                if (!flushPendingSubDeviceSyncOperations()) {
                    _lastSyncError.value = "تم حفظ العملية محلياً، وستبقى في قائمة الإرسال حتى يؤكد الجهاز الرئيسي استلامها."
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
        if (!cloudAuthorizationEnabled.value) {
            _lastSyncError.value = "المزامنة المحلية عبر Wi-Fi ونقطة الاتصال متوقفة. سجّل الجهاز في التعاون السحابي."
            return@withLock false
        }
        flushPendingSubDeviceOperationsOverCloud()
    }

    private suspend fun flushPendingSubDeviceOperationsOverCloud(): Boolean {
        if (!hasValidatedInternetConnection()) return false
        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
        val senderDeviceId = preferences.cloudDeviceIdFlow.first()
        val senderUserId = cloudCollaborationRepo.currentUserIdOrNull() ?: return false
        if (workspaceId.isBlank() || senderDeviceId.isBlank()) return false
        val workspace = runCatching { cloudCollaborationRepo.getWorkspace(workspaceId) }.getOrNull() ?: return false
        val ownerDevice = runCatching { cloudCollaborationRepo.listWorkspaceDevices(workspaceId) }
            .getOrNull()
            ?.filter { it.userId == workspace.ownerUserId && !it.isRevoked }
            ?.maxByOrNull { device ->
                device.lastSeenAt?.let { value ->
                    runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(0L)
                } ?: 0L
            } ?: return false
        val ownerLastSeen = ownerDevice.lastSeenAt?.let { value ->
            runCatching { java.time.OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrDefault(0L)
        } ?: 0L
        if (System.currentTimeMillis() - ownerLastSeen !in 0L..45_000L) return false

        val pending = repository.getPendingSyncOperations()
        for (operation in pending) {
            val body = JSONObject()
                .put("clientOperationId", operation.operationId)
                .put("actionType", operation.actionType)
                .put("operationTitle", operation.operationTitle)
                .put("operationDetails", operation.operationDetails)
                .put("actorName", operation.actorName)
                .put("warehouseIds", JSONArray(longIdsFromJson(operation.warehouseIdsJson).toList()))
                .put("targetItemIds", JSONArray(longIdsFromJson(operation.targetItemIdsJson).toList()))
                .put("targetVoucherIds", JSONArray(longIdsFromJson(operation.targetVoucherIdsJson).toList()))
                .put("entities", JSONArray(operation.entitiesJson))
            val event = buildJsonObject {
                put("type", "SUB_OPERATION")
                put("operationId", operation.operationId)
                put("senderDeviceId", senderDeviceId)
                put("senderUserId", senderUserId)
                put("targetDeviceId", ownerDevice.id)
                put("operation", body.toString())
            }
            val ack = runCatching {
                cloudCollaborationRepo.sendWorkspaceEventAndAwaitAck(
                    workspaceId, ownerDevice.id, event, transportSecret = preferences.subSessionTokenFlow.first(), expectedSenderDeviceId = ownerDevice.id
                )
            }.getOrElse { failure ->
                repository.recordPendingSyncFailure(
                    operation.operationId,
                    failure.localizedMessage ?: "لم يصل تأكيد من الجهاز الرئيسي"
                )
                _lastSyncError.value = "العملية محفوظة محلياً وتنتظر تأكيد الجهاز الرئيسي."
                return false
            }
            if (ack["success"]?.jsonPrimitive?.contentOrNull != "true") {
                val error = ack["error"]?.jsonPrimitive?.contentOrNull ?: "رفض الجهاز الرئيسي العملية"
                repository.recordPendingSyncFailure(operation.operationId, error)
                _lastSyncError.value = error
                return false
            }
            repository.removePendingSyncOperation(operation.operationId)
            // The local audit row was recorded when the offline operation was committed.
            // The main device records its own copy after receiving the same operation.
            _lastSyncError.value = null
        }
        return true
    }

    // 1. Warehouse Operations    // 1. Warehouse Operations
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

    // Google Drive appDataFolder belongs to one Google account and cannot be shared.
    // Keep one canonical cloud-backup owner: the main device only.
    private fun mayUseCloudBackups(): Boolean = devicePermissions.value.isMainDevice

    private fun emitBackupPermissionDenied() {
        viewModelScope.launch { _userMessage.emit("صلاحية النسخ الاحتياطي والتصدير غير ممنوحة من المستخدم الرئيسي") }
    }

    private fun emitCloudBackupOwnerOnly() {
        viewModelScope.launch {
            _cloudAuthError.value = "إدارة النسخ السحابية المركزية متاحة من الجهاز الرئيسي فقط؛ حساب Google للجهاز الفرعي مستقل ولا يشارك مساحة appDataFolder."
        }
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
        if (!mayUseCloudBackups()) { if (!silent) emitCloudBackupOwnerOnly(); return }
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
        if (!mayUseCloudBackups()) { emitCloudBackupOwnerOnly(); return }
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
        if (!mayUseCloudBackups()) { emitCloudBackupOwnerOnly(); return }
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
        if (!mayUseCloudBackups()) { emitCloudBackupOwnerOnly(); return }
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
        if (!mayUseCloudBackups()) { emitCloudBackupOwnerOnly(); return }
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
                    val cloudReady = cloudAuthorizationEnabled.value && hasValidatedInternetConnection()
                    val activeDevices = linkedSubDevices.value.count { slot ->
                        slot.isClaimed && !slot.isKicked && !slot.wasUnlinked
                    }
                    checks.add(
                        DiagnosticCheckItem(
                            title = "اتصال التعاون السحابي",
                            passed = cloudReady,
                            detail = if (cloudReady) {
                                "الاتصال بالإنترنت وقنوات Realtime متاحة • أجهزة فرعية مسجلة: $activeDevices"
                            } else {
                                "سجّل دخول الجهاز الرئيسي في مساحة التعاون وتحقق من اتصال الإنترنت. لا يستخدم التطبيق خادم LAN أو نقطة اتصال."
                            },
                            status = if (cloudReady) DiagnosticStatus.PASS else DiagnosticStatus.WARNING
                        )
                    )
                } else {
                    val connected = isSubConnected.value && cloudAuthorizationEnabled.value
                    val internetReady = hasValidatedInternetConnection()
                    checks.add(
                        DiagnosticCheckItem(
                            title = "اتصال الجهاز الفرعي عبر الإنترنت",
                            passed = connected && internetReady,
                            detail = when {
                                connected && internetReady -> "الجهاز مرتبط سحابياً والإنترنت متاح؛ المزامنة عبر Realtime."
                                connected -> "الجهاز مرتبط سحابياً لكن اتصال الإنترنت غير متاح حالياً."
                                else -> "سجّل حساب الجهاز الفرعي في مساحة التعاون وامسح باركود الإنترنت من الجهاز الرئيسي."
                            },
                            status = if (connected && internetReady) DiagnosticStatus.PASS else DiagnosticStatus.WARNING
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

    fun saveItemSerialSubUnit(name: String, onDone: (Long) -> Unit = {}) {
        runAuthorizedAction(action = DeviceAction.ADD_OR_MANAGE_CATALOG, deniedMessage = "إضافة الوحدات الفرعية متاحة للمستخدم الرئيسي فقط") {
            val id = repository.saveItemSerialSubUnit(name)
            notifyAndPropagate(action = DeviceAction.ADD_OR_MANAGE_CATALOG, notificationTitle = "إضافة وحدة فرعية", notificationDetails = name.trim())
            onDone(id)
        }
    }

    suspend fun checkItemSerialSubUnitHasOperations(name: String): Boolean =
        repository.checkItemSerialSubUnitHasOperations(name)

    fun updateItemSerialSubUnitSafely(unit: ItemSerialSubUnit, newName: String, onResult: (Boolean) -> Unit = {}) {
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "تعديل الوحدات الفرعية متاح للمستخدم الرئيسي فقط") {
            val ok = repository.updateItemSerialSubUnitSafely(unit, newName)
            if (ok) notifyAndPropagate(action = DeviceAction.EDIT_CATALOG_ITEMS, notificationTitle = "تعديل وحدة فرعية", notificationDetails = "${unit.name} ← ${newName.trim()}")
            onResult(ok)
        }
    }

    fun deleteItemSerialSubUnitSafely(unit: ItemSerialSubUnit, onResult: (Boolean) -> Unit = {}) {
        runAuthorizedAction(action = DeviceAction.EDIT_CATALOG_ITEMS, deniedMessage = "حذف الوحدات الفرعية متاح للمستخدم الرئيسي فقط") {
            val ok = repository.deleteItemSerialSubUnitSafely(unit)
            if (ok) notifyAndPropagate(action = DeviceAction.EDIT_CATALOG_ITEMS, notificationTitle = "حذف وحدة فرعية", notificationDetails = unit.name)
            onResult(ok)
        }
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
                old.status != candidate.status || old.subUnit != candidate.subUnit || old.notes != candidate.notes
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
                "${row.serialNumber.ifBlank { "وحدة ${row.unitIndex}" }} [${row.status}]" +
                    (row.subUnit.takeIf { it.isNotBlank() }?.let { " / $it" } ?: "") +
                    if (row.notes.isBlank()) "" else " (${row.notes})"
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
