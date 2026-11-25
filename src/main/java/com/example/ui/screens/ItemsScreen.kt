package com.example.ui.screens

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatListNumbered
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.mutableIntStateOf
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
import com.example.data.model.Item
import com.example.data.model.ItemCategory
import com.example.data.model.ItemStockInfo
import com.example.data.model.ItemUnit
import com.example.data.model.SmallerUnit
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseItemStock
import com.example.data.repository.StockSnapshot
import com.example.ui.components.DoubleWarningConfirmDialog
import com.example.ui.components.SimpleConfirmDialog
import com.example.ui.components.SubDeviceRestrictedDialog
import com.example.ui.theme.DangerRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.DeviceAction
import com.example.ui.viewmodel.InventoryViewModel
import com.example.util.ReportGenerator
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

@Composable
fun ItemsScreen(
    viewModel: InventoryViewModel,
    snapshot: StockSnapshot?
) {
    val coroutineScope = rememberCoroutineScope()
    var searchQuery by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf<String?>(null) }
    var showEditDialog by remember { mutableStateOf(false) }
    var itemToEdit by remember { mutableStateOf<Item?>(null) }
    var itemHasTransactions by remember { mutableStateOf(false) }

    // Management Dialogs
    var showManageUnitsDialog by remember { mutableStateOf(false) }
    var showManageCategoriesDialog by remember { mutableStateOf(false) }

    // Serial Tracking Overlay State & Quick Disbursement from Serials
    var serialTrackingTarget by remember { mutableStateOf<Pair<Warehouse, WarehouseItemStock>?>(null) }
    var disbursementTargetWarehouse by remember { mutableStateOf<Warehouse?>(null) }
    var preselectedDisburseLines by remember { mutableStateOf<List<VoucherItem>>(emptyList()) }

    // Deletion states
    var itemToDelete by remember { mutableStateOf<Item?>(null) }
    var showSimpleDeleteDialog by remember { mutableStateOf(false) }
    var doubleConfirmStep by remember { mutableIntStateOf(0) }
    var restrictedActionName by remember { mutableStateOf<String?>(null) }

    val itemsList = snapshot?.itemStockInfos ?: emptyList()
    val existingCarTypes = remember(itemsList) {
        itemsList.mapNotNull { it.item.carType.trim().takeIf(String::isNotEmpty) }
            .distinct()
            .sorted()
    }
    val warehouses = snapshot?.warehouses ?: emptyList()
    val warehouseItemStocks = snapshot?.warehouseItemStocks ?: emptyList()
    val categoriesFromDb by viewModel.categories.collectAsState()
    val unitsFromDb by viewModel.units.collectAsState()
    val smallerUnitsFromDb by viewModel.smallerUnits.collectAsState()
    val beneficiaries by viewModel.beneficiaries.collectAsState()
    val suppliers by viewModel.suppliers.collectAsState()
    val parties by viewModel.parties.collectAsState()

    // Full-screen overlay when opening serial tracking from ItemCard dropdown
    serialTrackingTarget?.let { (warehouse, itemStock) ->
        ItemUnitSerialTrackingScreen(
            warehouse = warehouse,
            itemStock = itemStock,
            viewModel = viewModel,
            onBack = { serialTrackingTarget = null },
            onDisburseSelected = { selectedRecords ->
                preselectedDisburseLines = selectedRecords.map { rec ->
                    VoucherItem(
                        voucherId = 0L,
                        itemId = itemStock.itemId,
                        itemName = itemStock.itemName,
                        itemUnit = itemStock.itemUnit,
                        packageUnit = itemStock.itemUnit,
                        packageQuantity = 0.0,
                        singleQuantity = 1.0,
                        unitsPerPackage = 1.0,
                        quantity = 1.0,
                        unitPrice = itemStock.purchasePrice,
                        serialNumber = rec.serialNumber.trim(),
                        itemStatus = rec.status,
                        itemReceiverName = "",
                        notes = rec.notes,
                        sourceSerialRecordId = rec.id.takeIf { it > 0L },
                        sourceUnitIndex = rec.unitIndex
                    )
                }
                disbursementTargetWarehouse = warehouse
                serialTrackingTarget = null
            }
        )
        return
    }

    val categories = remember(itemsList, categoriesFromDb) {
        val names = (categoriesFromDb.map { it.name } + itemsList.map { it.item.category.ifBlank { "عام" } }).distinct()
        listOf("الكل") + names
    }

    val filteredItems = remember(itemsList, searchQuery, selectedCategory) {
        itemsList.filter { stockInfo ->
            val item = stockInfo.item
            val matchesSearch = searchQuery.isBlank() ||
                item.name.contains(searchQuery, ignoreCase = true) ||
                item.barcode.contains(searchQuery, ignoreCase = true) ||
                item.category.contains(searchQuery, ignoreCase = true) ||
                item.carType.contains(searchQuery, ignoreCase = true) ||
                item.carName.contains(searchQuery, ignoreCase = true)

            val matchesCategory = selectedCategory == null ||
                selectedCategory == "الكل" ||
                item.category.equals(selectedCategory, ignoreCase = true)

            matchesSearch && matchesCategory
        }
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    viewModel.checkPermission(
                        action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                        onDenied = { restrictedActionName = "إضافة صنف جديد للدليل" }
                    ) {
                        itemToEdit = null
                        itemHasTransactions = false
                        showEditDialog = true
                    }
                },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.testTag("add_item_fab")
            ) {
                Icon(Icons.Default.Add, contentDescription = "إضافة صنف جديد")
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Search Input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("بحث باسم الصنف، الباركود، أو الفئة...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("item_search_input"),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Quick Catalog Management Buttons: Categories & Units
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = { showManageUnitsDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manage_units_btn"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Scale, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إدارة وحدات القياس", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { showManageCategoriesDialog = true },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("manage_categories_btn"),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                ) {
                    Icon(Icons.Default.Category, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إدارة التصنيفات", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Categories Filter Bar
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(categories) { cat ->
                    val isSelected = (selectedCategory == null && cat == "الكل") || selectedCategory == cat
                    FilterChip(
                        selected = isSelected,
                        onClick = { selectedCategory = if (cat == "الكل") null else cat },
                        label = { Text(cat) }
                    )
                }
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
                            imageVector = Icons.Default.Category,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(64.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = if (searchQuery.isEmpty()) "لا توجد أصناف مضافة بعد. اضغط + لإضافة صنف."
                            else "لا توجد أصناف مطابقة لبحثك",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 80.dp)
                ) {
                    items(filteredItems, key = { it.item.id }) { stockInfo ->
                        val stocksForItem = remember(warehouseItemStocks, stockInfo.item.id) {
                            warehouseItemStocks.filter { it.itemId == stockInfo.item.id }
                        }
                        ItemCard(
                            stockInfo = stockInfo,
                            itemWarehouseStocks = stocksForItem,
                            onEdit = {
                                viewModel.checkPermission(
                                    action = DeviceAction.EDIT_CATALOG_ITEMS,
                                    onDenied = { restrictedActionName = "تعديل بيانات الصنف داخل الدليل" }
                                ) {
                                    coroutineScope.launch {
                                        itemHasTransactions = viewModel.checkItemHasTransactions(stockInfo.item.id)
                                        itemToEdit = stockInfo.item
                                        showEditDialog = true
                                    }
                                }
                            },
                            onDelete = {
                                viewModel.checkPermission(
                                    action = DeviceAction.DELETE_ITEM,
                                    onDenied = { restrictedActionName = "حذف الصنف" }
                                ) {
                                    coroutineScope.launch {
                                        val hasTransactions = viewModel.checkItemHasTransactions(stockInfo.item.id)
                                        itemToDelete = stockInfo.item
                                        if (hasTransactions) {
                                            doubleConfirmStep = 1
                                        } else {
                                            showSimpleDeleteDialog = true
                                        }
                                    }
                                }
                            },
                            onOpenSerialTracking = { targetStock ->
                                val wh = warehouses.find { it.id == targetStock.warehouseId }
                                if (wh != null) {
                                    serialTrackingTarget = wh to targetStock
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    if (restrictedActionName != null) {
        SubDeviceRestrictedDialog(
            actionName = restrictedActionName!!,
            onDismiss = { restrictedActionName = null }
        )
    }

    // Add / Edit Item Dialog
    if (showEditDialog) {
        ItemFormDialog(
            initialItem = itemToEdit,
            hasTransactions = itemHasTransactions,
            unitsList = unitsFromDb,
            smallerUnitsList = smallerUnitsFromDb,
            categoriesList = categoriesFromDb,
            existingCarTypes = existingCarTypes,
            viewModel = viewModel,
            onAddNewCategory = { cat -> viewModel.saveCategory(cat) },
            onDismiss = { showEditDialog = false },
            onSave = { item ->
                viewModel.saveItem(item) {
                    showEditDialog = false
                }
            }
        )
    }

    // Manage Categories Dialog (Add / Safe Edit / Safe Delete)
    if (showManageCategoriesDialog) {
        ManageCategoriesDialog(
            categories = categoriesFromDb,
            viewModel = viewModel,
            onDismiss = { showManageCategoriesDialog = false }
        )
    }

    // Manage Units Dialog (Package Units & Smaller Units Tabs)
    if (showManageUnitsDialog) {
        ManageUnitsDialog(
            units = unitsFromDb,
            smallerUnits = smallerUnitsFromDb,
            viewModel = viewModel,
            onDismiss = { showManageUnitsDialog = false }
        )
    }

    // Quick Disbursement from Serials Overlay
    disbursementTargetWarehouse?.let { targetWh ->
        var autoVoucherNumber by remember { mutableStateOf("") }
        LaunchedEffect(targetWh.id) {
            autoVoucherNumber = viewModel.getNextVoucherNumber(VoucherType.DISBURSEMENT)
        }
        if (autoVoucherNumber.isNotBlank()) {
            VoucherFormDialog(
                voucherType = VoucherType.DISBURSEMENT,
                initialVoucherWithItems = null,
                preselectedLineItems = preselectedDisburseLines.ifEmpty { null },
                fixedSourceWarehouseId = targetWh.id,
                fixedVoucherNumber = autoVoucherNumber,
                warehouses = listOf(targetWh),
                items = itemsList.map { it.item },
                beneficiaries = beneficiaries,
                suppliers = suppliers,
                parties = parties,
                stockMap = snapshot?.stockMap ?: emptyMap(),
                onAddNewBeneficiary = { ben, onDone -> viewModel.saveBeneficiary(ben, onDone) },
                onAddNewSupplier = { supp, onDone -> viewModel.saveSupplier(supp, onDone) },
                onAddNewParty = { pty, onDone -> viewModel.saveParty(pty, onDone) },
                onUpdateSupplier = { oldName, newName -> viewModel.updateSupplierName(oldName, newName) },
                onDeleteSupplier = { supp, onResult -> viewModel.deleteSupplier(supp, onResult) },
                checkSupplierHasTransactions = { name -> viewModel.checkSupplierHasTransactions(name) },
                onUpdateParty = { oldName, newName -> viewModel.updatePartyName(oldName, newName) },
                onDeleteParty = { pty, onResult -> viewModel.deleteParty(pty, onResult) },
                checkPartyHasTransactions = { name -> viewModel.checkPartyHasTransactions(name) },
                onDismiss = {
                    preselectedDisburseLines = emptyList()
                    disbursementTargetWarehouse = null
                },
                onSave = { voucher, lineItems ->
                    viewModel.saveVoucher(voucher, lineItems) {
                        preselectedDisburseLines = emptyList()
                        disbursementTargetWarehouse = null
                    }
                }
            )
        }
    }

    // Single Delete Dialog
    if (showSimpleDeleteDialog && itemToDelete != null) {
        SimpleConfirmDialog(
            title = "تأكيد حذف الصنف",
            message = "هل أنت متأكد من حذف الصنف: \"${itemToDelete!!.name}\"؟ لا توجد له حركات توريد أو صرف سابقة.",
            confirmButtonText = "نعم، احذف",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteItem(itemToDelete!!)
                showSimpleDeleteDialog = false
                itemToDelete = null
            },
            onDismiss = {
                showSimpleDeleteDialog = false
                itemToDelete = null
            }
        )
    }

    // Double Warning Confirmation Dialog
    if (doubleConfirmStep > 0 && itemToDelete != null) {
        DoubleWarningConfirmDialog(
            step = doubleConfirmStep,
            itemName = itemToDelete!!.name,
            reasonText = "هذا الصنف تمت عليه حركات مخزنية سابقة (وارد أو صرف أو تحويل)! حذفه سيؤثر على تقارير وسجلات الحركات.",
            onProceedToStepTwo = { doubleConfirmStep = 2 },
            onFinalConfirm = {
                viewModel.deleteItem(itemToDelete!!)
                doubleConfirmStep = 0
                itemToDelete = null
            },
            onDismiss = {
                doubleConfirmStep = 0
                itemToDelete = null
            }
        )
    }
}

@Composable
private fun ItemCard(
    stockInfo: ItemStockInfo,
    itemWarehouseStocks: List<WarehouseItemStock>,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onOpenSerialTracking: (WarehouseItemStock) -> Unit
) {
    val item = stockInfo.item
    val context = LocalContext.current
    var serialsDropdownExpanded by remember { mutableStateOf(false) }
    var warehouseBreakdownExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("item_card_${item.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Surface(
                        shape = CircleShape,
                        color = when {
                            stockInfo.isOutOfStock -> DangerRed.copy(alpha = 0.15f)
                            stockInfo.isLowStock -> WarningOrange.copy(alpha = 0.15f)
                            else -> MaterialTheme.colorScheme.primaryContainer
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (stockInfo.isOutOfStock || stockInfo.isLowStock) Icons.Default.Warning else Icons.Default.Inventory2,
                                contentDescription = null,
                                tint = when {
                                    stockInfo.isOutOfStock -> DangerRed
                                    stockInfo.isLowStock -> WarningOrange
                                    else -> MaterialTheme.colorScheme.primary
                                },
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = item.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (item.carType.isNotBlank() || item.carName.isNotBlank()) {
                            Text(
                                text = listOf(item.carType, item.carName)
                                    .filter { it.isNotBlank() }.joinToString(" • "),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    text = item.category.ifBlank { "عام" },
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            if (item.hasMediumUnit && item.mediumUnit.isNotBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(1 ${item.packageUnit} = ${formatCapacity(item.mediumUnitsPerLarge)} ${item.mediumUnit} • ${formatCapacity(item.smallPerMediumEffective())} ${item.unit}/${item.mediumUnit})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else if (item.unitsPerPackage > 1.0) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "(${formatCapacity(item.unitsPerPackage)} ${item.unit} لكل ${item.packageUnit})",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (item.barcode.isNotBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.QrCode, contentDescription = null, modifier = Modifier.size(12.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text(item.barcode, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            if (!item.attachmentUri.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Surface(
                                    shape = RoundedCornerShape(4.dp),
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                                    modifier = Modifier.clickable {
                                        val file = File(item.attachmentUri)
                                        if (file.exists()) {
                                            val mime = if (item.attachmentType == "IMAGE") "image/*" else "application/pdf"
                                            ReportGenerator.viewFile(context, file, mime)
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(
                                            imageVector = if (item.attachmentType == "IMAGE") Icons.Default.Image else Icons.Default.Description,
                                            contentDescription = null,
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(2.dp))
                                        Text("مرفق", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                Row {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = DangerRed)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Stock Details
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        when {
                            stockInfo.isOutOfStock -> DangerRed.copy(alpha = 0.08f)
                            stockInfo.isLowStock -> WarningOrange.copy(alpha = 0.08f)
                            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        },
                        RoundedCornerShape(8.dp)
                    )
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("الرصيد الكلي", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val formatted = formatHierarchyStock(
                        totalSmall = stockInfo.currentStock,
                        largeUnit = item.packageUnit,
                        hasMediumUnit = item.hasMediumUnit,
                        mediumUnit = item.mediumUnit,
                        mediumPerLarge = item.mediumUnitsPerLarge,
                        smallPerMedium = item.smallPerMediumEffective(),
                        totalSmallPerLarge = item.totalSmallPerLargeEffective(),
                        smallUnit = item.unit
                    )
                    Text(
                        text = formatted,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            stockInfo.isOutOfStock -> DangerRed
                            stockInfo.isLowStock -> WarningOrange
                            else -> SuccessGreen
                        }
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("حد الطلب", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    val combinedMinStock = itemWarehouseStocks.sumOf { it.minStockLevel }.takeIf { it > 0.0 } ?: item.minStockLevel
                    Text("${combinedMinStock.toInt()} ${item.unit}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("سعر الشراء", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.2f $".format(item.purchasePrice), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("إجمالي القيمة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("%.0f $".format(stockInfo.totalValue), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                }
            }

            if (itemWarehouseStocks.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { warehouseBreakdownExpanded = !warehouseBreakdownExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(9.dp)
                ) {
                    Text(if (warehouseBreakdownExpanded) "إخفاء أرصدة المخازن" else "تفاصيل الرصيد وحد الطلب حسب المخزن (${itemWarehouseStocks.size})")
                }
                if (warehouseBreakdownExpanded) {
                    Column(
                        modifier = Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(8.dp)).padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        itemWarehouseStocks.sortedBy { it.warehouseName }.forEach { whStock ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(whStock.warehouseName, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                                Column(horizontalAlignment = Alignment.End) {
                                    Text("الرصيد: ${whStock.currentStock.toInt()} ${whStock.itemUnit}", style = MaterialTheme.typography.labelSmall, color = if (whStock.isOutOfStock) DangerRed else MaterialTheme.colorScheme.onSurface)
                                    Text("حد الطلب: ${whStock.minStockLevel.toInt()} ${whStock.itemUnit}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }

            // Dropdown Menu Button for Serial Numbers / Status / Notes when enabled on Item
            if (item.hasSerialNumbers) {
                Spacer(modifier = Modifier.height(10.dp))
                Box(modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = { serialsDropdownExpanded = true },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("item_serials_dropdown_btn_${item.id}"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FormatListNumbered,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "الأرقام التسلسلية والحالة والملاحظات",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.weight(1f)
                        )
                        Icon(
                            imageVector = Icons.Default.ArrowDropDown,
                            contentDescription = "فتح قائمة الأرقام التسلسلية"
                        )
                    }

                    DropdownMenu(
                        expanded = serialsDropdownExpanded,
                        onDismissRequest = { serialsDropdownExpanded = false }
                    ) {
                        if (itemWarehouseStocks.isEmpty()) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "لا يوجد رصيد بالمخازن بعد — أضف رصيداً افتتاحياً أو سند توريد",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                },
                                onClick = { serialsDropdownExpanded = false }
                            )
                        } else {
                            itemWarehouseStocks.forEach { whStock ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(
                                                text = whStock.warehouseName,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                            Text(
                                                text = "الكمية المتاحة: ${whStock.currentStock.toInt()} ${whStock.itemUnit} • إضافة رقم مميز وحالة وملاحظة",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }
                                    },
                                    onClick = {
                                        serialsDropdownExpanded = false
                                        onOpenSerialTracking(whStock)
                                    }
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
private fun ItemFormDialog(
    initialItem: Item?,
    hasTransactions: Boolean = false,
    unitsList: List<ItemUnit>,
    smallerUnitsList: List<SmallerUnit>,
    categoriesList: List<ItemCategory>,
    existingCarTypes: List<String>,
    viewModel: InventoryViewModel,
    onAddNewCategory: (ItemCategory) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialItem?.name ?: "") }
    var barcode by remember { mutableStateOf(initialItem?.barcode ?: "") }
    var carType by remember { mutableStateOf(initialItem?.carType ?: "") }
    var carName by remember { mutableStateOf(initialItem?.carName ?: "") }
    var carTypeDropdownExpanded by remember { mutableStateOf(false) }
    val allCarTypes = remember(existingCarTypes, carType) {
        (existingCarTypes + carType.trim().takeIf { it.isNotBlank() }.orEmpty())
            .filter { it.isNotBlank() }
            .distinct()
            .sorted()
    }

    val packageUnitOptions = remember(unitsList) {
        unitsList.map { it.name }.distinct().ifEmpty { listOf("صندوق") }
    }

    val defaultPackageName = remember(initialItem, unitsList) {
        initialItem?.packageUnit
            ?: unitsList.firstOrNull { it.name == "كرتون" }?.name
            ?: unitsList.firstOrNull { it.name == "صندوق" }?.name
            ?: unitsList.firstOrNull()?.name
            ?: "صندوق"
    }

    var packageUnit by remember { mutableStateOf(defaultPackageName) }

    val selectedLargeUnitObj = remember(unitsList, packageUnit, initialItem) {
        unitsList.firstOrNull {
            it.name == packageUnit &&
                (initialItem == null || it.smallerUnit == initialItem.unit || !initialItem.hasMediumUnit || it.mediumUnit == initialItem.mediumUnit)
        } ?: unitsList.firstOrNull { it.name == packageUnit }
    }

    val scopedMediumChildren = remember(smallerUnitsList, selectedLargeUnitObj, packageUnit) {
        val parentId = selectedLargeUnitObj?.id ?: -1L
        smallerUnitsList.filter { su ->
            su.unitLevel == "MEDIUM" && (
                (parentId > 0L && su.parentUnitId == parentId) ||
                    (su.parentUnitName == packageUnit && packageUnit.isNotBlank())
                )
        }
    }

    val scopedSmallChildren = remember(smallerUnitsList, selectedLargeUnitObj, packageUnit) {
        val parentId = selectedLargeUnitObj?.id ?: -1L
        smallerUnitsList.filter { su ->
            su.unitLevel != "MEDIUM" && (
                (parentId > 0L && su.parentUnitId == parentId) ||
                    (su.parentUnitName == packageUnit && packageUnit.isNotBlank())
                )
        }
    }

    var hasMediumUnit by remember {
        mutableStateOf(
            initialItem?.hasMediumUnit
                ?: selectedLargeUnitObj?.hasMediumUnit
                ?: scopedMediumChildren.isNotEmpty()
        )
    }

    val allowedMediumUnits = remember(scopedMediumChildren, selectedLargeUnitObj, initialItem, hasMediumUnit) {
        if (!hasMediumUnit) emptyList()
        else {
            val list = mutableListOf<String>()
            selectedLargeUnitObj?.mediumUnit?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            scopedMediumChildren.forEach { if (it.name.isNotBlank()) list.add(it.name) }
            initialItem?.mediumUnit?.takeIf { it.isNotBlank() }?.let { list.add(it) }
            list.distinct()
        }
    }

    var mediumUnit by remember {
        mutableStateOf(
            initialItem?.mediumUnit?.takeIf { initialItem.hasMediumUnit }
                ?: selectedLargeUnitObj?.mediumUnit?.takeIf { selectedLargeUnitObj.hasMediumUnit }
                ?: scopedMediumChildren.firstOrNull()?.name
                ?: ""
        )
    }

    var mediumPerLarge by remember {
        mutableStateOf(
            initialItem?.mediumUnitsPerLarge?.takeIf { initialItem.hasMediumUnit && it > 0.0 }
                ?: selectedLargeUnitObj?.mediumUnitsCount?.takeIf { selectedLargeUnitObj.hasMediumUnit && it > 0.0 }
                ?: scopedMediumChildren.firstOrNull()?.countInParent
                ?: 0.0
        )
    }

    val allowedSmallerUnits = remember(scopedSmallChildren, selectedLargeUnitObj, initialItem) {
        val list = mutableListOf<String>()
        selectedLargeUnitObj?.smallerUnit?.takeIf { it.isNotBlank() }?.let { list.add(it) }
        scopedSmallChildren.forEach { if (it.name.isNotBlank()) list.add(it.name) }
        if (list.isEmpty() && initialItem != null && initialItem.unit.isNotBlank()) {
            list.add(initialItem.unit)
        }
        list.distinct().ifEmpty { listOf("حبة") }
    }

    var unit by remember {
        mutableStateOf(
            initialItem?.unit
                ?: selectedLargeUnitObj?.smallerUnit
                ?: allowedSmallerUnits.firstOrNull()
                ?: "حبة"
        )
    }

    var smallPerMedium by remember {
        mutableStateOf(
            if (initialItem != null && initialItem.hasMediumUnit) {
                initialItem.smallPerMediumEffective()
            } else if (selectedLargeUnitObj?.hasMediumUnit == true) {
                selectedLargeUnitObj.smallUnitsPerMedium.coerceAtLeast(1.0)
            } else {
                1.0
            }
        )
    }

    var unitsPerPackageText by remember {
        mutableStateOf(
            formatCapacity(
                initialItem?.totalSmallPerLargeEffective()
                    ?: selectedLargeUnitObj?.totalSmallPerLarge()
                    ?: 1.0
            )
        )
    }

    var smallerUnitCapacityText by remember {
        mutableStateOf(
            formatCapacity(
                initialItem?.smallerUnitCapacity
                    ?: scopedSmallChildren.find { it.name == unit }?.capacity
                    ?: selectedLargeUnitObj?.smallerUnitCapacity
                    ?: 1.0
            )
        )
    }

    // Opening Stock: user can pick Large, Medium (if 3-level), Small, or Combined
    // Default is Small unit ("SMALL") so existing numbers stay 1:1
    var openingUnitMode by remember { mutableStateOf("SMALL") } // "LARGE", "MEDIUM", "SMALL", "COMBINED"
    var openingLargeInput by remember { mutableStateOf("") }
    var openingMediumInput by remember { mutableStateOf("") }
    var openingSmallInput by remember { mutableStateOf(initialItem?.openingStock?.toInt()?.toString() ?: "0") }

    val totalSmallPerLargeVal = remember(unitsPerPackageText, hasMediumUnit, mediumPerLarge, smallPerMedium) {
        if (hasMediumUnit && mediumUnit.isNotBlank() && mediumPerLarge > 0.0 && smallPerMedium > 0.0) {
            mediumPerLarge * smallPerMedium
        } else {
            (unitsPerPackageText.toDoubleOrNull() ?: 1.0).coerceAtLeast(1.0)
        }
    }

    val computedOpeningStock = remember(
        openingUnitMode,
        openingLargeInput,
        openingMediumInput,
        openingSmallInput,
        totalSmallPerLargeVal,
        smallPerMedium,
        hasMediumUnit
    ) {
        val l = openingLargeInput.toDoubleOrNull() ?: 0.0
        val m = openingMediumInput.toDoubleOrNull() ?: 0.0
        val s = openingSmallInput.toDoubleOrNull() ?: 0.0
        when (openingUnitMode) {
            "LARGE" -> l * totalSmallPerLargeVal
            "MEDIUM" -> m * (if (hasMediumUnit) smallPerMedium else 1.0)
            "COMBINED" -> (l * totalSmallPerLargeVal) + (if (hasMediumUnit) m * smallPerMedium else 0.0) + s
            else -> s
        }
    }

    var hasSerialNumbers by remember { mutableStateOf(initialItem?.hasSerialNumbers ?: false) }

    var category by remember { mutableStateOf(initialItem?.category ?: categoriesList.firstOrNull()?.name ?: "عام") }
    var minStock by remember { mutableStateOf(initialItem?.minStockLevel?.toInt()?.toString() ?: "0") }
    var purchasePrice by remember { mutableStateOf(initialItem?.purchasePrice?.toString() ?: "0") }
    var salePrice by remember { mutableStateOf(initialItem?.salePrice?.toString() ?: "0") }
    var notes by remember { mutableStateOf(initialItem?.notes ?: "") }

    // Attachment
    var attachmentUri by remember { mutableStateOf(initialItem?.attachmentUri) }
    var attachmentType by remember { mutableStateOf(initialItem?.attachmentType) }
    var showDeleteAttachmentDialog by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf("") }

    // Dropdown expansion states
    var unitDropdownExpanded by remember { mutableStateOf(false) }
    var mediumUnitDropdownExpanded by remember { mutableStateOf(false) }
    var packageUnitDropdownExpanded by remember { mutableStateOf(false) }
    var categoryDropdownExpanded by remember { mutableStateOf(false) }

    // Inline Category management dialogs (No Add Unit dialog here per requirements)
    var showAddCategoryDialog by remember { mutableStateOf(false) }
    var categoryToEdit by remember { mutableStateOf<ItemCategory?>(null) }
    var categoryToDelete by remember { mutableStateOf<ItemCategory?>(null) }
    var categoryBlockedWarning by remember { mutableStateOf<String?>(null) }

    val allCategories = remember(categoriesList) {
        categoriesList.map { it.name }.distinct().ifEmpty { listOf("عام") }
    }

    // Media pickers
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val copied = copyItemUriToInternalStorage(context, it, "item_img_${System.currentTimeMillis()}.jpg")
            attachmentUri = copied
            attachmentType = "IMAGE"
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val copied = copyItemUriToInternalStorage(context, it, "item_doc_${System.currentTimeMillis()}.pdf")
            attachmentUri = copied
            attachmentType = "PDF"
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialItem == null) "إضافة صنف جديد" else "تعديل بيانات الصنف",
                style = MaterialTheme.typography.titleLarge,
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
                if (hasTransactions) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.12f)),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Warning, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "ملاحظة: هذا الصنف تمت عليه حركات مخزنية، لذا تم قفل الاسم والباركود ونوع السيارة واسمها والوحدات والكمية الافتتاحية حمايةً للبيانات. يُسمح بتعديل حد الطلب والأسعار والأرقام التسلسلية والملاحظات والمرفق.",
                                style = MaterialTheme.typography.labelSmall,
                                color = WarningOrange,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = name,
                    onValueChange = {
                        name = it
                        errorMessage = ""
                    },
                    readOnly = hasTransactions,
                    enabled = !hasTransactions,
                    label = { Text("اسم الصنف *") },
                    isError = errorMessage.isNotBlank() && name.isBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("item_name_input"),
                    singleLine = true
                )

                OutlinedTextField(
                    value = barcode,
                    onValueChange = { barcode = it },
                    readOnly = hasTransactions,
                    enabled = !hasTransactions,
                    label = { Text("الباركود / الكود") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Category Selection with + Add New Category & Safe Edit/Delete
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = if (hasTransactions) false else categoryDropdownExpanded,
                            onExpandedChange = { if (!hasTransactions) categoryDropdownExpanded = !categoryDropdownExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = category,
                                onValueChange = {},
                                readOnly = true,
                                enabled = !hasTransactions,
                                label = { Text("الفئة / التصنيف *") },
                                trailingIcon = { if (!hasTransactions) ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            if (!hasTransactions) {
                                ExposedDropdownMenu(
                                    expanded = categoryDropdownExpanded,
                                    onDismissRequest = { categoryDropdownExpanded = false }
                                ) {
                                    allCategories.forEach { catName ->
                                        val catObj = categoriesList.find { it.name == catName }
                                        DropdownMenuItem(
                                            text = {
                                                Row(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.SpaceBetween,
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Text(catName, modifier = Modifier.weight(1f), maxLines = 1)
                                                    if (catObj != null) {
                                                        IconButton(
                                                            onClick = {
                                                                categoryDropdownExpanded = false
                                                                coroutineScope.launch {
                                                                    if (viewModel.checkCategoryHasOperations(catObj.name)) {
                                                                        categoryBlockedWarning = "لا يمكن تعديل الفئة (${catObj.name}) لوجود أصناف أو عمليات مرتبطة بها!"
                                                                    } else {
                                                                        categoryToEdit = catObj
                                                                    }
                                                                }
                                                            },
                                                            modifier = Modifier.size(30.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Edit,
                                                                contentDescription = "تعديل الفئة",
                                                                tint = MaterialTheme.colorScheme.primary,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                        IconButton(
                                                            onClick = {
                                                                categoryDropdownExpanded = false
                                                                coroutineScope.launch {
                                                                    if (viewModel.checkCategoryHasOperations(catObj.name)) {
                                                                        categoryBlockedWarning = "لا يمكن حذف الفئة (${catObj.name}) لوجود أصناف أو عمليات مرتبطة بها!"
                                                                    } else {
                                                                        categoryToDelete = catObj
                                                                    }
                                                                }
                                                            },
                                                            modifier = Modifier.size(30.dp)
                                                        ) {
                                                            Icon(
                                                                Icons.Default.Delete,
                                                                contentDescription = "حذف الفئة",
                                                                tint = DangerRed,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            },
                                            onClick = {
                                                category = catName
                                                categoryDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    if (!hasTransactions) {
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(
                            onClick = { showAddCategoryDialog = true },
                            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "إضافة فئة جديدة", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                if (!hasTransactions) {
                    ExposedDropdownMenuBox(
                        expanded = carTypeDropdownExpanded,
                        onExpandedChange = { carTypeDropdownExpanded = !carTypeDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = carType,
                            onValueChange = {
                                carType = it
                                carTypeDropdownExpanded = true
                            },
                            enabled = true,
                            label = { Text("نوع السيارة") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = carTypeDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = carTypeDropdownExpanded && allCarTypes.isNotEmpty(),
                            onDismissRequest = { carTypeDropdownExpanded = false }
                        ) {
                            allCarTypes.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type) },
                                    onClick = {
                                        carType = type
                                        carTypeDropdownExpanded = false
                                    }
                                )
                            }
                        }
                    }
                } else {
                    OutlinedTextField(
                        value = carType,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("نوع السيارة") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = carName,
                    onValueChange = { carName = it },
                    readOnly = hasTransactions,
                    enabled = !hasTransactions,
                    label = { Text("اسم السيارة") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Package Unit & Filtered Smaller Unit Row (No + Add Unit button!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Large Unit Dropdown
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = if (hasTransactions) false else packageUnitDropdownExpanded,
                            onExpandedChange = { if (!hasTransactions) packageUnitDropdownExpanded = !packageUnitDropdownExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = packageUnit,
                                onValueChange = {},
                                readOnly = true,
                                enabled = !hasTransactions,
                                label = { Text("الوحدة الكبيرة *") },
                                trailingIcon = { if (!hasTransactions) ExposedDropdownMenuDefaults.TrailingIcon(expanded = packageUnitDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                                    .testTag("item_package_unit_dropdown")
                            )
                            if (!hasTransactions) {
                                ExposedDropdownMenu(
                                    expanded = packageUnitDropdownExpanded,
                                    onDismissRequest = { packageUnitDropdownExpanded = false }
                                ) {
                                    packageUnitOptions.forEach { pkgName ->
                                        DropdownMenuItem(
                                            text = { Text(pkgName) },
                                            onClick = {
                                                packageUnit = pkgName
                                                packageUnitDropdownExpanded = false
                                                val primary = unitsList.firstOrNull { it.name == pkgName }
                                                if (primary != null) {
                                                    val medKids = smallerUnitsList.filter {
                                                        it.unitLevel == "MEDIUM" && (it.parentUnitId == primary.id || it.parentUnitName == pkgName)
                                                    }
                                                    val smallKids = smallerUnitsList.filter {
                                                        it.unitLevel != "MEDIUM" && (it.parentUnitId == primary.id || it.parentUnitName == pkgName)
                                                    }
                                                    hasMediumUnit = primary.hasMediumUnit || medKids.isNotEmpty()
                                                    if (hasMediumUnit) {
                                                        val firstMed = medKids.firstOrNull()
                                                        mediumUnit = primary.mediumUnit.ifBlank { firstMed?.name.orEmpty() }
                                                        mediumPerLarge = if (primary.mediumUnitsCount > 0.0) primary.mediumUnitsCount else (firstMed?.countInParent ?: 1.0)
                                                        val firstSmall = smallKids.firstOrNull()
                                                        unit = primary.smallerUnit.ifBlank { firstSmall?.name ?: "حبة" }
                                                        smallPerMedium = if (primary.smallUnitsPerMedium > 0.0) primary.smallUnitsPerMedium else (firstSmall?.countInParent ?: 1.0)
                                                        unitsPerPackageText = formatCapacity(mediumPerLarge * smallPerMedium)
                                                        smallerUnitCapacityText = formatCapacity(firstSmall?.capacity ?: primary.smallerUnitCapacity)
                                                    } else {
                                                        mediumUnit = ""
                                                        mediumPerLarge = 0.0
                                                        smallPerMedium = 1.0
                                                        val firstSmall = smallKids.firstOrNull()
                                                        unit = primary.smallerUnit.ifBlank { firstSmall?.name ?: "حبة" }
                                                        unitsPerPackageText = formatCapacity(firstSmall?.countInParent ?: primary.smallerUnitsCount)
                                                        smallerUnitCapacityText = formatCapacity(firstSmall?.capacity ?: primary.smallerUnitCapacity)
                                                    }
                                                    openingUnitMode = "SMALL"
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Filtered Smaller Unit Dropdown (shows ONLY small units belonging to selected Large unit)
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = if (hasTransactions) false else unitDropdownExpanded,
                            onExpandedChange = { if (!hasTransactions) unitDropdownExpanded = !unitDropdownExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = unit,
                                onValueChange = {},
                                readOnly = true,
                                enabled = !hasTransactions,
                                label = { Text("الوحدة الصغرى التابعة *") },
                                trailingIcon = { if (!hasTransactions) ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                                    .testTag("item_smaller_unit_dropdown")
                            )
                            if (!hasTransactions) {
                                ExposedDropdownMenu(
                                    expanded = unitDropdownExpanded,
                                    onDismissRequest = { unitDropdownExpanded = false }
                                ) {
                                    allowedSmallerUnits.forEach { smallerOpt ->
                                        DropdownMenuItem(
                                            text = { Text(smallerOpt) },
                                            onClick = {
                                                unit = smallerOpt
                                                unitDropdownExpanded = false
                                                val childSmall = scopedSmallChildren.find { it.name == smallerOpt }
                                                val exactPkg = unitsList.find {
                                                    it.name == packageUnit && it.smallerUnit == smallerOpt
                                                }
                                                if (hasMediumUnit) {
                                                    val sPerM = childSmall?.countInParent ?: exactPkg?.smallUnitsPerMedium ?: smallPerMedium
                                                    smallPerMedium = sPerM.coerceAtLeast(1.0)
                                                    unitsPerPackageText = formatCapacity(mediumPerLarge.coerceAtLeast(1.0) * smallPerMedium)
                                                    smallerUnitCapacityText = formatCapacity(childSmall?.capacity ?: exactPkg?.smallerUnitCapacity ?: 1.0)
                                                } else {
                                                    val count = childSmall?.countInParent ?: exactPkg?.smallerUnitsCount ?: 1.0
                                                    unitsPerPackageText = formatCapacity(count)
                                                    smallerUnitCapacityText = formatCapacity(childSmall?.capacity ?: exactPkg?.smallerUnitCapacity ?: 1.0)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Medium Unit Dropdown if the selected Large Unit uses a 3-level hierarchy
                if (hasMediumUnit && allowedMediumUnits.isNotEmpty()) {
                    ExposedDropdownMenuBox(
                        expanded = if (hasTransactions) false else mediumUnitDropdownExpanded,
                        onExpandedChange = { if (!hasTransactions) mediumUnitDropdownExpanded = !mediumUnitDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = mediumUnit,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !hasTransactions,
                            label = { Text("الوحدة المتوسطة التابعة لـ ($packageUnit)") },
                            trailingIcon = { if (!hasTransactions) ExposedDropdownMenuDefaults.TrailingIcon(expanded = mediumUnitDropdownExpanded) },
                            modifier = Modifier
                                .menuAnchor()
                                .fillMaxWidth()
                                .testTag("item_medium_unit_dropdown")
                        )
                        if (!hasTransactions) {
                            ExposedDropdownMenu(
                                expanded = mediumUnitDropdownExpanded,
                                onDismissRequest = { mediumUnitDropdownExpanded = false }
                            ) {
                                allowedMediumUnits.forEach { medOpt ->
                                    DropdownMenuItem(
                                        text = { Text(medOpt) },
                                        onClick = {
                                            mediumUnit = medOpt
                                            mediumUnitDropdownExpanded = false
                                            val medChild = scopedMediumChildren.find { it.name == medOpt }
                                            if (medChild != null) {
                                                mediumPerLarge = medChild.countInParent.coerceAtLeast(1.0)
                                                unitsPerPackageText = formatCapacity(mediumPerLarge * smallPerMedium.coerceAtLeast(1.0))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                // Auto-displayed Hierarchy & Capacity Breakdown Card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("item_units_hierarchy_summary"),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "تفصيل وحدات الصنف وسعة كل وحدة",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (hasMediumUnit && mediumUnit.isNotBlank()) {
                            Text(
                                text = "• الكبيرة ($packageUnit) = ${formatCapacity(mediumPerLarge)} $mediumUnit (إجمالي ${formatCapacity(totalSmallPerLargeVal)} $unit)",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "• المتوسطة ($mediumUnit) = ${formatCapacity(smallPerMedium)} $unit",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "• الصغرى ($unit) — السعة: $smallerUnitCapacityText",
                                style = MaterialTheme.typography.labelSmall
                            )
                        } else {
                            Text(
                                text = "• الكبيرة ($packageUnit) = ${formatCapacity(totalSmallPerLargeVal)} $unit",
                                style = MaterialTheme.typography.labelSmall
                            )
                            Text(
                                text = "• الصغرى ($unit) — السعة: $smallerUnitCapacityText",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }

                // Auto-displayed Package Capacity & Smaller Unit Capacity
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = unitsPerPackageText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("سعة العبوة ($unit)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("item_package_capacity_display"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = smallerUnitCapacityText,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("سعة الوحدة الصغرى ($unit)") },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("item_smaller_unit_capacity_display"),
                        singleLine = true
                    )
                }

                // Opening Stock with selectable unit options (Large / Medium / Small / Combined)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "الكمية الافتتاحية للمخزون (اختر وحدة الإدخال)",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold
                        )
                        if (!hasTransactions && (totalSmallPerLargeVal > 1.0 || (hasMediumUnit && mediumUnit.isNotBlank()))) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (packageUnit != unit && totalSmallPerLargeVal > 1.0) {
                                    item {
                                        FilterChip(
                                            selected = openingUnitMode == "LARGE",
                                            onClick = { openingUnitMode = "LARGE" },
                                            label = { Text("بالـ $packageUnit", fontSize = 11.sp) }
                                        )
                                    }
                                }
                                if (hasMediumUnit && mediumUnit.isNotBlank() && smallPerMedium > 1.0) {
                                    item {
                                        FilterChip(
                                            selected = openingUnitMode == "MEDIUM",
                                            onClick = { openingUnitMode = "MEDIUM" },
                                            label = { Text("بالـ $mediumUnit", fontSize = 11.sp) }
                                        )
                                    }
                                }
                                item {
                                    FilterChip(
                                        selected = openingUnitMode == "SMALL",
                                        onClick = { openingUnitMode = "SMALL" },
                                        label = { Text("بالـ $unit", fontSize = 11.sp) }
                                    )
                                }
                                if (totalSmallPerLargeVal > 1.0 || hasMediumUnit) {
                                    item {
                                        FilterChip(
                                            selected = openingUnitMode == "COMBINED",
                                            onClick = { openingUnitMode = "COMBINED" },
                                            label = { Text("مركب", fontSize = 11.sp) }
                                        )
                                    }
                                }
                            }
                        }

                        when (openingUnitMode) {
                            "LARGE" -> {
                                OutlinedTextField(
                                    value = openingLargeInput,
                                    onValueChange = { openingLargeInput = it.filter { ch -> ch.isDigit() } },
                                    readOnly = hasTransactions,
                                    enabled = !hasTransactions,
                                    label = { Text("الكمية الافتتاحية بالـ ($packageUnit)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().testTag("item_opening_stock_input"),
                                    singleLine = true
                                )
                            }
                            "MEDIUM" -> {
                                OutlinedTextField(
                                    value = openingMediumInput,
                                    onValueChange = { openingMediumInput = it.filter { ch -> ch.isDigit() } },
                                    readOnly = hasTransactions,
                                    enabled = !hasTransactions,
                                    label = { Text("الكمية الافتتاحية بالـ ($mediumUnit)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier.fillMaxWidth().testTag("item_opening_stock_input"),
                                    singleLine = true
                                )
                            }
                            "COMBINED" -> {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (packageUnit != unit && totalSmallPerLargeVal > 1.0) {
                                        OutlinedTextField(
                                            value = openingLargeInput,
                                            onValueChange = { openingLargeInput = it.filter { ch -> ch.isDigit() } },
                                            readOnly = hasTransactions,
                                            enabled = !hasTransactions,
                                            label = { Text(packageUnit, fontSize = 11.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                    }
                                    if (hasMediumUnit && mediumUnit.isNotBlank()) {
                                        OutlinedTextField(
                                            value = openingMediumInput,
                                            onValueChange = { openingMediumInput = it.filter { ch -> ch.isDigit() } },
                                            readOnly = hasTransactions,
                                            enabled = !hasTransactions,
                                            label = { Text(mediumUnit, fontSize = 11.sp) },
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                            modifier = Modifier.weight(1f),
                                            singleLine = true
                                        )
                                    }
                                    OutlinedTextField(
                                        value = openingSmallInput,
                                        onValueChange = { openingSmallInput = it.filter { ch -> ch.isDigit() } },
                                        readOnly = hasTransactions,
                                        enabled = !hasTransactions,
                                        label = { Text(unit, fontSize = 11.sp) },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                        singleLine = true
                                    )
                                }
                            }
                            else -> {
                                OutlinedTextField(
                                    value = openingSmallInput,
                                    onValueChange = { openingSmallInput = it.filter { ch -> ch.isDigit() } },
                                    readOnly = hasTransactions,
                                    enabled = !hasTransactions,
                                    label = { Text("الكمية الافتتاحية للمخزون (بالوحدة الصغرى - بالحبة / $unit)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("item_opening_stock_input"),
                                    singleLine = true
                                )
                            }
                        }

                        Text(
                            text = "المعادل المخزني الإجمالي: ${computedOpeningStock.toInt()} $unit",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                // Serial Numbers Tracking Toggle
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "هل الصنف يحتوي على أرقام تسلسلية؟",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = !hasSerialNumbers,
                                onClick = { hasSerialNumbers = false },
                                label = { Text("لا يحتوي", fontSize = 12.sp) },
                                modifier = Modifier.testTag("item_has_serials_no")
                            )
                            FilterChip(
                                selected = hasSerialNumbers,
                                onClick = { hasSerialNumbers = true },
                                label = { Text("نعم، يحتوي على أرقام تسلسلية", fontSize = 12.sp) },
                                modifier = Modifier.testTag("item_has_serials_yes")
                            )
                        }
                    }
                }

                // Reorder level and purchase price are ALWAYS editable
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = minStock,
                        onValueChange = { minStock = it.filter { ch -> ch.isDigit() } },
                        label = { Text("حد إعادة الطلب *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = purchasePrice,
                        onValueChange = { purchasePrice = it },
                        label = { Text("سعر الشراء الافتراضي") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
                OutlinedTextField(
                    value = salePrice,
                    onValueChange = { salePrice = it },
                    label = { Text("سعر البيع الافتراضي") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("ملاحظات / وصف الصنف (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                // Attachment Section (Image / PDF)
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "مرفق الصنف (صورة أو كتالوج PDF)",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        if (attachmentUri.isNullOrBlank()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { imagePickerLauncher.launch("image/*") },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("إرفاق صورة", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { pdfPickerLauncher.launch("application/pdf") },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("إرفاق PDF", fontSize = 12.sp)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = if (attachmentType == "IMAGE") Icons.Default.Image else Icons.Default.Description,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = if (attachmentType == "IMAGE") "تم حفظ صورة الصنف" else "تم حفظ ملف PDF",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                Row {
                                    IconButton(
                                        onClick = {
                                            attachmentUri?.let { path ->
                                                val file = File(path)
                                                if (file.exists()) {
                                                    val mime = if (attachmentType == "IMAGE") "image/*" else "application/pdf"
                                                    ReportGenerator.viewFile(context, file, mime)
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Visibility, contentDescription = "معاينة", tint = MaterialTheme.colorScheme.primary)
                                    }
                                    IconButton(onClick = { showDeleteAttachmentDialog = true }) {
                                        Icon(Icons.Default.Delete, contentDescription = "حذف المرفق", tint = DangerRed)
                                    }
                                }
                            }
                        }
                    }
                }

                if (errorMessage.isNotBlank()) {
                    Text(text = errorMessage, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        errorMessage = "يرجى إدخال اسم الصنف"
                        return@Button
                    }
                    val totalCapacity = if (packageUnit != unit) {
                        totalSmallPerLargeVal.coerceAtLeast(1.0)
                    } else 1.0
                    val smallerCap = (smallerUnitCapacityText.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.01)

                    val opening = computedOpeningStock
                    val minStockVal = minStock.toDoubleOrNull() ?: 0.0
                    val priceVal = purchasePrice.toDoubleOrNull() ?: 0.0
                    val salePriceVal = salePrice.toDoubleOrNull() ?: 0.0

                    val updated = (initialItem ?: Item(name = name)).copy(
                        name = name.trim(),
                        barcode = barcode.trim(),
                        unit = unit.trim(),
                        packageUnit = packageUnit.trim(),
                        unitsPerPackage = totalCapacity,
                        hasMediumUnit = hasMediumUnit && mediumUnit.isNotBlank(),
                        mediumUnit = if (hasMediumUnit) mediumUnit.trim() else "",
                        mediumUnitsPerLarge = if (hasMediumUnit) mediumPerLarge.coerceAtLeast(1.0) else 0.0,
                        smallUnitsPerMedium = if (hasMediumUnit) smallPerMedium.coerceAtLeast(1.0) else 1.0,
                        smallerUnitCapacity = smallerCap,
                        openingStock = opening,
                        hasSerialNumbers = hasSerialNumbers,
                        category = category.trim().ifBlank { "عام" },
                        carType = carType.trim(),
                        carName = carName.trim(),
                        minStockLevel = minStockVal,
                        purchasePrice = priceVal,
                        salePrice = salePriceVal,
                        notes = notes.trim(),
                        attachmentUri = attachmentUri,
                        attachmentType = attachmentType
                    )
                    onSave(updated)
                },
                modifier = Modifier.testTag("save_item_btn")
            ) {
                Text("حفظ الصنف")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        },
        shape = RoundedCornerShape(16.dp)
    )

    // Delete Attachment Confirmation Dialog
    if (showDeleteAttachmentDialog) {
        SimpleConfirmDialog(
            title = "تأكيد حذف مرفق الصنف",
            message = "هل أنت متأكد من حذف الملف المرفق مع هذا الصنف؟",
            confirmButtonText = "نعم، احذف المرفق",
            isDestructive = true,
            onConfirm = {
                attachmentUri = null
                attachmentType = null
                showDeleteAttachmentDialog = false
            },
            onDismiss = { showDeleteAttachmentDialog = false }
        )
    }

    // Add Category Quick Dialog
    if (showAddCategoryDialog) {
        var newCatName by remember { mutableStateOf("") }
        var catErr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddCategoryDialog = false },
            title = { Text("إضافة فئة / تصنيف جديد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newCatName,
                        onValueChange = { newCatName = it; catErr = "" },
                        label = { Text("اسم الفئة / التصنيف *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (catErr.isNotBlank()) {
                        Text(catErr, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newCatName.isBlank()) {
                            catErr = "يرجى إدخال اسم الفئة"
                            return@Button
                        }
                        onAddNewCategory(ItemCategory(name = newCatName.trim()))
                        category = newCatName.trim()
                        showAddCategoryDialog = false
                    }
                ) { Text("إضافة") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddCategoryDialog = false }) { Text("إلغاء") }
            }
        )
    }

    // Edit Unused Category Quick Dialog
    categoryToEdit?.let { cat ->
        var editedName by remember(cat) { mutableStateOf(cat.name) }
        var catEditErr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { categoryToEdit = null },
            title = { Text("تعديل اسم الفئة / التصنيف", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it; catEditErr = "" },
                        label = { Text("اسم الفئة الجديد *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (catEditErr.isNotBlank()) {
                        Text(catEditErr, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = editedName.trim()
                        if (trimmed.isBlank()) {
                            catEditErr = "يرجى إدخال اسم الفئة"
                            return@Button
                        }
                        viewModel.updateCategorySafely(cat, trimmed) { ok ->
                            if (ok && category == cat.name) {
                                category = trimmed
                            }
                            categoryToEdit = null
                        }
                    }
                ) { Text("حفظ التعديل") }
            },
            dismissButton = {
                OutlinedButton(onClick = { categoryToEdit = null }) { Text("إلغاء") }
            }
        )
    }

    // Confirm Delete Unused Category
    categoryToDelete?.let { cat ->
        SimpleConfirmDialog(
            title = "تأكيد حذف الفئة / التصنيف",
            message = "هل أنت متأكد من حذف الفئة \"${cat.name}\" نهائياً؟ لا توجد عليها أي أصناف أو عمليات.",
            confirmButtonText = "نعم، احذف الفئة",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteCategorySafely(cat) { ok ->
                    if (ok && category == cat.name) {
                        category = categoriesList.firstOrNull { it.id != cat.id }?.name ?: "عام"
                    }
                    categoryToDelete = null
                }
            },
            onDismiss = { categoryToDelete = null }
        )
    }

    // Category Edit/Delete Blocked Warning
    categoryBlockedWarning?.let { msg ->
        AlertDialog(
            onDismissRequest = { categoryBlockedWarning = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = WarningOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تعذر تعديل أو حذف الفئة", fontWeight = FontWeight.Bold)
                }
            },
            text = { Text(msg) },
            confirmButton = {
                Button(onClick = { categoryBlockedWarning = null }) { Text("فهمت ذلك") }
            }
        )
    }
}

/**
 * Manage Categories Dialog:
 * Supports Adding, Safe Editing (only when no items/vouchers exist), and Safe Deletion.
 */
@Composable
private fun ManageCategoriesDialog(
    categories: List<ItemCategory>,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showCreateDialog by remember { mutableStateOf(false) }
    var categoryToEdit by remember { mutableStateOf<ItemCategory?>(null) }
    var categoryToDelete by remember { mutableStateOf<ItemCategory?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("إدارة الفئات والتصنيفات", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                            onDenied = { errorMessage = "عذراً: هذا الجهاز فرعي ولا يملك صلاحية إضافة تصنيفات جديدة." }
                        ) {
                            showCreateDialog = true
                        }
                    },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .testTag("add_category_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة تصنيف جديد", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 380.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (errorMessage.isNotBlank()) {
                    Text(errorMessage, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                }

                if (categories.isEmpty()) {
                    Text(
                        text = "لا توجد فئات مسجلة حالياً. اضغط + لإضافة فئة جديدة.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    categories.forEach { cat ->
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cat.name,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f)
                                )
                                Row {
                                    IconButton(
                                        onClick = {
                                            viewModel.checkPermission(
                                                action = DeviceAction.EDIT_OR_DELETE,
                                                onDenied = { errorMessage = "عذراً: لا تملك صلاحية تعديل الفئة." }
                                            ) {
                                                coroutineScope.launch {
                                                    if (viewModel.checkCategoryHasOperations(cat.name)) {
                                                        errorMessage = "لا يمكن تعديل الفئة (${cat.name}) لوجود أصناف أو عمليات مرتبطة بها!"
                                                    } else {
                                                        errorMessage = ""
                                                        categoryToEdit = cat
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "تعديل التصنيف", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.checkPermission(
                                                action = DeviceAction.EDIT_OR_DELETE,
                                                onDenied = { errorMessage = "عذراً: لا تملك صلاحية حذف الفئة." }
                                            ) {
                                                coroutineScope.launch {
                                                    if (viewModel.checkCategoryHasOperations(cat.name)) {
                                                        errorMessage = "لا يمكن حذف الفئة (${cat.name}) لوجود أصناف أو عمليات مرتبطة بها!"
                                                    } else {
                                                        errorMessage = ""
                                                        categoryToDelete = cat
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "حذف التصنيف", tint = DangerRed, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("إغلاق") }
        },
        shape = RoundedCornerShape(16.dp)
    )

    if (showCreateDialog) {
        var newName by remember { mutableStateOf("") }
        var formErr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showCreateDialog = false },
            title = { Text("إضافة فئة / تصنيف جديد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it; formErr = "" },
                        label = { Text("اسم الفئة *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (formErr.isNotBlank()) Text(formErr, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = newName.trim()
                        if (trimmed.isBlank()) {
                            formErr = "يرجى إدخال اسم الفئة"
                            return@Button
                        }
                        viewModel.saveCategory(ItemCategory(name = trimmed)) {
                            showCreateDialog = false
                        }
                    }
                ) { Text("إضافة") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateDialog = false }) { Text("إلغاء") }
            }
        )
    }

    categoryToEdit?.let { cat ->
        var editedName by remember(cat) { mutableStateOf(cat.name) }
        var formErr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { categoryToEdit = null },
            title = { Text("تعديل الفئة / التصنيف", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it; formErr = "" },
                        label = { Text("اسم الفئة الجديد *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (formErr.isNotBlank()) Text(formErr, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = editedName.trim()
                        if (trimmed.isBlank()) {
                            formErr = "يرجى إدخال اسم الفئة"
                            return@Button
                        }
                        viewModel.updateCategorySafely(cat, trimmed) {
                            categoryToEdit = null
                        }
                    }
                ) { Text("حفظ التعديل") }
            },
            dismissButton = {
                OutlinedButton(onClick = { categoryToEdit = null }) { Text("إلغاء") }
            }
        )
    }

    categoryToDelete?.let { cat ->
        SimpleConfirmDialog(
            title = "تأكيد حذف الفئة / التصنيف",
            message = "هل أنت متأكد من حذف الفئة \"${cat.name}\" نهائياً؟ لا توجد عليها أي عمليات أو أصناف.",
            confirmButtonText = "نعم، احذف الفئة",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteCategorySafely(cat) {
                    categoryToDelete = null
                }
            },
            onDismiss = { categoryToDelete = null }
        )
    }
}

/**
 * Hierarchical Units Management Dialog:
 * Supports 3-level (Large -> Medium -> Small) and 2-level (Large -> Small) unit systems.
 * Medium & Small units are scoped to their parent Large unit, so identically-named child
 * units under different Large units can be independently edited or deleted when unused.
 */
@Composable
private fun ManageUnitsDialog(
    units: List<ItemUnit>,
    smallerUnits: List<SmallerUnit>,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showHierarchicalForm by remember { mutableStateOf(false) }
    var unitToEdit by remember { mutableStateOf<ItemUnit?>(null) }
    var unitToDelete by remember { mutableStateOf<ItemUnit?>(null) }

    var childFormParent by remember { mutableStateOf<ItemUnit?>(null) }
    var childFormLevel by remember { mutableStateOf("SMALL") }
    var childToEdit by remember { mutableStateOf<SmallerUnit?>(null) }
    var childToDelete by remember { mutableStateOf<SmallerUnit?>(null) }

    var errorMessage by remember { mutableStateOf("") }

    val legacyUnscopedChildren = remember(smallerUnits, units) {
        val unitIds = units.map { it.id }.toSet()
        val unitNames = units.map { it.name }.toSet()
        smallerUnits.filter { su ->
            (su.parentUnitId == 0L || su.parentUnitId !in unitIds) &&
                (su.parentUnitName.isBlank() || su.parentUnitName !in unitNames)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("إدارة وحدات القياس", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(
                        text = "نظام 3 وحدات (كبيرة/متوسطة/صغرى) أو وحدتين (كبيرة/صغرى)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                            onDenied = { errorMessage = "عذراً: هذا الجهاز فرعي ولا يملك صلاحية إضافة وحدات قياس جديدة." }
                        ) {
                            errorMessage = ""
                            unitToEdit = null
                            showHierarchicalForm = true
                        }
                    },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .testTag("add_unit_dialog_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة وحدة قياس", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (errorMessage.isNotBlank()) {
                    Surface(
                        color = DangerRed.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = errorMessage,
                            color = DangerRed,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 380.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    if (units.isEmpty()) {
                        Text(
                            text = "لا توجد وحدات قياس مضافة حالياً. اضغط + لإضافة نظام وحدات (وحدتين أو ثلاث وحدات).",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        units.forEach { u ->
                            val childrenForThisLarge = smallerUnits.filter { su ->
                                su.parentUnitId == u.id || (su.parentUnitId == 0L && su.parentUnitName == u.name)
                            }
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("manage_large_unit_card_${u.id}"),
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(u.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Surface(
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = (if (u.hasMediumUnit) SuccessGreen else MaterialTheme.colorScheme.primary).copy(alpha = 0.14f)
                                                ) {
                                                    Text(
                                                        text = if (u.hasMediumUnit) "3 وحدات" else "وحدتان",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = if (u.hasMediumUnit) SuccessGreen else MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.Bold,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                            val eqText = if (u.hasMediumUnit && u.mediumUnit.isNotBlank()) {
                                                "1 ${u.name} = ${formatCapacity(u.mediumUnitsCount)} ${u.mediumUnit} • 1 ${u.mediumUnit} = ${formatCapacity(u.smallUnitsPerMedium)} ${u.smallerUnit} (إجمالي ${formatCapacity(u.totalSmallPerLarge())})"
                                            } else {
                                                "1 ${u.name} = ${formatCapacity(u.smallerUnitsCount)} ${u.smallerUnit} • سعة الصغرى: ${formatCapacity(u.smallerUnitCapacity)}"
                                            }
                                            Text(
                                                text = eqText,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    viewModel.checkPermission(
                                                        action = DeviceAction.EDIT_OR_DELETE,
                                                        onDenied = { errorMessage = "عذراً: لا تملك صلاحية تعديل وحدات القياس." }
                                                    ) {
                                                        coroutineScope.launch {
                                                            if (viewModel.checkUnitHasTransactions(u.name)) {
                                                                errorMessage = "لا يمكن تعديل الوحدة الكبيرة (${u.name}) لأنها مستخدمة في أصناف أو عمليات مخزنية!"
                                                            } else {
                                                                errorMessage = ""
                                                                unitToEdit = u
                                                                showHierarchicalForm = true
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل الوحدة الكبيرة", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    viewModel.checkPermission(
                                                        action = DeviceAction.EDIT_OR_DELETE,
                                                        onDenied = { errorMessage = "عذراً: لا تملك صلاحية حذف وحدات القياس." }
                                                    ) {
                                                        coroutineScope.launch {
                                                            if (viewModel.checkUnitHasTransactions(u.name)) {
                                                                errorMessage = "لا يمكن حذف الوحدة الكبيرة (${u.name}) لأنها مستخدمة في أصناف أو عمليات مخزنية!"
                                                            } else {
                                                                errorMessage = ""
                                                                unitToDelete = u
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف الوحدة الكبيرة", tint = DangerRed, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }

                                    // Child Medium & Small Units scoped to this Large unit
                                    if (childrenForThisLarge.isNotEmpty()) {
                                        HorizontalDivider()
                                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                            childrenForThisLarge.forEach { child ->
                                                val isMed = child.unitLevel == "MEDIUM"
                                                Surface(
                                                    shape = RoundedCornerShape(8.dp),
                                                    color = MaterialTheme.colorScheme.surface,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .testTag("scoped_child_unit_${child.id}")
                                                ) {
                                                    Row(
                                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        horizontalArrangement = Arrangement.SpaceBetween
                                                    ) {
                                                        Column(modifier = Modifier.weight(1f)) {
                                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                                Surface(
                                                                    shape = RoundedCornerShape(4.dp),
                                                                    color = (if (isMed) WarningOrange else MaterialTheme.colorScheme.secondary).copy(alpha = 0.14f)
                                                                ) {
                                                                    Text(
                                                                        text = if (isMed) "متوسطة" else "صغرى",
                                                                        style = MaterialTheme.typography.labelSmall,
                                                                        fontSize = 10.sp,
                                                                        color = if (isMed) WarningOrange else MaterialTheme.colorScheme.secondary,
                                                                        modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                                                    )
                                                                }
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text(
                                                                    text = child.name,
                                                                    fontWeight = FontWeight.Bold,
                                                                    style = MaterialTheme.typography.bodySmall
                                                                )
                                                            }
                                                            val subDesc = if (isMed) {
                                                                "تحتوي ${u.name} على ${formatCapacity(child.countInParent)} ${child.name}"
                                                            } else if (u.hasMediumUnit && u.mediumUnit.isNotBlank()) {
                                                                "تحتوي ${child.mediumUnitName.ifBlank { u.mediumUnit }} على ${formatCapacity(child.countInParent)} ${child.name} • السعة: ${formatCapacity(child.capacity)}"
                                                            } else {
                                                                "تحتوي ${u.name} على ${formatCapacity(child.countInParent)} ${child.name} • السعة: ${formatCapacity(child.capacity)}"
                                                            }
                                                            Text(
                                                                text = subDesc,
                                                                style = MaterialTheme.typography.labelSmall,
                                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                                            )
                                                        }

                                                        Row {
                                                            IconButton(
                                                                onClick = {
                                                                    viewModel.checkPermission(
                                                                        action = DeviceAction.EDIT_OR_DELETE,
                                                                        onDenied = { errorMessage = "عذراً: لا تملك صلاحية تعديل هذه الوحدة." }
                                                                    ) {
                                                                        coroutineScope.launch {
                                                                            if (viewModel.checkChildUnitHasOperations(child)) {
                                                                                errorMessage = "لا يمكن تعديل (${child.name}) التابعة لـ (${u.name}) لوجود أصناف أو عمليات عليها!"
                                                                            } else {
                                                                                errorMessage = ""
                                                                                childToEdit = child
                                                                            }
                                                                        }
                                                                    }
                                                                },
                                                                modifier = Modifier.size(34.dp)
                                                            ) {
                                                                Icon(Icons.Default.Edit, contentDescription = "تعديل الوحدة التابعة", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                            }
                                                            IconButton(
                                                                onClick = {
                                                                    viewModel.checkPermission(
                                                                        action = DeviceAction.EDIT_OR_DELETE,
                                                                        onDenied = { errorMessage = "عذراً: لا تملك صلاحية حذف هذه الوحدة." }
                                                                    ) {
                                                                        coroutineScope.launch {
                                                                            if (viewModel.checkChildUnitHasOperations(child)) {
                                                                                errorMessage = "لا يمكن حذف (${child.name}) التابعة لـ (${u.name}) لوجود أصناف أو عمليات عليها!"
                                                                            } else {
                                                                                errorMessage = ""
                                                                                childToDelete = child
                                                                            }
                                                                        }
                                                                    }
                                                                },
                                                                modifier = Modifier.size(34.dp)
                                                            ) {
                                                                Icon(Icons.Default.Delete, contentDescription = "حذف الوحدة التابعة", tint = DangerRed, modifier = Modifier.size(16.dp))
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    // Quick Add scoped Medium / Small unit under THIS Large unit
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.checkPermission(
                                                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                                                    onDenied = { errorMessage = "عذراً: لا تملك صلاحية الإضافة." }
                                                ) {
                                                    errorMessage = ""
                                                    childFormParent = u
                                                    childFormLevel = "MEDIUM"
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("+ متوسطة", fontSize = 11.sp)
                                        }
                                        OutlinedButton(
                                            onClick = {
                                                viewModel.checkPermission(
                                                    action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                                                    onDenied = { errorMessage = "عذراً: لا تملك صلاحية الإضافة." }
                                                ) {
                                                    errorMessage = ""
                                                    childFormParent = u
                                                    childFormLevel = "SMALL"
                                                }
                                            },
                                            modifier = Modifier.weight(1f),
                                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                                            shape = RoundedCornerShape(8.dp)
                                        ) {
                                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(14.dp))
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("+ صغرى", fontSize = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Fallback section only if legacy unscoped smaller units exist
                    if (legacyUnscopedChildren.isNotEmpty()) {
                        Text(
                            text = "وحدات صغرى مستقلة إضافية (${legacyUnscopedChildren.size})",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        legacyUnscopedChildren.forEach { su ->
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(su.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                                        Text("السعة: ${formatCapacity(su.capacity)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    }
                                    Row {
                                        IconButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    if (viewModel.checkSmallerUnitHasOperations(su.name)) {
                                                        errorMessage = "لا يمكن تعديل (${su.name}) لوجود عمليات مرتبطة بها!"
                                                    } else {
                                                        errorMessage = ""
                                                        childToEdit = su
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        }
                                        IconButton(
                                            onClick = {
                                                coroutineScope.launch {
                                                    val usedOps = viewModel.checkSmallerUnitHasOperations(su.name)
                                                    val usedPkg = viewModel.checkSmallerUnitUsedByPackageUnit(su.name)
                                                    if (usedOps || usedPkg) {
                                                        errorMessage = "لا يمكن حذف (${su.name}) لوجود عمليات أو وحدات مرتبطة بها!"
                                                    } else {
                                                        errorMessage = ""
                                                        childToDelete = su
                                                    }
                                                }
                                            },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "حذف", tint = DangerRed, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) { Text("إغلاق") }
        },
        shape = RoundedCornerShape(16.dp)
    )

    // Add / Edit Large Unit Hierarchy (2-level OR 3-level)
    if (showHierarchicalForm) {
        HierarchicalUnitFormDialog(
            initialUnit = unitToEdit,
            onDismiss = {
                showHierarchicalForm = false
                unitToEdit = null
            },
            onSave = { largeName, hasMed, medName, medPerLarge, smallName, smallInParent, smallCap ->
                viewModel.saveHierarchicalUnit(
                    existingUnit = unitToEdit,
                    largeName = largeName,
                    hasMediumUnit = hasMed,
                    mediumName = medName,
                    mediumPerLarge = medPerLarge,
                    smallName = smallName,
                    smallCountInParent = smallInParent,
                    smallCapacity = smallCap
                ) { ok, err ->
                    if (ok) {
                        showHierarchicalForm = false
                        unitToEdit = null
                    } else if (!err.isNullOrBlank()) {
                        errorMessage = err
                    }
                }
            }
        )
    }

    // Add Scoped Child Unit (Medium or Small) under selected Large Unit
    childFormParent?.let { parent ->
        ChildUnitFormDialog(
            parentUnit = parent,
            unitLevel = childFormLevel,
            initialChild = null,
            onDismiss = { childFormParent = null },
            onSave = { nameVal, countVal, capVal ->
                viewModel.addChildUnitToLargeUnit(
                    parentUnit = parent,
                    unitLevel = childFormLevel,
                    name = nameVal,
                    countInParent = countVal,
                    capacity = capVal,
                    mediumUnitName = if (childFormLevel == "SMALL" && parent.hasMediumUnit) parent.mediumUnit else ""
                )
                childFormParent = null
            }
        )
    }

    // Edit Scoped Child Unit (Medium or Small)
    childToEdit?.let { targetChild ->
        val parent = units.find { it.id == targetChild.parentUnitId || (targetChild.parentUnitId == 0L && it.name == targetChild.parentUnitName) }
        ChildUnitFormDialog(
            parentUnit = parent,
            unitLevel = targetChild.unitLevel,
            initialChild = targetChild,
            onDismiss = { childToEdit = null },
            onSave = { nameVal, countVal, capVal ->
                viewModel.updateChildUnitSafely(targetChild, nameVal, countVal, capVal) { ok ->
                    if (!ok) {
                        errorMessage = "لا يمكن تعديل (${targetChild.name}) لوجود أصناف أو عمليات مرتبطة بها!"
                    }
                    childToEdit = null
                }
            }
        )
    }

    // Confirm Delete Large Unit
    unitToDelete?.let { target ->
        SimpleConfirmDialog(
            title = "تأكيد حذف وحدة القياس",
            message = "هل أنت متأكد من حذف الوحدة الكبيرة \"${target.name}\" وكافة وحداتها التابعة غير المستخدمة؟",
            confirmButtonText = "نعم، احذف الوحدة",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteUnit(target)
                unitToDelete = null
            },
            onDismiss = { unitToDelete = null }
        )
    }

    // Confirm Delete Scoped Child Unit
    childToDelete?.let { target ->
        val label = if (target.unitLevel == "MEDIUM") "الوحدة المتوسطة" else "الوحدة الصغرى"
        SimpleConfirmDialog(
            title = "تأكيد حذف $label",
            message = "هل أنت متأكد من حذف \"${target.name}\" التابعة لـ \"${target.parentUnitName.ifBlank { "الوحدة" }}\" نهائياً؟",
            confirmButtonText = "نعم، احذف",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteChildUnitSafely(target) { ok ->
                    if (!ok) {
                        errorMessage = "لا يمكن حذف (${target.name}) لوجود أصناف أو عمليات مرتبطة بها!"
                    }
                    childToDelete = null
                }
            },
            onDismiss = { childToDelete = null }
        )
    }
}

@Composable
private fun HierarchicalUnitFormDialog(
    initialUnit: ItemUnit?,
    onDismiss: () -> Unit,
    onSave: (
        largeName: String,
        hasMediumUnit: Boolean,
        mediumName: String,
        mediumPerLarge: Double,
        smallName: String,
        smallCountInParent: Double,
        smallCapacity: Double
    ) -> Unit
) {
    var hasMedium by remember { mutableStateOf(initialUnit?.hasMediumUnit ?: true) }
    var largeName by remember { mutableStateOf(initialUnit?.name ?: "") }
    var mediumName by remember { mutableStateOf(initialUnit?.mediumUnit ?: "") }
    var mediumCountText by remember {
        mutableStateOf(
            if (initialUnit != null && initialUnit.hasMediumUnit && initialUnit.mediumUnitsCount > 0.0)
                formatCapacity(initialUnit.mediumUnitsCount)
            else "4"
        )
    }
    var smallName by remember { mutableStateOf(initialUnit?.smallerUnit ?: "حبة") }
    var smallCountText by remember {
        mutableStateOf(
            when {
                initialUnit == null -> "12"
                initialUnit.hasMediumUnit -> formatCapacity(initialUnit.smallUnitsPerMedium)
                else -> formatCapacity(initialUnit.smallerUnitsCount)
            }
        )
    }
    var capacityText by remember {
        mutableStateOf(formatCapacity(initialUnit?.smallerUnitCapacity ?: 1.0))
    }
    var error by remember { mutableStateOf("") }

    val mCountPreview = if (hasMedium) (mediumCountText.toDoubleOrNull() ?: 0.0) else 1.0
    val sCountPreview = smallCountText.toDoubleOrNull() ?: 0.0
    val totalPreview = mCountPreview * sCountPreview

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialUnit == null) "إضافة وحدة قياس جديدة" else "تعديل وحدة القياس",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "اختر مستوى الوحدات:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = hasMedium,
                        onClick = { hasMedium = true; error = "" },
                        label = { Text("3 وحدات (كبيرة/متوسطة/صغرى)", fontSize = 11.sp) },
                        modifier = Modifier.testTag("unit_mode_3_levels")
                    )
                    FilterChip(
                        selected = !hasMedium,
                        onClick = { hasMedium = false; error = "" },
                        label = { Text("وحدتان (كبيرة/صغرى)", fontSize = 11.sp) },
                        modifier = Modifier.testTag("unit_mode_2_levels")
                    )
                }

                OutlinedTextField(
                    value = largeName,
                    onValueChange = { largeName = it; error = "" },
                    label = { Text("اسم الوحدة الكبيرة (مثال: كرتون، صندوق) *") },
                    modifier = Modifier.fillMaxWidth().testTag("unit_large_name_input"),
                    singleLine = true
                )

                if (hasMedium) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = mediumName,
                            onValueChange = { mediumName = it; error = "" },
                            label = { Text("الوحدة المتوسطة (مثال: باكت) *") },
                            modifier = Modifier.weight(1.3f).testTag("unit_medium_name_input"),
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = mediumCountText,
                            onValueChange = {
                                mediumCountText = it.filter { ch -> ch.isDigit() || ch == '.' }
                                error = ""
                            },
                            label = { Text("عددها بالكبيرة *") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            modifier = Modifier.weight(1f).testTag("unit_medium_count_input"),
                            singleLine = true
                        )
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = smallName,
                        onValueChange = { smallName = it; error = "" },
                        label = { Text("الوحدة الصغرى (مثال: حبة) *") },
                        modifier = Modifier.weight(1.3f).testTag("unit_small_name_input"),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = smallCountText,
                        onValueChange = {
                            smallCountText = it.filter { ch -> ch.isDigit() || ch == '.' }
                            error = ""
                        },
                        label = {
                            Text(if (hasMedium) "عددها بالمتوسطة *" else "عددها بالكبيرة *")
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f).testTag("unit_small_count_input"),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = capacityText,
                    onValueChange = {
                        capacityText = it.filter { ch -> ch.isDigit() || ch == '.' }
                        error = ""
                    },
                    label = { Text("سعة الوحدة الصغرى (${smallName.ifBlank { "حبة" }}) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("unit_small_capacity_input"),
                    singleLine = true
                )

                if (largeName.isNotBlank() && smallName.isNotBlank() && totalPreview > 0.0) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        val previewMsg = if (hasMedium && mediumName.isNotBlank()) {
                            "1 ${largeName.trim()} = ${formatCapacity(mCountPreview)} ${mediumName.trim()} • 1 ${mediumName.trim()} = ${formatCapacity(sCountPreview)} ${smallName.trim()} (إجمالي ${formatCapacity(totalPreview)} ${smallName.trim()})"
                        } else {
                            "1 ${largeName.trim()} = ${formatCapacity(sCountPreview)} ${smallName.trim()}"
                        }
                        Text(
                            text = previewMsg,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(8.dp)
                        )
                    }
                }

                if (error.isNotBlank()) {
                    Text(error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (largeName.isBlank()) {
                        error = "يرجى إدخال اسم الوحدة الكبيرة"
                        return@Button
                    }
                    val medVal = if (hasMedium) (mediumCountText.toDoubleOrNull() ?: 0.0) else 0.0
                    if (hasMedium && mediumName.isBlank()) {
                        error = "يرجى إدخال اسم الوحدة المتوسطة"
                        return@Button
                    }
                    if (hasMedium && medVal <= 0.0) {
                        error = "يرجى إدخال عدد الوحدات المتوسطة في الكبيرة"
                        return@Button
                    }
                    if (smallName.isBlank()) {
                        error = "يرجى إدخال اسم الوحدة الصغرى"
                        return@Button
                    }
                    val smallCnt = smallCountText.toDoubleOrNull() ?: 0.0
                    if (smallCnt <= 0.0) {
                        error = "يرجى إدخال عدد الوحدات الصغرى بشكل صحيح"
                        return@Button
                    }
                    val capVal = capacityText.toDoubleOrNull() ?: 0.0
                    if (capVal <= 0.0) {
                        error = "يرجى إدخال سعة الوحدة الصغرى"
                        return@Button
                    }
                    onSave(largeName.trim(), hasMedium, mediumName.trim(), medVal, smallName.trim(), smallCnt, capVal)
                },
                modifier = Modifier.testTag("save_hierarchical_unit_btn")
            ) { Text("حفظ") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun ChildUnitFormDialog(
    parentUnit: ItemUnit?,
    unitLevel: String,
    initialChild: SmallerUnit?,
    onDismiss: () -> Unit,
    onSave: (name: String, countInParent: Double, capacity: Double) -> Unit
) {
    val isMedium = unitLevel == "MEDIUM"
    var name by remember { mutableStateOf(initialChild?.name ?: "") }
    var countText by remember { mutableStateOf(formatCapacity(initialChild?.countInParent ?: if (isMedium) 4.0 else 12.0)) }
    var capacityText by remember { mutableStateOf(formatCapacity(initialChild?.capacity ?: 1.0)) }
    var error by remember { mutableStateOf("") }

    val parentLabel = if (isMedium) {
        parentUnit?.name ?: initialChild?.parentUnitName.orEmpty().ifBlank { "الكبيرة" }
    } else if (parentUnit?.hasMediumUnit == true && parentUnit.mediumUnit.isNotBlank()) {
        parentUnit.mediumUnit
    } else {
        parentUnit?.name ?: initialChild?.parentUnitName.orEmpty().ifBlank { "الكبيرة" }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = when {
                    initialChild == null && isMedium -> "إضافة وحدة متوسطة تابعة لـ (${parentUnit?.name ?: ""})"
                    initialChild == null -> "إضافة وحدة صغرى تابعة لـ (${parentUnit?.name ?: ""})"
                    isMedium -> "تعديل الوحدة المتوسطة"
                    else -> "تعديل الوحدة الصغرى"
                },
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text(if (isMedium) "اسم الوحدة المتوسطة (مثال: باكت، شد) *" else "اسم الوحدة الصغرى (مثال: حبة، قطعة) *") },
                    modifier = Modifier.fillMaxWidth().testTag("child_unit_name_input"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = countText,
                    onValueChange = { countText = it.filter { ch -> ch.isDigit() || ch == '.' }; error = "" },
                    label = { Text("كم تحتوي ($parentLabel) منها؟ *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().testTag("child_unit_count_input"),
                    singleLine = true
                )
                if (!isMedium) {
                    OutlinedTextField(
                        value = capacityText,
                        onValueChange = { capacityText = it.filter { ch -> ch.isDigit() || ch == '.' }; error = "" },
                        label = { Text("سعة الوحدة الصغرى *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth().testTag("child_unit_capacity_input"),
                        singleLine = true
                    )
                }
                if (error.isNotBlank()) {
                    Text(error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    val cnt = countText.toDoubleOrNull() ?: 0.0
                    val cap = if (isMedium) 1.0 else (capacityText.toDoubleOrNull() ?: 0.0)
                    if (trimmed.isBlank()) {
                        error = "يرجى إدخال الاسم"
                        return@Button
                    }
                    if (cnt <= 0.0) {
                        error = "يرجى إدخال العدد بشكل صحيح أكبر من صفر"
                        return@Button
                    }
                    if (!isMedium && cap <= 0.0) {
                        error = "يرجى إدخال سعة صالحة أكبر من صفر"
                        return@Button
                    }
                    onSave(trimmed, cnt, cap)
                },
                modifier = Modifier.testTag("save_child_unit_btn")
            ) { Text("حفظ") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

private fun formatHierarchyStock(
    totalSmall: Double,
    largeUnit: String,
    hasMediumUnit: Boolean,
    mediumUnit: String,
    mediumPerLarge: Double,
    smallPerMedium: Double,
    totalSmallPerLarge: Double,
    smallUnit: String
): String {
    val totalInt = totalSmall.toInt()
    if (hasMediumUnit && mediumUnit.isNotBlank() && totalSmallPerLarge > 1.0 && totalInt > 0) {
        val largePer = totalSmallPerLarge.toInt().coerceAtLeast(1)
        val medPer = smallPerMedium.toInt().coerceAtLeast(1)
        val largeQty = totalInt / largePer
        val remAfterLarge = totalInt % largePer
        val medQty = remAfterLarge / medPer
        val smallQty = remAfterLarge % medPer
        val parts = buildList {
            if (largeQty > 0) add("$largeQty $largeUnit")
            if (medQty > 0) add("$medQty $mediumUnit")
            if (smallQty > 0) add("$smallQty $smallUnit")
        }
        if (parts.isNotEmpty()) {
            return if (parts.size == 1 && largeQty == 0 && medQty == 0) parts.first()
            else "${parts.joinToString(" + ")} ($totalInt $smallUnit)"
        }
    } else if (totalSmallPerLarge > 1.0 && totalSmall >= totalSmallPerLarge) {
        val boxes = (totalSmall / totalSmallPerLarge).toInt()
        val remPieces = (totalSmall % totalSmallPerLarge).toInt()
        return if (remPieces > 0) "$boxes $largeUnit + $remPieces $smallUnit"
        else "$boxes $largeUnit ($totalInt $smallUnit)"
    }
    return "$totalInt $smallUnit"
}

private fun formatCapacity(value: Double): String {
    return if (value % 1.0 == 0.0) value.toInt().toString() else value.toString()
}

private fun copyItemUriToInternalStorage(context: Context, uri: Uri, fileName: String): String {
    val attachmentsDir = File(context.filesDir, "item_attachments")
    if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
    val destFile = File(attachmentsDir, fileName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(destFile).use { output ->
            input.copyTo(output)
        }
    }
    return destFile.absolutePath
}
