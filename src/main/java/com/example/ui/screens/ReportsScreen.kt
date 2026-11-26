package com.example.ui.screens

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.repository.StockSnapshot
import com.example.ui.theme.DangerRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import com.example.util.ReportGenerator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private fun adaptiveReportColumnWidth(text: String): androidx.compose.ui.unit.Dp =
    (text.trim().length * 7.1f + 26f).dp.coerceIn(82.dp, 240.dp)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReportsScreen(
    viewModel: InventoryViewModel,
    snapshot: StockSnapshot?
) {
    val context = LocalContext.current
    val appUserName by viewModel.userName.collectAsState()
    val orgName by viewModel.orgName.collectAsState()
    val suppliers by viewModel.suppliers.collectAsState()
    val parties by viewModel.parties.collectAsState()
    val beneficiaries by viewModel.beneficiaries.collectAsState()
    val devicePermissions by viewModel.devicePermissions.collectAsState()

    // 1. Report Category
    // Options: "ALL_STOCK", "OUT_OF_STOCK", "LOW_STOCK", "INWARD", "DISBURSEMENT", "TRANSFER", "ITEM_FLOW"
    var selectedReportCategory by remember { mutableStateOf("ALL_STOCK") }

    // 2. Filters State
    var searchVoucherNumber by remember { mutableStateOf("") }

    // Date range filter: from date to date
    var fromDateMillis by remember { mutableStateOf<Long?>(null) }
    var toDateMillis by remember { mutableStateOf<Long?>(null) }

    var showFromDatePicker by remember { mutableStateOf(false) }
    var showToDatePicker by remember { mutableStateOf(false) }

    // Multi-selection sets
    val selectedWarehouseIds = remember { mutableStateListOf<Long>() }
    val selectedSuppliers = remember { mutableStateListOf<String>() }
    val selectedReceivers = remember { mutableStateListOf<String>() }
    val selectedDisbursers = remember { mutableStateListOf<String>() }
    val selectedBeneficiaryEntities = remember { mutableStateListOf<String>() }
    val selectedItemIds = remember { mutableStateListOf<Long>() }
    val selectedStatuses = remember { mutableStateListOf<String>() }
    val selectedSubUnits = remember { mutableStateListOf<String>() }

    // Multi-entity layout display mode: HORIZONTAL (single unified column) vs VERTICAL (dedicated column per entity)
    var entitiesDisplayMode by remember { mutableStateOf("HORIZONTAL") }

    // Filter Dialogs
    var showWarehouseFilterDialog by remember { mutableStateOf(false) }
    var showSupplierFilterDialog by remember { mutableStateOf(false) }
    var showReceiverFilterDialog by remember { mutableStateOf(false) }
    var showDisburserFilterDialog by remember { mutableStateOf(false) }
    var showBeneficiaryFilterDialog by remember { mutableStateOf(false) }
    var showItemFilterDialog by remember { mutableStateOf(false) }

    // Selected columns filter
    val selectedColumns = remember {
        mutableStateListOf(
            ReportGenerator.ReportColumn.SERIAL,
            ReportGenerator.ReportColumn.VOUCHER_NUMBER,
            ReportGenerator.ReportColumn.ITEM_NAME,
            ReportGenerator.ReportColumn.ITEM_UNIT,
            ReportGenerator.ReportColumn.STATUS,
            ReportGenerator.ReportColumn.SUB_UNIT,
            ReportGenerator.ReportColumn.WAREHOUSE,
            ReportGenerator.ReportColumn.TOTAL_QUANTITY,
            ReportGenerator.ReportColumn.PURCHASE_PRICE,
            ReportGenerator.ReportColumn.TOTAL_VALUE
        )
    }

    val visibleWarehouses = snapshot?.warehouses ?: emptyList()
    val warehouses = if (devicePermissions.isMainDevice || (devicePermissions.canViewAllWarehouses && devicePermissions.warehousePermissions.isEmpty())) {
        visibleWarehouses
    } else {
        visibleWarehouses.filter { warehouse -> devicePermissions.warehousePermissions.any { it.warehouseId == warehouse.id && it.canView && it.canReports } }
    }
    val reportWarehouseIds = warehouses.map { it.id }.toSet()
    val allItems = snapshot?.items ?: emptyList()
    var reportSerials by remember { mutableStateOf<List<ItemUnitSerialRecord>>(emptyList()) }
    LaunchedEffect(warehouses.map { it.id }) {
        reportSerials = warehouses.flatMap { warehouse -> viewModel.getAllSerialsForWarehouse(warehouse.id) }
    }
    val availableStatuses = remember(reportSerials) { reportSerials.map { it.status }.filter { it.isNotBlank() }.distinct().sorted() }
    val availableSubUnits = remember(reportSerials) { reportSerials.map { it.subUnit }.filter { it.isNotBlank() }.distinct().sorted() }
    val reportItemNames = remember(allItems) {
        allItems.associate { item ->
            val label = listOf(item.name, item.carType, item.carName)
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .joinToString(" • ")
            item.id to label
        }
    }
    fun reportItemName(itemId: Long, fallback: String): String =
        reportItemNames[itemId]?.takeIf { it.isNotBlank() } ?: fallback
    val allVouchers = (snapshot?.vouchers ?: emptyList()).filter { v ->
        (v.voucher.sourceWarehouseId == null || v.voucher.sourceWarehouseId in reportWarehouseIds) &&
            (v.voucher.destinationWarehouseId == null || v.voucher.destinationWarehouseId in reportWarehouseIds)
    }

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }

    // Build Report Data Rows as ReportRowData
    var warehouseDisplayMode by remember { mutableStateOf("HORIZONTAL") }
    var receiverDisplayMode by remember { mutableStateOf("HORIZONTAL") }
    var disburserDisplayMode by remember { mutableStateOf("HORIZONTAL") }
    var pageOrientation by remember { mutableStateOf("PORTRAIT") }

    val reportData = remember(
        snapshot,
        selectedReportCategory,
        selectedWarehouseIds.toList(),
        searchVoucherNumber,
        fromDateMillis,
        toDateMillis,
        selectedSuppliers.toList(),
        selectedReceivers.toList(),
        selectedDisbursers.toList(),
        selectedBeneficiaryEntities.toList(),
        selectedItemIds.toList(),
        selectedStatuses.toList(),
        selectedSubUnits.toList(),
        reportSerials,
        entitiesDisplayMode,
        warehouseDisplayMode,
        receiverDisplayMode,
        disburserDisplayMode
    ) {
        val rows = mutableListOf<ReportGenerator.ReportRowData>()

        val matchesDateRange: (Long) -> Boolean = { date ->
            val afterFrom = fromDateMillis == null || date >= fromDateMillis!!
            // End date includes entire day (+ 86399999 ms)
            val beforeTo = toDateMillis == null || date <= (toDateMillis!! + 86399999L)
            afterFrom && beforeTo
        }

        when (selectedReportCategory) {
            "ALL_STOCK", "OUT_OF_STOCK", "LOW_STOCK" -> {
                val rawStocks = (snapshot?.warehouseItemStocks ?: emptyList()).filter { it.warehouseId in reportWarehouseIds }
                val filteredStocks = rawStocks.filter { itemStock ->
                    val matchesWarehouse = selectedWarehouseIds.isEmpty() || selectedWarehouseIds.contains(itemStock.warehouseId)
                    val matchesItem = selectedItemIds.isEmpty() || selectedItemIds.contains(itemStock.itemId)
                    val matchesCategoryState = when (selectedReportCategory) {
                        "OUT_OF_STOCK" -> itemStock.currentStock <= 0.0001
                        "LOW_STOCK" -> itemStock.currentStock > 0 && itemStock.currentStock <= itemStock.minStockLevel
                        else -> true
                    }
                    val itemSerials = reportSerials.filter { it.warehouseId == itemStock.warehouseId && it.itemId == itemStock.itemId }
                    val filteredSerials = itemSerials.filter { serial ->
                        (selectedStatuses.isEmpty() || serial.status in selectedStatuses) &&
                            (selectedSubUnits.isEmpty() || serial.subUnit in selectedSubUnits)
                    }
                    val serialFilterMatches = (selectedStatuses.isEmpty() && selectedSubUnits.isEmpty()) || filteredSerials.isNotEmpty()
                    matchesWarehouse && matchesItem && matchesCategoryState && serialFilterMatches
                }

                for (stock in filteredStocks) {
                    val itemSerials = reportSerials.filter { it.warehouseId == stock.warehouseId && it.itemId == stock.itemId }
                    val shownSerials = itemSerials.filter { serial ->
                        (selectedStatuses.isEmpty() || serial.status in selectedStatuses) &&
                            (selectedSubUnits.isEmpty() || serial.subUnit in selectedSubUnits)
                    }
                    val shownStatuses = shownSerials.map { it.status }.filter { it.isNotBlank() }.distinct()
                    val shownSubUnits = shownSerials.map { it.subUnit }.filter { it.isNotBlank() }.distinct()
                    val displayedQuantity = if (selectedStatuses.isNotEmpty() || selectedSubUnits.isNotEmpty()) shownSerials.size.toDouble() else stock.currentStock
                    rows.add(
                        ReportGenerator.ReportRowData(
                            voucherNumber = "-",
                            warehouseName = stock.warehouseName,
                            itemName = reportItemName(stock.itemId, stock.itemName),
                            itemUnit = stock.itemUnit,
                            status = shownStatuses.joinToString("، ").ifBlank { "-" },
                            subUnit = shownSubUnits.joinToString("، ").ifBlank { "-" },
                            beneficiaryName = "-",
                            receiverName = "-",
                            disburserName = "-",
                            voucherDate = "-",
                            quantity = displayedQuantity,
                            totalQuantity = displayedQuantity,
                            minStockLevel = stock.minStockLevel,
                            unitPrice = stock.purchasePrice,
                            totalValue = stock.currentStock * stock.purchasePrice,
                            warehouseQuantities = mapOf(stock.warehouseName to stock.currentStock)
                        )
                    )
                }
            }

            "INWARD", "DISBURSEMENT", "TRANSFER" -> {
                val targetType = when (selectedReportCategory) {
                    "INWARD" -> VoucherType.INWARD
                    "DISBURSEMENT" -> VoucherType.DISBURSEMENT
                    else -> VoucherType.TRANSFER
                }

                val matchingVouchers = allVouchers.filter { vWithItems ->
                    val v = vWithItems.voucher
                    val matchesType = v.voucherType == targetType
                    val matchesVoucherNum = searchVoucherNumber.isBlank() || v.voucherNumber.contains(searchVoucherNumber, ignoreCase = true)
                    val matchesDate = matchesDateRange(v.date)

                    val matchesWarehouse = selectedWarehouseIds.isEmpty() ||
                        selectedWarehouseIds.contains(v.sourceWarehouseId) ||
                        selectedWarehouseIds.contains(v.destinationWarehouseId)

                    val matchesSuppliers = selectedSuppliers.isEmpty() || selectedSuppliers.contains(v.supplierName)
                    val matchesReceivers = selectedReceivers.isEmpty() || selectedReceivers.contains(v.receiverName)
                    val matchesDisbursers = selectedDisbursers.isEmpty() || selectedDisbursers.contains(v.disburserName)
                    val matchesBeneficiaries = selectedBeneficiaryEntities.isEmpty() || selectedBeneficiaryEntities.contains(v.beneficiaryName)

                    matchesType && matchesVoucherNum && matchesDate && matchesWarehouse &&
                        matchesSuppliers && matchesReceivers && matchesDisbursers && matchesBeneficiaries
                }

                for (vWithItems in matchingVouchers) {
                    val v = vWithItems.voucher
                    val wName = when (targetType) {
                        VoucherType.INWARD -> warehouses.find { it.id == v.destinationWarehouseId }?.name ?: "المخزن المستلم"
                        VoucherType.TRANSFER -> {
                            val src = warehouses.find { it.id == v.sourceWarehouseId }?.name ?: ""
                            val dst = warehouses.find { it.id == v.destinationWarehouseId }?.name ?: ""
                            "من $src إلى $dst"
                        }
                        VoucherType.DISBURSEMENT -> warehouses.find { it.id == v.sourceWarehouseId }?.name ?: "مخزن الصرف"
                    }

                    val sourceName = warehouses.find { it.id == v.sourceWarehouseId }?.name.orEmpty()
                    val destinationName = warehouses.find { it.id == v.destinationWarehouseId }?.name.orEmpty()
                    for (itm in vWithItems.items) {
                        if (selectedItemIds.isEmpty() || selectedItemIds.contains(itm.itemId)) {
                            val warehouseMovement = when (targetType) {
                                VoucherType.INWARD -> mapOf(destinationName to itm.quantity)
                                VoucherType.DISBURSEMENT -> mapOf(sourceName to -itm.quantity)
                                VoucherType.TRANSFER -> mapOf(sourceName to -itm.quantity, destinationName to itm.quantity)
                            }.filterKeys { it.isNotBlank() }
                            rows.add(
                                ReportGenerator.ReportRowData(
                                    voucherNumber = v.voucherNumber,
                                    warehouseName = wName,
                                    itemName = reportItemName(itm.itemId, itm.itemName),
                                    itemUnit = itm.itemUnit,
                                    status = itm.itemStatus,
                                    beneficiaryName = v.beneficiaryName.ifBlank { "-" },
                                    supplierName = v.supplierName.ifBlank { "-" },
                                    receiverName = v.receiverName.ifBlank { "-" },
                                    disburserName = v.disburserName.ifBlank { "-" },
                                    voucherDate = dateFormat.format(Date(v.date)),
                                    quantity = itm.quantity,
                                    totalQuantity = itm.quantity,
                                    minStockLevel = 0.0,
                                    unitPrice = itm.unitPrice,
                                    totalValue = itm.quantity * itm.unitPrice,
                                    entityQuantities = if (v.beneficiaryName.isNotBlank()) mapOf(v.beneficiaryName to itm.quantity) else emptyMap(),
                                    warehouseQuantities = warehouseMovement,
                                    receiverQuantities = if (v.receiverName.isNotBlank()) mapOf(v.receiverName to itm.quantity) else emptyMap(),
                                    disburserQuantities = if (v.disburserName.isNotBlank()) mapOf(v.disburserName to itm.quantity) else emptyMap()
                                )
                            )
                        }
                    }
                }
            }

            "ITEM_FLOW" -> {
                for (vWithItems in allVouchers) {
                    val v = vWithItems.voucher
                    if (matchesDateRange(v.date)) {
                        val matchesWarehouse = selectedWarehouseIds.isEmpty() ||
                            selectedWarehouseIds.contains(v.sourceWarehouseId) ||
                            selectedWarehouseIds.contains(v.destinationWarehouseId)

                        if (matchesWarehouse) {
                            val wName = warehouses.find { it.id == (v.destinationWarehouseId ?: v.sourceWarehouseId) }?.name ?: ""
                            val sourceName = warehouses.find { it.id == v.sourceWarehouseId }?.name.orEmpty()
                            val destinationName = warehouses.find { it.id == v.destinationWarehouseId }?.name.orEmpty()
                            for (itm in vWithItems.items) {
                                if (selectedItemIds.isEmpty() || selectedItemIds.contains(itm.itemId)) {
                                    val direction = when (v.voucherType) {
                                        VoucherType.INWARD -> "وارد (+)"
                                        VoucherType.DISBURSEMENT -> "منصرف (-)"
                                        VoucherType.TRANSFER -> "تحويل"
                                    }
                                    val warehouseMovement = when (v.voucherType) {
                                        VoucherType.INWARD -> mapOf(destinationName to itm.quantity)
                                        VoucherType.DISBURSEMENT -> mapOf(sourceName to -itm.quantity)
                                        VoucherType.TRANSFER -> mapOf(sourceName to -itm.quantity, destinationName to itm.quantity)
                                    }.filterKeys { it.isNotBlank() }
                                    rows.add(
                                        ReportGenerator.ReportRowData(
                                            voucherNumber = "${v.voucherNumber} ($direction)",
                                            warehouseName = wName,
                                            itemName = reportItemName(itm.itemId, itm.itemName),
                                            itemUnit = itm.itemUnit,
                                            status = itm.itemStatus,
                                            beneficiaryName = v.beneficiaryName.ifBlank { "-" },
                                            supplierName = v.supplierName.ifBlank { "-" },
                                            receiverName = v.receiverName.ifBlank { "-" },
                                            disburserName = v.disburserName.ifBlank { "-" },
                                            voucherDate = dateFormat.format(Date(v.date)),
                                            quantity = itm.quantity,
                                            totalQuantity = itm.quantity,
                                            minStockLevel = 0.0,
                                            unitPrice = itm.unitPrice,
                                            totalValue = itm.quantity * itm.unitPrice,
                                            entityQuantities = if (v.beneficiaryName.isNotBlank()) mapOf(v.beneficiaryName to itm.quantity) else emptyMap(),
                                            warehouseQuantities = warehouseMovement,
                                            receiverQuantities = if (v.receiverName.isNotBlank()) mapOf(v.receiverName to itm.quantity) else emptyMap(),
                                            disburserQuantities = if (v.disburserName.isNotBlank()) mapOf(v.disburserName to itm.quantity) else emptyMap()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val shouldPivotDimensions =
            (warehouseDisplayMode == "VERTICAL" && selectedWarehouseIds.size > 1) ||
            (receiverDisplayMode == "VERTICAL" && selectedReceivers.size > 1) ||
            (disburserDisplayMode == "VERTICAL" && selectedDisbursers.size > 1) ||
            (entitiesDisplayMode == "VERTICAL" && selectedBeneficiaryEntities.size > 1)
        val finalRows = if (shouldPivotDimensions) {
            rows.groupBy { it.itemName to it.itemUnit }.map { (_, itemRows) ->
                val first = itemRows.first()
                fun sumDimension(selector: (ReportGenerator.ReportRowData) -> Map<String, Double>): Map<String, Double> =
                    itemRows.flatMap { selector(it).entries }.groupBy { it.key }
                        .mapValues { (_, values) -> values.sumOf { it.value } }
                val warehouseValues = sumDimension { it.warehouseQuantities }
                val receiverValues = sumDimension { it.receiverQuantities }
                val disburserValues = sumDimension { it.disburserQuantities }
                val entityValues = sumDimension { it.entityQuantities }
                val uniqueVouchers = itemRows.map { it.voucherNumber }.filter { it.isNotBlank() }.distinct()
                val uniqueDates = itemRows.map { it.voucherDate }.filter { it.isNotBlank() }.distinct()
                ReportGenerator.ReportRowData(
                    voucherNumber = if (uniqueVouchers.size == 1) uniqueVouchers.first() else "مجمع (${uniqueVouchers.size})",
                    warehouseName = warehouseValues.keys.joinToString("، ").ifBlank { itemRows.map { it.warehouseName }.distinct().joinToString("، ") },
                    itemName = first.itemName,
                    itemUnit = first.itemUnit,
                    status = itemRows.map { it.status }.filter { it.isNotBlank() }.distinct().joinToString("، "),
                    subUnit = itemRows.map { it.subUnit }.filter { it.isNotBlank() }.distinct().joinToString("، "),
                    beneficiaryName = if (entityValues.size == 1) entityValues.keys.first() else "متعدد",
                    supplierName = itemRows.map { it.supplierName }.filter { it.isNotBlank() }.distinct().joinToString("، "),
                    receiverName = receiverValues.keys.joinToString("، ").ifBlank { itemRows.map { it.receiverName }.filter { it.isNotBlank() }.distinct().joinToString("، ") },
                    disburserName = disburserValues.keys.joinToString("، ").ifBlank { itemRows.map { it.disburserName }.filter { it.isNotBlank() }.distinct().joinToString("، ") },
                    voucherDate = if (uniqueDates.size == 1) uniqueDates.first() else "متعدد",
                    quantity = itemRows.sumOf { it.quantity },
                    totalQuantity = itemRows.sumOf { it.totalQuantity },
                    minStockLevel = first.minStockLevel,
                    unitPrice = first.unitPrice,
                    totalValue = itemRows.sumOf { it.totalValue },
                    entityQuantities = entityValues,
                    warehouseQuantities = warehouseValues,
                    receiverQuantities = receiverValues,
                    disburserQuantities = disburserValues
                )
            }
        } else rows

        // Alphabetical Sorting (أ-ي)
        finalRows.sortedBy { it.itemName.lowercase(Locale.getDefault()) }
    }

    // Dynamic title based on report category
    val dynamicReportTitle = remember(selectedReportCategory) {
        when (selectedReportCategory) {
            "ALL_STOCK" -> "تقرير جرد المخزون العام"
            "OUT_OF_STOCK" -> "تقرير المخزون المنتهي والنافد"
            "LOW_STOCK" -> "تقرير الأصناف التي بحاجة إلى إعادة طلب"
            "INWARD" -> "تقرير حركة التوريد والمشتريات"
            "DISBURSEMENT" -> "تقرير أوامر الصرف للجهات المستفيدة"
            "TRANSFER" -> "تقرير التحويلات المخزنية"
            "ITEM_FLOW" -> "تقرير حركة الوارد والمنصرف للأصناف"
            else -> "تقرير المخزون"
        }
    }

    val selectedWarehouseTitle = remember(warehouses, selectedWarehouseIds.size) {
        if (selectedWarehouseIds.isEmpty()) "كافة المخازن"
        else if (selectedWarehouseIds.size == 1) warehouses.find { it.id == selectedWarehouseIds.first() }?.name ?: "مخزن محدد"
        else "عدة مخازن مختارة (${selectedWarehouseIds.size})"
    }

    var lastGeneratedPdfFile by remember { mutableStateOf<File?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("reports_screen_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Report Type Tabs
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("نوع التقرير المطلوب استخراجه:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        item {
                            FilterChip(
                                selected = selectedReportCategory == "ALL_STOCK",
                                onClick = { selectedReportCategory = "ALL_STOCK" },
                                label = { Text("المخزون العام") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReportCategory == "OUT_OF_STOCK",
                                onClick = { selectedReportCategory = "OUT_OF_STOCK" },
                                label = { Text("المخزون المنتهي") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReportCategory == "LOW_STOCK",
                                onClick = { selectedReportCategory = "LOW_STOCK" },
                                label = { Text("بحاجة إعادة طلب") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReportCategory == "INWARD",
                                onClick = { selectedReportCategory = "INWARD" },
                                label = { Text("أوامر الوارد") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReportCategory == "DISBURSEMENT",
                                onClick = { selectedReportCategory = "DISBURSEMENT" },
                                label = { Text("المنصرف للجهات") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReportCategory == "TRANSFER",
                                onClick = { selectedReportCategory = "TRANSFER" },
                                label = { Text("التحويلات") }
                            )
                        }
                        item {
                            FilterChip(
                                selected = selectedReportCategory == "ITEM_FLOW",
                                onClick = { selectedReportCategory = "ITEM_FLOW" },
                                label = { Text("حركة صنف") }
                            )
                        }
                    }
                }
            }
        }

        // 2. Search & Modern Date Pickers (من تاريخ - إلى تاريخ)
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("فلاتر البحث والتاريخ والمخازن:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                    // Voucher number search
                    OutlinedTextField(
                        value = searchVoucherNumber,
                        onValueChange = { searchVoucherNumber = it },
                        placeholder = { Text("البحث برقم السند (مثال: IN-1001، OUT-1001)...") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.fillMaxWidth().testTag("search_voucher_number_input"),
                        shape = RoundedCornerShape(10.dp),
                        singleLine = true
                    )

                    // Modern Material 3 Date Picker Row (من تاريخ - إلى تاريخ)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val fromDateStr = fromDateMillis?.let { dateFormat.format(Date(it)) } ?: "غير محدد"
                        OutlinedTextField(
                            value = fromDateStr,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("من تاريخ") },
                            trailingIcon = {
                                IconButton(onClick = { showFromDatePicker = true }) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = "اختيار من تاريخ", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showFromDatePicker = true }
                        )

                        val toDateStr = toDateMillis?.let { dateFormat.format(Date(it)) } ?: "غير محدد"
                        OutlinedTextField(
                            value = toDateStr,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("إلى تاريخ") },
                            trailingIcon = {
                                IconButton(onClick = { showToDatePicker = true }) {
                                    Icon(Icons.Default.CalendarMonth, contentDescription = "اختيار إلى تاريخ", tint = MaterialTheme.colorScheme.primary)
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .clickable { showToDatePicker = true }
                        )
                    }

                    // Quick Date Chips
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text("فترات سريعة:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        val curr = System.currentTimeMillis()
                        FilterChip(
                            selected = fromDateMillis == null && toDateMillis == null,
                            onClick = {
                                fromDateMillis = null
                                toDateMillis = null
                            },
                            label = { Text("الكل", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                fromDateMillis = curr - 86400000L
                                toDateMillis = curr
                            },
                            label = { Text("اليوم", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                fromDateMillis = curr - (86400000L * 7)
                                toDateMillis = curr
                            },
                            label = { Text("أسبوع", fontSize = 11.sp) }
                        )
                        FilterChip(
                            selected = false,
                            onClick = {
                                fromDateMillis = curr - (86400000L * 30)
                                toDateMillis = curr
                            },
                            label = { Text("شهر", fontSize = 11.sp) }
                        )
                    }

                    // Advanced Multi-Selection Filter Buttons (المخازن، الموردين، المستلمين، القائمين بالصرف، الجهات، الأصناف)
                    Text("اختيار متعدد للمخازن والجهات:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showWarehouseFilterDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val count = selectedWarehouseIds.size
                            Text(if (count == 0) "المخازن (الكل)" else "المخازن ($count)", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = { showBeneficiaryFilterDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val count = selectedBeneficiaryEntities.size
                            Text(if (count == 0) "الجهات" else "الجهات ($count)", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = { showItemFilterDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val count = selectedItemIds.size
                            Text(if (count == 0) "الأصناف" else "الأصناف ($count)", fontSize = 10.sp)
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showSupplierFilterDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val count = selectedSuppliers.size
                            Text(if (count == 0) "الموردين" else "الموردين ($count)", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = { showReceiverFilterDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val count = selectedReceivers.size
                            Text(if (count == 0) "المستلمين" else "المستلمين ($count)", fontSize = 10.sp)
                        }

                        OutlinedButton(
                            onClick = { showDisburserFilterDialog = true },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            val count = selectedDisbursers.size
                            Text(if (count == 0) "الصارفين" else "الصارفين ($count)", fontSize = 10.sp)
                        }
                    }
                    if (selectedReportCategory in listOf("ALL_STOCK", "OUT_OF_STOCK", "LOW_STOCK")) {
                        Text("حالات الصنف والوحدات الفرعية (اختيار متعدد):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(selected = selectedStatuses.isEmpty(), onClick = { selectedStatuses.clear() }, label = { Text("كل الحالات", fontSize = 10.sp) })
                            availableStatuses.forEach { status ->
                                val count = reportSerials.count { it.status == status }
                                val selected = status in selectedStatuses
                                FilterChip(selected = selected, onClick = { if (selected) selectedStatuses.remove(status) else selectedStatuses.add(status) }, label = { Text("$status ($count)", fontSize = 10.sp) })
                            }
                        }
                        if (availableSubUnits.isNotEmpty()) {
                            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                FilterChip(selected = selectedSubUnits.isEmpty(), onClick = { selectedSubUnits.clear() }, label = { Text("كل الوحدات", fontSize = 10.sp) })
                                availableSubUnits.forEach { unit ->
                                    val count = reportSerials.count { it.subUnit == unit }
                                    val selected = unit in selectedSubUnits
                                    FilterChip(selected = selected, onClick = { if (selected) selectedSubUnits.remove(unit) else selectedSubUnits.add(unit) }, label = { Text("$unit ($count)", fontSize = 10.sp) })
                                }
                            }
                        }
                    }
                }
            }
        }

        // 3. Columns Selector Checkboxes
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("تحديد الأعمدة المطلوب إظهارها في التقرير:", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))

                    val allCols = ReportGenerator.ReportColumn.values()
                    val chunked = allCols.toList().chunked(2)
                    chunked.forEach { rowCols ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowCols.forEach { col ->
                                val isChecked = selectedColumns.contains(col)
                                Row(
                                    modifier = Modifier.weight(1f).padding(vertical = 2.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Checkbox(
                                        checked = isChecked,
                                        onCheckedChange = { checked ->
                                            if (checked) selectedColumns.add(col)
                                            else if (selectedColumns.size > 1) selectedColumns.remove(col)
                                        }
                                    )
                                    Text(text = col.titleAr, style = MaterialTheme.typography.bodySmall)
                                }
                            }
                        }
                    }
                }
            }
        }

        // 4. Column layout selectors are independent and appear when the relevant field has multiple values.
        val showWarehouseLayout = selectedWarehouseIds.size > 1
        val showReceiverLayout = selectedReceivers.size > 1
        val showDisburserLayout = selectedDisbursers.size > 1
        val showEntityLayout = selectedBeneficiaryEntities.size > 1
        if (showWarehouseLayout || showReceiverLayout || showDisburserLayout || showEntityLayout) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("طريقة عرض الأعمدة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        @Composable fun ColumnLayoutPicker(title: String, mode: String, onMode: (String) -> Unit, modifier: Modifier = Modifier) {
                            Column(verticalArrangement = Arrangement.spacedBy(3.dp), modifier = modifier) {
                                Text(title, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    FilterChip(selected = mode == "HORIZONTAL", onClick = { onMode("HORIZONTAL") }, label = { Text("أفقي", fontSize = 10.sp) })
                                    FilterChip(selected = mode == "VERTICAL", onClick = { onMode("VERTICAL") }, label = { Text("عمودي", fontSize = 10.sp) })
                                }
                            }
                        }
                        if (showWarehouseLayout) {
                            ColumnLayoutPicker("المخزن (${selectedWarehouseIds.size})", warehouseDisplayMode, { warehouseDisplayMode = it }, Modifier.fillMaxWidth())
                        }
                        if (showReceiverLayout || showDisburserLayout) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                if (showReceiverLayout) ColumnLayoutPicker("المستلم (${selectedReceivers.size})", receiverDisplayMode, { receiverDisplayMode = it }, Modifier.weight(1f))
                                if (showDisburserLayout) ColumnLayoutPicker("القائم بالصرف (${selectedDisbursers.size})", disburserDisplayMode, { disburserDisplayMode = it }, Modifier.weight(1f))
                            }
                        }
                        if (showEntityLayout) {
                            androidx.compose.material3.HorizontalDivider()
                            Text("الجهات المستفيدة (${selectedBeneficiaryEntities.size})", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = entitiesDisplayMode == "HORIZONTAL", onClick = { entitiesDisplayMode = "HORIZONTAL" }, label = { Text("أفقي: عمود موحد") }, modifier = Modifier.weight(1f))
                                FilterChip(selected = entitiesDisplayMode == "VERTICAL", onClick = { entitiesDisplayMode = "VERTICAL" }, label = { Text("عمودي: عمود لكل جهة") }, modifier = Modifier.weight(1f))
                            }
                        }
                    }
                }
            }
        }

        // 6. Export & Share Action Buttons (Direct Preview, Share PDF, Share Excel)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = dynamicReportTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("اتجاه ورقة التقرير", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(selected = pageOrientation == "PORTRAIT", onClick = { pageOrientation = "PORTRAIT" }, label = { Text("عمودي A4") })
                        FilterChip(selected = pageOrientation == "LANDSCAPE", onClick = { pageOrientation = "LANDSCAPE" }, label = { Text("أفقي A4") })
                    }
                    Spacer(modifier = Modifier.height(8.dp))

                    val activeEntityCols = if (entitiesDisplayMode == "VERTICAL" && selectedBeneficiaryEntities.size > 1) selectedBeneficiaryEntities.toList() else emptyList()
                    val activeWarehouseCols = if (warehouseDisplayMode == "VERTICAL" && selectedWarehouseIds.size > 1) selectedWarehouseIds.mapNotNull { id -> warehouses.find { it.id == id }?.name } else emptyList()
                    val activeReceiverCols = if (receiverDisplayMode == "VERTICAL" && selectedReceivers.size > 1) selectedReceivers.toList() else emptyList()
                    val activeDisburserCols = if (disburserDisplayMode == "VERTICAL" && selectedDisbursers.size > 1) selectedDisbursers.toList() else emptyList()
                    val activeDimensionColumns = mapOf(
                        ReportGenerator.ReportColumn.WAREHOUSE to activeWarehouseCols,
                        ReportGenerator.ReportColumn.RECEIVER to activeReceiverCols,
                        ReportGenerator.ReportColumn.DISBURSER to activeDisburserCols
                    ).filterValues { it.isNotEmpty() }

                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                val file = ReportGenerator.generatePdf(
                                    context = context,
                                    title = dynamicReportTitle,
                                    warehouseName = selectedWarehouseTitle,
                                    appUserName = appUserName,
                                    orgName = orgName,
                                    rows = reportData,
                                    selectedColumns = selectedColumns.toSet(),
                                    entityColumns = activeEntityCols,
                                    columnDisplayModes = mapOf(
                                        ReportGenerator.ReportColumn.WAREHOUSE to warehouseDisplayMode,
                                        ReportGenerator.ReportColumn.RECEIVER to receiverDisplayMode,
                                        ReportGenerator.ReportColumn.DISBURSER to disburserDisplayMode
                                    ),
                                    columnEntityValues = activeDimensionColumns,
                                    isLandscape = pageOrientation == "LANDSCAPE"
                                )
                                lastGeneratedPdfFile = file
                                ReportGenerator.viewFile(context, file, "application/pdf")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                            modifier = Modifier.weight(1f).testTag("preview_pdf_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("معاينة", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                val file = ReportGenerator.generatePdf(
                                    context = context,
                                    title = dynamicReportTitle,
                                    warehouseName = selectedWarehouseTitle,
                                    appUserName = appUserName,
                                    orgName = orgName,
                                    rows = reportData,
                                    selectedColumns = selectedColumns.toSet(),
                                    entityColumns = activeEntityCols,
                                    columnDisplayModes = mapOf(
                                        ReportGenerator.ReportColumn.WAREHOUSE to warehouseDisplayMode,
                                        ReportGenerator.ReportColumn.RECEIVER to receiverDisplayMode,
                                        ReportGenerator.ReportColumn.DISBURSER to disburserDisplayMode
                                    ),
                                    columnEntityValues = activeDimensionColumns,
                                    isLandscape = pageOrientation == "LANDSCAPE"
                                )
                                lastGeneratedPdfFile = file
                                ReportGenerator.shareFile(context, file, "application/pdf", "مشاركة التقرير PDF")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                            modifier = Modifier.weight(1f).testTag("export_pdf_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تصدير PDF", fontSize = 11.sp)
                        }

                        Button(
                            onClick = {
                                val file = ReportGenerator.generateExcel(
                                    context = context,
                                    title = dynamicReportTitle,
                                    warehouseName = selectedWarehouseTitle,
                                    appUserName = appUserName,
                                    rows = reportData,
                                    selectedColumns = selectedColumns.toSet(),
                                    entityColumns = activeEntityCols,
                                    columnDisplayModes = mapOf(
                                        ReportGenerator.ReportColumn.WAREHOUSE to warehouseDisplayMode,
                                        ReportGenerator.ReportColumn.RECEIVER to receiverDisplayMode,
                                        ReportGenerator.ReportColumn.DISBURSER to disburserDisplayMode
                                    ),
                                    columnEntityValues = activeDimensionColumns
                                )
                                Toast.makeText(context, "تم تصدير ملف إكسل CSV بنجاح", Toast.LENGTH_SHORT).show()
                                ReportGenerator.shareFile(context, file, "text/csv", "مشاركة تقرير إكسل")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            modifier = Modifier.weight(1f).testTag("export_excel_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.TableChart, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("تصدير Excel", fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        // 6. Direct Live On-Screen Table Preview with Word Wrapping & Multi-Entity Pivot Columns
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "المعاينة المباشرة للتقرير (${reportData.size} سجل)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant
                        ) {
                            Text(
                                text = "المستخدم: $appUserName",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val activeCols = ReportGenerator.ReportColumn.values().filter { selectedColumns.contains(it) }
                    val previewColumnWidths = remember(reportData, activeCols) {
                        activeCols.associateWith { col ->
                            val values = reportData.map { row ->
                                when (col) {
                                    ReportGenerator.ReportColumn.SERIAL -> "0000"
                                    ReportGenerator.ReportColumn.VOUCHER_NUMBER -> row.voucherNumber
                                    ReportGenerator.ReportColumn.ITEM_NAME -> row.itemName
                                    ReportGenerator.ReportColumn.ITEM_UNIT -> row.itemUnit
                                    ReportGenerator.ReportColumn.STATUS -> row.status
                                    ReportGenerator.ReportColumn.SUB_UNIT -> row.subUnit
                                    ReportGenerator.ReportColumn.WAREHOUSE -> row.warehouseName
                                    ReportGenerator.ReportColumn.BENEFICIARY -> row.beneficiaryName
                                    ReportGenerator.ReportColumn.RECEIVER -> row.receiverName
                                    ReportGenerator.ReportColumn.DISBURSER -> row.disburserName
                                    ReportGenerator.ReportColumn.VOUCHER_DATE -> row.voucherDate
                                    ReportGenerator.ReportColumn.CURRENT_STOCK -> "%.0f".format(row.quantity)
                                    ReportGenerator.ReportColumn.TOTAL_QUANTITY -> "%.0f".format(row.totalQuantity)
                                    ReportGenerator.ReportColumn.MIN_STOCK -> "%.0f".format(row.minStockLevel)
                                    ReportGenerator.ReportColumn.PURCHASE_PRICE -> "%.2f $".format(row.unitPrice)
                                    ReportGenerator.ReportColumn.TOTAL_VALUE -> "%.2f $".format(row.totalValue)
                                }
                            }
                            val longest = maxOf(col.titleAr.length, values.maxOfOrNull { it.length } ?: 0)
                            val minWidth = when (col) {
                                ReportGenerator.ReportColumn.SERIAL -> 48.dp
                                ReportGenerator.ReportColumn.ITEM_NAME -> 110.dp
                                ReportGenerator.ReportColumn.ITEM_UNIT -> 64.dp
                                else -> 84.dp
                            }
                            val maxWidth = if (col == ReportGenerator.ReportColumn.ITEM_NAME) 260.dp else 220.dp
                            (longest * 7.1f + 26f).dp.coerceIn(minWidth, maxWidth)
                        }
                    }
                    val activeEntityCols = if (entitiesDisplayMode == "VERTICAL" && selectedBeneficiaryEntities.size > 1) selectedBeneficiaryEntities.toList() else emptyList()
                    val activeWarehouseCols = if (warehouseDisplayMode == "VERTICAL" && selectedWarehouseIds.size > 1) selectedWarehouseIds.mapNotNull { id -> warehouses.find { it.id == id }?.name } else emptyList()
                    val activeReceiverCols = if (receiverDisplayMode == "VERTICAL" && selectedReceivers.size > 1) selectedReceivers.toList() else emptyList()
                    val activeDisburserCols = if (disburserDisplayMode == "VERTICAL" && selectedDisbursers.size > 1) selectedDisbursers.toList() else emptyList()

                    // Horizontal scrolling RTL preview with soft text wrapping
                    val horizontalScrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalScrollState)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    ) {
                        Column(
                            modifier = Modifier
                                .heightIn(max = 360.dp)
                                .verticalScroll(rememberScrollState())
                        ) {
                            // Header Row
                            Row(
                                modifier = Modifier
                                    .background(MaterialTheme.colorScheme.primaryContainer)
                                    .padding(vertical = 8.dp, horizontal = 4.dp)
                            ) {
                                activeCols.forEach { col ->
                                    if (col == ReportGenerator.ReportColumn.BENEFICIARY && activeEntityCols.isNotEmpty()) {
                                        activeEntityCols.forEach { entName ->
                                            Text(
                                                text = entName,
                                                modifier = Modifier.width(adaptiveReportColumnWidth(entName)).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                softWrap = true,
                                                maxLines = 8
                                            )
                                        }
                                    } else if (col == ReportGenerator.ReportColumn.WAREHOUSE && activeWarehouseCols.isNotEmpty()) {
                                        activeWarehouseCols.forEach { name -> Text(name, modifier = Modifier.width(adaptiveReportColumnWidth(name)).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, softWrap = true, maxLines = 8) }
                                    } else if (col == ReportGenerator.ReportColumn.RECEIVER && activeReceiverCols.isNotEmpty()) {
                                        activeReceiverCols.forEach { name -> Text(name, modifier = Modifier.width(adaptiveReportColumnWidth(name)).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, softWrap = true, maxLines = 8) }
                                    } else if (col == ReportGenerator.ReportColumn.DISBURSER && activeDisburserCols.isNotEmpty()) {
                                        activeDisburserCols.forEach { name -> Text(name, modifier = Modifier.width(adaptiveReportColumnWidth(name)).padding(horizontal = 4.dp), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer, softWrap = true, maxLines = 8) }
                                    } else {
                                        val colWidth = previewColumnWidths[col] ?: 100.dp
                                        Text(
                                            text = col.titleAr,
                                            modifier = Modifier.width(colWidth).padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            softWrap = true,
                                            maxLines = 8
                                        )
                                    }
                                }

                                if (activeEntityCols.isNotEmpty() && activeCols.none { it == ReportGenerator.ReportColumn.BENEFICIARY }) {
                                    activeEntityCols.forEach { entName ->
                                        Text(
                                            text = entName,
                                            modifier = Modifier.width(adaptiveReportColumnWidth(entName)).padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            softWrap = true,
                                            maxLines = 8
                                        )
                                    }
                                }
                            }

                            // Data Rows with text wrapping enabled
                            reportData.forEachIndexed { index, rowItem ->
                                val rowBg = if (index % 2 == 1) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surface

                                Row(
                                    modifier = Modifier
                                        .background(rowBg)
                                        .padding(vertical = 8.dp, horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    activeCols.forEach { col ->
                                        if (col == ReportGenerator.ReportColumn.BENEFICIARY && activeEntityCols.isNotEmpty()) {
                                            activeEntityCols.forEach { entName ->
                                                val q = rowItem.entityQuantities[entName] ?: if (rowItem.beneficiaryName == entName) rowItem.quantity else null
                                                val displayVal = if (q != null && q > 0) "%.0f".format(q) else "-"
                                                Text(
                                                    text = displayVal,
                                                    modifier = Modifier.width(adaptiveReportColumnWidth(entName)).padding(horizontal = 4.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    softWrap = true,
                                                    maxLines = 8
                                                )
                                            }
                                        } else if (col == ReportGenerator.ReportColumn.WAREHOUSE && activeWarehouseCols.isNotEmpty()) {
                                            activeWarehouseCols.forEach { name -> Text(rowItem.warehouseQuantities[name]?.let { "%.0f".format(it) } ?: "-", modifier = Modifier.width(adaptiveReportColumnWidth(name)).padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, softWrap = true) }
                                        } else if (col == ReportGenerator.ReportColumn.RECEIVER && activeReceiverCols.isNotEmpty()) {
                                            activeReceiverCols.forEach { name -> Text(rowItem.receiverQuantities[name]?.let { "%.0f".format(it) } ?: "-", modifier = Modifier.width(adaptiveReportColumnWidth(name)).padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, softWrap = true) }
                                        } else if (col == ReportGenerator.ReportColumn.DISBURSER && activeDisburserCols.isNotEmpty()) {
                                            activeDisburserCols.forEach { name -> Text(rowItem.disburserQuantities[name]?.let { "%.0f".format(it) } ?: "-", modifier = Modifier.width(adaptiveReportColumnWidth(name)).padding(horizontal = 4.dp), style = MaterialTheme.typography.bodySmall, softWrap = true) }
                                        } else {
                                            val colWidth = previewColumnWidths[col] ?: 100.dp
                                            val text = when (col) {
                                                ReportGenerator.ReportColumn.SERIAL -> "${index + 1}"
                                                ReportGenerator.ReportColumn.VOUCHER_NUMBER -> rowItem.voucherNumber
                                                ReportGenerator.ReportColumn.ITEM_NAME -> rowItem.itemName
                                                ReportGenerator.ReportColumn.ITEM_UNIT -> rowItem.itemUnit
                                                ReportGenerator.ReportColumn.STATUS -> rowItem.status
                                                ReportGenerator.ReportColumn.SUB_UNIT -> rowItem.subUnit
                                                ReportGenerator.ReportColumn.WAREHOUSE -> rowItem.warehouseName
                                                ReportGenerator.ReportColumn.BENEFICIARY -> rowItem.beneficiaryName
                                                ReportGenerator.ReportColumn.RECEIVER -> rowItem.receiverName
                                                ReportGenerator.ReportColumn.DISBURSER -> rowItem.disburserName
                                                ReportGenerator.ReportColumn.VOUCHER_DATE -> rowItem.voucherDate
                                                ReportGenerator.ReportColumn.CURRENT_STOCK -> "%.0f".format(rowItem.quantity)
                                                ReportGenerator.ReportColumn.TOTAL_QUANTITY -> "%.0f".format(rowItem.totalQuantity)
                                                ReportGenerator.ReportColumn.MIN_STOCK -> "%.0f".format(rowItem.minStockLevel)
                                                ReportGenerator.ReportColumn.PURCHASE_PRICE -> "%.2f $".format(rowItem.unitPrice)
                                                ReportGenerator.ReportColumn.TOTAL_VALUE -> "%.2f $".format(rowItem.totalValue)
                                            }
                                            Text(
                                                text = text,
                                                modifier = Modifier.width(colWidth).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = if (col == ReportGenerator.ReportColumn.ITEM_NAME) FontWeight.Bold else FontWeight.Normal,
                                                softWrap = true,
                                                maxLines = 8
                                            )
                                        }
                                    }

                                    if (activeEntityCols.isNotEmpty() && activeCols.none { it == ReportGenerator.ReportColumn.BENEFICIARY }) {
                                        activeEntityCols.forEach { entName ->
                                            val q = rowItem.entityQuantities[entName] ?: if (rowItem.beneficiaryName == entName) rowItem.quantity else null
                                            val displayVal = if (q != null && q > 0) "%.0f".format(q) else "-"
                                            Text(
                                                text = displayVal,
                                                modifier = Modifier.width(adaptiveReportColumnWidth(entName)).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                softWrap = true,
                                                maxLines = 8
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Bottom Footer: Developer Credit on bottom-left & User Name on right
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "المستخدم: $appUserName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "طور بواسطة عيسى عبدربه 771438740",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
    }

    // Modern From Date Picker Dialog
    if (showFromDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = fromDateMillis ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showFromDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            fromDateMillis = it
                        }
                        showFromDatePicker = false
                    }
                ) {
                    Text("تأكيد")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    fromDateMillis = null
                    showFromDatePicker = false
                }) {
                    Text("مسح / إلغاء")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Modern To Date Picker Dialog
    if (showToDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = toDateMillis ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showToDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            toDateMillis = it
                        }
                        showToDatePicker = false
                    }
                ) {
                    Text("تأكيد")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    toDateMillis = null
                    showToDatePicker = false
                }) {
                    Text("مسح / إلغاء")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Warehouse Multi-Selection Dialog
    if (showWarehouseFilterDialog) {
        AlertDialog(
            onDismissRequest = { showWarehouseFilterDialog = false },
            title = { Text("اختيار مخزن واحد أو أكثر", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    warehouses.forEach { wh ->
                        val isChecked = selectedWarehouseIds.contains(wh.id)
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (isChecked) selectedWarehouseIds.remove(wh.id)
                                else selectedWarehouseIds.add(wh.id)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = {
                                    if (it) selectedWarehouseIds.add(wh.id)
                                    else selectedWarehouseIds.remove(wh.id)
                                }
                            )
                            Text(text = wh.name, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showWarehouseFilterDialog = false }) { Text("تأكيد") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    selectedWarehouseIds.clear()
                    showWarehouseFilterDialog = false
                }) { Text("تحديد كافة المخازن") }
            }
        )
    }

    // Suppliers Multi-Selection Dialog
    if (showSupplierFilterDialog) {
        MultiSelectDialog(
            title = "اختيار مورد أو أكثر",
            allItems = suppliers.map { it.name }.sorted(),
            selectedItems = selectedSuppliers,
            onDismiss = { showSupplierFilterDialog = false }
        )
    }

    // Receivers Multi-Selection Dialog
    if (showReceiverFilterDialog) {
        MultiSelectDialog(
            title = "اختيار اسم مستلم أو أكثر",
            allItems = parties.map { it.name }.sorted(),
            selectedItems = selectedReceivers,
            onDismiss = { showReceiverFilterDialog = false }
        )
    }

    // Disbursers Multi-Selection Dialog
    if (showDisburserFilterDialog) {
        MultiSelectDialog(
            title = "اختيار القائم بالصرف أو أكثر",
            allItems = parties.map { it.name }.sorted(),
            selectedItems = selectedDisbursers,
            onDismiss = { showDisburserFilterDialog = false }
        )
    }

    // Beneficiaries Multi-Selection Dialog
    if (showBeneficiaryFilterDialog) {
        MultiSelectDialog(
            title = "اختيار جهة مستفيدة أو أكثر",
            allItems = beneficiaries.map { it.name }.sorted(),
            selectedItems = selectedBeneficiaryEntities,
            onDismiss = { showBeneficiaryFilterDialog = false }
        )
    }

    // Items Multi-Selection Dialog
    if (showItemFilterDialog) {
        AlertDialog(
            onDismissRequest = { showItemFilterDialog = false },
            title = { Text("اختيار صنف واحد أو أكثر للتقرير", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    allItems.forEach { itm ->
                        val isChecked = selectedItemIds.contains(itm.id)
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable {
                                if (isChecked) selectedItemIds.remove(itm.id)
                                else selectedItemIds.add(itm.id)
                            }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = isChecked,
                                onCheckedChange = {
                                    if (it) selectedItemIds.add(itm.id)
                                    else selectedItemIds.remove(itm.id)
                                }
                            )
                            Text(text = "${itm.name} (${itm.unit})", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showItemFilterDialog = false }) { Text("تأكيد") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    selectedItemIds.clear()
                    showItemFilterDialog = false
                }) { Text("تحديد الكل") }
            }
        )
    }
}

@Composable
private fun MultiSelectDialog(
    title: String,
    allItems: List<String>,
    selectedItems: androidx.compose.runtime.snapshots.SnapshotStateList<String>,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                allItems.forEach { itemText ->
                    val isChecked = selectedItems.contains(itemText)
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable {
                            if (isChecked) selectedItems.remove(itemText)
                            else selectedItems.add(itemText)
                        }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = {
                                if (it) selectedItems.add(itemText)
                                else selectedItems.remove(itemText)
                            }
                        )
                        Text(text = itemText, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("تأكيد") }
        },
        dismissButton = {
            OutlinedButton(onClick = {
                selectedItems.clear()
                onDismiss()
            }) { Text("إلغاء التحديد") }
        }
    )
}


private fun formatColumnLayout(value: String, mode: String): String {
    if (mode != "VERTICAL") return value
    return value.split("،", ",", " | ", "\n").map { it.trim() }.filter { it.isNotBlank() }.joinToString("\n")
}
