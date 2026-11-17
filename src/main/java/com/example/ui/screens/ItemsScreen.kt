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
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
                item.category.contains(searchQuery, ignoreCase = true)

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
                                    action = DeviceAction.EDIT_OR_DELETE,
                                    onDenied = { restrictedActionName = "تعديل بيانات الصنف" }
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
                                    action = DeviceAction.EDIT_OR_DELETE,
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
                            if (item.unitsPerPackage > 1.0) {
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
                    val formatted = if (item.unitsPerPackage > 1.0 && stockInfo.currentStock >= item.unitsPerPackage) {
                        val boxes = (stockInfo.currentStock / item.unitsPerPackage).toInt()
                        val remPieces = (stockInfo.currentStock % item.unitsPerPackage).toInt()
                        if (remPieces > 0) "$boxes ${item.packageUnit} + $remPieces ${item.unit}"
                        else "$boxes ${item.packageUnit} (${stockInfo.currentStock.toInt()} ${item.unit})"
                    } else {
                        "${stockInfo.currentStock.toInt()} ${item.unit}"
                    }
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
                    Text("${item.minStockLevel.toInt()} ${item.unit}", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
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
    viewModel: InventoryViewModel,
    onAddNewCategory: (ItemCategory) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var name by remember { mutableStateOf(initialItem?.name ?: "") }
    var barcode by remember { mutableStateOf(initialItem?.barcode ?: "") }

    val packageUnitOptions = remember(unitsList) {
        unitsList.map { it.name }.distinct().ifEmpty { listOf("صندوق") }
    }

    val defaultPackageName = remember(initialItem, unitsList) {
        initialItem?.packageUnit
            ?: unitsList.firstOrNull { it.name == "صندوق" }?.name
            ?: unitsList.firstOrNull()?.name
            ?: "صندوق"
    }

    var packageUnit by remember { mutableStateOf(defaultPackageName) }

    val matchingPackageUnits = remember(unitsList, packageUnit) {
        unitsList.filter { it.name == packageUnit }
    }

    val allowedSmallerUnits = remember(matchingPackageUnits, smallerUnitsList, initialItem) {
        val fromPackage = matchingPackageUnits.map { it.smallerUnit }.distinct()
        when {
            fromPackage.isNotEmpty() -> fromPackage
            initialItem != null && initialItem.unit.isNotBlank() -> listOf(initialItem.unit)
            smallerUnitsList.isNotEmpty() -> smallerUnitsList.map { it.name }.distinct()
            else -> listOf("حبة")
        }
    }

    val initialPackageMatch = remember(unitsList, defaultPackageName, initialItem) {
        unitsList.find {
            it.name == defaultPackageName &&
                (initialItem == null || it.smallerUnit == initialItem.unit)
        } ?: unitsList.find { it.name == defaultPackageName }
    }

    var unit by remember {
        mutableStateOf(
            initialItem?.unit
                ?: initialPackageMatch?.smallerUnit
                ?: allowedSmallerUnits.firstOrNull()
                ?: "حبة"
        )
    }

    var unitsPerPackageText by remember {
        mutableStateOf(
            formatCapacity(
                initialItem?.unitsPerPackage ?: initialPackageMatch?.smallerUnitsCount ?: 1.0
            )
        )
    }

    var smallerUnitCapacityText by remember {
        mutableStateOf(
            formatCapacity(
                initialItem?.smallerUnitCapacity
                    ?: smallerUnitsList.find { it.name == unit }?.capacity
                    ?: initialPackageMatch?.smallerUnitCapacity
                    ?: 1.0
            )
        )
    }

    var openingStockText by remember { mutableStateOf(initialItem?.openingStock?.toInt()?.toString() ?: "0") }
    var hasSerialNumbers by remember { mutableStateOf(initialItem?.hasSerialNumbers ?: false) }

    var category by remember { mutableStateOf(initialItem?.category ?: categoriesList.firstOrNull()?.name ?: "عام") }
    var minStock by remember { mutableStateOf(initialItem?.minStockLevel?.toInt()?.toString() ?: "5") }
    var purchasePrice by remember { mutableStateOf(initialItem?.purchasePrice?.toString() ?: "0.0") }
    var notes by remember { mutableStateOf(initialItem?.notes ?: "") }

    // Attachment
    var attachmentUri by remember { mutableStateOf(initialItem?.attachmentUri) }
    var attachmentType by remember { mutableStateOf(initialItem?.attachmentType) }
    var showDeleteAttachmentDialog by remember { mutableStateOf(false) }

    var errorMessage by remember { mutableStateOf("") }

    // Dropdown expansion states
    var unitDropdownExpanded by remember { mutableStateOf(false) }
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
                                text = "ملاحظة: هذا الصنف تمت عليه حركات مخزنية، لذا تم قفل الاسم والباركود والوحدات والكمية الافتتاحية حمايةً للبيانات. يُسمح بتعديل حد الطلب وسعر الشراء والأرقام التسلسلية والملاحظات والمرفق.",
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

                // Package Unit & Filtered Smaller Unit Row (No + Add Unit button!)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Package Unit Dropdown
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
                                label = { Text("وحدة العبوة") },
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
                                                val matched = unitsList.filter { it.name == pkgName }
                                                val primary = matched.firstOrNull()
                                                if (primary != null) {
                                                    unit = primary.smallerUnit
                                                    unitsPerPackageText = formatCapacity(primary.smallerUnitsCount)
                                                    val suCap = smallerUnitsList.find { it.name == primary.smallerUnit }?.capacity
                                                    smallerUnitCapacityText = formatCapacity(suCap ?: primary.smallerUnitCapacity)
                                                }
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Filtered Smaller Unit Dropdown (shows ONLY smaller units belonging to selected packageUnit)
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
                                label = { Text("الوحدة الصغرى التابعة") },
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
                                                val exactMatch = unitsList.find {
                                                    it.name == packageUnit && it.smallerUnit == smallerOpt
                                                }
                                                if (exactMatch != null) {
                                                    unitsPerPackageText = formatCapacity(exactMatch.smallerUnitsCount)
                                                    val suCap = smallerUnitsList.find { it.name == smallerOpt }?.capacity
                                                    smallerUnitCapacityText = formatCapacity(suCap ?: exactMatch.smallerUnitCapacity)
                                                } else {
                                                    val su = smallerUnitsList.find { it.name == smallerOpt }
                                                    if (su != null) {
                                                        smallerUnitCapacityText = formatCapacity(su.capacity)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                }
                            }
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

                // Opening Stock field explicitly in smaller unit (حبة / $unit)
                OutlinedTextField(
                    value = openingStockText,
                    onValueChange = {
                        openingStockText = it.filter { ch -> ch.isDigit() }
                    },
                    readOnly = hasTransactions,
                    enabled = !hasTransactions,
                    label = { Text("الكمية الافتتاحية للمخزون (بالوحدة الصغرى - بالحبة / $unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("item_opening_stock_input"),
                    singleLine = true
                )

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
                    val capacity = if (packageUnit != unit) {
                        (unitsPerPackageText.toDoubleOrNull() ?: 1.0).coerceAtLeast(1.0)
                    } else 1.0
                    val smallerCap = (smallerUnitCapacityText.toDoubleOrNull() ?: 1.0).coerceAtLeast(0.01)

                    val opening = openingStockText.toDoubleOrNull() ?: 0.0
                    val minStockVal = minStock.toDoubleOrNull() ?: 5.0
                    val priceVal = purchasePrice.toDoubleOrNull() ?: 0.0

                    val updated = (initialItem ?: Item(name = name)).copy(
                        name = name.trim(),
                        barcode = barcode.trim(),
                        unit = unit.trim(),
                        packageUnit = packageUnit.trim(),
                        unitsPerPackage = capacity,
                        smallerUnitCapacity = smallerCap,
                        openingStock = opening,
                        hasSerialNumbers = hasSerialNumbers,
                        category = category.trim().ifBlank { "عام" },
                        minStockLevel = minStockVal,
                        purchasePrice = priceVal,
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
 * Manage Units Dialog:
 * Tab 0: Package Units (Add / Safe Edit / Safe Delete) linked to DB Smaller Units.
 * Tab 1: Smaller Units (Add / Safe Edit / Safe Delete).
 */
@Composable
private fun ManageUnitsDialog(
    units: List<ItemUnit>,
    smallerUnits: List<SmallerUnit>,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedTab by remember { mutableIntStateOf(0) }
    var showPackageUnitForm by remember { mutableStateOf(false) }
    var packageUnitToEdit by remember { mutableStateOf<ItemUnit?>(null) }
    var packageUnitToDelete by remember { mutableStateOf<ItemUnit?>(null) }

    var showSmallerUnitForm by remember { mutableStateOf(false) }
    var smallerUnitToEdit by remember { mutableStateOf<SmallerUnit?>(null) }
    var smallerUnitToDelete by remember { mutableStateOf<SmallerUnit?>(null) }

    var errorMessage by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("إدارة وحدات القياس", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                IconButton(
                    onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.ADD_OR_MANAGE_CATALOG,
                            onDenied = { errorMessage = "عذراً: هذا الجهاز فرعي ولا يملك صلاحية إضافة وحدات قياس جديدة." }
                        ) {
                            errorMessage = ""
                            if (selectedTab == 0) {
                                packageUnitToEdit = null
                                showPackageUnitForm = true
                            } else {
                                smallerUnitToEdit = null
                                showSmallerUnitForm = true
                            }
                        }
                    },
                    modifier = Modifier
                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        .testTag("add_unit_dialog_btn")
                ) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة وحدة جديدة", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0; errorMessage = "" },
                        text = { Text("وحدات العبوات (${units.size})", fontSize = 12.sp) }
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1; errorMessage = "" },
                        text = { Text("الوحدات الصغرى (${smallerUnits.size})", fontSize = 12.sp) }
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                if (errorMessage.isNotBlank()) {
                    Text(errorMessage, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (selectedTab == 0) {
                        if (units.isEmpty()) {
                            Text(
                                text = "لا توجد وحدات عبوات مضافة حالياً. اضغط + لإضافة وحدة.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            units.forEach { u ->
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
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(u.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "تحتوي ${formatCapacity(u.smallerUnitsCount)} ${u.smallerUnit} • سعة الوحدة الصغرى: ${formatCapacity(u.smallerUnitCapacity)}",
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
                                                                errorMessage = "لا يمكن تعديل وحدة (${u.name}) لأنها مستخدمة في أصناف أو حركات توريد وصرف!"
                                                            } else {
                                                                errorMessage = ""
                                                                packageUnitToEdit = u
                                                                showPackageUnitForm = true
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل الوحدة", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    viewModel.checkPermission(
                                                        action = DeviceAction.EDIT_OR_DELETE,
                                                        onDenied = { errorMessage = "عذراً: لا تملك صلاحية حذف وحدات القياس." }
                                                    ) {
                                                        coroutineScope.launch {
                                                            if (viewModel.checkUnitHasTransactions(u.name)) {
                                                                errorMessage = "لا يمكن حذف وحدة (${u.name}) لأنها مستخدمة في أصناف أو حركات توريد وصرف!"
                                                            } else {
                                                                errorMessage = ""
                                                                packageUnitToDelete = u
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف الوحدة", tint = DangerRed, modifier = Modifier.size(18.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        if (smallerUnits.isEmpty()) {
                            Text(
                                text = "لا توجد وحدات صغرى مضافة حالياً. اضغط + لإضافة وحدة صغرى.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            smallerUnits.forEach { su ->
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
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(su.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                text = "سعة الوحدة الصغرى: ${formatCapacity(su.capacity)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row {
                                            IconButton(
                                                onClick = {
                                                    viewModel.checkPermission(
                                                        action = DeviceAction.EDIT_OR_DELETE,
                                                        onDenied = { errorMessage = "عذراً: لا تملك صلاحية تعديل الوحدة الصغرى." }
                                                    ) {
                                                        coroutineScope.launch {
                                                            if (viewModel.checkSmallerUnitHasOperations(su.name)) {
                                                                errorMessage = "لا يمكن تعديل الوحدة الصغرى (${su.name}) لأنها مستخدمة في أصناف أو عمليات مخزنية!"
                                                            } else {
                                                                errorMessage = ""
                                                                smallerUnitToEdit = su
                                                                showSmallerUnitForm = true
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل الوحدة الصغرى", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                            }
                                            IconButton(
                                                onClick = {
                                                    viewModel.checkPermission(
                                                        action = DeviceAction.EDIT_OR_DELETE,
                                                        onDenied = { errorMessage = "عذراً: لا تملك صلاحية حذف الوحدة الصغرى." }
                                                    ) {
                                                        coroutineScope.launch {
                                                            val usedInOps = viewModel.checkSmallerUnitHasOperations(su.name)
                                                            val usedInPkg = viewModel.checkSmallerUnitUsedByPackageUnit(su.name)
                                                            if (usedInOps || usedInPkg) {
                                                                errorMessage = "لا يمكن حذف الوحدة الصغرى (${su.name}) لأنها مرتبطة بوحدات عبوة أو عمليات سابقة!"
                                                            } else {
                                                                errorMessage = ""
                                                                smallerUnitToDelete = su
                                                            }
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "حذف الوحدة الصغرى", tint = DangerRed, modifier = Modifier.size(18.dp))
                                            }
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

    // Add / Edit Package Unit Dialog (supports selecting, adding, editing & deleting SmallerUnit)
    if (showPackageUnitForm) {
        PackageUnitFormDialog(
            initialUnit = packageUnitToEdit,
            smallerUnits = smallerUnits,
            viewModel = viewModel,
            onDismiss = {
                showPackageUnitForm = false
                packageUnitToEdit = null
            },
            onSave = { unitEntity ->
                if (unitEntity.id == 0L) {
                    viewModel.saveUnit(unitEntity)
                } else {
                    viewModel.updateUnitSafely(unitEntity)
                }
                showPackageUnitForm = false
                packageUnitToEdit = null
            }
        )
    }

    // Add / Edit Smaller Unit Dialog
    if (showSmallerUnitForm) {
        SmallerUnitFormDialog(
            initialUnit = smallerUnitToEdit,
            onDismiss = {
                showSmallerUnitForm = false
                smallerUnitToEdit = null
            },
            onSave = { nameVal, capVal ->
                val target = smallerUnitToEdit
                if (target == null) {
                    viewModel.saveSmallerUnit(SmallerUnit(name = nameVal, capacity = capVal))
                } else {
                    viewModel.updateSmallerUnitSafely(target, nameVal, capVal)
                }
                showSmallerUnitForm = false
                smallerUnitToEdit = null
            }
        )
    }

    // Confirm Delete Package Unit Dialog
    packageUnitToDelete?.let { target ->
        SimpleConfirmDialog(
            title = "تأكيد حذف وحدة العبوة",
            message = "هل أنت متأكد من حذف وحدة القياس: \"${target.name}\"؟ لا توجد لها أي عمليات أو أصناف مسجلة.",
            confirmButtonText = "نعم، احذف الوحدة",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteUnit(target)
                packageUnitToDelete = null
            },
            onDismiss = { packageUnitToDelete = null }
        )
    }

    // Confirm Delete Smaller Unit Dialog
    smallerUnitToDelete?.let { target ->
        SimpleConfirmDialog(
            title = "تأكيد حذف الوحدة الصغرى",
            message = "هل أنت متأكد من حذف الوحدة الصغرى: \"${target.name}\" نهائياً؟ لا توجد عليها أي عمليات.",
            confirmButtonText = "نعم، احذف الوحدة الصغرى",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSmallerUnitSafely(target)
                smallerUnitToDelete = null
            },
            onDismiss = { smallerUnitToDelete = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PackageUnitFormDialog(
    initialUnit: ItemUnit?,
    smallerUnits: List<SmallerUnit>,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit,
    onSave: (ItemUnit) -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var name by remember { mutableStateOf(initialUnit?.name ?: "") }
    var smallerUnit by remember {
        mutableStateOf(
            initialUnit?.smallerUnit ?: smallerUnits.firstOrNull()?.name ?: "حبة"
        )
    }
    var countText by remember {
        mutableStateOf(formatCapacity(initialUnit?.smallerUnitsCount ?: 12.0))
    }
    var capacityText by remember {
        mutableStateOf(
            formatCapacity(
                initialUnit?.smallerUnitCapacity
                    ?: smallerUnits.find { it.name == smallerUnit }?.capacity
                    ?: 1.0
            )
        )
    }
    var smallerUnitDropdownExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    var showCreateSmallerDialog by remember { mutableStateOf(false) }
    var smallerToEditInline by remember { mutableStateOf<SmallerUnit?>(null) }
    var smallerToDeleteInline by remember { mutableStateOf<SmallerUnit?>(null) }

    val smallerUnitOptions = remember(smallerUnits, initialUnit) {
        if (smallerUnits.isNotEmpty()) smallerUnits
        else listOf(SmallerUnit(name = initialUnit?.smallerUnit ?: "حبة", capacity = initialUnit?.smallerUnitCapacity ?: 1.0))
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialUnit == null) "إضافة وحدة قياس وتحديد سعتها" else "تعديل وحدة القياس وسعتها",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("اسم وحدة العبوة (مثال: درزن، كرتون) *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Select smaller unit + inline add/edit/delete
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        ExposedDropdownMenuBox(
                            expanded = smallerUnitDropdownExpanded,
                            onExpandedChange = { smallerUnitDropdownExpanded = !smallerUnitDropdownExpanded },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedTextField(
                                value = smallerUnit,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("الوحدة الصغرى التابعة لها *") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = smallerUnitDropdownExpanded) },
                                modifier = Modifier
                                    .menuAnchor()
                                    .fillMaxWidth()
                            )
                            ExposedDropdownMenu(
                                expanded = smallerUnitDropdownExpanded,
                                onDismissRequest = { smallerUnitDropdownExpanded = false }
                            ) {
                                smallerUnitOptions.forEach { su ->
                                    DropdownMenuItem(
                                        text = {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("${su.name} (${formatCapacity(su.capacity)})", modifier = Modifier.weight(1f), maxLines = 1)
                                                if (su.id > 0L) {
                                                    IconButton(
                                                        onClick = {
                                                            smallerUnitDropdownExpanded = false
                                                            coroutineScope.launch {
                                                                if (viewModel.checkSmallerUnitHasOperations(su.name)) {
                                                                    error = "لا يمكن تعديل الوحدة الصغرى (${su.name}) لوجود عمليات مرتبطة بها!"
                                                                } else {
                                                                    error = ""
                                                                    smallerToEditInline = su
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.size(30.dp)
                                                    ) {
                                                        Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                                    }
                                                    IconButton(
                                                        onClick = {
                                                            smallerUnitDropdownExpanded = false
                                                            coroutineScope.launch {
                                                                val ops = viewModel.checkSmallerUnitHasOperations(su.name)
                                                                val pkg = viewModel.checkSmallerUnitUsedByPackageUnit(su.name)
                                                                if (ops || pkg) {
                                                                    error = "لا يمكن حذف الوحدة الصغرى (${su.name}) لأنها مرتبطة بوحدات عبوة أو عمليات!"
                                                                } else {
                                                                    error = ""
                                                                    smallerToDeleteInline = su
                                                                }
                                                            }
                                                        },
                                                        modifier = Modifier.size(30.dp)
                                                    ) {
                                                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = DangerRed, modifier = Modifier.size(16.dp))
                                                    }
                                                }
                                            }
                                        },
                                        onClick = {
                                            smallerUnit = su.name
                                            capacityText = formatCapacity(su.capacity)
                                            smallerUnitDropdownExpanded = false
                                        }
                                    )
                                }
                                HorizontalDivider()
                                DropdownMenuItem(
                                    text = {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text("+ إضافة وحدة صغرى جديدة", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                        }
                                    },
                                    onClick = {
                                        smallerUnitDropdownExpanded = false
                                        showCreateSmallerDialog = true
                                    }
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                    IconButton(
                        onClick = { showCreateSmallerDialog = true },
                        modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "إضافة وحدة صغرى جديدة", tint = MaterialTheme.colorScheme.primary)
                    }
                }

                OutlinedTextField(
                    value = countText,
                    onValueChange = {
                        countText = it.filter { ch -> ch.isDigit() || ch == '.' }
                        error = ""
                    },
                    label = { Text("كم تحتوي العبوة من ($smallerUnit)؟ (إجباري) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                OutlinedTextField(
                    value = capacityText,
                    onValueChange = {
                        capacityText = it.filter { ch -> ch.isDigit() || ch == '.' }
                        error = ""
                    },
                    label = { Text("سعة الـ ($smallerUnit) (إجباري) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                if (error.isNotBlank()) {
                    Text(error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (name.isBlank()) {
                        error = "يرجى إدخال اسم الوحدة"
                        return@Button
                    }
                    val cnt = countText.toDoubleOrNull() ?: 0.0
                    if (cnt <= 0) {
                        error = "يرجى إدخال كم تحتوي العبوة بشكل صحيح"
                        return@Button
                    }
                    val cap = capacityText.toDoubleOrNull() ?: 0.0
                    if (cap <= 0) {
                        error = "يرجى إدخال سعة الوحدة الصغرى بشكل صحيح"
                        return@Button
                    }
                    onSave(
                        (initialUnit ?: ItemUnit(name = name.trim())).copy(
                            name = name.trim(),
                            smallerUnit = smallerUnit.trim(),
                            smallerUnitsCount = cnt,
                            smallerUnitCapacity = cap
                        )
                    )
                }
            ) { Text("حفظ الوحدة") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )

    if (showCreateSmallerDialog) {
        SmallerUnitFormDialog(
            initialUnit = null,
            onDismiss = { showCreateSmallerDialog = false },
            onSave = { newName, newCap ->
                viewModel.saveSmallerUnit(SmallerUnit(name = newName, capacity = newCap)) {
                    smallerUnit = newName
                    capacityText = formatCapacity(newCap)
                }
                showCreateSmallerDialog = false
            }
        )
    }

    smallerToEditInline?.let { su ->
        SmallerUnitFormDialog(
            initialUnit = su,
            onDismiss = { smallerToEditInline = null },
            onSave = { newName, newCap ->
                viewModel.updateSmallerUnitSafely(su, newName, newCap) { ok ->
                    if (ok && smallerUnit == su.name) {
                        smallerUnit = newName
                        capacityText = formatCapacity(newCap)
                    }
                    smallerToEditInline = null
                }
            }
        )
    }

    smallerToDeleteInline?.let { su ->
        SimpleConfirmDialog(
            title = "تأكيد حذف الوحدة الصغرى",
            message = "هل أنت متأكد من حذف الوحدة الصغرى \"${su.name}\" نهائياً؟",
            confirmButtonText = "نعم، احذف",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSmallerUnitSafely(su) { ok ->
                    if (ok && smallerUnit == su.name) {
                        smallerUnit = smallerUnits.firstOrNull { it.id != su.id }?.name ?: "حبة"
                    }
                    smallerToDeleteInline = null
                }
            },
            onDismiss = { smallerToDeleteInline = null }
        )
    }
}

@Composable
private fun SmallerUnitFormDialog(
    initialUnit: SmallerUnit?,
    onDismiss: () -> Unit,
    onSave: (name: String, capacity: Double) -> Unit
) {
    var name by remember { mutableStateOf(initialUnit?.name ?: "") }
    var capacityText by remember { mutableStateOf(formatCapacity(initialUnit?.capacity ?: 1.0)) }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = if (initialUnit == null) "إضافة وحدة صغرى جديدة" else "تعديل الوحدة الصغرى",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("اسم الوحدة الصغرى (مثال: حبة، مل، جرام) *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = capacityText,
                    onValueChange = {
                        capacityText = it.filter { ch -> ch.isDigit() || ch == '.' }
                        error = ""
                    },
                    label = { Text("سعة الوحدة الصغرى *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                if (error.isNotBlank()) {
                    Text(error, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = name.trim()
                    val cap = capacityText.toDoubleOrNull() ?: 0.0
                    if (trimmed.isBlank()) {
                        error = "يرجى إدخال اسم الوحدة الصغرى"
                        return@Button
                    }
                    if (cap <= 0.0) {
                        error = "يرجى إدخال سعة صالحة أكبر من صفر"
                        return@Button
                    }
                    onSave(trimmed, cap)
                }
            ) { Text("حفظ") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
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
