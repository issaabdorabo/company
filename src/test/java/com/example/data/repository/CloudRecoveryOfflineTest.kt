package com.example.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.cloud.CloudEntityRecord
import com.example.data.database.AppDatabase
import com.example.data.model.VoucherType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CloudRecoveryOfflineTest {

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
    fun replaceDatabaseFromCloudEntitiesSafely_restoresFullWorkspaceAndSerialStatesWithoutErrors() = runBlocking {
        val cloudRecords = listOf(
            CloudEntityRecord(
                id = "warehouse_1",
                workspaceId = "ws_1",
                entityType = "warehouse",
                localId = 1L,
                payload = mapOf(
                    "id" to 1L,
                    "name" to "المستودع الرئيسي",
                    "managerName" to "عيسى عبدربه"
                )
            ),
            CloudEntityRecord(
                id = "item_10",
                workspaceId = "ws_1",
                entityType = "item",
                localId = 10L,
                payload = mapOf(
                    "id" to "10", // String coercion resilience
                    "name" to "طابعة شبكة",
                    "unit" to "حبة",
                    "packageUnit" to "صندوق",
                    "unitsPerPackage" to 2,
                    "minStockLevel" to 1.0
                )
            ),
            CloudEntityRecord(
                id = "voucher_100",
                workspaceId = "ws_1",
                entityType = "voucher",
                localId = 100L,
                payload = mapOf(
                    "id" to 100L,
                    "voucherNumber" to "IN-100",
                    "voucherType" to VoucherType.INWARD.name,
                    "destinationWarehouseId" to 1L
                )
            ),
            CloudEntityRecord(
                id = "vitem_101",
                workspaceId = "ws_1",
                entityType = "voucher_item",
                localId = 101L,
                payload = mapOf(
                    "id" to 101L,
                    "voucherId" to 100L,
                    "itemId" to 10L,
                    "itemName" to "طابعة شبكة",
                    "quantity" to 3.0,
                    "singleQuantity" to 3.0
                )
            ),
            CloudEntityRecord(
                id = "voucher_200",
                workspaceId = "ws_1",
                entityType = "voucher",
                localId = 200L,
                payload = mapOf(
                    "id" to 200L,
                    "voucherNumber" to "OUT-200",
                    "voucherType" to VoucherType.DISBURSEMENT.name,
                    "sourceWarehouseId" to 1L,
                    "beneficiaryName" to "الإدارة العامة"
                )
            ),
            CloudEntityRecord(
                id = "vitem_201",
                workspaceId = "ws_1",
                entityType = "voucher_item",
                localId = 201L,
                payload = mapOf(
                    "id" to 201L,
                    "voucherId" to 200L,
                    "itemId" to 10L,
                    "itemName" to "طابعة شبكة",
                    "quantity" to 1.0,
                    "singleQuantity" to 1.0,
                    "sourceSerialRecordId" to 501L,
                    "sourceUnitIndex" to 1
                )
            ),
            CloudEntityRecord(
                id = "serial_501",
                workspaceId = "ws_1",
                entityType = "serial",
                localId = 501L,
                payload = mapOf(
                    "id" to 501L,
                    "warehouseId" to 1L,
                    "itemId" to 10L,
                    "unitIndex" to 1,
                    "serialNumber" to "PRN-001",
                    "status" to "موجود",
                    "notes" to "مصروفة",
                    "disbursedVoucherId" to 200L
                )
            ),
            CloudEntityRecord(
                id = "serial_502",
                workspaceId = "ws_1",
                entityType = "serial",
                localId = 502L,
                payload = mapOf(
                    "id" to 502L,
                    "warehouseId" to 1L,
                    "itemId" to 10L,
                    "unitIndex" to 2,
                    "serialNumber" to "PRN-002",
                    "status" to "موجود",
                    "notes" to "متاحة بالمخزن",
                    "disbursedVoucherId" to null
                )
            ),
            // Corrupted / invalid record with id <= 0 should be safely ignored
            CloudEntityRecord(
                id = "item_bad",
                workspaceId = "ws_1",
                entityType = "item",
                localId = 0L,
                payload = mapOf("id" to 0L, "name" to "")
            )
        )

        val result = repository.replaceDatabaseFromCloudEntitiesSafely(cloudRecords)
        assertTrue(result.isSuccess)
        val summary = result.getOrNull()
        assertNotNull(summary)
        assertEquals(1, summary!!.warehouses)
        assertEquals(1, summary.items)
        assertEquals(2, summary.vouchers)
        assertEquals(2, summary.serials)

        val activeSerials = repository.getSerialsList(1L, 10L)
        assertEquals(1, activeSerials.size)
        assertEquals("PRN-002", activeSerials.first().serialNumber)

        val snapshot = repository.getCombinedStockStream().first()
        val stock = snapshot.warehouseItemStocks.first { it.warehouseId == 1L && it.itemId == 10L }.currentStock
        assertEquals(2.0, stock, 0.001)
    }
}
