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
        val pkgId = repo.saveHierarchicalUnit(
            existingUnit = null,
            largeName = "عبوة سوائل",
            hasMediumUnit = false,
            mediumName = "",
            mediumPerLarge = 0.0,
            smallName = "مل",
            smallCountInParent = 6.0,
            smallCapacity = 250.0
        ).getOrThrow()

        val childrenBefore = repo.allSmallerUnits.first().filter { it.parentUnitId == pkgId }
        assertEquals(1, childrenBefore.size)
        val suChild = childrenBefore.first()

        // Unused by items/vouchers -> scoped SmallerUnit edit succeeds and cascades to parent ItemUnit
        assertTrue(repo.updateChildUnitSafely(suChild, "مليلتر", 8.0, 500.0))

        val pkgAfterCascade = repo.allUnits.first().first { it.id == pkgId }
        assertEquals("مليلتر", pkgAfterCascade.smallerUnit)
        assertEquals(8.0, pkgAfterCascade.smallerUnitsCount, 0.001)
        assertEquals(500.0, pkgAfterCascade.smallerUnitCapacity, 0.001)

        // Parent-scoped child unit with zero operations CAN also be deleted directly
        val suEdited = repo.allSmallerUnits.first().first { it.id == suChild.id }
        assertTrue(repo.deleteSmallerUnitSafely(suEdited))

        // Delete package unit (no items/vouchers)
        assertTrue(repo.deleteUnitSafely(pkgAfterCascade))

        // Larger + Smaller unit used in an Item -> both edit and delete are blocked
        val pkgUsedId = repo.saveHierarchicalUnit(
            existingUnit = null,
            largeName = "كرتون قياسي",
            hasMediumUnit = false,
            mediumName = "",
            mediumPerLarge = 0.0,
            smallName = "حبة",
            smallCountInParent = 12.0,
            smallCapacity = 1.0
        ).getOrThrow()
        repo.insertItem(
            Item(
                name = "شاحن سريع",
                unit = "حبة",
                packageUnit = "كرتون قياسي",
                unitsPerPackage = 12.0,
                smallerUnitCapacity = 1.0
            )
        )
        val suUsed = repo.allSmallerUnits.first().first { it.parentUnitId == pkgUsedId }
        val pkgUsed = repo.allUnits.first().first { it.id == pkgUsedId }

        assertFalse(repo.updateSmallerUnitSafely(suUsed, "قطعة", 1.0))
        assertFalse(repo.deleteSmallerUnitSafely(suUsed))
        assertFalse(repo.updateUnitSafely(pkgUsed.copy(name = "كرتون معدل")))
        assertFalse(repo.deleteUnitSafely(pkgUsed))
    }

    @Test
    fun hierarchicalUnits_3Level_sameNameDifferentParent_scopedEditDelete_and_roundTrip() = runBlocking {
        // 3-level hierarchy A: كرتون إلكترونيات -> باكت (4) -> حبة (12) = 48 حبة
        val unitAId = repo.saveHierarchicalUnit(
            existingUnit = null,
            largeName = "كرتون إلكترونيات",
            hasMediumUnit = true,
            mediumName = "باكت",
            mediumPerLarge = 4.0,
            smallName = "حبة",
            smallCountInParent = 12.0,
            smallCapacity = 1.0
        ).getOrThrow()

        // 3-level hierarchy B with IDENTICAL child unit names ("باكت", "حبة") under a DIFFERENT parent
        val unitBId = repo.saveHierarchicalUnit(
            existingUnit = null,
            largeName = "صندوق قرطاسية",
            hasMediumUnit = true,
            mediumName = "باكت",
            mediumPerLarge = 5.0,
            smallName = "حبة",
            smallCountInParent = 10.0,
            smallCapacity = 1.0
        ).getOrThrow()

        val unitA = repo.allUnits.first().first { it.id == unitAId }
        assertTrue(unitA.hasMediumUnit)
        assertEquals(48.0, unitA.smallerUnitsCount, 0.001)

        // Create an item & inward voucher using Hierarchy A ONLY
        val wId = repo.insertWarehouse(Warehouse(name = "مخزن التقنية", managerName = "سالم"))
        val itemId = repo.insertItem(
            Item(
                name = "فأرة لاسلكية",
                unit = "حبة",
                packageUnit = "كرتون إلكترونيات",
                unitsPerPackage = 48.0,
                hasMediumUnit = true,
                mediumUnit = "باكت",
                mediumUnitsPerLarge = 4.0,
                smallUnitsPerMedium = 12.0,
                openingStock = 0.0
            )
        )
        repo.saveVoucherWithItems(
            Voucher(
                voucherNumber = "IN-300",
                voucherType = VoucherType.INWARD,
                destinationWarehouseId = wId,
                supplierName = "مورد",
                receiverName = "مستلم"
            ),
            listOf(
                VoucherItem(
                    voucherId = 0L,
                    itemId = itemId,
                    itemName = "فأرة لاسلكية",
                    itemUnit = "حبة",
                    packageUnit = "كرتون إلكترونيات",
                    packageQuantity = 1.0,
                    mediumUnit = "باكت",
                    mediumQuantity = 2.0,
                    singleQuantity = 5.0,
                    unitsPerPackage = 48.0,
                    mediumUnitsPerLarge = 4.0,
                    smallUnitsPerMedium = 12.0,
                    quantity = 77.0, // 48 + 24 + 5
                    unitPrice = 10.0
                )
            )
        )

        val childrenA = repo.allSmallerUnits.first().filter { it.parentUnitId == unitAId }
        val medA = childrenA.first { it.unitLevel == "MEDIUM" }
        val smallA = childrenA.first { it.unitLevel == "SMALL" }

        val childrenB = repo.allSmallerUnits.first().filter { it.parentUnitId == unitBId }
        val medB = childrenB.first { it.unitLevel == "MEDIUM" }
        val smallB = childrenB.first { it.unitLevel == "SMALL" }

        // Operations on Hierarchy A block editing/deleting A's "باكت" & "حبة"
        assertTrue(repo.checkChildUnitHasOperations(medA))
        assertTrue(repo.checkChildUnitHasOperations(smallA))
        assertFalse(repo.updateChildUnitSafely(medA, "باكت معدل", 4.0, 1.0))
        assertFalse(repo.deleteSmallerUnitSafely(smallA))

        // Identically-named children under UNUSED Hierarchy B MUST still be editable & deletable!
        assertFalse(repo.checkChildUnitHasOperations(medB))
        assertFalse(repo.checkChildUnitHasOperations(smallB))
        assertTrue(repo.updateChildUnitSafely(medB, "علبة", 6.0, 1.0))
        assertTrue(repo.updateChildUnitSafely(smallB, "قطعة", 8.0, 2.0))

        val unitBUpdated = repo.allUnits.first().first { it.id == unitBId }
        assertEquals("علبة", unitBUpdated.mediumUnit)
        assertEquals("قطعة", unitBUpdated.smallerUnit)
        assertEquals(48.0, unitBUpdated.smallerUnitsCount, 0.001) // 6 * 8

        val smallBUpdated = repo.allSmallerUnits.first().first { it.id == smallB.id }
        assertTrue(repo.deleteSmallerUnitSafely(smallBUpdated))

        // Verify 3-level fields survive LAN Sync AND JSON Backup/Restore round-trip
        val cloudUpserts = repo.exportAllEntitiesForCloud()
        val cloudRecords = LocalSyncJson.recordsFromJsonArray(
            LocalSyncJson.upsertsToJsonArray(cloudUpserts),
            "INV-3LVL"
        )
        targetRepo.replaceDatabaseFromCloudEntitiesSafely(cloudRecords)

        val syncedItem = targetRepo.allItems.first().first { it.name == "فأرة لاسلكية" }
        assertTrue(syncedItem.hasMediumUnit)
        assertEquals("باكت", syncedItem.mediumUnit)
        assertEquals(4.0, syncedItem.mediumUnitsPerLarge, 0.001)
        assertEquals(12.0, syncedItem.smallUnitsPerMedium, 0.001)

        val syncedVoucherItem = targetRepo.allVouchers.first().first().items.first()
        assertEquals("باكت", syncedVoucherItem.mediumUnit)
        assertEquals(2.0, syncedVoucherItem.mediumQuantity, 0.001)
        assertEquals(77.0, syncedVoucherItem.quantity, 0.001)

        val backupJson = repo.createBackup("نسخة ثلاثية").backupJson
        targetRepo.clearAllSyncedData()
        assertTrue(targetRepo.restoreBackupReplacingExisting(backupJson))
        val restoredItem = targetRepo.allItems.first().first { it.name == "فأرة لاسلكية" }
        assertTrue(restoredItem.hasMediumUnit)
        assertEquals("باكت", restoredItem.mediumUnit)
        assertEquals(4.0, restoredItem.mediumUnitsPerLarge, 0.001)
        assertEquals(12.0, restoredItem.smallUnitsPerMedium, 0.001)
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
