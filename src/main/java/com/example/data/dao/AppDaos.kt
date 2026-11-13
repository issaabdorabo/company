package com.example.data.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.BackupRecord
import com.example.data.model.Beneficiary
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemUnit
import com.example.data.model.StockAdjustmentLog
import com.example.data.model.Supplier
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseItemAssociation
import com.example.data.model.WarehouseParty
import com.example.data.model.ItemUnitSerialRecord
import kotlinx.coroutines.flow.Flow

@Dao
interface WarehouseDao {
    @Query("SELECT * FROM warehouses ORDER BY name ASC")
    fun getAllWarehouses(): Flow<List<Warehouse>>

    @Query("SELECT * FROM warehouses ORDER BY name ASC")
    suspend fun getAllWarehousesList(): List<Warehouse>

    @Query("SELECT * FROM warehouses WHERE id = :id LIMIT 1")
    suspend fun getWarehouseById(id: Long): Warehouse?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertWarehouse(warehouse: Warehouse): Long

    @Update
    suspend fun updateWarehouse(warehouse: Warehouse)

    @Delete
    suspend fun deleteWarehouse(warehouse: Warehouse)

    @Query("SELECT COUNT(*) FROM warehouses")
    suspend fun getCount(): Int

    @Query("DELETE FROM warehouses")
    suspend fun clearAll()
}

@Dao
interface ItemDao {
    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE ASC")
    fun getAllItems(): Flow<List<Item>>

    @Query("SELECT * FROM items ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllItemsList(): List<Item>

    @Query("SELECT * FROM items WHERE id = :id LIMIT 1")
    suspend fun getItemById(id: Long): Item?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: Item): Long

    @Update
    suspend fun updateItem(item: Item)

    @Delete
    suspend fun deleteItem(item: Item)

    @Query("SELECT COUNT(*) FROM items")
    suspend fun getCount(): Int

    @Query("DELETE FROM items")
    suspend fun clearAll()
}

@Dao
interface SupplierDao {
    @Query("SELECT * FROM suppliers ORDER BY name COLLATE NOCASE ASC")
    fun getAllSuppliers(): Flow<List<Supplier>>

    @Query("SELECT * FROM suppliers ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllSuppliersList(): List<Supplier>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSupplier(supplier: Supplier): Long

    @Update
    suspend fun updateSupplier(supplier: Supplier)

    @Delete
    suspend fun deleteSupplier(supplier: Supplier)

    @Query("SELECT COUNT(*) FROM vouchers WHERE supplierName = :supplierName")
    suspend fun getVouchersCountForSupplier(supplierName: String): Int

    @Query("DELETE FROM suppliers")
    suspend fun clearAll()
}

@Dao
interface WarehousePartyDao {
    @Query("SELECT * FROM warehouse_parties ORDER BY name COLLATE NOCASE ASC")
    fun getAllParties(): Flow<List<WarehouseParty>>

    @Query("SELECT * FROM warehouse_parties WHERE role = :role OR role = 'GENERAL' ORDER BY name COLLATE NOCASE ASC")
    fun getPartiesByRole(role: String): Flow<List<WarehouseParty>>

    @Query("SELECT * FROM warehouse_parties ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllPartiesList(): List<WarehouseParty>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertParty(party: WarehouseParty): Long

    @Update
    suspend fun updateParty(party: WarehouseParty)

    @Delete
    suspend fun deleteParty(party: WarehouseParty)

    @Query("SELECT COUNT(*) FROM vouchers WHERE receiverName = :name OR disburserName = :name")
    suspend fun getVouchersCountForParty(name: String): Int

    @Query("DELETE FROM warehouse_parties")
    suspend fun clearAll()
}

@Dao
interface ItemCategoryDao {
    @Query("SELECT * FROM item_categories ORDER BY name COLLATE NOCASE ASC")
    fun getAllCategories(): Flow<List<ItemCategory>>

    @Query("SELECT * FROM item_categories ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllCategoriesList(): List<ItemCategory>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCategory(category: ItemCategory): Long

    @Update
    suspend fun updateCategory(category: ItemCategory)

    @Delete
    suspend fun deleteCategory(category: ItemCategory)

    @Query("DELETE FROM item_categories")
    suspend fun clearAll()
}

@Dao
interface ItemUnitDao {
    @Query("SELECT * FROM item_units ORDER BY name COLLATE NOCASE ASC")
    fun getAllUnits(): Flow<List<ItemUnit>>

    @Query("SELECT * FROM item_units ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllUnitsList(): List<ItemUnit>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertUnit(unit: ItemUnit): Long

    @Update
    suspend fun updateUnit(unit: ItemUnit)

    @Delete
    suspend fun deleteUnit(unit: ItemUnit)

    @Query("SELECT COUNT(*) FROM items WHERE unit = :name OR packageUnit = :name")
    suspend fun getItemsCountForUnit(name: String): Int

    @Query("SELECT COUNT(*) FROM voucher_items WHERE itemUnit = :name OR packageUnit = :name")
    suspend fun getVoucherItemsCountForUnit(name: String): Int

    @Query("DELETE FROM item_units")
    suspend fun clearAll()
}

@Dao
interface BeneficiaryDao {
    @Query("SELECT * FROM beneficiaries ORDER BY name COLLATE NOCASE ASC")
    fun getAllBeneficiaries(): Flow<List<Beneficiary>>

    @Query("SELECT * FROM beneficiaries ORDER BY name COLLATE NOCASE ASC")
    suspend fun getAllBeneficiariesList(): List<Beneficiary>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBeneficiary(beneficiary: Beneficiary): Long

    @Update
    suspend fun updateBeneficiary(beneficiary: Beneficiary)

    @Delete
    suspend fun deleteBeneficiary(beneficiary: Beneficiary)

    @Query("SELECT COUNT(*) FROM vouchers WHERE beneficiaryName = :name OR beneficiaryId = :id")
    suspend fun getVouchersCountForBeneficiary(name: String, id: Long): Int

    @Query("DELETE FROM beneficiaries")
    suspend fun clearAll()
}

@Dao
interface WarehouseItemAssociationDao {
    @Query("SELECT * FROM warehouse_items WHERE warehouseId = :warehouseId AND isRemoved = 0")
    fun getActiveAssociationsForWarehouse(warehouseId: Long): Flow<List<WarehouseItemAssociation>>

    @Query("SELECT * FROM warehouse_items")
    fun getAllAssociations(): Flow<List<WarehouseItemAssociation>>

    @Query("SELECT * FROM warehouse_items")
    suspend fun getAllAssociationsList(): List<WarehouseItemAssociation>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssociation(association: WarehouseItemAssociation)

    @Query("UPDATE warehouse_items SET isRemoved = 1 WHERE warehouseId = :warehouseId AND itemId = :itemId")
    suspend fun markItemRemoved(warehouseId: Long, itemId: Long)

    @Query("DELETE FROM warehouse_items WHERE warehouseId = :warehouseId AND itemId = :itemId")
    suspend fun deleteAssociation(warehouseId: Long, itemId: Long)

    @Query("DELETE FROM warehouse_items")
    suspend fun clearAll()
}

@Dao
interface StockAdjustmentDao {
    @Query("SELECT * FROM stock_adjustments WHERE warehouseId = :wId AND itemId = :iId ORDER BY adjustedAt DESC")
    fun getLogsForItemInWarehouse(wId: Long, iId: Long): Flow<List<StockAdjustmentLog>>

    @Query("SELECT * FROM stock_adjustments WHERE warehouseId = :wId ORDER BY adjustedAt DESC")
    fun getLogsForWarehouse(wId: Long): Flow<List<StockAdjustmentLog>>

    @Query("SELECT * FROM stock_adjustments ORDER BY adjustedAt DESC")
    suspend fun getAllLogs(): List<StockAdjustmentLog>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertLog(log: StockAdjustmentLog): Long

    @Query("DELETE FROM stock_adjustments")
    suspend fun clearAll()
}

data class VoucherWithItems(
    @androidx.room.Embedded val voucher: Voucher,
    @androidx.room.Relation(
        parentColumn = "id",
        entityColumn = "voucherId"
    )
    val items: List<VoucherItem>
)

@Dao
interface VoucherDao {
    @Transaction
    @Query("SELECT * FROM vouchers ORDER BY date DESC, id DESC")
    fun getAllVouchersWithItems(): Flow<List<VoucherWithItems>>

    @Transaction
    @Query("SELECT * FROM vouchers WHERE voucherType = :type ORDER BY date DESC, id DESC")
    fun getVouchersByTypeWithItems(type: String): Flow<List<VoucherWithItems>>

    @Transaction
    @Query("SELECT * FROM vouchers ORDER BY date DESC, id DESC")
    suspend fun getAllVouchersWithItemsList(): List<VoucherWithItems>

    @Transaction
    @Query("SELECT * FROM vouchers WHERE id = :id LIMIT 1")
    suspend fun getVoucherById(id: Long): VoucherWithItems?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucher(voucher: Voucher): Long

    @Update
    suspend fun updateVoucher(voucher: Voucher)

    @Delete
    suspend fun deleteVoucher(voucher: Voucher)

    @Query("DELETE FROM vouchers WHERE id = :id")
    suspend fun deleteVoucherById(id: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVoucherItems(items: List<VoucherItem>)

    @Query("DELETE FROM voucher_items WHERE voucherId = :voucherId")
    suspend fun deleteVoucherItemsByVoucherId(voucherId: Long)

    @Query("SELECT COUNT(*) FROM voucher_items WHERE itemId = :itemId")
    suspend fun getVoucherItemsCountByItemId(itemId: Long): Int

    @Query("SELECT * FROM voucher_items")
    suspend fun getAllVoucherItems(): List<VoucherItem>

    @Query("SELECT * FROM vouchers")
    suspend fun getAllVouchers(): List<Voucher>

    @Query("SELECT MAX(id) FROM vouchers WHERE voucherType = :type")
    suspend fun getMaxVoucherIdForType(type: String): Long?

    @Query("SELECT COUNT(*) FROM vouchers WHERE voucherType = :type")
    suspend fun getVoucherCountForType(type: String): Int

    @Query("UPDATE vouchers SET attachmentUri = NULL, attachmentType = NULL WHERE id = :voucherId")
    suspend fun removeAttachment(voucherId: Long)

    @Query("DELETE FROM voucher_items")
    suspend fun clearAllVoucherItems()

    @Query("DELETE FROM vouchers")
    suspend fun clearAllVouchers()
}

@Dao
interface BackupDao {
    @Query("SELECT * FROM backups ORDER BY timestamp DESC")
    fun getAllBackups(): Flow<List<BackupRecord>>

    @Query("SELECT * FROM backups WHERE isCloud = 1 ORDER BY timestamp DESC")
    fun getCloudBackups(): Flow<List<BackupRecord>>

    @Query("SELECT * FROM backups WHERE isCloud = 0 ORDER BY timestamp DESC")
    fun getLocalBackups(): Flow<List<BackupRecord>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBackup(backup: BackupRecord): Long

    @Delete
    suspend fun deleteBackup(backup: BackupRecord)

    @Query("DELETE FROM backups WHERE id = :id")
    suspend fun deleteBackupById(id: Long)
}

@Dao
interface ItemUnitSerialDao {
    @Query("SELECT * FROM item_unit_serials WHERE warehouseId = :warehouseId AND itemId = :itemId ORDER BY unitIndex ASC")
    fun getSerialsForWarehouseItem(warehouseId: Long, itemId: Long): Flow<List<ItemUnitSerialRecord>>

    @Query("SELECT * FROM item_unit_serials WHERE warehouseId = :warehouseId AND itemId = :itemId ORDER BY unitIndex ASC")
    suspend fun getSerialsList(warehouseId: Long, itemId: Long): List<ItemUnitSerialRecord>

    @Query("SELECT * FROM item_unit_serials WHERE warehouseId = :warehouseId ORDER BY unitIndex ASC")
    suspend fun getAllSerialsForWarehouse(warehouseId: Long): List<ItemUnitSerialRecord>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSerial(serialRecord: ItemUnitSerialRecord): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdateSerials(serials: List<ItemUnitSerialRecord>)

    @Query("SELECT * FROM item_unit_serials ORDER BY warehouseId, itemId, unitIndex ASC")
    suspend fun getAllSerialsList(): List<ItemUnitSerialRecord>

    @Query("DELETE FROM item_unit_serials WHERE warehouseId = :warehouseId AND itemId = :itemId AND unitIndex > :newCount")
    suspend fun deleteSerialsBeyondCount(warehouseId: Long, itemId: Long, newCount: Int)

    @Query("DELETE FROM item_unit_serials")
    suspend fun clearAll()
}

