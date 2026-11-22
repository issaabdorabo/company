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
import com.example.data.dao.ItemStatusDao
import com.example.data.dao.ItemUnitDao
import com.example.data.dao.ItemUnitSerialDao
import com.example.data.dao.OperationAuditLogDao
import com.example.data.dao.SmallerUnitDao
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
import com.example.data.model.ItemStatus
import com.example.data.model.ItemUnit
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.OperationAuditLog
import com.example.data.model.SmallerUnit
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
        SmallerUnit::class,
        ItemStatus::class,
        StockAdjustmentLog::class,
        WarehouseItemAssociation::class,
        ItemUnitSerialRecord::class,
        OperationAuditLog::class
    ],
    version = 10,
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
    abstract fun smallerUnitDao(): SmallerUnitDao
    abstract fun itemStatusDao(): ItemStatusDao
    abstract fun stockAdjustmentDao(): StockAdjustmentDao
    abstract fun warehouseItemAssociationDao(): WarehouseItemAssociationDao
    abstract fun itemUnitSerialDao(): ItemUnitSerialDao
    abstract fun operationAuditLogDao(): OperationAuditLogDao

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
                "CREATE TABLE IF NOT EXISTS `item_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `smallerUnit` TEXT NOT NULL DEFAULT 'حبة', `smallerUnitsCount` REAL NOT NULL DEFAULT 1.0, `smallerUnitCapacity` REAL NOT NULL DEFAULT 1.0, `hasMediumUnit` INTEGER NOT NULL DEFAULT 0, `mediumUnit` TEXT NOT NULL DEFAULT '', `mediumUnitsCount` REAL NOT NULL DEFAULT 0.0, `smallUnitsPerMedium` REAL NOT NULL DEFAULT 1.0)"
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
                "CREATE TABLE IF NOT EXISTS `item_unit_serials` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `warehouseId` INTEGER NOT NULL, `itemId` INTEGER NOT NULL, `unitIndex` INTEGER NOT NULL, `serialNumber` TEXT NOT NULL, `status` TEXT NOT NULL, `notes` TEXT NOT NULL, `disbursedVoucherId` INTEGER, `updatedAt` INTEGER NOT NULL)"
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
            if (!hasColumn(db, "item_units", "hasMediumUnit")) {
                db.execSQL("ALTER TABLE `item_units` ADD COLUMN `hasMediumUnit` INTEGER NOT NULL DEFAULT 0")
            }
            if (!hasColumn(db, "item_units", "mediumUnit")) {
                db.execSQL("ALTER TABLE `item_units` ADD COLUMN `mediumUnit` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "item_units", "mediumUnitsCount")) {
                db.execSQL("ALTER TABLE `item_units` ADD COLUMN `mediumUnitsCount` REAL NOT NULL DEFAULT 0.0")
            }
            if (!hasColumn(db, "item_units", "smallUnitsPerMedium")) {
                db.execSQL("ALTER TABLE `item_units` ADD COLUMN `smallUnitsPerMedium` REAL NOT NULL DEFAULT 1.0")
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
            if (!hasColumn(db, "voucher_items", "sourceSerialRecordId")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `sourceSerialRecordId` INTEGER")
            }
            if (!hasColumn(db, "voucher_items", "sourceUnitIndex")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `sourceUnitIndex` INTEGER")
            }
            if (!hasColumn(db, "voucher_items", "mediumUnit")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `mediumUnit` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "voucher_items", "mediumQuantity")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `mediumQuantity` REAL NOT NULL DEFAULT 0.0")
            }
            if (!hasColumn(db, "voucher_items", "mediumUnitsPerLarge")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `mediumUnitsPerLarge` REAL NOT NULL DEFAULT 0.0")
            }
            if (!hasColumn(db, "voucher_items", "smallUnitsPerMedium")) {
                db.execSQL("ALTER TABLE `voucher_items` ADD COLUMN `smallUnitsPerMedium` REAL NOT NULL DEFAULT 1.0")
            }
            if (!hasColumn(db, "item_unit_serials", "disbursedVoucherId")) {
                db.execSQL("ALTER TABLE `item_unit_serials` ADD COLUMN `disbursedVoucherId` INTEGER")
            }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `smaller_units` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL, `capacity` REAL NOT NULL DEFAULT 1.0, `parentUnitId` INTEGER NOT NULL DEFAULT 0, `parentUnitName` TEXT NOT NULL DEFAULT '', `unitLevel` TEXT NOT NULL DEFAULT 'SMALL', `countInParent` REAL NOT NULL DEFAULT 1.0, `mediumUnitName` TEXT NOT NULL DEFAULT '')"
            )
            if (!hasColumn(db, "smaller_units", "parentUnitId")) {
                db.execSQL("ALTER TABLE `smaller_units` ADD COLUMN `parentUnitId` INTEGER NOT NULL DEFAULT 0")
            }
            if (!hasColumn(db, "smaller_units", "parentUnitName")) {
                db.execSQL("ALTER TABLE `smaller_units` ADD COLUMN `parentUnitName` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "smaller_units", "unitLevel")) {
                db.execSQL("ALTER TABLE `smaller_units` ADD COLUMN `unitLevel` TEXT NOT NULL DEFAULT 'SMALL'")
            }
            if (!hasColumn(db, "smaller_units", "countInParent")) {
                db.execSQL("ALTER TABLE `smaller_units` ADD COLUMN `countInParent` REAL NOT NULL DEFAULT 1.0")
            }
            if (!hasColumn(db, "smaller_units", "mediumUnitName")) {
                db.execSQL("ALTER TABLE `smaller_units` ADD COLUMN `mediumUnitName` TEXT NOT NULL DEFAULT ''")
            }
            db.execSQL(
                "CREATE TABLE IF NOT EXISTS `item_statuses` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `name` TEXT NOT NULL)"
            )
            if (!hasColumn(db, "items", "smallerUnitCapacity")) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `smallerUnitCapacity` REAL NOT NULL DEFAULT 1.0")
            }
            if (!hasColumn(db, "items", "hasSerialNumbers")) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `hasSerialNumbers` INTEGER NOT NULL DEFAULT 0")
            }
            if (!hasColumn(db, "items", "hasMediumUnit")) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `hasMediumUnit` INTEGER NOT NULL DEFAULT 0")
            }
            if (!hasColumn(db, "items", "mediumUnit")) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `mediumUnit` TEXT NOT NULL DEFAULT ''")
            }
            if (!hasColumn(db, "items", "mediumUnitsPerLarge")) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `mediumUnitsPerLarge` REAL NOT NULL DEFAULT 0.0")
            }
            if (!hasColumn(db, "items", "smallUnitsPerMedium")) {
                db.execSQL("ALTER TABLE `items` ADD COLUMN `smallUnitsPerMedium` REAL NOT NULL DEFAULT 1.0")
            }
        }

        val MIGRATION_1_8 = object : Migration(1, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_2_8 = object : Migration(2, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_3_8 = object : Migration(3, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_4_8 = object : Migration(4, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_5_8 = object : Migration(5, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_6_8 = object : Migration(6, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(db: SupportSQLiteDatabase) = ensureUpgradeTablesAndColumns(db)
        }

        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("CREATE TABLE IF NOT EXISTS `operation_audit_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `timestamp` INTEGER NOT NULL, `actorName` TEXT NOT NULL, `deviceName` TEXT NOT NULL, `operationType` TEXT NOT NULL, `title` TEXT NOT NULL, `details` TEXT NOT NULL)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_operation_audit_logs_timestamp` ON `operation_audit_logs` (`timestamp`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_operation_audit_logs_actorName` ON `operation_audit_logs` (`actorName`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_operation_audit_logs_operationType` ON `operation_audit_logs` (`operationType`)")
            }
        }

        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(db: SupportSQLiteDatabase) {
                if (!hasColumn(db, "vouchers", "createdBy")) {
                    db.execSQL("ALTER TABLE `vouchers` ADD COLUMN `createdBy` TEXT NOT NULL DEFAULT ''")
                }
                if (!hasColumn(db, "vouchers", "deviceName")) {
                    db.execSQL("ALTER TABLE `vouchers` ADD COLUMN `deviceName` TEXT NOT NULL DEFAULT 'الجهاز الرئيسي'")
                }
                if (!hasColumn(db, "vouchers", "numberingWarehouseId")) {
                    db.execSQL("ALTER TABLE `vouchers` ADD COLUMN `numberingWarehouseId` INTEGER")
                }
                // Continue existing numbering after migration, per warehouse.
                db.execSQL("UPDATE `vouchers` SET `numberingWarehouseId` = CASE WHEN `voucherType` = 'INWARD' THEN `destinationWarehouseId` ELSE `sourceWarehouseId` END WHERE `numberingWarehouseId` IS NULL")
            }
        }

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "smart_inventory_database.db"
                )
                    .addMigrations(
                        MIGRATION_1_8,
                        MIGRATION_2_8,
                        MIGRATION_3_8,
                        MIGRATION_4_8,
                        MIGRATION_5_8,
                        MIGRATION_6_8,
                        MIGRATION_7_8,
                        MIGRATION_8_9,
                        MIGRATION_9_10
                    )
                    .fallbackToDestructiveMigrationOnDowngrade(true)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
