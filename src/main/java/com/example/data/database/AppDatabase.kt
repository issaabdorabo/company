package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.example.data.dao.BackupDao
import com.example.data.dao.BeneficiaryDao
import com.example.data.dao.ItemCategoryDao
import com.example.data.dao.ItemDao
import com.example.data.dao.ItemUnitDao
import com.example.data.dao.StockAdjustmentDao
import com.example.data.dao.SupplierDao
import com.example.data.dao.VoucherDao
import com.example.data.dao.WarehouseDao
import com.example.data.dao.WarehousePartyDao
import com.example.data.model.BackupRecord
import com.example.data.model.Beneficiary
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemUnit
import com.example.data.model.StockAdjustmentLog
import com.example.data.model.Supplier
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseParty

import com.example.data.dao.WarehouseItemAssociationDao
import com.example.data.model.WarehouseItemAssociation

class DatabaseConverters {
    @TypeConverter
    fun fromVoucherType(type: VoucherType?): String? = type?.name

    @TypeConverter
    fun toVoucherType(value: String?): VoucherType? {
        return value?.let {
            try {
                VoucherType.valueOf(it)
            } catch (e: Exception) {
                VoucherType.INWARD
            }
        }
    }
}

@Database(
    entities = [
        Warehouse::class,
        Item::class,
        Beneficiary::class,
        Voucher::class,
        VoucherItem::class,
        BackupRecord::class,
        Supplier::class,
        WarehouseParty::class,
        ItemCategory::class,
        ItemUnit::class,
        StockAdjustmentLog::class,
        WarehouseItemAssociation::class,
        com.example.data.model.ItemUnitSerialRecord::class
    ],
    version = 5,
    exportSchema = false
)
@TypeConverters(DatabaseConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun warehouseDao(): WarehouseDao
    abstract fun itemDao(): ItemDao
    abstract fun beneficiaryDao(): BeneficiaryDao
    abstract fun voucherDao(): VoucherDao
    abstract fun backupDao(): BackupDao
    abstract fun supplierDao(): SupplierDao
    abstract fun warehousePartyDao(): WarehousePartyDao
    abstract fun itemCategoryDao(): ItemCategoryDao
    abstract fun itemUnitDao(): ItemUnitDao
    abstract fun stockAdjustmentDao(): StockAdjustmentDao
    abstract fun warehouseItemAssociationDao(): WarehouseItemAssociationDao
    abstract fun itemUnitSerialDao(): com.example.data.dao.ItemUnitSerialDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_inventory_database.db"
                )
                    .fallbackToDestructiveMigration(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
