package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.database.AppDatabase
import com.example.data.model.Item
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class ExactUnitDisbursementTest {

    private lateinit var db: AppDatabase
    private lateinit var repository: InventoryRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = InventoryRepository(db, context)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun disbursingSelectedUnit_hidesExactUnit_andCancellationRestoresAllMetadata() = runBlocking {
        // 1. Setup warehouse, item, and initial inward stock of 3 units
        val whId = repository.insertWarehouse(
            Warehouse(name = "المخزن المركزي", managerName = "أحمد علي")
        )
        val itemId = repository.insertItem(
            Item(name = "جهاز كمبيوتر محمول", unit = "حبة", packageUnit = "كرتون", unitsPerPackage = 1.0)
        )
        repository.saveVoucherWithItems(
            Voucher(
                voucherNumber = "IN-1001",
                voucherType = VoucherType.INWARD,
                destinationWarehouseId = whId,
                supplierName = "مورد التقنية"
            ),
            listOf(
                VoucherItem(
                    voucherId = 0L,
                    itemId = itemId,
                    itemName = "جهاز كمبيوتر محمول",
                    quantity = 3.0,
                    singleQuantity = 3.0
                )
            )
        )

        // 2. Save 3 tracked unit serial records with distinct numbers, statuses, and notes
        val savedSerials = repository.saveSerialsAndReturn(
            listOf(
                ItemUnitSerialRecord(
                    warehouseId = whId,
                    itemId = itemId,
                    unitIndex = 1,
                    serialNumber = "SN-001",
                    status = "موجود",
                    notes = "وحدة سليمة"
                ),
                ItemUnitSerialRecord(
                    warehouseId = whId,
                    itemId = itemId,
                    unitIndex = 2,
                    serialNumber = "SN-SPECIAL",
                    status = "مفقود",
                    notes = "مكسور من الجانب"
                ),
                ItemUnitSerialRecord(
                    warehouseId = whId,
                    itemId = itemId,
                    unitIndex = 3,
                    serialNumber = "SN-003",
                    status = "عاطل",
                    notes = "بحاجة لصيانة"
                )
            )
        )
        assertEquals(3, savedSerials.size)
        val unit2 = savedSerials.first { it.unitIndex == 2 }
        assertTrue(unit2.id > 0L)

        // 3. Disburse ONLY unit #2 (the middle unit with unique serial/status/note)
        val disburseVoucherId = repository.saveVoucherWithItems(
            Voucher(
                voucherNumber = "OUT-2001",
                voucherType = VoucherType.DISBURSEMENT,
                sourceWarehouseId = whId,
                beneficiaryName = "قسم المالية",
                disburserName = "أمين المخزن"
            ),
            listOf(
                VoucherItem(
                    voucherId = 0L,
                    itemId = itemId,
                    itemName = "جهاز كمبيوتر محمول",
                    quantity = 1.0,
                    singleQuantity = 1.0,
                    serialNumber = unit2.serialNumber,
                    itemStatus = unit2.status,
                    notes = unit2.notes,
                    sourceSerialRecordId = unit2.id,
                    sourceUnitIndex = unit2.unitIndex
                )
            )
        )

        // 4. Verify ONLY unit #1 and unit #3 remain active, with their exact metadata preserved
        val activeAfterDisburse = repository.getSerialsList(whId, itemId)
        assertEquals(2, activeAfterDisburse.size)
        assertEquals(listOf(1, 3), activeAfterDisburse.map { it.unitIndex })
        assertEquals("SN-001", activeAfterDisburse[0].serialNumber)
        assertEquals("SN-003", activeAfterDisburse[1].serialNumber)
        assertEquals("بحاجة لصيانة", activeAfterDisburse[1].notes)

        val allWhSerialsAfterDisburse = repository.getAllSerialsForWarehouse(whId)
        assertEquals(2, allWhSerialsAfterDisburse.size)

        val stockAfterDisburse = repository.getCombinedStockStream().first()
            .warehouseItemStocks.first { it.warehouseId == whId && it.itemId == itemId }.currentStock
        assertEquals(2.0, stockAfterDisburse, 0.001)

        val countsDuringDisburse = repository.getLocalDiagnosticsCounts()
        assertEquals(2, countsDuringDisburse.activeSerials)
        assertEquals(1, countsDuringDisburse.disbursedSerials)

        // 5. Cancel / delete the disbursement voucher and assert exact restoration of unit #2
        repository.deleteVoucher(disburseVoucherId)

        val restoredList = repository.getSerialsList(whId, itemId)
        assertEquals(3, restoredList.size)
        val restoredUnit2 = restoredList.first { it.unitIndex == 2 }
        assertEquals(unit2.id, restoredUnit2.id)
        assertEquals("SN-SPECIAL", restoredUnit2.serialNumber)
        assertEquals("مفقود", restoredUnit2.status)
        assertEquals("مكسور من الجانب", restoredUnit2.notes)
        assertNull(restoredUnit2.disbursedVoucherId)

        val stockAfterCancel = repository.getCombinedStockStream().first()
            .warehouseItemStocks.first { it.warehouseId == whId && it.itemId == itemId }.currentStock
        assertEquals(3.0, stockAfterCancel, 0.001)

        val countsAfterCancel = repository.getLocalDiagnosticsCounts()
        assertEquals(3, countsAfterCancel.activeSerials)
        assertEquals(0, countsAfterCancel.disbursedSerials)
    }

    @Test
    fun nextStartUnitIndex_tracksHighestHistoricalUnitIndexIncludingDisbursed() = runBlocking {
        val whId = repository.insertWarehouse(Warehouse(name = "مخزن الاختبار", managerName = "سعيد"))
        val itemId = repository.insertItem(Item(name = "شاشة"))

        val saved = repository.saveSerialsAndReturn(
            listOf(
                ItemUnitSerialRecord(warehouseId = whId, itemId = itemId, unitIndex = 1, serialNumber = "A1"),
                ItemUnitSerialRecord(warehouseId = whId, itemId = itemId, unitIndex = 2, serialNumber = "A2")
            )
        )
        val unit2 = saved.first { it.unitIndex == 2 }

        repository.saveVoucherWithItems(
            Voucher(voucherNumber = "OUT-9", voucherType = VoucherType.DISBURSEMENT, sourceWarehouseId = whId),
            listOf(
                VoucherItem(
                    voucherId = 0L,
                    itemId = itemId,
                    itemName = "شاشة",
                    quantity = 1.0,
                    singleQuantity = 1.0,
                    sourceSerialRecordId = unit2.id,
                    sourceUnitIndex = unit2.unitIndex
                )
            )
        )

        // Next unit index must be 3 (not colliding with disbursed unitIndex = 2)
        val nextIdx = repository.getNextStartUnitIndex(whId, itemId)
        assertEquals(3, nextIdx)
    }
}
