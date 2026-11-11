package com.example.ui.screens

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.VoucherWithItems
import com.example.data.model.Item
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.repository.StockSnapshot
import com.example.data.model.WarehouseItemStock
import com.example.ui.components.DeviceRoleStatusChip
import com.example.ui.components.SimpleConfirmDialog
import com.example.ui.components.SubDeviceRestrictedDialog
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import com.example.util.ReportGenerator
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WarehouseDetailsScreen(
    warehouse: Warehouse,
    viewModel: InventoryViewModel,
    snapshot: StockSnapshot?,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    BackHandler { onBack() }

    val appUserName by viewModel.userName.collectAsState()
    val orgName by viewModel.orgName.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var sortByStock by remember { mutableStateOf(false) } // false = alphabetical, true = by stock descending

    var showAddItemDialog by remember { mutableStateOf(false) }
    var showDisbursementDialog by remember { mutableStateOf(false) }
    var showWarehouseReportDialog by remember { mutableStateOf(false) }

    // Edit item stock inside warehouse
    var itemToAdjust by remember { mutableStateOf<WarehouseItemStock?>(null) }
    var itemToDeleteFromWarehouse by remember { mutableStateOf<WarehouseItemStock?>(null) }
    var showStep2DeleteConfirm by remember { mutableStateOf(false) }

    // Sub-screen for unit repetition & serial number tracking
    var selectedItemForSerials by remember { mutableStateOf<WarehouseItemStock?>(null) }
    var preselectedLineItemsForDisbursement by remember { mutableStateOf<List<VoucherItem>>(emptyList()) }

    val rawItemsInWarehouse = snapshot?.warehouseItemStocks?.filter { it.warehouseId == warehouse.id } ?: emptyList()
    val allCatalogItems = snapshot?.items ?: emptyList()
    val allVouchers = snapshot?.vouchers ?: emptyList()

    val availableCatalogItems = remember(allCatalogItems, rawItemsInWarehouse) {
        allCatalogItems.filter { catItem -> rawItemsInWarehouse.none { it.itemId == catItem.id } }
    }
    val beneficiaries by viewModel.beneficiaries.collectAsState()
    val suppliers by viewModel.suppliers.collectAsState()
    val parties by viewModel.parties.collectAsState()

    val filteredItems = remember(rawItemsInWarehouse, searchQuery, sortByStock) {
        val list = if (searchQuery.isBlank()) rawItemsInWarehouse
        else rawItemsInWarehouse.filter {
            it.itemName.contains(searchQuery, ignoreCase = true)
        }

        if (sortByStock) {
            list.sortedByDescending { it.currentStock }
        } else {
            list.sortedBy { it.itemName.lowercase(Locale.getDefault()) }
        }
    }

    // If unit serial tracking screen is active, display it full screen
    if (selectedItemForSerials != null) {
        val currentTargetItem = selectedItemForSerials!!
        ItemUnitSerialTrackingScreen(
            warehouse = warehouse,
            itemStock = currentTargetItem,
            viewModel = viewModel,
            onBack = { selectedItemForSerials = null },
            onDisburseSelected = { selectedRecords ->
                preselectedLineItemsForDisbursement = selectedRecords.map { rec ->
                    VoucherItem(
                        voucherId = 0L,
                        itemId = currentTargetItem.itemId,
                        itemName = currentTargetItem.itemName,
                        itemUnit = currentTargetItem.itemUnit,
                        packageUnit = currentTargetItem.itemUnit,
                        packageQuantity = 0.0,
                        singleQuantity = 1.0,
                        unitsPerPackage = 1.0,
                        quantity = 1.0,
                        unitPrice = currentTargetItem.purchasePrice,
                        serialNumber = rec.serialNumber.ifBlank { "SN-${rec.unitIndex}" },
                        itemStatus = rec.status,
                        itemReceiverName = "",
                        notes = rec.notes
                    )
                }
                selectedItemForSerials = null
                showDisbursementDialog = true
            }
        )
        return
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = warehouse.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "أمين المخزن: ${warehouse.managerName.ifBlank { "غير محدد" }}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
        ) {
            // Action Buttons Row: Add Item, Quick Disbursement, and Warehouse Report Export
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Button(
                    onClick = { showAddItemDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("warehouse_add_item_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("إضافة صنف", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        preselectedLineItemsForDisbursement = emptyList()
                        showDisbursementDialog = true
                    },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("warehouse_disbursement_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("صرف مخزن", fontSize = 11.sp)
                }

                Button(
                    onClick = { showWarehouseReportDialog = true },
                    modifier = Modifier
                        .weight(1.1f)
                        .testTag("warehouse_report_export_btn"),
                    colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تقرير المخزن", fontSize = 11.sp)
                }
            }

            // Search Bar & Sort Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("بحث عن صنف داخل هذا المخزن...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("warehouse_items_search_input"),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                FilterChip(
                    selected = sortByStock,
                    onClick = { sortByStock = !sortByStock },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = {
                        Text(if (sortByStock) "حسب المخزون" else "أبجدياً (أ-ي)", fontSize = 11.sp)
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            if (filteredItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            imageVector = Icons.Default.Inventory2,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) "لا توجد أصناف في هذا المخزن بعد. اضغط \"إضافة صنف\"."
                            else "لا توجد نتائج تطابق بحثك",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredItems, key = { it.itemId }) { itemStock ->
                        WarehouseItemCard(
                            itemStock = itemStock,
                            warehouseId = warehouse.id,
                            allVouchers = allVouchers,
                            onEdit = { itemToAdjust = itemStock },
                            onDelete = {
                                itemToDeleteFromWarehouse = itemStock
                                showStep2DeleteConfirm = false
                            },
                            onSerialTracking = { selectedItemForSerials = itemStock }
                        )
                    }
                }
            }
        }
    }

    // Add Item Directly To Warehouse Dialog (showing only items not already in warehouse)
    if (showAddItemDialog) {
        AddItemToWarehouseDialog(
            catalogItems = availableCatalogItems,
            onDismiss = { showAddItemDialog = false },
            onAdd = { itemId, quantity ->
                val selectedCatItem = allCatalogItems.find { it.id == itemId }
                val defaultMin = selectedCatItem?.minStockLevel ?: 5.0
                viewModel.addItemDirectlyToWarehouse(warehouse.id, itemId, quantity, defaultMin) {
                    showAddItemDialog = false
                }
            }
        )
    }

    // Adjust Item Stock Dialog (Reason Note Mandatory ONLY if actual stock quantity is changed!)
    if (itemToAdjust != null) {
        AdjustStockQuantityDialog(
            itemStock = itemToAdjust!!,
            onDismiss = { itemToAdjust = null },
            onConfirm = { newQty, newMinStock, reasonNote ->
                viewModel.adjustWarehouseStock(
                    warehouseId = warehouse.id,
                    itemId = itemToAdjust!!.itemId,
                    newQuantity = newQty,
                    reasonNote = reasonNote
                ) {
                    viewModel.updateWarehouseItemMinStock(
                        warehouseId = warehouse.id,
                        itemId = itemToAdjust!!.itemId,
                        customMinStock = newMinStock
                    ) {
                        itemToAdjust = null
                    }
                }
            }
        )
    }

    // Delete item permanently from warehouse with 2-step confirmation if movements exist
    if (itemToDeleteFromWarehouse != null) {
        val targetItem = itemToDeleteFromWarehouse!!
        val hasTransactions = remember(allVouchers, warehouse.id, targetItem.itemId) {
            allVouchers.any { vWithItems ->
                val v = vWithItems.voucher
                (v.sourceWarehouseId == warehouse.id || v.destinationWarehouseId == warehouse.id) &&
                    vWithItems.items.any { it.itemId == targetItem.itemId }
            }
        }

        if (hasTransactions) {
            if (!showStep2DeleteConfirm) {
                AlertDialog(
                    onDismissRequest = {
                        itemToDeleteFromWarehouse = null
                        showStep2DeleteConfirm = false
                    },
                    title = {
                        Text(
                            "تنبيه: توجد حركات مخزنية سابقة على هذا الصنف",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = WarningOrange
                        )
                    },
                    text = {
                        Text(
                            "صنف \"${targetItem.itemName}\" قد تم تسجيل حركات توريد أو صرف سابقة عليه في هذا المخزن.\n\nنظراً لوجود حركات سابقة، يتطلب حذف الصنف تأكيداً مزدوجاً لضمان سلامة العمليات.\n\nهل ترغب بالمتابعة إلى التأكيد النهائي لحذف الصنف من هذا المخزن؟"
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = { showStep2DeleteConfirm = true },
                            colors = ButtonDefaults.buttonColors(containerColor = WarningOrange)
                        ) {
                            Text("متابعة الحذف (خطوة 1 من 2)")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            itemToDeleteFromWarehouse = null
                            showStep2DeleteConfirm = false
                        }) {
                            Text("إلغاء")
                        }
                    }
                )
            } else {
                AlertDialog(
                    onDismissRequest = {
                        itemToDeleteFromWarehouse = null
                        showStep2DeleteConfirm = false
                    },
                    title = {
                        Text(
                            "تأكيد نهائي صارم (خطوة 2 من 2)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = DangerRed
                        )
                    },
                    text = {
                        Text(
                            "تحذير نهائي: هل أنت متأكد تماماً من حذف صنف \"${targetItem.itemName}\" نهائياً من مخزن \"${warehouse.name}\"؟\n\nلن يمكن التراجع عن هذه العملية بعد التأكيد."
                        )
                    },
                    confirmButton = {
                        Button(
                            onClick = {
                                viewModel.removeItemFromWarehouse(
                                    warehouseId = warehouse.id,
                                    itemId = targetItem.itemId
                                ) {
                                    itemToDeleteFromWarehouse = null
                                    showStep2DeleteConfirm = false
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                        ) {
                            Text("نعم، احذف الصنف الآن نهائياً")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(onClick = {
                            itemToDeleteFromWarehouse = null
                            showStep2DeleteConfirm = false
                        }) {
                            Text("تراجع")
                        }
                    }
                )
            }
        } else {
            SimpleConfirmDialog(
                title = "تأكيد حذف الصنف من المخزن",
                message = "هل أنت متأكد من حذف صنف \"${targetItem.itemName}\" نهائياً من مخزن \"${warehouse.name}\"؟",
                confirmButtonText = "نعم، احذف الصنف",
                isDestructive = true,
                onConfirm = {
                    viewModel.removeItemFromWarehouse(
                        warehouseId = warehouse.id,
                        itemId = targetItem.itemId
                    ) {
                        itemToDeleteFromWarehouse = null
                        showStep2DeleteConfirm = false
                    }
                },
                onDismiss = {
                    itemToDeleteFromWarehouse = null
                    showStep2DeleteConfirm = false
                }
            )
        }
    }

    // Disbursement Voucher Dialog with Fixed Warehouse
    if (showDisbursementDialog) {
        var autoVoucherNumber by remember { mutableStateOf("") }
        LaunchedEffect(Unit) {
            autoVoucherNumber = viewModel.getNextVoucherNumber(VoucherType.DISBURSEMENT)
        }

        if (autoVoucherNumber.isNotBlank()) {
            VoucherFormDialog(
                voucherType = VoucherType.DISBURSEMENT,
                initialVoucherWithItems = null,
                preselectedLineItems = if (preselectedLineItemsForDisbursement.isNotEmpty()) preselectedLineItemsForDisbursement else null,
                fixedSourceWarehouseId = warehouse.id,
                fixedVoucherNumber = autoVoucherNumber,
                warehouses = listOf(warehouse),
                items = allCatalogItems,
                beneficiaries = beneficiaries,
                suppliers = suppliers,
                parties = parties,
                stockMap = snapshot?.stockMap ?: emptyMap(),
                onAddNewBeneficiary = { ben, onDone ->
                    viewModel.saveBeneficiary(ben, onDone)
                },
                onAddNewSupplier = { supp, onDone ->
                    viewModel.saveSupplier(supp, onDone)
                },
                onAddNewParty = { pty, onDone ->
                    viewModel.saveParty(pty, onDone)
                },
                onUpdateSupplier = { oldName, newName -> viewModel.updateSupplierName(oldName, newName) },
                onDeleteSupplier = { supp, onResult -> viewModel.deleteSupplier(supp, onResult) },
                checkSupplierHasTransactions = { name -> viewModel.checkSupplierHasTransactions(name) },
                onUpdateParty = { oldName, newName -> viewModel.updatePartyName(oldName, newName) },
                onDeleteParty = { pty, onResult -> viewModel.deleteParty(pty, onResult) },
                checkPartyHasTransactions = { name -> viewModel.checkPartyHasTransactions(name) },
                onDismiss = {
                    preselectedLineItemsForDisbursement = emptyList()
                    showDisbursementDialog = false
                },
                onSave = { voucher, lineItems ->
                    viewModel.saveVoucher(voucher, lineItems) {
                        preselectedLineItemsForDisbursement = emptyList()
                        showDisbursementDialog = false
                    }
                }
            )
        }
    }

    // Warehouse Report Export Dialog (PDF/Excel, Summary vs Detailed, Item/Status filters, Column selection)
    if (showWarehouseReportDialog) {
        WarehouseReportExportDialog(
            warehouse = warehouse,
            itemsInWarehouse = rawItemsInWarehouse,
            appUserName = appUserName,
            orgName = orgName,
            viewModel = viewModel,
            onDismiss = { showWarehouseReportDialog = false }
        )
    }
}

/**
 * Warehouse Item Card with expandable last 3 operations, edit button, unit repetition button, and delete button
 */
@Composable
private fun WarehouseItemCard(
    itemStock: WarehouseItemStock,
    warehouseId: Long,
    allVouchers: List<VoucherWithItems>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onSerialTracking: () -> Unit
) {
    var isExpanded by remember { mutableStateOf(false) }

    val isOutOfStock = itemStock.isOutOfStock
    val isLowStock = itemStock.isLowStock

    val badgeColor = when {
        isOutOfStock -> DangerRed
        isLowStock -> WarningOrange
        else -> SuccessGreen
    }

    val badgeText = when {
        isOutOfStock -> "نفدت الكمية"
        isLowStock -> "بحاجة طلب"
        else -> "متوفر"
    }

    // Retrieve last 3 operations for this item in this warehouse
    val last3Operations = remember(allVouchers, warehouseId, itemStock.itemId) {
        val matches = mutableListOf<Triple<String, Double, VoucherWithItems>>()
        for (vWithItems in allVouchers) {
            val v = vWithItems.voucher
            val isRelWarehouse = v.sourceWarehouseId == warehouseId || v.destinationWarehouseId == warehouseId
            if (isRelWarehouse) {
                val line = vWithItems.items.find { it.itemId == itemStock.itemId }
                if (line != null) {
                    val opType = when (v.voucherType) {
                        VoucherType.INWARD -> "توريد وارد"
                        VoucherType.TRANSFER -> if (v.destinationWarehouseId == warehouseId) "تحويل مستلم (+)" else "تحويل صادر (-)"
                        VoucherType.DISBURSEMENT -> "صرف لجهة مستفيدة"
                    }
                    matches.add(Triple(opType, line.quantity, vWithItems))
                }
            }
        }
        matches.sortedByDescending { it.third.voucher.date }.take(3)
    }

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .testTag("warehouse_item_card_${itemStock.itemId}"),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = badgeColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (isOutOfStock || isLowStock) Icons.Default.Warning else Icons.Default.Inventory2,
                                contentDescription = null,
                                tint = badgeColor,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Column {
                        Text(
                            text = itemStock.itemName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = badgeColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = badgeText,
                                    color = badgeColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (itemStock.unitsPerPackage > 1.0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${itemStock.unitsPerPackage.toInt()} ${itemStock.itemUnit} لكل ${itemStock.packageUnit})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Button to open item serial / unit breakdown
                    IconButton(onClick = onSerialTracking) {
                        Icon(
                            imageVector = Icons.Default.FormatListNumbered,
                            contentDescription = "تفصيل وترقيم الوحدات",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "تعديل الكمية وحد الطلب", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف نهائي من المخزن", tint = DangerRed)
                    }
                    IconButton(onClick = { isExpanded = !isExpanded }) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "عرض آخر العمليات",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stock Details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f), RoundedCornerShape(8.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("الرصيد الفعلي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val formattedQty = if (itemStock.unitsPerPackage > 1.0 && itemStock.currentStock >= itemStock.unitsPerPackage) {
                        val boxes = (itemStock.currentStock / itemStock.unitsPerPackage).toInt()
                        val remPieces = (itemStock.currentStock % itemStock.unitsPerPackage).toInt()
                        if (remPieces > 0) "$boxes ${itemStock.packageUnit} + $remPieces ${itemStock.itemUnit}"
                        else "$boxes ${itemStock.packageUnit} (${itemStock.currentStock.toInt()} ${itemStock.itemUnit})"
                    } else {
                        "${itemStock.currentStock.toInt()} ${itemStock.itemUnit}"
                    }
                    Text(formattedQty, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = badgeColor)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("حد إعادة الطلب", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${itemStock.minStockLevel.toInt()} ${itemStock.itemUnit}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("سعر الوحدة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.2f $".format(itemStock.purchasePrice), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            // Expandable Last 3 Operations Section
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 10.dp)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.History, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "تفاصيل آخر 3 عمليات لهذا الصنف في هذا المخزن:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (last3Operations.isEmpty()) {
                        Text(
                            text = "لا توجد عمليات سابقة مسجلة لهذا الصنف في هذا المخزن.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        last3Operations.forEachIndexed { idx, op ->
                            val v = op.third.voucher
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = "${idx + 1}. ${op.first} (${v.voucherNumber})",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    val partyText = when (v.voucherType) {
                                        VoucherType.INWARD -> "المورد: ${v.supplierName.ifBlank { "غير محدد" }}"
                                        VoucherType.DISBURSEMENT -> "الجهة: ${v.beneficiaryName.ifBlank { "غير محددة" }}"
                                        VoucherType.TRANSFER -> "القائم بالتحويل: ${v.disburserName.ifBlank { "أمين المخزن" }}"
                                    }
                                    Text(
                                        text = "$partyText | ${dateFormat.format(Date(v.date))}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(
                                    text = "${op.second.toInt()} ${itemStock.itemUnit}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (v.voucherType == VoucherType.INWARD) SuccessGreen else WarningOrange
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddItemToWarehouseDialog(
    catalogItems: List<Item>,
    onDismiss: () -> Unit,
    onAdd: (Long, Double) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    var selectedItem by remember { mutableStateOf(catalogItems.firstOrNull()) }
    var quantityText by remember { mutableStateOf("1") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة صنف داخل المخزن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            if (catalogItems.isEmpty()) {
                Text(
                    text = "جميع أصناف الدليل مضافة بالفعل في هذا المخزن!",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ExposedDropdownMenuBox(
                        expanded = expanded,
                        onExpandedChange = { expanded = !expanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedItem?.name ?: "اختر الصنف من القائمة",
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("الصنف الغير مضاف *") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = expanded,
                            onDismissRequest = { expanded = false }
                        ) {
                            for (itm in catalogItems) {
                                DropdownMenuItem(
                                    text = { Text("${itm.name} (${itm.unit})") },
                                    onClick = {
                                        selectedItem = itm
                                        expanded = false
                                    }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = quantityText,
                        onValueChange = {
                            quantityText = it.filter { char -> char.isDigit() }
                            error = ""
                        },
                        label = { Text("الكمية (${selectedItem?.unit ?: "حبة"}) *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (error.isNotBlank()) {
                        Text(error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            if (catalogItems.isNotEmpty()) {
                Button(
                    onClick = {
                        val itm = selectedItem
                        if (itm == null) {
                            error = "يرجى اختيار صنف"
                            return@Button
                        }
                        val qty = quantityText.toDoubleOrNull() ?: 0.0
                        if (qty <= 0) {
                            error = "يرجى إدخال كمية صحيحة أكبر من صفر"
                            return@Button
                        }
                        onAdd(itm.id, qty)
                    }
                ) {
                    Text("إضافة للمخزن")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

/**
 * Adjust Stock Dialog:
 * Reason Note is mandatory ONLY if actual stock quantity is modified!
 * If only minStock is changed, reason note is optional!
 */
@Composable
private fun AdjustStockQuantityDialog(
    itemStock: WarehouseItemStock,
    onDismiss: () -> Unit,
    onConfirm: (Double, Double, String) -> Unit
) {
    var newQtyText by remember { mutableStateOf(itemStock.currentStock.toInt().toString()) }
    var customMinStockText by remember { mutableStateOf(itemStock.minStockLevel.toInt().toString()) }
    var reasonNote by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    val currentQty = itemStock.currentStock.toInt()
    val enteredQty = newQtyText.toIntOrNull() ?: currentQty
    val isStockChanged = enteredQty != currentQty

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "تعديل بيانات ورصيد: ${itemStock.itemName}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "الرصيد الفعلي الحالي: ${itemStock.currentStock.toInt()} ${itemStock.itemUnit}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = newQtyText,
                    onValueChange = {
                        newQtyText = it.filter { char -> char.isDigit() }
                        error = ""
                    },
                    label = { Text("الكمية الفعلية الجديدة *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = customMinStockText,
                    onValueChange = {
                        customMinStockText = it.filter { char -> char.isDigit() }
                        error = ""
                    },
                    label = { Text("حد إعادة الطلب الخاص بالمخزن *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = reasonNote,
                    onValueChange = {
                        reasonNote = it
                        error = ""
                    },
                    label = { Text(if (isStockChanged) "سبب تعديل المخزون (إجباري) *" else "سبب التعديل / ملاحظات (اختياري)") },
                    placeholder = { Text(if (isStockChanged) "مثال: تسوية جردية، تصحيح إدخال سابق، تلف..." else "ملاحظات إضافية...") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 3,
                    isError = error.isNotBlank()
                )

                if (error.isNotBlank()) {
                    Text(error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val qty = newQtyText.toDoubleOrNull()
                    if (qty == null) {
                        error = "يرجى إدخال كمية صحيحة"
                        return@Button
                    }
                    val minStock = customMinStockText.toDoubleOrNull() ?: itemStock.minStockLevel
                    if (isStockChanged && reasonNote.isBlank()) {
                        error = "إضافة سبب تعديل المخزون إجباري عند تعديل كمية الرصيد!"
                        return@Button
                    }
                    val finalReason = if (reasonNote.isBlank()) "تعديل حد إعادة الطلب" else reasonNote.trim()
                    onConfirm(qty, minStock, finalReason)
                }
            ) {
                Text("تأكيد التعديل")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

/**
 * Warehouse Report Export Dialog:
 * Supports:
 * - Summary Quantity report or Detailed serial/unit report
 * - Column selection & hiding
 * - Alphabetical ordering
 * - Multi-selection of items and statuses
 * - Direct preview, export PDF, export Excel
 */
/**
 * Warehouse Report Export Dialog:
 * Supports:
 * - Summary Quantity report or Detailed serial/unit report
 * - Eliminates warehouse column from report
 * - Custom columns & renaming: "رقم الصنف" after "اسم الصنف", "حالة الصنف", "الملاحظات"
 * - Multi-selection of items
 * - All saved statuses in status filter
 * - Live preview table right inside the modal
 * - Direct preview, export PDF, export Excel
 */
@Composable
private fun WarehouseReportExportDialog(
    warehouse: Warehouse,
    itemsInWarehouse: List<WarehouseItemStock>,
    appUserName: String,
    orgName: String,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    var reportMode by remember { mutableStateOf("SUMMARY") } // "SUMMARY" or "DETAILED"

    // Multi-selected item IDs
    val selectedItemIds = remember { mutableStateListOf<Long>() }

    // Multi-selected statuses for detailed mode
    val selectedStatuses = remember { mutableStateListOf<String>() }

    var allSerialsList by remember { mutableStateOf<List<ItemUnitSerialRecord>>(emptyList()) }

    LaunchedEffect(warehouse.id) {
        allSerialsList = viewModel.getAllSerialsForWarehouse(warehouse.id)
    }

    val allAvailableStatuses = remember(allSerialsList) {
        val base = listOf("موجود", "مفقود", "مدمر", "عاطل")
        val fromSerials = allSerialsList.map { it.status }
        (base + fromSerials).filter { it.isNotBlank() }.distinct()
    }

    // Columns selection for summary mode (Warehouse column eliminated)
    val showItemName = remember { mutableStateOf(true) }
    val showUnit = remember { mutableStateOf(true) }
    val showStock = remember { mutableStateOf(true) }
    val showMinStock = remember { mutableStateOf(true) }
    val showPrice = remember { mutableStateOf(true) }
    val showTotalValue = remember { mutableStateOf(true) }

    // Columns selection for detailed mode (Warehouse column eliminated)
    val showItemNameDetailed = remember { mutableStateOf(true) }
    val showSerialNumDetailed = remember { mutableStateOf(true) } // "رقم الصنف"
    val showStatusDetailed = remember { mutableStateOf(true) } // "حالة الصنف"
    val showNotesDetailed = remember { mutableStateOf(true) } // "الملاحظات"

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    val dateStr = dateFormat.format(Date())

    val sortedItems = remember(itemsInWarehouse) {
        itemsInWarehouse.sortedBy { it.itemName.lowercase(Locale.getDefault()) }
    }

    val activeItems = remember(sortedItems, selectedItemIds.toList()) {
        if (selectedItemIds.isEmpty()) sortedItems
        else sortedItems.filter { selectedItemIds.contains(it.itemId) }
    }

    // Prepare table rows based on mode and filters
    val rowsData = remember(reportMode, activeItems, selectedStatuses.toList(), allSerialsList) {
        if (reportMode == "SUMMARY") {
            activeItems.map { itm ->
                ReportGenerator.ReportRowData(
                    voucherNumber = "-",
                    warehouseName = warehouse.name,
                    itemName = itm.itemName,
                    itemUnit = itm.itemUnit,
                    beneficiaryName = "-",
                    receiverName = "-",
                    disburserName = "-",
                    voucherDate = dateStr,
                    quantity = itm.currentStock,
                    totalQuantity = itm.currentStock,
                    minStockLevel = itm.minStockLevel,
                    unitPrice = itm.purchasePrice,
                    totalValue = itm.currentStock * itm.purchasePrice
                )
            }
        } else {
            val rows = mutableListOf<ReportGenerator.ReportRowData>()
            val serialsMap = allSerialsList.groupBy { it.itemId }

            activeItems.forEach { itm ->
                val itemSerials = serialsMap[itm.itemId] ?: emptyList()
                val count = itm.currentStock.toInt().coerceAtLeast(1)

                for (i in 1..count) {
                    val rec = itemSerials.find { it.unitIndex == i }
                    val st = rec?.status ?: "موجود"
                    val sn = rec?.serialNumber?.ifBlank { "SN-$i" } ?: "SN-$i"
                    val nt = rec?.notes ?: ""

                    if (selectedStatuses.isEmpty() || selectedStatuses.contains(st)) {
                        rows.add(
                            ReportGenerator.ReportRowData(
                                voucherNumber = sn,
                                warehouseName = warehouse.name,
                                itemName = itm.itemName,
                                itemUnit = itm.itemUnit,
                                beneficiaryName = st, // maps to "حالة الصنف"
                                receiverName = nt, // maps to "الملاحظات"
                                disburserName = "-",
                                voucherDate = dateStr,
                                quantity = 1.0,
                                totalQuantity = 1.0,
                                minStockLevel = 0.0,
                                unitPrice = itm.purchasePrice,
                                totalValue = itm.purchasePrice
                            )
                        )
                    }
                }
            }
            rows
        }
    }

    val summarySelectedColumns = remember(
        showItemName.value, showUnit.value, showStock.value,
        showMinStock.value, showPrice.value, showTotalValue.value
    ) {
        mutableSetOf(ReportGenerator.ReportColumn.SERIAL).apply {
            if (showItemName.value) add(ReportGenerator.ReportColumn.ITEM_NAME)
            if (showUnit.value) add(ReportGenerator.ReportColumn.ITEM_UNIT)
            if (showStock.value) add(ReportGenerator.ReportColumn.TOTAL_QUANTITY)
            if (showMinStock.value) add(ReportGenerator.ReportColumn.MIN_STOCK)
            if (showPrice.value) add(ReportGenerator.ReportColumn.PURCHASE_PRICE)
            if (showTotalValue.value) add(ReportGenerator.ReportColumn.TOTAL_VALUE)
        }
    }

    val detailedSelectedColumns = remember(
        showItemNameDetailed.value, showSerialNumDetailed.value,
        showStatusDetailed.value, showNotesDetailed.value
    ) {
        mutableSetOf(ReportGenerator.ReportColumn.SERIAL).apply {
            if (showItemNameDetailed.value) add(ReportGenerator.ReportColumn.ITEM_NAME)
            if (showSerialNumDetailed.value) add(ReportGenerator.ReportColumn.VOUCHER_NUMBER)
            if (showStatusDetailed.value) add(ReportGenerator.ReportColumn.BENEFICIARY)
            if (showNotesDetailed.value) add(ReportGenerator.ReportColumn.RECEIVER)
        }
    }

    val detailedColumnOrder = listOf(
        ReportGenerator.ReportColumn.SERIAL,
        ReportGenerator.ReportColumn.ITEM_NAME,
        ReportGenerator.ReportColumn.VOUCHER_NUMBER,
        ReportGenerator.ReportColumn.BENEFICIARY,
        ReportGenerator.ReportColumn.RECEIVER
    )

    val detailedCustomTitles = mapOf(
        ReportGenerator.ReportColumn.VOUCHER_NUMBER to "رقم الصنف",
        ReportGenerator.ReportColumn.BENEFICIARY to "حالة الصنف",
        ReportGenerator.ReportColumn.RECEIVER to "الملاحظات"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "تصدير ومعاينة تقرير مخزن: ${warehouse.name}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Report Mode Tabs
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = reportMode == "SUMMARY",
                        onClick = { reportMode = "SUMMARY" },
                        label = { Text("تقرير ملخص بالكميات") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = reportMode == "DETAILED",
                        onClick = { reportMode = "DETAILED" },
                        label = { Text("تقرير تفصيلي أرقام") },
                        modifier = Modifier.weight(1f)
                    )
                }

                // Multi-Item Filter Section
                Text("تصفية الأصناف (اختياري - افتراضياً الكل):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val allItemsSelected = selectedItemIds.isEmpty()
                    FilterChip(
                        selected = allItemsSelected,
                        onClick = { selectedItemIds.clear() },
                        label = { Text("جميع الأصناف (${sortedItems.size})", fontSize = 11.sp) }
                    )
                    for (itm in sortedItems) {
                        val isSelected = selectedItemIds.contains(itm.itemId)
                        FilterChip(
                            selected = isSelected,
                            onClick = {
                                if (isSelected) selectedItemIds.remove(itm.itemId)
                                else selectedItemIds.add(itm.itemId)
                            },
                            label = { Text(itm.itemName, fontSize = 11.sp) }
                        )
                    }
                }

                // Columns Customization Checkboxes
                Text("تخصيص أعمدة التقرير:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                if (reportMode == "SUMMARY") {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showItemName.value, onCheckedChange = { showItemName.value = it })
                            Text("اسم الصنف", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showUnit.value, onCheckedChange = { showUnit.value = it })
                            Text("الوحدة", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showStock.value, onCheckedChange = { showStock.value = it })
                            Text("الرصيد المتوفر", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showMinStock.value, onCheckedChange = { showMinStock.value = it })
                            Text("حد الطلب", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showPrice.value, onCheckedChange = { showPrice.value = it })
                            Text("سعر الوحدة", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showTotalValue.value, onCheckedChange = { showTotalValue.value = it })
                            Text("إجمالي القيمة", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                } else {
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showItemNameDetailed.value, onCheckedChange = { showItemNameDetailed.value = it })
                            Text("اسم الصنف", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showSerialNumDetailed.value, onCheckedChange = { showSerialNumDetailed.value = it })
                            Text("رقم الصنف", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showStatusDetailed.value, onCheckedChange = { showStatusDetailed.value = it })
                            Text("حالة الصنف", style = MaterialTheme.typography.labelSmall)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showNotesDetailed.value, onCheckedChange = { showNotesDetailed.value = it })
                            Text("الملاحظات", style = MaterialTheme.typography.labelSmall)
                        }
                    }

                    // Status filter for detailed mode with ALL saved statuses
                    Text("تصفية بحسب الحالة (اختياري):", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        val allStSelected = selectedStatuses.isEmpty()
                        FilterChip(
                            selected = allStSelected,
                            onClick = { selectedStatuses.clear() },
                            label = { Text("جميع الحالات", fontSize = 10.sp) }
                        )
                        for (st in allAvailableStatuses) {
                            val isStSelected = selectedStatuses.contains(st)
                            FilterChip(
                                selected = isStSelected,
                                onClick = {
                                    if (isStSelected) selectedStatuses.remove(st)
                                    else selectedStatuses.add(st)
                                },
                                label = { Text(st, fontSize = 10.sp) }
                            )
                        }
                    }
                }

                // Live Preview Table inside modal dialog
                Text(
                    text = "معاينة الجدول (${rowsData.size} سجل):",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold
                )

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(6.dp)
                            .horizontalScroll(rememberScrollState())
                            .verticalScroll(rememberScrollState())
                    ) {
                        // Header Row
                        Row(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f), RoundedCornerShape(4.dp))
                                .padding(vertical = 4.dp, horizontal = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("م", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(26.dp))
                            Text("اسم الصنف", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(130.dp))
                            if (reportMode == "SUMMARY") {
                                if (showUnit.value) Text("الوحدة", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(50.dp))
                                if (showStock.value) Text("الرصيد", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(55.dp))
                                if (showMinStock.value) Text("حد الطلب", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(55.dp))
                                if (showPrice.value) Text("السعر", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(60.dp))
                                if (showTotalValue.value) Text("الإجمالي", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(65.dp))
                            } else {
                                if (showSerialNumDetailed.value) Text("رقم الصنف", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(80.dp))
                                if (showStatusDetailed.value) Text("حالة الصنف", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(70.dp))
                                if (showNotesDetailed.value) Text("الملاحظات", fontWeight = FontWeight.Bold, fontSize = 10.sp, modifier = Modifier.width(100.dp))
                            }
                        }

                        // Data Rows
                        rowsData.forEachIndexed { idx, r ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp, horizontal = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("${idx + 1}", fontSize = 9.sp, modifier = Modifier.width(26.dp))
                                Text(r.itemName, fontSize = 9.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(130.dp), softWrap = true)
                                if (reportMode == "SUMMARY") {
                                    if (showUnit.value) Text(r.itemUnit, fontSize = 9.sp, modifier = Modifier.width(50.dp))
                                    if (showStock.value) Text("%.0f".format(r.quantity), fontSize = 9.sp, modifier = Modifier.width(55.dp))
                                    if (showMinStock.value) Text("%.0f".format(r.minStockLevel), fontSize = 9.sp, modifier = Modifier.width(55.dp))
                                    if (showPrice.value) Text("%.1f".format(r.unitPrice), fontSize = 9.sp, modifier = Modifier.width(60.dp))
                                    if (showTotalValue.value) Text("%.1f".format(r.totalValue), fontSize = 9.sp, modifier = Modifier.width(65.dp))
                                } else {
                                    if (showSerialNumDetailed.value) Text(r.voucherNumber, fontSize = 9.sp, modifier = Modifier.width(80.dp))
                                    if (showStatusDetailed.value) Text(r.beneficiaryName, fontSize = 9.sp, modifier = Modifier.width(70.dp))
                                    if (showNotesDetailed.value) Text(r.receiverName.ifBlank { "-" }, fontSize = 9.sp, modifier = Modifier.width(100.dp))
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val title = if (reportMode == "SUMMARY") "تقرير ملخص مخزون: ${warehouse.name}" else "تقرير تفصيلي لوحدات مخزن: ${warehouse.name}"
            val activeCols = if (reportMode == "SUMMARY") summarySelectedColumns else detailedSelectedColumns
            val activeOrder = if (reportMode == "SUMMARY") null else detailedColumnOrder
            val customTitles = if (reportMode == "SUMMARY") emptyMap() else detailedCustomTitles

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        val pdf = ReportGenerator.generatePdf(
                            context = context,
                            title = title,
                            warehouseName = warehouse.name,
                            appUserName = appUserName,
                            orgName = orgName,
                            rows = rowsData,
                            selectedColumns = activeCols,
                            customColumnTitles = customTitles,
                            columnOrder = activeOrder
                        )
                        ReportGenerator.viewFile(context, pdf, "application/pdf")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("معاينة", fontSize = 11.sp) }

                Button(
                    onClick = {
                        val pdf = ReportGenerator.generatePdf(
                            context = context,
                            title = title,
                            warehouseName = warehouse.name,
                            appUserName = appUserName,
                            orgName = orgName,
                            rows = rowsData,
                            selectedColumns = activeCols,
                            customColumnTitles = customTitles,
                            columnOrder = activeOrder
                        )
                        ReportGenerator.shareFile(context, pdf, "application/pdf", "مشاركة تقرير المخزن PDF")
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) { Text("PDF", fontSize = 11.sp) }

                Button(
                    onClick = {
                        val excel = ReportGenerator.generateExcel(
                            context = context,
                            title = title,
                            warehouseName = warehouse.name,
                            appUserName = appUserName,
                            rows = rowsData,
                            selectedColumns = activeCols,
                            customColumnTitles = customTitles,
                            columnOrder = activeOrder
                        )
                        ReportGenerator.shareFile(context, excel, "text/csv", "مشاركة تقرير المخزن Excel")
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) { Text("Excel", fontSize = 11.sp) }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}
