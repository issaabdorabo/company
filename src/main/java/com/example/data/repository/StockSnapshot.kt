package com.example.data.repository

import com.example.data.dao.VoucherWithItems
import com.example.data.model.Item
import com.example.data.model.ItemStockInfo
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseItemStock
import com.example.data.model.WarehouseStockSummary

data class StockSnapshot(
    val warehouses: List<Warehouse> = emptyList(),
    val items: List<Item> = emptyList(),
    val vouchers: List<VoucherWithItems> = emptyList(),
    val warehouseSummaries: List<WarehouseStockSummary> = emptyList(),
    val itemStockInfos: List<ItemStockInfo> = emptyList(),
    val warehouseItemStocks: List<WarehouseItemStock> = emptyList(),
    val lowStockItems: List<ItemStockInfo> = emptyList(),
    val totalInventoryValue: Double = 0.0,
    val totalInventoryQuantity: Double = 0.0,
    val stockMap: Map<Pair<Long, Long>, Double> = emptyMap()
)
