package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemStatus
import com.example.data.model.ItemUnit
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.SmallerUnit
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.sync.LocalSyncJson
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], manifest = Config.NONE)
class InventoryRulesAndLookupTest {

    private lateinit var db: AppDatabase
    private lateinit var targetDb: AppDatabase
    private lateinit var repo: InventoryRepository
    private lateinit var targetRepo: InventoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        targetDb = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = InventoryRepository(db, context)
        targetRepo = InventoryRepository(targetDb, context)
    }

    @After
    fun tearDown() {
        db.close()
        targetDb.close()
    }

    @Test
    fun category_editAndDelete_blockedWhenUsed_allowedWhenUnused() = runBlocking {
        val unusedId = repo.insertCategory(ItemCategory(name = "تصنيف فارغ"))
        val usedId = repo.insertCategory(ItemCategory(name = "أجهزة شبكات"))
        repo.insertItem(
            Item(
                name = "راوتر",
                unit = "حبة",
                packageUnit = "صندوق",
                unitsPerPackage = 4.0,
                category = "أجهزة شبكات"
            )
        )

        val unusedCat = repo.allCategories.first().first { it.id == unusedId }
        val usedCat = repo.allCategories.first().first { it.id == usedId }

        assertTrue(repo.checkCategoryHasOperations(usedCat.name))
        assertFalse(repo.checkCategoryHasOperations(unusedCat.name))

        assertFalse(repo.updateCategorySafely(usedCat, "أجهزة معدلة"))
        assertFalse(repo.deleteCategorySafely(usedCat))

        assertTrue(repo.updateCategorySafely(unusedCat, "تصنيف محدث"))
        val updatedUnused = repo.allCategories.first().first { it.id == unusedId }
        assertEquals("تصنيف محدث", updatedUnused.name)

        assertTrue(repo.deleteCategorySafely(updatedUnused))
        assertTrue(repo.allCategories.first().none { it.id == unusedId })
    }

    @Test
    fun smallerUnit_and_packageUnit_editAndDelete_guardAndCascadeWorkCorrectly() = runBlocking {
        val suUnusedId = repo.insertSmallerUnit(SmallerUnit(name = "مل", capacity = 250.0))
        val pkgLinkedToUnusedId = repo.insertUnit(
            ItemUnit(
                name = "عبوة سوائل",
                smallerUnit = "مل",
                smallerUnitsCount = 6.0,
                smallerUnitCapacity = 250.0
            )
        )

        val suUnused = repo.allSmallerUnits.first().first { it.id == suUnusedId }
        val pkgLinked = repo.allUnits.first().first { it.id == pkgLinkedToUnusedId }

        // Unused by items/vouchers -> SmallerUnit edit succeeds and cascades to linked ItemUnit,
        // while delete is blocked while a package unit still references it
        assertFalse(repo.deleteSmallerUnitSafely(suUnused))
        assertTrue(repo.updateSmallerUnitSafely(suUnused, "مليلتر", 500.0))

        val pkgAfterCascade = repo.allUnits.first().first { it.id == pkgLinkedToUnusedId }
        assertEquals("مليلتر", pkgAfterCascade.smallerUnit)
        assertEquals(500.0, pkgAfterCascade.smallerUnitCapacity, 0.001)

        // Delete package unit (no items/vouchers) -> now smaller unit can also be deleted
        assertTrue(repo.deleteUnitSafely(pkgAfterCascade))
        val suNowFree = repo.allSmallerUnits.first().first { it.id == suUnusedId }
        assertTrue(repo.deleteSmallerUnitSafely(suNowFree))

        // SmallerUnit and ItemUnit used in an Item -> both edit and delete are blocked
        val suUsedId = repo.insertSmallerUnit(SmallerUnit(name = "حبة", capacity = 1.0))
        val pkgUsedId = repo.insertUnit(
            ItemUnit(
                name = "كرتون قياسي",
                smallerUnit = "حبة",
                smallerUnitsCount = 12.0,
                smallerUnitCapacity = 1.0
            )
        )
        repo.insertItem(
            Item(
                name = "شاحن سريع",
                unit = "حبة",
                packageUnit = "كرتون قياسي",
                unitsPerPackage = 12.0,
                smallerUnitCapacity = 1.0
            )
        )
        val suUsed = repo.allSmallerUnits.first().first { it.id == suUsedId }
        val pkgUsed = repo.allUnits.first().first { it.id == pkgUsedId }

        assertFalse(repo.updateSmallerUnitSafely(suUsed, "قطعة", 1.0))
        assertFalse(repo.deleteSmallerUnitSafely(suUsed))
        assertFalse(repo.updateUnitSafely(pkgUsed.copy(name = "كرتون معدل")))
        assertFalse(repo.deleteUnitSafely(pkgUsed))
    }

    @Test
    fun itemStatus_editAndDelete_blockedWhenUsed_allowedWhenUnused() = runBlocking {
        val unusedId = repo.insertItemStatus(ItemStatus(name = "قيد الفحص"))
        val usedId = repo.insertItemStatus(ItemStatus(name = "مفقود"))

        val wId = repo.insertWarehouse(Warehouse(name = "مخزن رئيسي", managerName = "مدير"))
        val iId = repo.insertItem(Item(name = "ماسح باركود", unit = "حبة", hasSerialNumbers = true))
        repo.saveSerialsAndReturn(
            listOf(
                ItemUnitSerialRecord(
                    warehouseId = wId,
                    itemId = iId,
                    unitIndex = 1,
                    serialNumber = "SN-01",
                    status = "مفقود"
                )
            )
        )

        val unusedStatus = repo.allItemStatuses.first().first { it.id == unusedId }
        val usedStatus = repo.allItemStatuses.first().first { it.id == usedId }

        assertTrue(repo.checkItemStatusHasOperations("مفقود"))
        assertFalse(repo.checkItemStatusHasOperations("قيد الفحص"))

        assertFalse(repo.updateItemStatusSafely(usedStatus, "ضائع"))
        assertFalse(repo.deleteItemStatusSafely(usedStatus))

        assertTrue(repo.updateItemStatusSafely(unusedStatus, "تحت التجربة"))
        val edited = repo.allItemStatuses.first().first { it.id == unusedId }
        assertEquals("تحت التجربة", edited.name)

        assertTrue(repo.deleteItemStatusSafely(edited))
        assertTrue(repo.allItemStatuses.first().none { it.id == unusedId })
    }

    @Test
    fun hasSerialNumbers_smallerUnits_and_itemStatuses_roundTripThroughBackupAndSync() = runBlocking {
        repo.insertSmallerUnit(SmallerUnit(name = "زجاجة", capacity = 2.0))
        repo.insertItemStatus(ItemStatus(name = "محجوز للإدارة"))
        val wId = repo.insertWarehouse(Warehouse(name = "المخزن المركزي", managerName = "أحمد"))
        val itemId = repo.insertItem(
            Item(
                name = "جهاز بصمة",
                unit = "زجاجة",
                packageUnit = "صندوق",
                unitsPerPackage = 6.0,
                smallerUnitCapacity = 2.0,
                openingStock = 4.0,
                hasSerialNumbers = true
            )
        )
        repo.saveVoucherWithItems(
            Voucher(
                voucherNumber = "IN-001",
                voucherType = VoucherType.INWARD,
                destinationWarehouseId = wId,
                supplierName = "مورد عام",
                receiverName = "مستلم"
            ),
            listOf(
                VoucherItem(
                    voucherId = 0L,
                    itemId = itemId,
                    itemName = "جهاز بصمة",
                    itemUnit = "زجاجة",
                    packageUnit = "صندوق",
                    unitsPerPackage = 6.0,
                    quantity = 2.0,
                    unitPrice = 50.0,
                    itemStatus = "محجوز للإدارة"
                )
            )
        )

        // Check snapshot propagates hasSerialNumbers
        val snapshot = repo.getCombinedStockStream().first()
        assertTrue(snapshot.warehouseItemStocks.first { it.itemId == itemId }.hasSerialNumbers)

        // Check LAN/Cloud sync round-trip
        val cloudUpserts = repo.exportAllEntitiesForCloud()
        val cloudRecords = LocalSyncJson.recordsFromJsonArray(
            LocalSyncJson.upsertsToJsonArray(cloudUpserts),
            "INV-TEST"
        )
        targetRepo.replaceDatabaseFromCloudEntitiesSafely(cloudRecords)

        val syncedItem = targetRepo.allItems.first().first()
        assertTrue(syncedItem.hasSerialNumbers)
        assertEquals(2.0, syncedItem.smallerUnitCapacity, 0.001)
        assertTrue(targetRepo.allSmallerUnits.first().any { it.name == "زجاجة" && it.capacity == 2.0 })
        assertTrue(targetRepo.allItemStatuses.first().any { it.name == "محجوز للإدارة" })

        // Check Backup/Restore round-trip
        val backupJson = repo.createBackup("نسخة كاملة").backupJson
        targetRepo.clearAllSyncedData()
        assertTrue(targetRepo.restoreBackupReplacingExisting(backupJson))
        val restoredItem = targetRepo.allItems.first().first()
        assertTrue(restoredItem.hasSerialNumbers)
        assertEquals(2.0, restoredItem.smallerUnitCapacity, 0.001)
        assertTrue(targetRepo.allSmallerUnits.first().any { it.name == "زجاجة" })
        assertTrue(targetRepo.allItemStatuses.first().any { it.name == "محجوز للإدارة" })
    }
}
