package com.example.data.database

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.data.dao.BackupDao
import com.example.data.dao.BeneficiaryDao
import com.example.data.dao.ItemCategoryDao
import com.example.data.dao.ItemDao
import com.example.data.dao.ItemUnitDao
import com.example.data.dao.ItemUnitSerialDao
import com.example.data.dao.StockAdjustmentDao
import com.example.data.dao.SupplierDao
import com.example.data.dao.VoucherDao
import com.example.data.dao.WarehouseDao
import com.example.data.dao.WarehouseItemAssociationDao
import com.example.data.dao.WarehousePartyDao
import com.example.data.model.BackupRecord
import com.example.data.model.Beneficiary
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemUnit
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.StockAdjustmentLog
import com.example.data.model.Supplier
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseItemAssociation
import com.example.data.model.WarehouseParty

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
        ItemUnitSerialRecord::class
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
    abstract fun itemUnitSerialDao(): ItemUnitSerialDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        private fun hasColumn(db: SupportSQLiteDatabase, table: String, column: String): Boolean {
            db.query("PRAGMA table_info(`$table`)").use { cursor ->
                val nameIdx = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (nameIdx >= 0 && cursor.getString(nameIdx) == column) {
                        return true
                    }
                }
            }
            return false
        }

        private fun ensureUpgradeTablesAndColumns(db: SupportSQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `suppliers` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `phone` TEXT NOT NULL, `address` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `warehouse_parties` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `role` TEXT NOT NULL, `phone` TEXT NOT NULL, `notes` TEXT NOT NULL, `createdAt` INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `item_categories` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `notes` TEXT NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `item_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `smallerUnit` TEXT NOT NULL DEFAULT 'حبة', `smallerUnitsCount` REAL NOT NULL DEFAULT 1.0, `smallerUnitCapacity` REAL NOT NULL DEFAULT 1.0)"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `warehouse_items` (`warehouseId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `customMinStock` REAL, `isRemoved` INTEGER NOT NULL DEFAULT 0, PRIMARY KEY(`warehouseId`, `itemId`))"
            )
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `stock_adjustments` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `warehouseId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `oldQuantity` REAL NOT NULL, `newQuantity` REAL NOT NULL, `reasonNote` TEXT NOT NULL, `adjustedAt` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_adjustments_warehouseId` ON `stock_adjustments` (`warehouseId`)")
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_stock_adjustments_itemId` ON `stock_adjustments` (`itemId`)")

            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `item_unit_serials` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `warehouseId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `unitIndex` INTEGER NOT NULL, `serialNumber` TEXT NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `updatedAt` INTEGER NOT NULL)"
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS `index_item_unit_serials_warehouseId_itemId` ON `item_unit_serials` (`warehouseId`, `itemId`)")

            if (!hasColumn(db, "items", "attachmentUri")) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `attachmentUri` TEXT")
            }
            if (!hasColumn(db, "items", "attachmentType")) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `attachmentType` TEXT")
            }
            if (!hasColumn(db, "item_units", "smallerUnit")) {
                db.execSQL("ALTER TABLE `item_units` ADD COLUMN `smallerUnit` TEXT NOT NULL DEFAULT 'حبة'")
            }
            if (!hasColumn(db, "item_units", "smallerUnitsCount")) {
                db.execSQL("ALTER TABLE `item_units` ADD COLUMN `smallerUnitsCount` REAL NOT NULL DEFAULT 1.0")
            }
            if (!hasColumn(db, "item_units", "smallerUnitCapacity")) {
                db.execSQL("ALTER TABLE `item_units` ADD COLUMN `smallerUnitCapacity` REAL NOT NULL DEFAULT 1.0")
            }
            if (!hasColumn(db, "voucher_items", "serialNumber")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `serialNumber` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "voucher_items", "itemStatus")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `itemStatus` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "voucher_items", "itemReceiverName")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `itemReceiverName` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "voucher_items", "attachmentUri")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `attachmentUri` TEXT")
            }
            if (!hasColumn(db, "voucher_items", "attachmentType")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `attachmentType` TEXT")
            }
        }

        val MIGRATION_1_5 = object : Migration(1, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_2_5 = object : Migration(2, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_3_5 = object : Migration(3, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_inventory_database.db"
                )
                    .addMigrations(MIGRATION_1_5, MIGRATION_2_5, MIGRATION_3_5, MIGRATION_4_5)
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
