package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.InventoryApp
import com.example.data.dao.VoucherWithItems
import com.example.data.model.BackupRecord
import com.example.data.model.Beneficiary
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemUnit
import com.example.data.model.Supplier
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseParty
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.repository.StockSnapshot
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class InventoryViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as InventoryApp
    private val repository = app.repository
    private val preferences = app.preferencesManager

    // Preferences & Theme
    val theme: StateFlow<AppTheme> = preferences.themeFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), AppTheme.LIGHT)

    val userName: StateFlow<String> = preferences.userNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "عيسى عبدربه")

    val orgName: StateFlow<String> = preferences.orgNameFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "المؤسسة العامة للتوريد والمخازن")

    val googleAccount: StateFlow<String> = preferences.googleAccountFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "issanew2026@gmail.com")

    val syncEnabled: StateFlow<Boolean> = preferences.syncEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val lastSyncTime: StateFlow<Long> = preferences.lastSyncFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())

    val deviceRole: StateFlow<String> = preferences.deviceRoleFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "MAIN")

    val isMainDevice: StateFlow<Boolean> = preferences.isMainDeviceFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

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
            }
            onDone()
        }
    }

    // 7. Categories & Units Operations
    fun saveCategory(category: ItemCategory, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            if (category.id == 0L) repository.insertCategory(category)
            else repository.updateCategory(category)
            _userMessage.emit("تم حفظ التصنيف")
            onDone()
        }
    }

    fun deleteCategory(category: ItemCategory) {
        viewModelScope.launch {
            repository.deleteCategory(category)
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
            repository.deleteVoucher(voucherId)
            _userMessage.emit("تم حذف السند بنجاح")
        }
    }

    fun removeAttachment(voucherId: Long) {
        viewModelScope.launch {
            repository.removeVoucherAttachment(voucherId)
            _userMessage.emit("تم حذف المرفق بنجاح")
        }
    }

    // 10. Backups & Sync Operations
    fun createBackup(name: String, isCloud: Boolean = false) {
        viewModelScope.launch {
            try {
                val email = if (isCloud) googleAccount.value else ""
                repository.createBackup(name, isCloud = isCloud, accountEmail = email)
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
            preferences.updateLastSync()
            _userMessage.emit("تمت المزامنة بنجاح مع حساب Google (${googleAccount.value})")
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
            val label = if (role == "MAIN") "الجهاز الرئيسي (صلاحيات كاملة)" else "جهاز فرعي (اطلاع وقراءة فقط)"
            _userMessage.emit("تم تبديل النمط إلى: $label")
        }
    }

    fun checkCanEdit(onSubDeviceWarning: () -> Unit, onAllowed: () -> Unit) {
        if (isMainDevice.value) {
            onAllowed()
        } else {
            onSubDeviceWarning()
        }
    }

    // Item Unit Serial Tracking (الرقم المميز، الحالة، الملاحظات)
    fun getSerialsForWarehouseItem(warehouseId: Long, itemId: Long): kotlinx.coroutines.flow.Flow<List<ItemUnitSerialRecord>> {
        return repository.getSerialsForWarehouseItem(warehouseId, itemId)
    }

    suspend fun getSerialsList(warehouseId: Long, itemId: Long): List<ItemUnitSerialRecord> {
        return repository.getSerialsList(warehouseId, itemId)
    }

    fun saveSerials(serials: List<ItemUnitSerialRecord>, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            repository.saveSerials(serials)
            _userMessage.emit("تم حفظ الأرقام المميزة والحالات بنجاح")
            onDone()
        }
    }

    suspend fun getAllSerialsForWarehouse(warehouseId: Long): List<ItemUnitSerialRecord> {
        return repository.getAllSerialsForWarehouse(warehouseId)
    }
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
