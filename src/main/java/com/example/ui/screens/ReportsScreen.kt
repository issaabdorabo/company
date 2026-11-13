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
            ReportGenerator.ReportColumn.WAREHOUSE,
            ReportGenerator.ReportColumn.TOTAL_QUANTITY,
            ReportGenerator.ReportColumn.PURCHASE_PRICE,
            ReportGenerator.ReportColumn.TOTAL_VALUE
        )
    }

    val warehouses = snapshot?.warehouses ?: emptyList()
    val allItems = snapshot?.items ?: emptyList()
    val allVouchers = snapshot?.vouchers ?: emptyList()

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }

    // Build Report Data Rows as ReportRowData
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
        entitiesDisplayMode
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
                val rawStocks = snapshot?.warehouseItemStocks ?: emptyList()
                val filteredStocks = rawStocks.filter { itemStock ->
                    val matchesWarehouse = selectedWarehouseIds.isEmpty() || selectedWarehouseIds.contains(itemStock.warehouseId)
                    val matchesItem = selectedItemIds.isEmpty() || selectedItemIds.contains(itemStock.itemId)
                    val matchesCategoryState = when (selectedReportCategory) {
                        "OUT_OF_STOCK" -> itemStock.currentStock <= 0
                        "LOW_STOCK" -> itemStock.currentStock > 0 && itemStock.currentStock <= itemStock.minStockLevel
                        else -> true
                    }
                    matchesWarehouse && matchesItem && matchesCategoryState
                }

                for (stock in filteredStocks) {
                    rows.add(
                        ReportGenerator.ReportRowData(
                            voucherNumber = "-",
                            warehouseName = stock.warehouseName,
                            itemName = stock.itemName,
                            itemUnit = stock.itemUnit,
                            beneficiaryName = "-",
                            receiverName = "-",
                            disburserName = "-",
                            voucherDate = "-",
                            quantity = stock.currentStock,
                            totalQuantity = stock.currentStock,
                            minStockLevel = stock.minStockLevel,
                            unitPrice = stock.purchasePrice,
                            totalValue = stock.currentStock * stock.purchasePrice
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

                    for (itm in vWithItems.items) {
                        if (selectedItemIds.isEmpty() || selectedItemIds.contains(itm.itemId)) {
                            rows.add(
                                ReportGenerator.ReportRowData(
                                    voucherNumber = v.voucherNumber,
                                    warehouseName = wName,
                                    itemName = itm.itemName,
                                    itemUnit = itm.itemUnit,
                                    beneficiaryName = v.beneficiaryName.ifBlank { "-" },
                                    receiverName = v.receiverName.ifBlank { "-" },
                                    disburserName = v.disburserName.ifBlank { "-" },
                                    voucherDate = dateFormat.format(Date(v.date)),
                                    quantity = itm.quantity,
                                    totalQuantity = itm.quantity,
                                    minStockLevel = 0.0,
                                    unitPrice = itm.unitPrice,
                                    totalValue = itm.quantity * itm.unitPrice,
                                    entityQuantities = if (v.beneficiaryName.isNotBlank()) mapOf(v.beneficiaryName to itm.quantity) else emptyMap()
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
                            for (itm in vWithItems.items) {
                                if (selectedItemIds.isEmpty() || selectedItemIds.contains(itm.itemId)) {
                                    val direction = when (v.voucherType) {
                                        VoucherType.INWARD -> "وارد (+)"
                                        VoucherType.DISBURSEMENT -> "منصرف (-)"
                                        VoucherType.TRANSFER -> "تحويل"
                                    }
                                    rows.add(
                                        ReportGenerator.ReportRowData(
                                            voucherNumber = "${v.voucherNumber} ($direction)",
                                            warehouseName = wName,
                                            itemName = itm.itemName,
                                            itemUnit = itm.itemUnit,
                                            beneficiaryName = v.beneficiaryName.ifBlank { "-" },
                                            receiverName = v.receiverName.ifBlank { "-" },
                                            disburserName = v.disburserName.ifBlank { "-" },
                                            voucherDate = dateFormat.format(Date(v.date)),
                                            quantity = itm.quantity,
                                            totalQuantity = itm.quantity,
                                            minStockLevel = 0.0,
                                            unitPrice = itm.unitPrice,
                                            totalValue = itm.quantity * itm.unitPrice,
                                            entityQuantities = if (v.beneficiaryName.isNotBlank()) mapOf(v.beneficiaryName to itm.quantity) else emptyMap()
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        val finalRows = if ((selectedReportCategory == "DISBURSEMENT" || selectedReportCategory == "ITEM_FLOW") &&
            entitiesDisplayMode == "VERTICAL" &&
            selectedBeneficiaryEntities.size > 1
        ) {
            // Group duplicate items across entities, showing sum of quantities for each entity and total in totalQuantity
            rows.groupBy { it.itemName }.map { (itemName, itemRows) ->
                val first = itemRows.first()
                val mergedEntityQuantities = mutableMapOf<String, Double>()
                for (r in itemRows) {
                    for ((ent, q) in r.entityQuantities) {
                        mergedEntityQuantities[ent] = (mergedEntityQuantities[ent] ?: 0.0) + q
                    }
                }
                val totalQty = mergedEntityQuantities.values.sum()
                val totalVal = totalQty * first.unitPrice
                ReportGenerator.ReportRowData(
                    voucherNumber = if (itemRows.size == 1) first.voucherNumber else "مجمع (${itemRows.size})",
                    warehouseName = first.warehouseName,
                    itemName = itemName,
                    itemUnit = first.itemUnit,
                    beneficiaryName = "متعدد",
                    receiverName = "-",
                    disburserName = "-",
                    voucherDate = if (itemRows.size == 1) first.voucherDate else "متعدد",
                    quantity = totalQty,
                    totalQuantity = totalQty,
                    minStockLevel = first.minStockLevel,
                    unitPrice = first.unitPrice,
                    totalValue = totalVal,
                    entityQuantities = mergedEntityQuantities
                )
            }
        } else {
            rows
        }

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

        // 4. Multi-Entity Layout Mode Selector (Horizontal vs Vertical per-entity columns)
        if (selectedBeneficiaryEntities.size > 1) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f))
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = "طريقة عرض الجهات المستفيدة (${selectedBeneficiaryEntities.size} جهات مختارة):",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            FilterChip(
                                selected = entitiesDisplayMode == "HORIZONTAL",
                                onClick = { entitiesDisplayMode = "HORIZONTAL" },
                                label = { Text("أفقي (عمود موحد للجهات)") },
                                modifier = Modifier.weight(1f)
                            )
                            FilterChip(
                                selected = entitiesDisplayMode == "VERTICAL",
                                onClick = { entitiesDisplayMode = "VERTICAL" },
                                label = { Text("عمودي (أعمدة مخصصة لكل جهة)") },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
            }
        }

        // 5. Export & Share Action Buttons (Direct Preview, Share PDF, Share Excel)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text(text = dynamicReportTitle, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(10.dp))

                    val activeEntityCols = if (entitiesDisplayMode == "VERTICAL" && selectedBeneficiaryEntities.size > 1) {
                        selectedBeneficiaryEntities.toList()
                    } else emptyList()

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
                                    entityColumns = activeEntityCols
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
                                    entityColumns = activeEntityCols
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
                                    entityColumns = activeEntityCols
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
                    val activeEntityCols = if (entitiesDisplayMode == "VERTICAL" && selectedBeneficiaryEntities.size > 1) {
                        selectedBeneficiaryEntities.toList()
                    } else emptyList()

                    // Horizontal scrolling RTL preview with soft text wrapping
                    val horizontalScrollState = rememberScrollState()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(horizontalScrollState)
                            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                    ) {
                        Column {
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
                                                modifier = Modifier.width(130.dp).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                                softWrap = true,
                                                maxLines = 2
                                            )
                                        }
                                    } else {
                                        val colWidth = when (col) {
                                            ReportGenerator.ReportColumn.SERIAL -> 48.dp
                                            ReportGenerator.ReportColumn.VOUCHER_NUMBER -> 110.dp
                                            ReportGenerator.ReportColumn.ITEM_NAME -> 160.dp
                                            ReportGenerator.ReportColumn.ITEM_UNIT -> 65.dp
                                            ReportGenerator.ReportColumn.WAREHOUSE -> 130.dp
                                            ReportGenerator.ReportColumn.BENEFICIARY -> 130.dp
                                            ReportGenerator.ReportColumn.RECEIVER -> 110.dp
                                            ReportGenerator.ReportColumn.DISBURSER -> 110.dp
                                            ReportGenerator.ReportColumn.VOUCHER_DATE -> 95.dp
                                            ReportGenerator.ReportColumn.CURRENT_STOCK -> 80.dp
                                            ReportGenerator.ReportColumn.TOTAL_QUANTITY -> 95.dp
                                            ReportGenerator.ReportColumn.MIN_STOCK -> 75.dp
                                            ReportGenerator.ReportColumn.PURCHASE_PRICE -> 80.dp
                                            ReportGenerator.ReportColumn.TOTAL_VALUE -> 90.dp
                                        }
                                        Text(
                                            text = col.titleAr,
                                            modifier = Modifier.width(colWidth).padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            softWrap = true,
                                            maxLines = 2
                                        )
                                    }
                                }

                                if (activeEntityCols.isNotEmpty() && activeCols.none { it == ReportGenerator.ReportColumn.BENEFICIARY }) {
                                    activeEntityCols.forEach { entName ->
                                        Text(
                                            text = entName,
                                            modifier = Modifier.width(130.dp).padding(horizontal = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            softWrap = true,
                                            maxLines = 2
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
                                                    modifier = Modifier.width(130.dp).padding(horizontal = 4.dp),
                                                    style = MaterialTheme.typography.bodySmall,
                                                    softWrap = true,
                                                    maxLines = 4
                                                )
                                            }
                                        } else {
                                            val colWidth = when (col) {
                                                ReportGenerator.ReportColumn.SERIAL -> 48.dp
                                                ReportGenerator.ReportColumn.VOUCHER_NUMBER -> 110.dp
                                                ReportGenerator.ReportColumn.ITEM_NAME -> 160.dp
                                                ReportGenerator.ReportColumn.ITEM_UNIT -> 65.dp
                                                ReportGenerator.ReportColumn.WAREHOUSE -> 130.dp
                                                ReportGenerator.ReportColumn.BENEFICIARY -> 130.dp
                                                ReportGenerator.ReportColumn.RECEIVER -> 110.dp
                                                ReportGenerator.ReportColumn.DISBURSER -> 110.dp
                                                ReportGenerator.ReportColumn.VOUCHER_DATE -> 95.dp
                                                ReportGenerator.ReportColumn.CURRENT_STOCK -> 80.dp
                                                ReportGenerator.ReportColumn.TOTAL_QUANTITY -> 95.dp
                                                ReportGenerator.ReportColumn.MIN_STOCK -> 75.dp
                                                ReportGenerator.ReportColumn.PURCHASE_PRICE -> 80.dp
                                                ReportGenerator.ReportColumn.TOTAL_VALUE -> 90.dp
                                            }
                                            val text = when (col) {
                                                ReportGenerator.ReportColumn.SERIAL -> "${index + 1}"
                                                ReportGenerator.ReportColumn.VOUCHER_NUMBER -> rowItem.voucherNumber
                                                ReportGenerator.ReportColumn.ITEM_NAME -> rowItem.itemName
                                                ReportGenerator.ReportColumn.ITEM_UNIT -> rowItem.itemUnit
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
                                                maxLines = 4
                                            )
                                        }
                                    }

                                    if (activeEntityCols.isNotEmpty() && activeCols.none { it == ReportGenerator.ReportColumn.BENEFICIARY }) {
                                        activeEntityCols.forEach { entName ->
                                            val q = rowItem.entityQuantities[entName] ?: if (rowItem.beneficiaryName == entName) rowItem.quantity else null
                                            val displayVal = if (q != null && q > 0) "%.0f".format(q) else "-"
                                            Text(
                                                text = displayVal,
                                                modifier = Modifier.width(130.dp).padding(horizontal = 4.dp),
                                                style = MaterialTheme.typography.bodySmall,
                                                softWrap = true,
                                                maxLines = 4
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
