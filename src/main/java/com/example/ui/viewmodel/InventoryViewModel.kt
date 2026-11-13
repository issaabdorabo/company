package com.example.ui.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.InventoryApp
import com.example.data.cloud.CloudEntityUpsert
import com.example.data.cloud.CloudWorkspace
import com.example.data.cloud.CreatedDeviceCredentials
import com.example.data.cloud.DevicePermissions
import com.example.data.cloud.LinkedSubDevice
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
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as InventoryApp
    private val repository = app.repository
    private val preferences = app.preferencesManager
    private val cloudRepo = app.cloudSyncRepository

    // Preferences & Theme
    val theme: StateFlow<AppTheme> = preferences.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.LIGHT)

    val userName: StateFlow<String> = preferences.userNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "عيسى عبدربه")

    val orgName: StateFlow<String> = preferences.orgNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "المؤسسة العامة للتوريد والمخازن")

    val googleAccount: StateFlow<String> = preferences.googleAccountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val syncEnabled: StateFlow<Boolean> = preferences.syncEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lastSyncTime: StateFlow<Long> = preferences.lastSyncFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    val deviceRole: StateFlow<String> = preferences.deviceRoleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MAIN")

    val isMainDevice: StateFlow<Boolean> = preferences.isMainDeviceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val workspaceId: StateFlow<String> = preferences.workspaceIdFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val workspaceCode: StateFlow<String> = preferences.workspaceCodeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val devicePermissions: StateFlow<DevicePermissions> = preferences.devicePermissionsFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DevicePermissions())

    val pairingLockedUntil: StateFlow<Long> = preferences.pairingLockedUntilFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

    // Multi-device live state
    private val _linkedDevices = MutableStateFlow<List<LinkedSubDevice>>(emptyList())
    val linkedDevices: StateFlow<List<LinkedSubDevice>> = _linkedDevices.asStateFlow()

    private val _lastCreatedDeviceCredentials = MutableStateFlow<CreatedDeviceCredentials?>(null)
    val lastCreatedDeviceCredentials: StateFlow<CreatedDeviceCredentials?> = _lastCreatedDeviceCredentials.asStateFlow()

    private val _cloudRecoveryPromptWorkspace = MutableStateFlow<CloudWorkspace?>(null)
    val cloudRecoveryPromptWorkspace: StateFlow<CloudWorkspace?> = _cloudRecoveryPromptWorkspace.asStateFlow()

    private val _kickedAlertMessage = MutableStateFlow<String?>(null)
    val kickedAlertMessage: StateFlow<String?> = _kickedAlertMessage.asStateFlow()

    private val _isSyncingCloud = MutableStateFlow(false)
    val isSyncingCloud: StateFlow<Boolean> = _isSyncingCloud.asStateFlow()

    private val _pairingError = MutableStateFlow<String?>(null)
    val pairingError: StateFlow<String?> = _pairingError.asStateFlow()

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

    private var entityListenerJob: Job? = null
    private var devicesListenerJob: Job? = null
    private var memberListenerJob: Job? = null
    private var activeAuthUid: String? = null

    @Volatile
    private var lastLocalPushTimestamp = 0L

    /**
     * Must ONLY be called after Google Sign-In succeeds so unauthenticated Firestore calls never run.
     */
    fun onAuthenticatedSessionStarted(uid: String, email: String?) {
        if (activeAuthUid == uid && (entityListenerJob?.isActive == true)) return
        activeAuthUid = uid
        stopCloudListeners()

        viewModelScope.launch {
            if (!email.isNullOrBlank()) {
                preferences.setGoogleAccount(email)
            }
            val role = preferences.deviceRoleFlow.first()
            val savedWsId = preferences.workspaceIdFlow.first()
            val installId = preferences.getOrCreateInstallationId()

            if (role == "SUB" && savedWsId.isNotBlank()) {
                startSubDeviceSync(savedWsId)
            } else {
                // Main Device initialization & reinstall recovery detection
                val existingWorkspaces = cloudRepo.findOwnerWorkspaces().getOrNull().orEmpty()
                if (savedWsId.isBlank() && existingWorkspaces.isNotEmpty()) {
                    val ws = existingWorkspaces.first()
                    if (repository.isLikelyFreshSeedOnly()) {
                        // Offer immediate reinstall data restoration from Firestore
                        _cloudRecoveryPromptWorkspace.value = ws
                    } else {
                        preferences.saveMainWorkspace(ws.id, ws.workspaceCode)
                        startMainDeviceSync(ws.id)
                    }
                } else {
                    val wsResult = cloudRepo.ensureOwnerWorkspace(orgName.value, installId)
                    wsResult.onSuccess { ws ->
                        preferences.saveMainWorkspace(ws.id, ws.workspaceCode)
                        if (savedWsId.isBlank()) {
                            pushAllLocalToCloud(ws.id)
                        }
                        startMainDeviceSync(ws.id)
                    }.onFailure { err ->
                        Log.w("CloudSync", "Workspace init skipped/offline: ${err.message}")
                    }
                }
            }
        }
    }

    fun onSignedOut() {
        activeAuthUid = null
        stopCloudListeners()
        _linkedDevices.value = emptyList()
    }

    private fun stopCloudListeners() {
        entityListenerJob?.cancel()
        entityListenerJob = null
        devicesListenerJob?.cancel()
        devicesListenerJob = null
        memberListenerJob?.cancel()
        memberListenerJob = null
    }

    private fun startMainDeviceSync(wsId: String) {
        stopCloudListeners()
        devicesListenerJob = viewModelScope.launch {
            try {
                cloudRepo.observeLinkedDevices(wsId).collect { list ->
                    _linkedDevices.value = list
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CloudSync", "Devices listener ended: ${e.message}")
            }
        }
        startEntitiesListener(wsId)
    }

    private fun startSubDeviceSync(wsId: String) {
        stopCloudListeners()
        memberListenerJob = viewModelScope.launch {
            try {
                cloudRepo.observeMyMemberStatus(wsId).collect { status ->
                    if (status == null || status.isKicked || !status.isActive) {
                        preferences.markKickedLocally()
                        repository.clearAllSyncedData()
                        stopCloudListeners()
                        _kickedAlertMessage.value = "تم طرد هذا الجهاز أو إلغاء صلاحية دخوله من قبل الجهاز الرئيسي."
                    } else {
                        preferences.updateLiveMemberPermissions(status)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CloudSync", "Member listener error: ${e.message}")
            }
        }
        startEntitiesListener(wsId)
    }

    private fun startEntitiesListener(wsId: String) {
        entityListenerJob?.cancel()
        entityListenerJob = viewModelScope.launch {
            try {
                cloudRepo.observeWorkspaceEntities(wsId).collect { cloudRecords ->
                    if (!preferences.syncEnabledFlow.first()) return@collect
                    // Avoid redundant rewrite immediately after our own local batch push
                    val elapsedSincePush = System.currentTimeMillis() - lastLocalPushTimestamp
                    if (elapsedSincePush < 1200L) return@collect

                    val activeCloud = cloudRecords.filter { !it.isDeleted }
                    if (activeCloud.isNotEmpty()) {
                        val localSnapshotSize = repository.exportAllEntitiesForCloud().size
                        val shouldApply = !preferences.isMainDeviceFlow.first() ||
                            activeCloud.size != localSnapshotSize ||
                            elapsedSincePush > 2500L
                        if (shouldApply) {
                            repository.replaceDatabaseFromCloudEntities(cloudRecords)
                            preferences.updateLastSync()
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("CloudSync", "Entities listener stopped: ${e.message}")
            }
        }
    }

    private fun pushAllLocalToCloud(explicitWsId: String? = null, tombstones: List<CloudEntityUpsert> = emptyList()) {
        viewModelScope.launch {
            if (!syncEnabled.value || activeAuthUid == null) return@launch
            val wsId = explicitWsId ?: preferences.workspaceIdFlow.first()
            if (wsId.isBlank()) return@launch
            val perms = preferences.devicePermissionsFlow.first()
            if (!perms.isMainDevice && (perms.isKicked || perms.isReadOnly)) return@launch

            lastLocalPushTimestamp = System.currentTimeMillis()
            val allUpserts = repository.exportAllEntitiesForCloud() + tombstones
            cloudRepo.pushEntityRecords(wsId, allUpserts)
                .onSuccess { preferences.updateLastSync() }
                .onFailure { err -> Log.w("CloudSync", "Push error: ${err.message}") }
        }
    }

    // --- Cloud Recovery After Uninstall & Reinstall ---
    fun confirmCloudRecovery() {
        val targetWs = _cloudRecoveryPromptWorkspace.value ?: return
        _cloudRecoveryPromptWorkspace.value = null
        restoreFromCloudWorkspace(targetWs)
    }

    fun dismissRecoveryPrompt() {
        val targetWs = _cloudRecoveryPromptWorkspace.value
        _cloudRecoveryPromptWorkspace.value = null
        viewModelScope.launch {
            if (targetWs != null) {
                preferences.saveMainWorkspace(targetWs.id, targetWs.workspaceCode)
                startMainDeviceSync(targetWs.id)
            }
        }
    }

    fun restoreFromCloudWorkspace(targetWorkspace: CloudWorkspace? = null) {
        viewModelScope.launch {
            _isSyncingCloud.value = true
            try {
                val ws = targetWorkspace ?: run {
                    val savedId = preferences.workspaceIdFlow.first()
                    val owned = cloudRepo.findOwnerWorkspaces().getOrNull().orEmpty()
                    owned.firstOrNull { it.id == savedId } ?: owned.firstOrNull()
                }
                val wsIdToFetch = ws?.id ?: preferences.workspaceIdFlow.first()
                if (wsIdToFetch.isBlank()) {
                    _userMessage.emit("لم يتم العثور على قاعدة بيانات سحابية مرتبطة بهذا الحساب")
                    return@launch
                }

                val entitiesResult = cloudRepo.fetchAllWorkspaceEntities(wsIdToFetch)
                entitiesResult.onSuccess { records ->
                    val active = records.filter { !it.isDeleted }
                    if (active.isEmpty()) {
                        _userMessage.emit("القاعدة السحابية فارغة حالياً ولا تحتوي على بيانات سابقة")
                    } else {
                        repository.replaceDatabaseFromCloudEntities(records)
                        if (ws != null) {
                            preferences.saveMainWorkspace(ws.id, ws.workspaceCode)
                            startMainDeviceSync(ws.id)
                        }
                        preferences.updateLastSync()
                        _userMessage.emit("تم استرداد كافة البيانات من السحابة بنجاح (${active.size} سجل)")
                    }
                }.onFailure { e ->
                    _userMessage.emit("تعذر الاسترداد من السحابة: ${e.localizedMessage}")
                }
            } finally {
                _isSyncingCloud.value = false
            }
        }
    }

    // --- Multi-Device Management (Main Device Controls & Sub-Device Pairing) ---
    fun createSubDeviceSlot(
        deviceName: String,
        canAddItems: Boolean,
        canInward: Boolean,
        canDisburse: Boolean,
        canEditDelete: Boolean
    ) {
        viewModelScope.launch {
            if (!isMainDevice.value) {
                _userMessage.emit("فقط الجهاز الرئيسي يمكنه إنشاء كلمات مرور للأجهزة الفرعية")
                return@launch
            }
            var wsId = preferences.workspaceIdFlow.first()
            var wsCode = preferences.workspaceCodeFlow.first()
            if (wsId.isBlank() || wsCode.isBlank()) {
                val installId = preferences.getOrCreateInstallationId()
                val wsRes = cloudRepo.ensureOwnerWorkspace(orgName.value, installId)
                val ws = wsRes.getOrNull()
                if (ws == null) {
                    _userMessage.emit("تعذر تهيئة القاعدة السحابية، تأكد من الاتصال بالإنترنت")
                    return@launch
                }
                preferences.saveMainWorkspace(ws.id, ws.workspaceCode)
                wsId = ws.id
                wsCode = ws.workspaceCode
                pushAllLocalToCloud(wsId)
                startMainDeviceSync(wsId)
            }

            cloudRepo.createSubDeviceSlot(
                workspaceId = wsId,
                workspaceCode = wsCode,
                deviceName = deviceName,
                canAddItems = canAddItems,
                canInward = canInward,
                canDisburse = canDisburse,
                canEditDelete = canEditDelete
            ).onSuccess { creds ->
                _lastCreatedDeviceCredentials.value = creds
                _userMessage.emit("تم توليد كلمة مرور فريدة خاصة بالجهاز: ${creds.deviceName}")
            }.onFailure { err ->
                _userMessage.emit("فشل إنشاء الجهاز الفرعي: ${err.localizedMessage}")
            }
        }
    }

    fun clearCreatedDeviceCredentials() {
        _lastCreatedDeviceCredentials.value = null
    }

    fun updateSubDevicePermissions(
        device: LinkedSubDevice,
        canAddItems: Boolean,
        canInward: Boolean,
        canDisburse: Boolean,
        canEditDelete: Boolean
    ) {
        viewModelScope.launch {
            val wsId = preferences.workspaceIdFlow.first()
            if (wsId.isBlank()) return@launch
            cloudRepo.updateSubDevicePermissions(wsId, device, canAddItems, canInward, canDisburse, canEditDelete)
                .onSuccess {
                    _userMessage.emit("تم تحديث صلاحيات \"${device.deviceName}\" فوراً")
                }
                .onFailure {
                    _userMessage.emit("فشل تحديث الصلاحيات: ${it.localizedMessage}")
                }
        }
    }

    fun kickSubDevice(device: LinkedSubDevice) {
        viewModelScope.launch {
            val wsId = preferences.workspaceIdFlow.first()
            if (wsId.isBlank()) return@launch
            cloudRepo.kickSubDevice(wsId, device)
                .onSuccess {
                    _userMessage.emit("تم طرد \"${device.deviceName}\" وإلغاء كلمة مروره فوراً")
                }
                .onFailure {
                    _userMessage.emit("تعذر تنفيذ الطرد: ${it.localizedMessage}")
                }
        }
    }

    fun deleteSubDeviceSlot(device: LinkedSubDevice) {
        viewModelScope.launch {
            val wsId = preferences.workspaceIdFlow.first()
            if (wsId.isBlank()) return@launch
            cloudRepo.deleteSubDeviceSlot(wsId, device)
                .onSuccess { _userMessage.emit("تم حذف سجل الجهاز من القائمة") }
                .onFailure { _userMessage.emit("تعذر الحذف: ${it.localizedMessage}") }
        }
    }

    fun pairAsSubDevice(
        workspaceCodeInput: String,
        devicePasswordInput: String,
        onPaired: () -> Unit = {}
    ) {
        viewModelScope.launch {
            val lockedUntil = preferences.pairingLockedUntilFlow.first()
            val now = System.currentTimeMillis()
            if (lockedUntil > now) {
                val secs = ((lockedUntil - now) / 1000L).coerceAtLeast(1L)
                _pairingError.value = "تم قفل المحاولة مؤقتاً للحماية الأمنية. يرجى الانتظار $secs ثانية."
                return@launch
            }

            _pairingError.value = null
            _isSyncingCloud.value = true
            try {
                val installId = preferences.getOrCreateInstallationId()
                val res = cloudRepo.pairSubDevice(workspaceCodeInput, devicePasswordInput, installId)
                res.onSuccess { paired ->
                    preferences.resetPairingFailures()
                    preferences.savePairedSubDevice(paired)
                    val entitiesRes = cloudRepo.fetchAllWorkspaceEntities(paired.workspaceId)
                    entitiesRes.onSuccess { recs ->
                        if (recs.any { !it.isDeleted }) {
                            repository.replaceDatabaseFromCloudEntities(recs)
                        }
                    }
                    startSubDeviceSync(paired.workspaceId)
                    preferences.updateLastSync()
                    _userMessage.emit("تم الربط كجهاز فرعي (${paired.deviceName}) ومزامنة القاعدة الموحدة بنجاح")
                    onPaired()
                }.onFailure { err ->
                    val lockTs = preferences.recordPairingFailure()
                    _pairingError.value = if (lockTs > 0L) {
                        "تجاوزت عدد المحاولات الخاطئة المسموح به. تم تفعيل الحظر المؤقت لمدة 60 ثانية."
                    } else {
                        err.localizedMessage ?: "فشل الربط، تأكد من رمز القاعدة وكلمة المرور المنفردة"
                    }
                }
            } finally {
                _isSyncingCloud.value = false
            }
        }
    }

    fun clearPairingError() {
        _pairingError.value = null
    }

    fun switchToMainDeviceMode() {
        viewModelScope.launch {
            stopCloudListeners()
            preferences.clearWorkspaceLink()
            preferences.setDeviceRole("MAIN")
            val installId = preferences.getOrCreateInstallationId()
            val uid = activeAuthUid
            if (uid != null) {
                val existing = cloudRepo.findOwnerWorkspaces().getOrNull().orEmpty().firstOrNull()
                if (existing != null) {
                    preferences.saveMainWorkspace(existing.id, existing.workspaceCode)
                    startMainDeviceSync(existing.id)
                } else {
                    cloudRepo.ensureOwnerWorkspace(orgName.value, installId).onSuccess { ws ->
                        preferences.saveMainWorkspace(ws.id, ws.workspaceCode)
                        pushAllLocalToCloud(ws.id)
                        startMainDeviceSync(ws.id)
                    }
                }
            }
            _userMessage.emit("تم التحويل إلى نمط الجهاز الرئيسي")
        }
    }

    fun acknowledgeKickAndDisconnect() {
        _kickedAlertMessage.value = null
        viewModelScope.launch {
            stopCloudListeners()
            preferences.clearWorkspaceLink()
            preferences.setDeviceRole("MAIN")
            repository.seedInitialDataIfEmpty()
        }
    }

    // Permission Helpers
    fun checkPermission(action: DeviceAction, onDenied: () -> Unit, onAllowed: () -> Unit) {
        val p = devicePermissions.value
        if (p.isMainDevice) {
            onAllowed()
            return
        }
        if (p.isKicked) {
            onDenied()
            return
        }
        val allowed = when (action) {
            DeviceAction.ADD_OR_MANAGE_CATALOG -> p.canAddItems
            DeviceAction.INWARD -> p.canInward
            DeviceAction.DISBURSE_OR_TRANSFER -> p.canDisburse
            DeviceAction.EDIT_OR_DELETE -> p.canEditDelete
        }
        if (allowed) onAllowed() else onDenied()
    }

    fun checkCanEdit(onSubDeviceWarning: () -> Unit, onAllowed: () -> Unit) {
        checkPermission(DeviceAction.EDIT_OR_DELETE, onSubDeviceWarning, onAllowed)
    }

    // 1. Warehouse Operations
    fun saveWarehouse(warehouse: Warehouse, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (warehouse.id == 0L) {
                repository.insertWarehouse(warehouse)
                _userMessage.emit("تمت إضافة المخزن بنجاح")
            } else {
                repository.updateWarehouse(warehouse)
                _userMessage.emit("تم تحديث بيانات المخزن بنجاح")
            }
            pushAllLocalToCloud()
            onDone()
        }
    }

    suspend fun checkWarehouseHasStock(warehouseId: Long): Boolean {
        return repository.warehouseHasStock(warehouseId)
    }

    fun deleteWarehouse(warehouse: Warehouse) {
        viewModelScope.launch {
            repository.deleteWarehouse(warehouse)
            val tombstone = CloudEntityUpsert(
                docId = "warehouse_${warehouse.id}",
                entityType = "warehouse",
                localId = warehouse.id,
                payload = mapOf("id" to warehouse.id, "name" to warehouse.name),
                isDeleted = true
            )
            pushAllLocalToCloud(tombstones = listOf(tombstone))
            _userMessage.emit("تم حذف المخزن نهائياً")
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
            pushAllLocalToCloud()
            _userMessage.emit("تمت إضافة الصنف داخل المخزن بنجاح")
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
            pushAllLocalToCloud()
            _userMessage.emit("تم حذف الصنف نهائياً من هذا المخزن")
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
            pushAllLocalToCloud()
            _userMessage.emit("تم تحديث حد إعادة الطلب الخاص بالمخزن")
            onDone()
        }
    }

    // 2. Item Operations
    fun saveItem(item: Item, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (item.id == 0L) {
                repository.insertItem(item)
                _userMessage.emit("تمت إضافة الصنف بنجاح")
            } else {
                repository.updateItem(item)
                _userMessage.emit("تم تحديث بيانات الصنف بنجاح")
            }
            pushAllLocalToCloud()
            onDone()
        }
    }

    suspend fun checkItemHasTransactions(itemId: Long): Boolean {
        return repository.itemHasTransactions(itemId)
    }

    fun deleteItem(item: Item) {
        viewModelScope.launch {
            repository.deleteItem(item)
            val tombstone = CloudEntityUpsert(
                docId = "item_${item.id}",
                entityType = "item",
                localId = item.id,
                payload = mapOf("id" to item.id, "name" to item.name),
                isDeleted = true
            )
            pushAllLocalToCloud(tombstones = listOf(tombstone))
            _userMessage.emit("تم حذف الصنف نهائياً")
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
            pushAllLocalToCloud()
            _userMessage.emit("تم تعديل كمية المخزون وتوثيق السبب بنجاح")
            onDone()
        }
    }

    // 4. Beneficiary Operations
    fun saveBeneficiary(beneficiary: Beneficiary, onDone: (Long) -> Unit = {}) {
        viewModelScope.launch {
            val id = if (beneficiary.id == 0L) {
                val newId = repository.insertBeneficiary(beneficiary)
                _userMessage.emit("تمت إضافة الجهة المستفيدة بنجاح")
                newId
            } else {
                repository.updateBeneficiary(beneficiary)
                _userMessage.emit("تم تحديث بيانات الجهة المستفيدة")
                beneficiary.id
            }
            pushAllLocalToCloud()
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
                val tombstone = CloudEntityUpsert(
                    docId = "beneficiary_${beneficiary.id}",
                    entityType = "beneficiary",
                    localId = beneficiary.id,
                    payload = mapOf("id" to beneficiary.id),
                    isDeleted = true
                )
                pushAllLocalToCloud(tombstones = listOf(tombstone))
                _userMessage.emit("تم حذف الجهة المستفيدة بنجاح")
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
                newId
            } else {
                repository.updateSupplier(supplier)
                _userMessage.emit("تم تحديث بيانات المورد")
                supplier.id
            }
            pushAllLocalToCloud()
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
                val tombstone = CloudEntityUpsert(
                    docId = "supplier_${supplier.id}",
                    entityType = "supplier",
                    localId = supplier.id,
                    payload = mapOf("id" to supplier.id),
                    isDeleted = true
                )
                pushAllLocalToCloud(tombstones = listOf(tombstone))
                _userMessage.emit("تم حذف المورد بنجاح")
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
                pushAllLocalToCloud()
                _userMessage.emit("تم تعديل اسم المورد بنجاح")
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
                newId
            } else {
                repository.updateParty(party)
                _userMessage.emit("تم تحديث البيانات")
                party.id
            }
            pushAllLocalToCloud()
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
                val tombstone = CloudEntityUpsert(
                    docId = "party_${party.id}",
                    entityType = "party",
                    localId = party.id,
                    payload = mapOf("id" to party.id),
                    isDeleted = true
                )
                pushAllLocalToCloud(tombstones = listOf(tombstone))
                _userMessage.emit("تم الحذف بنجاح")
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
                pushAllLocalToCloud()
                _userMessage.emit("تم تعديل الاسم بنجاح")
            }
            onDone()
        }
    }

    // 7. Categories & Units Operations
    fun saveCategory(category: ItemCategory, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (category.id == 0L) repository.insertCategory(category)
            else repository.updateCategory(category)
            pushAllLocalToCloud()
            _userMessage.emit("تم حفظ التصنيف")
            onDone()
        }
    }

    fun deleteCategory(category: ItemCategory) {
        viewModelScope.launch {
            repository.deleteCategory(category)
            val tombstone = CloudEntityUpsert(
                docId = "category_${category.id}",
                entityType = "category",
                localId = category.id,
                payload = mapOf("id" to category.id),
                isDeleted = true
            )
            pushAllLocalToCloud(tombstones = listOf(tombstone))
            _userMessage.emit("تم حذف التصنيف")
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
            pushAllLocalToCloud()
            _userMessage.emit("تم حفظ وحدة القياس بنجاح")
            onDone(id)
        }
    }

    fun saveUnit(unitName: String, onDone: (Long) -> Unit = {}) {
        saveUnit(ItemUnit(name = unitName), onDone)
    }

    fun updateUnitName(unitId: Long, newName: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.updateUnitName(unitId, newName)
            pushAllLocalToCloud()
            _userMessage.emit("تم تعديل اسم الوحدة بنجاح")
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
                val tombstone = CloudEntityUpsert(
                    docId = "unit_${unit.id}",
                    entityType = "unit",
                    localId = unit.id,
                    payload = mapOf("id" to unit.id),
                    isDeleted = true
                )
                pushAllLocalToCloud(tombstones = listOf(tombstone))
                _userMessage.emit("تم حذف الوحدة بنجاح")
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
            pushAllLocalToCloud()
            val actionName = when (voucher.voucherType) {
                VoucherType.INWARD -> "التوريد"
                VoucherType.TRANSFER -> "التحويل المخزني"
                VoucherType.DISBURSEMENT -> "أمر الصرف"
            }
            _userMessage.emit("تم حفظ $actionName بنجاح")
            onDone()
        }
    }

    fun deleteVoucher(voucherId: Long) {
        viewModelScope.launch {
            val existing = vouchers.value.find { it.voucher.id == voucherId }
            val tombstones = mutableListOf(
                CloudEntityUpsert(
                    docId = "voucher_$voucherId",
                    entityType = "voucher",
                    localId = voucherId,
                    payload = mapOf("id" to voucherId, "voucherType" to (existing?.voucher?.voucherType?.name ?: "INWARD")),
                    isDeleted = true
                )
            )
            existing?.items?.forEach { vi ->
                tombstones.add(
                    CloudEntityUpsert(
                        docId = "vitem_${vi.id}",
                        entityType = "voucher_item",
                        localId = vi.id,
                        payload = mapOf("id" to vi.id, "voucherType" to existing.voucher.voucherType.name),
                        isDeleted = true
                    )
                )
            }
            repository.deleteVoucher(voucherId)
            pushAllLocalToCloud(tombstones = tombstones)
            _userMessage.emit("تم حذف السند بنجاح")
        }
    }

    fun removeAttachment(voucherId: Long) {
        viewModelScope.launch {
            repository.removeVoucherAttachment(voucherId)
            pushAllLocalToCloud()
            _userMessage.emit("تم حذف المرفق بنجاح")
        }
    }

    // 10. Backups & Sync Operations
    fun createBackup(name: String, isCloud: Boolean = false) {
        viewModelScope.launch {
            try {
                val email = if (isCloud) googleAccount.value else ""
                repository.createBackup(name, isCloud = isCloud, accountEmail = email)
                if (isCloud) {
                    pushAllLocalToCloud()
                }
                val typeStr = if (isCloud) "سحابياً على بريد $email" else "محلياً على الهاتف"
                _userMessage.emit("تم إنشاء وحفظ النسخة الاحتياطية $typeStr بنجاح")
            } catch (e: Exception) {
                _userMessage.emit("تعذر إنشاء النسخة: ${e.localizedMessage}")
            }
        }
    }

    fun restoreBackup(record: BackupRecord) {
        viewModelScope.launch {
            try {
                val ok = repository.restoreBackup(record.backupJson)
                if (ok) {
                    pushAllLocalToCloud()
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

    fun performManualSync() {
        viewModelScope.launch {
            _isSyncingCloud.value = true
            try {
                val wsId = preferences.workspaceIdFlow.first()
                if (isMainDevice.value) {
                    if (wsId.isBlank()) {
                        val installId = preferences.getOrCreateInstallationId()
                        val ws = cloudRepo.ensureOwnerWorkspace(orgName.value, installId).getOrNull()
                        if (ws != null) {
                            preferences.saveMainWorkspace(ws.id, ws.workspaceCode)
                            pushAllLocalToCloud(ws.id)
                            startMainDeviceSync(ws.id)
                        }
                    } else {
                        pushAllLocalToCloud(wsId)
                    }
                    _userMessage.emit("تمت المزامنة والرفع للسحابة الموحدة بنجاح")
                } else if (wsId.isNotBlank()) {
                    val res = cloudRepo.fetchAllWorkspaceEntities(wsId)
                    res.onSuccess { recs ->
                        if (recs.any { !it.isDeleted }) {
                            repository.replaceDatabaseFromCloudEntities(recs)
                        }
                        preferences.updateLastSync()
                        _userMessage.emit("تم تحديث البيانات من القاعدة الموحدة بنجاح")
                    }.onFailure { err ->
                        _userMessage.emit("تعذرت المزامنة: ${err.localizedMessage}")
                    }
                }
            } finally {
                _isSyncingCloud.value = false
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

    fun setGoogleAccount(account: String) {
        viewModelScope.launch { preferences.setGoogleAccount(account) }
    }

    fun setSyncEnabled(enabled: Boolean) {
        viewModelScope.launch { preferences.setSyncEnabled(enabled) }
    }

    fun setDeviceRole(role: String) {
        viewModelScope.launch {
            preferences.setDeviceRole(role)
            val label = if (role == "MAIN") "الجهاز الرئيسي (صلاحيات كاملة)" else "جهاز فرعي"
            _userMessage.emit("تم تبديل النمط إلى: $label")
        }
    }

    // Item Unit Serial Tracking
    fun getSerialsForWarehouseItem(warehouseId: Long, itemId: Long): kotlinx.coroutines.flow.Flow<List<ItemUnitSerialRecord>> {
        return repository.getSerialsForWarehouseItem(warehouseId, itemId)
    }

    suspend fun getSerialsList(warehouseId: Long, itemId: Long): List<ItemUnitSerialRecord> {
        return repository.getSerialsList(warehouseId, itemId)
    }

    fun saveSerials(serials: List<ItemUnitSerialRecord>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.saveSerials(serials)
            pushAllLocalToCloud()
            _userMessage.emit("تم حفظ الأرقام المميزة والحالات بنجاح")
            onDone()
        }
    }

    suspend fun getAllSerialsForWarehouse(warehouseId: Long): List<ItemUnitSerialRecord> {
        return repository.getAllSerialsForWarehouse(warehouseId)
    }

    override fun onCleared() {
        stopCloudListeners()
        super.onCleared()
    }
}

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
