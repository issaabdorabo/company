package com.example.data.repository

import android.content.Context
import androidx.room.withTransaction
import com.example.data.dao.VoucherWithItems
import com.example.data.database.AppDatabase
import com.example.data.model.BackupRecord
import com.example.data.model.Beneficiary
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemStockInfo
import com.example.data.model.ItemUnit
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.StockAdjustmentLog
import com.example.data.model.Supplier
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseItemAssociation
import com.example.data.model.WarehouseItemStock
import com.example.data.model.WarehouseParty
import com.example.data.model.WarehouseStockSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class InventoryRepository(
    private val database: AppDatabase,
    private val context: Context
) {
    private val warehouseDao = database.warehouseDao()
    private val itemDao = database.itemDao()
    private val beneficiaryDao = database.beneficiaryDao()
    private val voucherDao = database.voucherDao()
    private val backupDao = database.backupDao()
    private val supplierDao = database.supplierDao()
    private val warehousePartyDao = database.warehousePartyDao()
    private val categoryDao = database.itemCategoryDao()
    private val unitDao = database.itemUnitDao()
    private val adjustmentDao = database.stockAdjustmentDao()
    private val warehouseItemAssociationDao = database.warehouseItemAssociationDao()
    private val itemUnitSerialDao = database.itemUnitSerialDao()

    val allWarehouses: Flow<List<Warehouse>> = warehouseDao.getAllWarehouses()
    val allItems: Flow<List<Item>> = itemDao.getAllItems()
    val allBeneficiaries: Flow<List<Beneficiary>> = beneficiaryDao.getAllBeneficiaries()
    val allVouchers: Flow<List<VoucherWithItems>> = voucherDao.getAllVouchersWithItems()
    val allBackups: Flow<List<BackupRecord>> = backupDao.getAllBackups()
    val allSuppliers: Flow<List<Supplier>> = supplierDao.getAllSuppliers()
    val allParties: Flow<List<WarehouseParty>> = warehousePartyDao.getAllParties()
    val allCategories: Flow<List<ItemCategory>> = categoryDao.getAllCategories()
    val allUnits: Flow<List<ItemUnit>> = unitDao.getAllUnits()
    val allAssociations: Flow<List<WarehouseItemAssociation>> = warehouseItemAssociationDao.getAllAssociations()

    // 1. Warehouse Operations
    suspend fun insertWarehouse(warehouse: Warehouse): Long = withContext(Dispatchers.IO) {
        warehouseDao.insertWarehouse(warehouse)
    }

    suspend fun updateWarehouse(warehouse: Warehouse) = withContext(Dispatchers.IO) {
        warehouseDao.updateWarehouse(warehouse)
    }

    suspend fun deleteWarehouse(warehouse: Warehouse) = withContext(Dispatchers.IO) {
        warehouseDao.deleteWarehouse(warehouse)
    }

    suspend fun warehouseHasStock(warehouseId: Long): Boolean = withContext(Dispatchers.IO) {
        val vouchers = voucherDao.getAllVouchersWithItemsList()
        val adjustments = adjustmentDao.getAllLogs().filter { it.warehouseId == warehouseId }
        var netStock = 0.0

        for (v in vouchers) {
            val type = v.voucher.voucherType
            val src = v.voucher.sourceWarehouseId
            val dest = v.voucher.destinationWarehouseId

            when (type) {
                VoucherType.INWARD -> {
                    if (dest == warehouseId) {
                        netStock += v.items.sumOf { it.quantity }
                    }
                }
                VoucherType.TRANSFER -> {
                    if (dest == warehouseId) {
                        netStock += v.items.sumOf { it.quantity }
                    }
                    if (src == warehouseId) {
                        netStock -= v.items.sumOf { it.quantity }
                    }
                }
                VoucherType.DISBURSEMENT -> {
                    if (src == warehouseId) {
                        netStock -= v.items.sumOf { it.quantity }
                    }
                }
            }
        }
        netStock > 0.0001
    }

    // 2. Item Operations
    suspend fun insertItem(item: Item): Long = withContext(Dispatchers.IO) {
        itemDao.insertItem(item)
    }

    suspend fun updateItem(item: Item) = withContext(Dispatchers.IO) {
        itemDao.updateItem(item)
    }

    suspend fun deleteItem(item: Item) = withContext(Dispatchers.IO) {
        itemDao.deleteItem(item)
    }

    suspend fun itemHasTransactions(itemId: Long): Boolean = withContext(Dispatchers.IO) {
        voucherDao.getVoucherItemsCountByItemId(itemId) > 0
    }

    // 3. Beneficiary Operations
    suspend fun insertBeneficiary(beneficiary: Beneficiary): Long = withContext(Dispatchers.IO) {
        beneficiaryDao.insertBeneficiary(beneficiary)
    }

    suspend fun updateBeneficiary(beneficiary: Beneficiary) = withContext(Dispatchers.IO) {
        beneficiaryDao.updateBeneficiary(beneficiary)
    }

    suspend fun deleteBeneficiary(beneficiary: Beneficiary) = withContext(Dispatchers.IO) {
        beneficiaryDao.deleteBeneficiary(beneficiary)
    }

    // 4. Supplier Operations
    suspend fun insertSupplier(supplier: Supplier): Long = withContext(Dispatchers.IO) {
        supplierDao.insertSupplier(supplier)
    }

    suspend fun updateSupplier(supplier: Supplier) = withContext(Dispatchers.IO) {
        supplierDao.updateSupplier(supplier)
    }

    suspend fun canDeleteSupplier(name: String): Boolean = withContext(Dispatchers.IO) {
        supplierDao.getVouchersCountForSupplier(name) == 0
    }

    suspend fun deleteSupplier(supplier: Supplier): Boolean = withContext(Dispatchers.IO) {
        if (canDeleteSupplier(supplier.name)) {
            supplierDao.deleteSupplier(supplier)
            true
        } else false
    }

    // 5. WarehouseParty Operations (Receivers / Disbursers)
    suspend fun insertParty(party: WarehouseParty): Long = withContext(Dispatchers.IO) {
        warehousePartyDao.insertParty(party)
    }

    suspend fun updateParty(party: WarehouseParty) = withContext(Dispatchers.IO) {
        warehousePartyDao.updateParty(party)
    }

    suspend fun canDeleteParty(name: String): Boolean = withContext(Dispatchers.IO) {
        warehousePartyDao.getVouchersCountForParty(name) == 0
    }

    suspend fun deleteParty(party: WarehouseParty): Boolean = withContext(Dispatchers.IO) {
        if (canDeleteParty(party.name)) {
            warehousePartyDao.deleteParty(party)
            true
        } else false
    }

    // 6. Category & Unit Operations
    suspend fun insertCategory(cat: ItemCategory): Long = withContext(Dispatchers.IO) {
        categoryDao.insertCategory(cat)
    }

    suspend fun updateCategory(cat: ItemCategory) = withContext(Dispatchers.IO) {
        categoryDao.updateCategory(cat)
    }

    suspend fun deleteCategory(cat: ItemCategory) = withContext(Dispatchers.IO) {
        categoryDao.deleteCategory(cat)
    }

    suspend fun insertUnit(unit: ItemUnit): Long = withContext(Dispatchers.IO) {
        unitDao.insertUnit(unit)
    }

    suspend fun updateUnit(unit: ItemUnit) = withContext(Dispatchers.IO) {
        unitDao.updateUnit(unit)
    }

    suspend fun updateUnitName(unitId: Long, newName: String) = withContext(Dispatchers.IO) {
        val existing = unitDao.getAllUnitsList().find { it.id == unitId }
        if (existing != null) {
            unitDao.updateUnit(existing.copy(name = newName.trim()))
        }
    }

    suspend fun checkUnitHasTransactions(unitName: String): Boolean = withContext(Dispatchers.IO) {
        val inItems = unitDao.getItemsCountForUnit(unitName)
        val inVouchers = unitDao.getVoucherItemsCountForUnit(unitName)
        (inItems + inVouchers) > 0
    }

    suspend fun deleteUnitSafely(unit: ItemUnit): Boolean = withContext(Dispatchers.IO) {
        if (checkUnitHasTransactions(unit.name)) {
            false
        } else {
            unitDao.deleteUnit(unit)
            true
        }
    }

    suspend fun deleteUnit(unit: ItemUnit) = withContext(Dispatchers.IO) {
        unitDao.deleteUnit(unit)
    }

    suspend fun checkSupplierHasTransactions(supplierName: String): Boolean = withContext(Dispatchers.IO) {
        supplierDao.getVouchersCountForSupplier(supplierName) > 0
    }

    suspend fun deleteSupplierSafely(supplier: Supplier): Boolean = withContext(Dispatchers.IO) {
        if (checkSupplierHasTransactions(supplier.name)) {
            false
        } else {
            supplierDao.deleteSupplier(supplier)
            true
        }
    }

    suspend fun checkPartyHasTransactions(name: String): Boolean = withContext(Dispatchers.IO) {
        warehousePartyDao.getVouchersCountForParty(name) > 0
    }

    suspend fun deletePartySafely(party: WarehouseParty): Boolean = withContext(Dispatchers.IO) {
        if (checkPartyHasTransactions(party.name)) {
            false
        } else {
            warehousePartyDao.deleteParty(party)
            true
        }
    }

    suspend fun checkBeneficiaryHasTransactions(name: String, id: Long): Boolean = withContext(Dispatchers.IO) {
        beneficiaryDao.getVouchersCountForBeneficiary(name, id) > 0
    }

    suspend fun deleteBeneficiarySafely(beneficiary: Beneficiary): Boolean = withContext(Dispatchers.IO) {
        if (checkBeneficiaryHasTransactions(beneficiary.name, beneficiary.id)) {
            false
        } else {
            beneficiaryDao.deleteBeneficiary(beneficiary)
            true
        }
    }

    // Warehouse Item Associations (Direct add, permanent remove, custom min stock)
    suspend fun addItemDirectlyToWarehouse(
        warehouseId: Long,
        itemId: Long,
        quantity: Double,
        customMinStock: Double? = null
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            warehouseItemAssociationDao.insertAssociation(
                WarehouseItemAssociation(
                    warehouseId = warehouseId,
                    itemId = itemId,
                    customMinStock = customMinStock,
                    isRemoved = false
                )
            )
            if (quantity > 0.0001) {
                adjustWarehouseStock(
                    warehouseId = warehouseId,
                    itemId = itemId,
                    newQuantity = quantity,
                    reasonNote = "إضافة صنف أولي للمخزن"
                )
            }
        }
    }

    suspend fun removeItemFromWarehouse(
        warehouseId: Long,
        itemId: Long
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            warehouseItemAssociationDao.markItemRemoved(warehouseId, itemId)
            val currentStock = getWarehouseItemStockQty(warehouseId, itemId)
            if (currentStock > 0.0001) {
                adjustWarehouseStock(
                    warehouseId = warehouseId,
                    itemId = itemId,
                    newQuantity = 0.0,
                    reasonNote = "حذف الصنف نهائياً من المخزن"
                )
            }
        }
    }

    suspend fun updateWarehouseItemMinStock(
        warehouseId: Long,
        itemId: Long,
        customMinStock: Double
    ) = withContext(Dispatchers.IO) {
        warehouseItemAssociationDao.insertAssociation(
            WarehouseItemAssociation(
                warehouseId = warehouseId,
                itemId = itemId,
                customMinStock = customMinStock,
                isRemoved = false
            )
        )
    }

    suspend fun validateInwardVoucherUpdate(
        voucherId: Long,
        destWarehouseId: Long,
        newLineItems: List<VoucherItem>
    ): Pair<Boolean, String?> = withContext(Dispatchers.IO) {
        val existingVoucher = voucherDao.getVoucherById(voucherId)
        if (existingVoucher == null) return@withContext Pair(true, null)

        val oldItemsMap = existingVoucher.items.associate { it.itemId to it.quantity }

        for (newItem in newLineItems) {
            val oldQty = oldItemsMap[newItem.itemId] ?: 0.0
            val diff = newItem.quantity - oldQty
            if (diff < -0.0001) {
                val currentStock = getWarehouseItemStockQty(destWarehouseId, newItem.itemId)
                if (currentStock + diff < -0.0001) {
                    return@withContext Pair(
                        false,
                        "لا يمكن تقليل كمية الصنف \"${newItem.itemName}\" بمقدار ${kotlin.math.abs(diff).toInt()} لأن الرصيد المتوفر حالياً في المخزن (${currentStock.toInt()}) لا يكفي لتغطية الكميات المنصرفة سابقاً!"
                    )
                }
            }
        }
        Pair(true, null)
    }

    // 7. Stock Adjustment (with mandatory reason note)
    suspend fun adjustWarehouseStock(
        warehouseId: Long,
        itemId: Long,
        newQuantity: Double,
        reasonNote: String
    ) = withContext(Dispatchers.IO) {
        database.withTransaction {
            val currentStock = getWarehouseItemStockQty(warehouseId, itemId)
            val log = StockAdjustmentLog(
                warehouseId = warehouseId,
                itemId = itemId,
                oldQuantity = currentStock,
                newQuantity = newQuantity,
                reasonNote = reasonNote,
                adjustedAt = System.currentTimeMillis()
            )
            adjustmentDao.insertLog(log)

            val diff = newQuantity - currentStock
            if (kotlin.math.abs(diff) > 0.0001) {
                val item = itemDao.getItemById(itemId)
                val voucherType = if (diff > 0) VoucherType.INWARD else VoucherType.DISBURSEMENT
                val voucherNumber = "ADJ-${System.currentTimeMillis() % 100000}"
                val voucher = Voucher(
                    voucherNumber = voucherNumber,
                    voucherType = voucherType,
                    date = System.currentTimeMillis(),
                    sourceWarehouseId = if (diff < 0) warehouseId else null,
                    destinationWarehouseId = if (diff > 0) warehouseId else null,
                    supplierName = "تسوية جردية للمخزون",
                    receiverName = "أمين المخزن",
                    disburserName = "أمين المخزن",
                    beneficiaryName = "تسوية رصيد",
                    notes = "تعديل رصيد: $reasonNote"
                )
                val vId = voucherDao.insertVoucher(voucher)
                voucherDao.insertVoucherItems(
                    listOf(
                        VoucherItem(
                            voucherId = vId,
                            itemId = itemId,
                            itemName = item?.name ?: "صنف",
                            itemUnit = item?.unit ?: "حبة",
                            packageQuantity = 0.0,
                            singleQuantity = kotlin.math.abs(diff),
                            quantity = kotlin.math.abs(diff),
                            unitPrice = item?.purchasePrice ?: 0.0,
                            notes = reasonNote
                        )
                    )
                )
            }
        }
    }

    suspend fun getWarehouseItemStockQty(warehouseId: Long, itemId: Long): Double = withContext(Dispatchers.IO) {
        val vouchers = voucherDao.getAllVouchersWithItemsList()
        var net = 0.0
        for (vWithItems in vouchers) {
            val v = vWithItems.voucher
            for (vi in vWithItems.items) {
                if (vi.itemId == itemId) {
                    when (v.voucherType) {
                        VoucherType.INWARD -> if (v.destinationWarehouseId == warehouseId) net += vi.quantity
                        VoucherType.TRANSFER -> {
                            if (v.destinationWarehouseId == warehouseId) net += vi.quantity
                            if (v.sourceWarehouseId == warehouseId) net -= vi.quantity
                        }
                        VoucherType.DISBURSEMENT -> if (v.sourceWarehouseId == warehouseId) net -= vi.quantity
                    }
                }
            }
        }
        net
    }

    // Item Unit Serial Tracking (الرقم المميز والحالة والملاحظات)
    fun getSerialsForWarehouseItem(warehouseId: Long, itemId: Long): Flow<List<ItemUnitSerialRecord>> {
        return itemUnitSerialDao.getSerialsForWarehouseItem(warehouseId, itemId)
    }

    suspend fun getSerialsList(warehouseId: Long, itemId: Long): List<ItemUnitSerialRecord> = withContext(Dispatchers.IO) {
        itemUnitSerialDao.getSerialsList(warehouseId, itemId)
    }

    suspend fun saveSerials(serials: List<ItemUnitSerialRecord>) = withContext(Dispatchers.IO) {
        itemUnitSerialDao.insertOrUpdateSerials(serials)
    }

    suspend fun getAllSerialsForWarehouse(warehouseId: Long): List<ItemUnitSerialRecord> = withContext(Dispatchers.IO) {
        itemUnitSerialDao.getAllSerialsForWarehouse(warehouseId)
    }

    // 8. Sequential Voucher Number Generator (Non-editable, Unique)
    suspend fun generateNextVoucherNumber(type: VoucherType): String = withContext(Dispatchers.IO) {
        val count = voucherDao.getVoucherCountForType(type.name)
        val prefix = when (type) {
            VoucherType.INWARD -> "IN"
            VoucherType.TRANSFER -> "TR"
            VoucherType.DISBURSEMENT -> "OUT"
        }
        "$prefix-${1001 + count}"
    }

    // 9. Voucher Operations
    suspend fun saveVoucherWithItems(
        voucher: Voucher,
        items: List<VoucherItem>
    ): Long = withContext(Dispatchers.IO) {
        database.withTransaction {
            val voucherId = if (voucher.id == 0L) {
                voucherDao.insertVoucher(voucher)
            } else {
                voucherDao.updateVoucher(voucher)
                voucherDao.deleteVoucherItemsByVoucherId(voucher.id)
                voucher.id
            }

            val itemsWithVoucherId = items.map { it.copy(voucherId = voucherId) }
            voucherDao.insertVoucherItems(itemsWithVoucherId)
            voucherId
        }
    }

    suspend fun deleteVoucher(voucherId: Long) = withContext(Dispatchers.IO) {
        database.withTransaction {
            voucherDao.deleteVoucherItemsByVoucherId(voucherId)
            voucherDao.deleteVoucherById(voucherId)
        }
    }

    suspend fun removeVoucherAttachment(voucherId: Long) = withContext(Dispatchers.IO) {
        voucherDao.removeAttachment(voucherId)
    }

    // 10. Reactive Stock Calculations & Stream
    fun getCombinedStockStream(): Flow<StockSnapshot> {
        return combine(
            allWarehouses,
            allItems,
            allVouchers,
            allAssociations
        ) { warehouses, items, vouchers, associations ->
            calculateStockSnapshot(warehouses, items, vouchers, associations)
        }
    }

    private fun calculateStockSnapshot(
        warehouses: List<Warehouse>,
        items: List<Item>,
        vouchers: List<VoucherWithItems>,
        associations: List<WarehouseItemAssociation>
    ): StockSnapshot {
        val warehouseItemStockMap = mutableMapOf<Pair<Long, Long>, Double>()
        val itemTotalStockMap = mutableMapOf<Long, Double>()

        val removedPairs = associations.filter { it.isRemoved }.map { Pair(it.warehouseId, it.itemId) }.toSet()
        val explicitlyAddedPairs = associations.filter { !it.isRemoved }.map { Pair(it.warehouseId, it.itemId) }.toSet()
        val customMinStockMap = associations.associate { Pair(it.warehouseId, it.itemId) to it.customMinStock }

        // Initialize with item openingStock assigned to primary warehouse or overall stock
        val firstWarehouseId = warehouses.firstOrNull()?.id
        items.forEach { itm ->
            if (itm.openingStock > 0.0001 && firstWarehouseId != null) {
                warehouseItemStockMap[Pair(firstWarehouseId, itm.id)] = itm.openingStock
                itemTotalStockMap[itm.id] = itm.openingStock
            }
        }

        for (vWithItems in vouchers) {
            val v = vWithItems.voucher
            val type = v.voucherType
            val src = v.sourceWarehouseId
            val dest = v.destinationWarehouseId

            for (vi in vWithItems.items) {
                val qty = vi.quantity
                val itemId = vi.itemId

                when (type) {
                    VoucherType.INWARD -> {
                        if (dest != null) {
                            val key = Pair(dest, itemId)
                            warehouseItemStockMap[key] = (warehouseItemStockMap[key] ?: 0.0) + qty
                            itemTotalStockMap[itemId] = (itemTotalStockMap[itemId] ?: 0.0) + qty
                        }
                    }
                    VoucherType.TRANSFER -> {
                        if (src != null) {
                            val keySrc = Pair(src, itemId)
                            warehouseItemStockMap[keySrc] = (warehouseItemStockMap[keySrc] ?: 0.0) - qty
                        }
                        if (dest != null) {
                            val keyDest = Pair(dest, itemId)
                            warehouseItemStockMap[keyDest] = (warehouseItemStockMap[keyDest] ?: 0.0) + qty
                        }
                    }
                    VoucherType.DISBURSEMENT -> {
                        if (src != null) {
                            val keySrc = Pair(src, itemId)
                            warehouseItemStockMap[keySrc] = (warehouseItemStockMap[keySrc] ?: 0.0) - qty
                            itemTotalStockMap[itemId] = (itemTotalStockMap[itemId] ?: 0.0) - qty
                        }
                    }
                }
            }
        }

        // Warehouse Item Stocks (only items present in warehouse and not removed)
        val warehouseItemStocks = mutableListOf<WarehouseItemStock>()
        warehouses.forEach { w ->
            items.forEach { item ->
                val pair = Pair(w.id, item.id)
                val stock = warehouseItemStockMap[pair] ?: 0.0
                val isExplicitlyRemoved = pair in removedPairs
                val isExplicitlyAdded = pair in explicitlyAddedPairs
                val isInWarehouse = !isExplicitlyRemoved && (isExplicitlyAdded || stock > 0.0001)

                if (isInWarehouse) {
                    val minLevel = customMinStockMap[pair] ?: item.minStockLevel
                    warehouseItemStocks.add(
                        WarehouseItemStock(
                            warehouseId = w.id,
                            warehouseName = w.name,
                            itemId = item.id,
                            itemName = item.name,
                            itemUnit = item.unit,
                            packageUnit = item.packageUnit,
                            unitsPerPackage = item.unitsPerPackage,
                            currentStock = stock,
                            minStockLevel = minLevel,
                            purchasePrice = item.purchasePrice,
                            attachmentUri = item.attachmentUri,
                            attachmentType = item.attachmentType,
                            isLowStock = stock <= minLevel && stock > 0.0001,
                            isOutOfStock = stock <= 0.0001
                        )
                    )
                }
            }
        }

        // Warehouse Summaries based on items actually present
        val warehouseSummaries = warehouses.map { w ->
            val itemsForWarehouse = warehouseItemStocks.filter { it.warehouseId == w.id }
            val activeCount = itemsForWarehouse.count { it.currentStock > 0.0001 }
            val totalQty = itemsForWarehouse.sumOf { it.currentStock }
            val totalVal = itemsForWarehouse.sumOf { it.currentStock * it.purchasePrice }
            val lowCount = itemsForWarehouse.count { it.isLowStock }
            val outCount = itemsForWarehouse.count { it.isOutOfStock }

            WarehouseStockSummary(
                warehouseId = w.id,
                warehouseName = w.name,
                totalItemsCount = activeCount,
                totalQuantity = totalQty,
                totalValue = totalVal,
                lowStockCount = lowCount,
                outOfStockCount = outCount
            )
        }

        // Item Stock Info
        val itemStockInfos = items.map { item ->
            val stock = itemTotalStockMap[item.id] ?: 0.0
            val isOutOfStock = stock <= 0.0001
            val isLowStock = !isOutOfStock && stock <= item.minStockLevel
            ItemStockInfo(
                item = item,
                currentStock = stock,
                totalValue = stock * item.purchasePrice,
                isLowStock = isLowStock,
                isOutOfStock = isOutOfStock
            )
        }.sortedBy { it.item.name }

        val lowStockItems = itemStockInfos.filter { it.isLowStock || it.isOutOfStock }

        return StockSnapshot(
            warehouses = warehouses,
            items = items,
            vouchers = vouchers,
            warehouseSummaries = warehouseSummaries,
            itemStockInfos = itemStockInfos,
            warehouseItemStocks = warehouseItemStocks,
            lowStockItems = lowStockItems,
            totalInventoryValue = itemStockInfos.sumOf { it.totalValue },
            totalInventoryQuantity = itemStockInfos.sumOf { it.currentStock },
            stockMap = warehouseItemStockMap
        )
    }

    // 11. Multi-Backup (Local and Cloud Ready)
    suspend fun createBackup(name: String, isCloud: Boolean = false, accountEmail: String = ""): BackupRecord = withContext(Dispatchers.IO) {
        val warehouses = warehouseDao.getAllWarehousesList()
        val items = itemDao.getAllItemsList()
        val beneficiaries = beneficiaryDao.getAllBeneficiariesList()
        val vouchers = voucherDao.getAllVouchers()
        val voucherItems = voucherDao.getAllVoucherItems()
        val suppliers = supplierDao.getAllSuppliersList()
        val parties = warehousePartyDao.getAllPartiesList()
        val categories = categoryDao.getAllCategoriesList()
        val units = unitDao.getAllUnitsList()

        val json = JSONObject()
        json.put("appName", "مخزني الذكي")
        json.put("version", 2)
        json.put("timestamp", System.currentTimeMillis())
        json.put("backupName", name)
        json.put("isCloud", isCloud)
        json.put("cloudAccount", accountEmail)

        val wArr = JSONArray()
        warehouses.forEach { w ->
            wArr.put(JSONObject().apply {
                put("id", w.id)
                put("name", w.name)
                put("location", w.location)
                put("managerName", w.managerName)
                put("phone", w.phone)
                put("notes", w.notes)
                put("createdAt", w.createdAt)
            })
        }
        json.put("warehouses", wArr)

        val iArr = JSONArray()
        items.forEach { i ->
            iArr.put(JSONObject().apply {
                put("id", i.id)
                put("name", i.name)
                put("barcode", i.barcode)
                put("unit", i.unit)
                put("packageUnit", i.packageUnit)
                put("unitsPerPackage", i.unitsPerPackage)
                put("openingStock", i.openingStock)
                put("category", i.category)
                put("minStockLevel", i.minStockLevel)
                put("purchasePrice", i.purchasePrice)
                put("salePrice", i.salePrice)
                put("notes", i.notes)
                put("createdAt", i.createdAt)
            })
        }
        json.put("items", iArr)

        val bArr = JSONArray()
        beneficiaries.forEach { b ->
            bArr.put(JSONObject().apply {
                put("id", b.id)
                put("name", b.name)
                put("phone", b.phone)
                put("department", b.department)
                put("notes", b.notes)
                put("createdAt", b.createdAt)
            })
        }
        json.put("beneficiaries", bArr)

        val sArr = JSONArray()
        suppliers.forEach { s ->
            sArr.put(JSONObject().apply {
                put("id", s.id)
                put("name", s.name)
                put("phone", s.phone)
                put("address", s.address)
                put("notes", s.notes)
            })
        }
        json.put("suppliers", sArr)

        val pArr = JSONArray()
        parties.forEach { p ->
            pArr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("role", p.role)
                put("phone", p.phone)
            })
        }
        json.put("parties", pArr)

        val vArr = JSONArray()
        vouchers.forEach { v ->
            vArr.put(JSONObject().apply {
                put("id", v.id)
                put("voucherNumber", v.voucherNumber)
                put("voucherType", v.voucherType.name)
                put("date", v.date)
                put("sourceWarehouseId", v.sourceWarehouseId ?: JSONObject.NULL)
                put("destinationWarehouseId", v.destinationWarehouseId ?: JSONObject.NULL)
                put("supplierName", v.supplierName)
                put("receiverName", v.receiverName)
                put("beneficiaryId", v.beneficiaryId ?: JSONObject.NULL)
                put("beneficiaryName", v.beneficiaryName)
                put("disburserName", v.disburserName)
                put("notes", v.notes)
                put("attachmentUri", v.attachmentUri ?: JSONObject.NULL)
                put("attachmentType", v.attachmentType ?: JSONObject.NULL)
                put("createdAt", v.createdAt)
            })
        }
        json.put("vouchers", vArr)

        val viArr = JSONArray()
        voucherItems.forEach { vi ->
            viArr.put(JSONObject().apply {
                put("id", vi.id)
                put("voucherId", vi.voucherId)
                put("itemId", vi.itemId)
                put("itemName", vi.itemName)
                put("itemUnit", vi.itemUnit)
                put("packageUnit", vi.packageUnit)
                put("packageQuantity", vi.packageQuantity)
                put("singleQuantity", vi.singleQuantity)
                put("unitsPerPackage", vi.unitsPerPackage)
                put("quantity", vi.quantity)
                put("unitPrice", vi.unitPrice)
                put("notes", vi.notes)
            })
        }
        json.put("voucherItems", viArr)

        val jsonString = json.toString(2)
        val bytes = jsonString.toByteArray(Charsets.UTF_8)

        val record = BackupRecord(
            name = name,
            timestamp = System.currentTimeMillis(),
            isCloud = isCloud,
            cloudAccount = accountEmail,
            warehouseCount = warehouses.size,
            itemCount = items.size,
            voucherCount = vouchers.size,
            sizeBytes = bytes.size.toLong(),
            backupJson = jsonString
        )
        val id = backupDao.insertBackup(record)
        record.copy(id = id)
    }

    suspend fun restoreBackup(backupJson: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject(backupJson)
            database.withTransaction {
                // Parse Warehouses
                val wArr = json.optJSONArray("warehouses")
                if (wArr != null) {
                    for (i in 0 until wArr.length()) {
                        val obj = wArr.getJSONObject(i)
                        val w = Warehouse(
                            id = obj.optLong("id", 0L),
                            name = obj.getString("name"),
                            location = obj.optString("location", ""),
                            managerName = obj.optString("managerName", ""),
                            phone = obj.optString("phone", ""),
                            notes = obj.optString("notes", ""),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                        warehouseDao.insertWarehouse(w)
                    }
                }

                // Parse Items
                val iArr = json.optJSONArray("items")
                if (iArr != null) {
                    for (i in 0 until iArr.length()) {
                        val obj = iArr.getJSONObject(i)
                        val item = Item(
                            id = obj.optLong("id", 0L),
                            name = obj.getString("name"),
                            barcode = obj.optString("barcode", ""),
                            unit = obj.optString("unit", "حبة"),
                            packageUnit = obj.optString("packageUnit", "صندوق"),
                            unitsPerPackage = obj.optDouble("unitsPerPackage", 1.0),
                            openingStock = obj.optDouble("openingStock", 0.0),
                            category = obj.optString("category", "عام"),
                            minStockLevel = obj.optDouble("minStockLevel", 5.0),
                            purchasePrice = obj.optDouble("purchasePrice", 0.0),
                            salePrice = obj.optDouble("salePrice", 0.0),
                            notes = obj.optString("notes", ""),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                        itemDao.insertItem(item)
                    }
                }

                // Parse Beneficiaries
                val bArr = json.optJSONArray("beneficiaries")
                if (bArr != null) {
                    for (i in 0 until bArr.length()) {
                        val obj = bArr.getJSONObject(i)
                        val b = Beneficiary(
                            id = obj.optLong("id", 0L),
                            name = obj.getString("name"),
                            phone = obj.optString("phone", ""),
                            department = obj.optString("department", ""),
                            notes = obj.optString("notes", ""),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                        beneficiaryDao.insertBeneficiary(b)
                    }
                }

                // Parse Suppliers
                val sArr = json.optJSONArray("suppliers")
                if (sArr != null) {
                    for (i in 0 until sArr.length()) {
                        val obj = sArr.getJSONObject(i)
                        val s = Supplier(
                            id = obj.optLong("id", 0L),
                            name = obj.getString("name"),
                            phone = obj.optString("phone", ""),
                            address = obj.optString("address", ""),
                            notes = obj.optString("notes", "")
                        )
                        supplierDao.insertSupplier(s)
                    }
                }

                // Parse Parties
                val pArr = json.optJSONArray("parties")
                if (pArr != null) {
                    for (i in 0 until pArr.length()) {
                        val obj = pArr.getJSONObject(i)
                        val p = WarehouseParty(
                            id = obj.optLong("id", 0L),
                            name = obj.getString("name"),
                            role = obj.optString("role", "RECEIVER"),
                            phone = obj.optString("phone", "")
                        )
                        warehousePartyDao.insertParty(p)
                    }
                }

                // Parse Vouchers
                val vArr = json.optJSONArray("vouchers")
                if (vArr != null) {
                    for (i in 0 until vArr.length()) {
                        val obj = vArr.getJSONObject(i)
                        val vType = try {
                            VoucherType.valueOf(obj.getString("voucherType"))
                        } catch (e: Exception) {
                            VoucherType.INWARD
                        }
                        val v = Voucher(
                            id = obj.optLong("id", 0L),
                            voucherNumber = obj.optString("voucherNumber", ""),
                            voucherType = vType,
                            date = obj.optLong("date", System.currentTimeMillis()),
                            sourceWarehouseId = if (obj.isNull("sourceWarehouseId")) null else obj.optLong("sourceWarehouseId"),
                            destinationWarehouseId = if (obj.isNull("destinationWarehouseId")) null else obj.optLong("destinationWarehouseId"),
                            supplierName = obj.optString("supplierName", ""),
                            receiverName = obj.optString("receiverName", ""),
                            beneficiaryId = if (obj.isNull("beneficiaryId")) null else obj.optLong("beneficiaryId"),
                            beneficiaryName = obj.optString("beneficiaryName", ""),
                            disburserName = obj.optString("disburserName", ""),
                            notes = obj.optString("notes", ""),
                            attachmentUri = if (obj.isNull("attachmentUri")) null else obj.optString("attachmentUri"),
                            attachmentType = if (obj.isNull("attachmentType")) null else obj.optString("attachmentType"),
                            createdAt = obj.optLong("createdAt", System.currentTimeMillis())
                        )
                        voucherDao.insertVoucher(v)
                    }
                }

                // Parse Voucher Items
                val viArr = json.optJSONArray("voucherItems")
                if (viArr != null) {
                    val viList = mutableListOf<VoucherItem>()
                    for (i in 0 until viArr.length()) {
                        val obj = viArr.getJSONObject(i)
                        viList.add(
                            VoucherItem(
                                id = obj.optLong("id", 0L),
                                voucherId = obj.getLong("voucherId"),
                                itemId = obj.getLong("itemId"),
                                itemName = obj.getString("itemName"),
                                itemUnit = obj.optString("itemUnit", "حبة"),
                                packageUnit = obj.optString("packageUnit", "صندوق"),
                                packageQuantity = obj.optDouble("packageQuantity", 0.0),
                                singleQuantity = obj.optDouble("singleQuantity", 0.0),
                                unitsPerPackage = obj.optDouble("unitsPerPackage", 1.0),
                                quantity = obj.getDouble("quantity"),
                                unitPrice = obj.optDouble("unitPrice", 0.0),
                                notes = obj.optString("notes", "")
                            )
                        )
                    }
                    if (viList.isNotEmpty()) {
                        voucherDao.insertVoucherItems(viList)
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    suspend fun deleteBackup(backup: BackupRecord) = withContext(Dispatchers.IO) {
        backupDao.deleteBackup(backup)
    }

    suspend fun seedInitialDataIfEmpty() = withContext(Dispatchers.IO) {
        if (warehouseDao.getCount() == 0 && itemDao.getCount() == 0) {
            // Seed Units
            val defaultUnits = listOf("حبة", "صندوق", "كرتون", "طرد", "كيلو", "لتر", "متر", "طقم")
            defaultUnits.forEach { unitDao.insertUnit(ItemUnit(name = it)) }

            // Seed Categories
            val defaultCategories = listOf(
                "أجهزة إلكترونية",
                "قرطاسية ومكتبيات",
                "أجهزة مكتبية",
                "شبكات وأسلاك",
                "قطع غيار ومعدات",
                "مستلزمات عامة"
            )
            defaultCategories.forEach { categoryDao.insertCategory(ItemCategory(name = it)) }

            // Seed Suppliers
            val defaultSuppliers = listOf(
                Supplier(name = "شركة التكنولوجيا الحديثة", phone = "770111222", address = "صنعاء - شارع الزبيري"),
                Supplier(name = "مؤسسة الوفاق التجارية للتوريدات", phone = "770333444", address = "عدن - المعلا"),
                Supplier(name = "شركة البركة للمستلزمات المكتبية", phone = "770555666", address = "صنعاء - التحرير")
            )
            defaultSuppliers.forEach { supplierDao.insertSupplier(it) }

            // Seed Warehouse Parties (Receivers / Disbursers)
            val defaultParties = listOf(
                WarehouseParty(name = "عيسى عبدربه", role = "GENERAL", phone = "771438740"),
                WarehouseParty(name = "أحمد محمد سالم", role = "RECEIVER", phone = "770998877"),
                WarehouseParty(name = "خالد عبدالله المهدي", role = "DISBURSER", phone = "771223344")
            )
            defaultParties.forEach { warehousePartyDao.insertParty(it) }

            // Seed Warehouses (managerName is mandatory!)
            val wMainId = warehouseDao.insertWarehouse(
                Warehouse(
                    name = "المستودع الرئيسي (صنعاء)",
                    location = "المنطقة المركزية - شارع الستين",
                    managerName = "عيسى عبدربه",
                    phone = "771438740",
                    notes = "المخزن العام المعتمد لكافة البضائع"
                )
            )
            val wBranchId = warehouseDao.insertWarehouse(
                Warehouse(
                    name = "مخزن الفرع الثاني (عدن)",
                    location = "المنطقة الصناعية - خور مكسر",
                    managerName = "أحمد محمد سالم",
                    phone = "770998877",
                    notes = "مخزن التوزيع الإقليمي"
                )
            )

            // Seed Items with Package & UnitsPerPackage
            val i1 = itemDao.insertItem(
                Item(
                    name = "حاسوب محمول Dell Latitude",
                    barcode = "62910001",
                    unit = "حبة",
                    packageUnit = "صندوق",
                    unitsPerPackage = 5.0, // 5 laptops per master box
                    openingStock = 0.0,
                    category = "أجهزة إلكترونية",
                    minStockLevel = 4.0,
                    purchasePrice = 450.0,
                    salePrice = 520.0
                )
            )
            val i2 = itemDao.insertItem(
                Item(
                    name = "شاشة سامسونج 27 بوصة IPS",
                    barcode = "62910002",
                    unit = "حبة",
                    packageUnit = "كرتون",
                    unitsPerPackage = 4.0,
                    openingStock = 0.0,
                    category = "أجهزة إلكترونية",
                    minStockLevel = 5.0,
                    purchasePrice = 180.0,
                    salePrice = 220.0
                )
            )
            val i3 = itemDao.insertItem(
                Item(
                    name = "ورق طباعة A4 دبل إيه",
                    barcode = "62910003",
                    unit = "حبة",
                    packageUnit = "صندوق",
                    unitsPerPackage = 5.0, // 5 reams per box
                    openingStock = 0.0,
                    category = "قرطاسية ومكتبيات",
                    minStockLevel = 10.0,
                    purchasePrice = 25.0,
                    salePrice = 32.0
                )
            )
            val i4 = itemDao.insertItem(
                Item(
                    name = "طابعة HP Laser Pro متعددة المهام",
                    barcode = "62910004",
                    unit = "حبة",
                    packageUnit = "صندوق",
                    unitsPerPackage = 1.0,
                    openingStock = 0.0,
                    category = "أجهزة مكتبية",
                    minStockLevel = 3.0,
                    purchasePrice = 310.0,
                    salePrice = 370.0
                )
            )

            // Seed Beneficiaries (الجهات المستفيدة)
            val b1 = beneficiaryDao.insertBeneficiary(
                Beneficiary(
                    name = "إدارة تقنية المعلومات والبرمجة",
                    department = "المقر العام",
                    phone = "771112233"
                )
            )
            val b2 = beneficiaryDao.insertBeneficiary(
                Beneficiary(
                    name = "قسم الموارد البشرية والتدريب",
                    department = "المبنى الإداري",
                    phone = "772223344"
                )
            )

            // Seed Initial Inward Voucher
            val vInId = voucherDao.insertVoucher(
                Voucher(
                    voucherNumber = "IN-1001",
                    voucherType = VoucherType.INWARD,
                    date = System.currentTimeMillis() - 86400000L * 3,
                    destinationWarehouseId = wMainId,
                    supplierName = "شركة التكنولوجيا الحديثة",
                    receiverName = "عيسى عبدربه",
                    disburserName = "",
                    notes = "توريد الدفعة الربع سنوية المعتمدة"
                )
            )
            voucherDao.insertVoucherItems(
                listOf(
                    VoucherItem(
                        voucherId = vInId,
                        itemId = i1,
                        itemName = "حاسوب محمول Dell Latitude",
                        itemUnit = "حبة",
                        packageUnit = "صندوق",
                        packageQuantity = 3.0,
                        singleQuantity = 0.0,
                        unitsPerPackage = 5.0,
                        quantity = 15.0, // 3 boxes * 5 = 15 pieces
                        unitPrice = 450.0
                    ),
                    VoucherItem(
                        voucherId = vInId,
                        itemId = i2,
                        itemName = "شاشة سامسونج 27 بوصة IPS",
                        itemUnit = "حبة",
                        packageUnit = "كرتون",
                        packageQuantity = 5.0,
                        singleQuantity = 0.0,
                        unitsPerPackage = 4.0,
                        quantity = 20.0,
                        unitPrice = 180.0
                    ),
                    VoucherItem(
                        voucherId = vInId,
                        itemId = i3,
                        itemName = "ورق طباعة A4 دبل إيه",
                        itemUnit = "حبة",
                        packageUnit = "صندوق",
                        packageQuantity = 1.0,
                        singleQuantity = 3.0,
                        unitsPerPackage = 5.0,
                        quantity = 8.0, // 8 pieces (under min stock of 10)
                        unitPrice = 25.0
                    )
                )
            )

            // Seed Initial Transfer Voucher
            val vTrId = voucherDao.insertVoucher(
                Voucher(
                    voucherNumber = "TR-1001",
                    voucherType = VoucherType.TRANSFER,
                    date = System.currentTimeMillis() - 86400000L * 2,
                    sourceWarehouseId = wMainId,
                    destinationWarehouseId = wBranchId,
                    disburserName = "عيسى عبدربه",
                    receiverName = "أحمد محمد سالم",
                    notes = "تحويل تعزيزي لمخزن عدن"
                )
            )
            voucherDao.insertVoucherItems(
                listOf(
                    VoucherItem(
                        voucherId = vTrId,
                        itemId = i1,
                        itemName = "حاسوب محمول Dell Latitude",
                        itemUnit = "حبة",
                        packageUnit = "صندوق",
                        packageQuantity = 0.0,
                        singleQuantity = 3.0,
                        unitsPerPackage = 5.0,
                        quantity = 3.0,
                        unitPrice = 450.0
                    )
                )
            )

            // Seed Initial Disbursement Voucher to Beneficiary
            val vOutId = voucherDao.insertVoucher(
                Voucher(
                    voucherNumber = "OUT-1001",
                    voucherType = VoucherType.DISBURSEMENT,
                    date = System.currentTimeMillis() - 86400000L * 1,
                    sourceWarehouseId = wMainId,
                    beneficiaryId = b1,
                    beneficiaryName = "إدارة تقنية المعلومات والبرمجة",
                    disburserName = "عيسى عبدربه",
                    receiverName = "خالد عبدالله المهدي",
                    notes = "صرف أجهزة لمهندسي النظم"
                )
            )
            voucherDao.insertVoucherItems(
                listOf(
                    VoucherItem(
                        voucherId = vOutId,
                        itemId = i1,
                        itemName = "حاسوب محمول Dell Latitude",
                        itemUnit = "حبة",
                        packageUnit = "صندوق",
                        packageQuantity = 0.0,
                        singleQuantity = 2.0,
                        unitsPerPackage = 5.0,
                        quantity = 2.0,
                        unitPrice = 450.0
                    )
                )
            )
        }
    }
}
