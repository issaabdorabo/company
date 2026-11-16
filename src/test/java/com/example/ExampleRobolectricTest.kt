package com.example

import com.example.data.model.Item
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTestSuite {

    @Test
    fun testWarehouseModel() {
        val warehouse = Warehouse(
            id = 1L,
            name = "المستودع الرئيسي",
            location = "شارع الستين",
            managerName = "عيسى عبدربه",
            phone = "771438740"
        )
        assertEquals(1L, warehouse.id)
        assertEquals("المستودع الرئيسي", warehouse.name)
        assertEquals("عيسى عبدربه", warehouse.managerName)
    }

    @Test
    fun testItemModel() {
        val item = Item(
            id = 10L,
            name = "حاسوب محمول",
            barcode = "62910001",
            unit = "جهاز",
            minStockLevel = 4.0,
            purchasePrice = 500.0
        )
        assertEquals(10L, item.id)
        assertEquals("حاسوب محمول", item.name)
        assertEquals(4.0, item.minStockLevel, 0.001)
    }

    @Test
    fun testVoucherTypeTitles() {
        assertEquals("توريد وارد", VoucherType.INWARD.titleAr)
        assertEquals("صرف وتحويل لمخزن", VoucherType.TRANSFER.titleAr)
        assertEquals("صرف لجهة مستفيدة", VoucherType.DISBURSEMENT.titleAr)
    }

    @Test
    fun testDualUnitStockCalculation() {
        // Dual unit formula: (packages * unitsPerPackage) + singles
        val unitsPerPackage = 12.0
        val packages = 5.0
        val singles = 4.0
        val totalPieces = (packages * unitsPerPackage) + singles
        assertEquals(64.0, totalPieces, 0.001)
    }
}
