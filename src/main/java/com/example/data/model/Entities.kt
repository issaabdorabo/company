package com.example.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.example.data.dao.VoucherWithItems

@Entity(tableName = "warehouses")
data class Warehouse(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val location: String = "",
    val managerName: String = "", // إجباري عند الإضافة
    val phone: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "items")
data class Item(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val barcode: String = "",
    val unit: String = "حبة", // الوحدة الصغرى
    val packageUnit: String = "صندوق", // الوحدة الكبيرة
    val unitsPerPackage: Double = 1.0, // إجمالي عدد الوحدات الصغرى في الوحدة الكبيرة
    val hasMediumUnit: Boolean = false,
    val mediumUnit: String = "", // الوحدة المتوسطة
    val mediumUnitsPerLarge: Double = 0.0, // عدد الوحدات المتوسطة في الكبيرة
    val smallUnitsPerMedium: Double = 1.0, // عدد الوحدات الصغرى في المتوسطة
    val openingStock: Double = 0.0, // الكمية الافتتاحية للمخزون (بالوحدة الصغرى)
    val category: String = "عام",
    val minStockLevel: Double = 5.0,
    val purchasePrice: Double = 0.0,
    val salePrice: Double = 0.0,
    val notes: String = "",
    val attachmentUri: String? = null,
    val attachmentType: String? = null, // "IMAGE" or "PDF"
    val createdAt: Long = System.currentTimeMillis(),
    val smallerUnitCapacity: Double = 1.0, // سعة الوحدة الصغرى
    val hasSerialNumbers: Boolean = false // هل يحتوي الصنف على أرقام تسلسلية
) {
    fun smallPerMediumEffective(): Double =
        if (hasMediumUnit && mediumUnit.isNotBlank() && smallUnitsPerMedium > 0.0) smallUnitsPerMedium
        else if (hasMediumUnit && mediumUnit.isNotBlank() && mediumUnitsPerLarge > 0.0) (unitsPerPackage / mediumUnitsPerLarge).coerceAtLeast(1.0)
        else 1.0

    fun totalSmallPerLargeEffective(): Double =
        if (hasMediumUnit && mediumUnit.isNotBlank() && mediumUnitsPerLarge > 0.0 && smallUnitsPerMedium > 0.0)
            mediumUnitsPerLarge * smallUnitsPerMedium
        else unitsPerPackage.coerceAtLeast(1.0)
}

@Entity(tableName = "suppliers")
data class Supplier(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val phone: String = "",
    val address: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "warehouse_parties")
data class WarehouseParty(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val role: String = "RECEIVER", // "RECEIVER" or "DISBURSER" or "GENERAL"
    val phone: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "item_categories")
data class ItemCategory(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val notes: String = ""
)

@Entity(tableName = "item_units")
data class ItemUnit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, // اسم الوحدة الكبيرة
    val smallerUnit: String = "حبة", // اسم الوحدة الصغرى
    val smallerUnitsCount: Double = 1.0, // إجمالي الصغرى في الكبيرة (أو المباشر في نظام الوحدتين)
    val smallerUnitCapacity: Double = 1.0, // سعة الوحدة الصغرى
    val hasMediumUnit: Boolean = false,
    val mediumUnit: String = "", // اسم الوحدة المتوسطة
    val mediumUnitsCount: Double = 0.0, // عدد المتوسطة في الكبيرة
    val smallUnitsPerMedium: Double = 1.0 // عدد الصغرى في المتوسطة
) {
    fun totalSmallPerLarge(): Double =
        if (hasMediumUnit && mediumUnit.isNotBlank() && mediumUnitsCount > 0.0 && smallUnitsPerMedium > 0.0)
            mediumUnitsCount * smallUnitsPerMedium
        else smallerUnitsCount.coerceAtLeast(1.0)
}

@Entity(tableName = "smaller_units")
data class SmallerUnit(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val capacity: Double = 1.0,
    val parentUnitId: Long = 0L, // معرف الوحدة الكبيرة المالكة
    val parentUnitName: String = "", // اسم الوحدة الكبيرة المالكة
    val unitLevel: String = "SMALL", // "MEDIUM" أو "SMALL"
    val countInParent: Double = 1.0, // عددها في الوحدة الأب (المتوسطة في الكبيرة، أو الصغرى في المتوسطة/الكبيرة)
    val mediumUnitName: String = "" // اسم الوحدة المتوسطة إن كانت مرتبطة بها
)

@Entity(tableName = "item_statuses")
data class ItemStatus(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String
)

@Entity(tableName = "warehouse_items", primaryKeys = ["warehouseId", "itemId"])
data class WarehouseItemAssociation(
    val warehouseId: Long,
    val itemId: Long,
    val customMinStock: Double? = null,
    val isRemoved: Boolean = false
)

@Entity(tableName = "beneficiaries")
data class Beneficiary(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String, // اسم الجهة المستفيدة
    val phone: String = "",
    val department: String = "",
    val notes: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "stock_adjustments",
    indices = [Index(value = ["warehouseId"]), Index(value = ["itemId"])]
)
data class StockAdjustmentLog(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val warehouseId: Long,
    val itemId: Long,
    val oldQuantity: Double,
    val newQuantity: Double,
    val reasonNote: String, // إجباري لتوثيق سبب التعديل
    val adjustedAt: Long = System.currentTimeMillis()
)

enum class VoucherType(val titleAr: String) {
    INWARD("توريد وارد"),
    TRANSFER("صرف وتحويل لمخزن"),
    DISBURSEMENT("صرف لجهة مستفيدة")
}

@Entity(tableName = "vouchers")
data class Voucher(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherNumber: String,
    val voucherType: VoucherType,
    val date: Long = System.currentTimeMillis(),
    val sourceWarehouseId: Long? = null,
    val destinationWarehouseId: Long? = null,
    val supplierName: String = "",
    val receiverName: String = "",
    val beneficiaryId: Long? = null,
    val beneficiaryName: String = "",
    val disburserName: String = "",
    val notes: String = "",
    val attachmentUri: String? = null,
    val attachmentType: String? = null, // "IMAGE" or "PDF"
    val createdAt: Long = System.currentTimeMillis()
)

@Entity(
    tableName = "voucher_items",
    indices = [Index(value = ["voucherId"]), Index(value = ["itemId"])]
)
data class VoucherItem(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val voucherId: Long,
    val itemId: Long,
    val itemName: String,
    val itemUnit: String = "حبة",
    val packageUnit: String = "صندوق",
    val packageQuantity: Double = 0.0, // عدد الوحدات الكبيرة
    val mediumUnit: String = "",
    val mediumQuantity: Double = 0.0, // عدد الوحدات المتوسطة
    val singleQuantity: Double = 0.0, // عدد الوحدات الصغرى الفردية
    val unitsPerPackage: Double = 1.0, // إجمالي الصغرى في الكبيرة
    val mediumUnitsPerLarge: Double = 0.0,
    val smallUnitsPerMedium: Double = 1.0,
    val quantity: Double = 0.0, // الإجمالي بالوحدة الصغرى
    val unitPrice: Double = 0.0,
    val notes: String = "",
    val serialNumber: String = "",
    val itemStatus: String = "",
    val itemReceiverName: String = "",
    val attachmentUri: String? = null,
    val attachmentType: String? = null,
    val sourceSerialRecordId: Long? = null,
    val sourceUnitIndex: Int? = null
)

@Entity(tableName = "backups")
data class BackupRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val timestamp: Long = System.currentTimeMillis(),
    val isCloud: Boolean = false,
    val cloudAccount: String = "",
    val warehouseCount: Int = 0,
    val itemCount: Int = 0,
    val voucherCount: Int = 0,
    val sizeBytes: Long = 0,
    val backupJson: String = ""
)

data class WarehouseStockSummary(
    val warehouseId: Long,
    val warehouseName: String,
    val totalItemsCount: Int,
    val totalQuantity: Double,
    val totalValue: Double,
    val lowStockCount: Int,
    val outOfStockCount: Int
)

data class ItemStockInfo(
    val item: Item,
    val currentStock: Double,
    val totalValue: Double,
    val isLowStock: Boolean,
    val isOutOfStock: Boolean
)

data class WarehouseItemStock(
    val warehouseId: Long,
    val warehouseName: String,
    val itemId: Long,
    val itemName: String,
    val itemUnit: String,
    val packageUnit: String,
    val unitsPerPackage: Double,
    val currentStock: Double,
    val minStockLevel: Double,
    val purchasePrice: Double,
    val attachmentUri: String? = null,
    val attachmentType: String? = null,
    val isLowStock: Boolean = currentStock <= minStockLevel && currentStock > 0.0001,
    val isOutOfStock: Boolean = currentStock <= 0.0001,
    val hasSerialNumbers: Boolean = false,
    val hasMediumUnit: Boolean = false,
    val mediumUnit: String = "",
    val mediumUnitsPerLarge: Double = 0.0,
    val smallUnitsPerMedium: Double = 1.0
)

@Entity(
    tableName = "item_unit_serials",
    indices = [Index(value = ["warehouseId", "itemId"])]
)
data class ItemUnitSerialRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val warehouseId: Long,
    val itemId: Long,
    val unitIndex: Int, // 1, 2, 3...
    val serialNumber: String = "",
    val status: String = "موجود", // موجود، مفقود، مدمر، عاطل
    val notes: String = "",
    val disbursedVoucherId: Long? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

