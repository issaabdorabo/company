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
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.Scale
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

    // Manage Units Dialog
    var showManageUnitsDialog by remember { mutableStateOf(false) }

    // Deletion states
    var itemToDelete by remember { mutableStateOf<Item?>(null) }
    var showSimpleDeleteDialog by remember { mutableStateOf(false) }
    var doubleConfirmStep by remember { mutableIntStateOf(0) }
    var restrictedActionName by remember { mutableStateOf<String?>(null) }

    val itemsList = snapshot?.itemStockInfos ?: emptyList()
    val categoriesFromDb by viewModel.categories.collectAsState()
    val unitsFromDb by viewModel.units.collectAsState()

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
            // Search Input & Manage Units Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("بحث باسم الصنف، الباركود، أو الفئة...") },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("item_search_input"),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true
                )

                OutlinedButton(
                    onClick = { showManageUnitsDialog = true },
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 12.dp)
                ) {
                    Icon(Icons.Default.Scale, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("الوحدات", fontSize = 12.sp)
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
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(filteredItems, key = { it.item.id }) { stockInfo ->
                        ItemCard(
                            stockInfo = stockInfo,
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
            categoriesList = categoriesFromDb,
            onAddNewUnit = { unitName ->
                viewModel.saveUnit(unitName)
            },
            onAddNewCategory = { cat ->
                viewModel.saveCategory(cat)
            },
            onDismiss = { showEditDialog = false },
            onSave = { item ->
                viewModel.saveItem(item) {
                    showEditDialog = false
                }
            }
        )
    }

    // Manage Units Dialog
    if (showManageUnitsDialog) {
        ManageUnitsDialog(
            units = unitsFromDb,
            viewModel = viewModel,
            onDismiss = { showManageUnitsDialog = false }
        )
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
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val item = stockInfo.item
    val context = LocalContext.current

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
                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                    text = "(${item.unitsPerPackage.toInt()} ${item.unit} لكل ${item.packageUnit})",
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
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemFormDialog(
    initialItem: Item?,
    hasTransactions: Boolean = false,
    unitsList: List<ItemUnit>,
    categoriesList: List<ItemCategory>,
    onAddNewUnit: (String) -> Unit,
    onAddNewCategory: (ItemCategory) -> Unit,
    onDismiss: () -> Unit,
    onSave: (Item) -> Unit
) {
    val context = LocalContext.current

    var name by remember { mutableStateOf(initialItem?.name ?: "") }
    var barcode by remember { mutableStateOf(initialItem?.barcode ?: "") }

    // Units
    var unit by remember { mutableStateOf(initialItem?.unit ?: "حبة") }
    var packageUnit by remember { mutableStateOf(initialItem?.packageUnit ?: "صندوق") }
    var unitsPerPackageText by remember { mutableStateOf(initialItem?.unitsPerPackage?.toInt()?.toString() ?: "1") }
    var openingStockText by remember { mutableStateOf(initialItem?.openingStock?.toInt()?.toString() ?: "0") }

    var category by remember { mutableStateOf(initialItem?.category ?: "عام") }
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

    // Inline dialog states
    var showAddUnitDialog by remember { mutableStateOf(false) }
    var showAddCategoryDialog by remember { mutableStateOf(false) }

    val allUnits = remember(unitsList) {
        (listOf("حبة", "صندوق", "كرتون", "طرد", "كيلو", "لتر", "متر") + unitsList.map { it.name }).distinct()
    }

    val allCategories = remember(categoriesList) {
        (listOf("أجهزة إلكترونية", "قرطاسية ومكتبيات", "أجهزة مكتبية", "شبكات وأسلاك", "قطع غيار ومعدات", "عام") + categoriesList.map { it.name }).distinct()
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
                                text = "ملاحظة: هذا الصنف تمت عليه حركات مخزنية، لذا تم قفل الاسم والباركود والوحدات والكمية الافتتاحية حمايةً للبيانات. يُسمح فقط بتعديل حد الطلب وسعر الشراء والملاحظات والمرفق.",
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
                    modifier = Modifier.fillMaxWidth().testTag("item_name_input"),
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

                // Category Selection with + Add New Category
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
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            if (!hasTransactions) {
                                ExposedDropdownMenu(
                                    expanded = categoryDropdownExpanded,
                                    onDismissRequest = { categoryDropdownExpanded = false }
                                ) {
                                    allCategories.forEach { catName ->
                                        DropdownMenuItem(
                                            text = { Text(catName) },
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

                // Packaging & Units Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Main Unit (Default حبة)
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
                                label = { Text("الوحدة الصغرى") },
                                trailingIcon = { if (!hasTransactions) ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitDropdownExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            if (!hasTransactions) {
                                ExposedDropdownMenu(
                                    expanded = unitDropdownExpanded,
                                    onDismissRequest = { unitDropdownExpanded = false }
                                ) {
                                    allUnits.forEach { u ->
                                        DropdownMenuItem(
                                            text = { Text(u) },
                                            onClick = {
                                                unit = u
                                                unitDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Package Unit (صندوق / كرتون)
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
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            if (!hasTransactions) {
                                ExposedDropdownMenu(
                                    expanded = packageUnitDropdownExpanded,
                                    onDismissRequest = { packageUnitDropdownExpanded = false }
                                ) {
                                    allUnits.forEach { u ->
                                        DropdownMenuItem(
                                            text = { Text(u) },
                                            onClick = {
                                                packageUnit = u
                                                packageUnitDropdownExpanded = false
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    if (!hasTransactions) {
                        IconButton(
                            onClick = { showAddUnitDialog = true },
                            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "إضافة وحدة جديدة", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                }

                // If packageUnit != unit, show mandatory capacity field
                if (packageUnit != unit) {
                    OutlinedTextField(
                        value = unitsPerPackageText,
                        onValueChange = {
                            unitsPerPackageText = it.filter { ch -> ch.isDigit() }
                            errorMessage = ""
                        },
                        readOnly = hasTransactions,
                        enabled = !hasTransactions,
                        label = { Text("سعة الـ ($packageUnit): كم ($unit) يحتوي؟ *") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                }

                // Opening Stock field
                OutlinedTextField(
                    value = openingStockText,
                    onValueChange = {
                        openingStockText = it.filter { ch -> ch.isDigit() }
                    },
                    readOnly = hasTransactions,
                    enabled = !hasTransactions,
                    label = { Text("الكمية الافتتاحية للمخزون (بالـ $unit)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

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
                        val cap = unitsPerPackageText.toDoubleOrNull() ?: 0.0
                        if (cap <= 0) {
                            errorMessage = "يرجى تحديد سعة العبوة / الصندوق بشكل صحيح"
                            return@Button
                        }
                        cap
                    } else 1.0

                    val opening = openingStockText.toDoubleOrNull() ?: 0.0
                    val minStockVal = minStock.toDoubleOrNull() ?: 5.0
                    val priceVal = purchasePrice.toDoubleOrNull() ?: 0.0

                    val updated = (initialItem ?: Item(name = name)).copy(
                        name = name.trim(),
                        barcode = barcode.trim(),
                        unit = unit.trim(),
                        packageUnit = packageUnit.trim(),
                        unitsPerPackage = capacity,
                        openingStock = opening,
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

    // Add Unit Quick Dialog
    if (showAddUnitDialog) {
        var newUnitName by remember { mutableStateOf("") }
        var unitErr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddUnitDialog = false },
            title = { Text("إضافة وحدة قياس جديدة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newUnitName,
                    onValueChange = { newUnitName = it; unitErr = "" },
                    label = { Text("اسم الوحدة (مثال: درزن، لفة...)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newUnitName.isBlank()) {
                            unitErr = "يرجى إدخال اسم الوحدة"
                            return@Button
                        }
                        onAddNewUnit(newUnitName.trim())
                        unit = newUnitName.trim()
                        showAddUnitDialog = false
                    }
                ) { Text("إضافة") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddUnitDialog = false }) { Text("إلغاء") }
            }
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
                OutlinedTextField(
                    value = newCatName,
                    onValueChange = { newCatName = it; catErr = "" },
                    label = { Text("اسم الفئة / التصنيف") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
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
}

/**
 * Manage Units Dialog:
 * Supports:
 * - Adding new unit with smaller unit selection, package count, and capacity.
 * - Editing unit name only (conversion ratio locked).
 * - Safe deletion of unused units (checks if transactions or items exist).
 */
@Composable
private fun ManageUnitsDialog(
    units: List<ItemUnit>,
    viewModel: InventoryViewModel,
    onDismiss: () -> Unit
) {
    val coroutineScope = rememberCoroutineScope()
    var showCreateUnitDialog by remember { mutableStateOf(false) }
    var unitToRename by remember { mutableStateOf<ItemUnit?>(null) }
    var unitToDelete by remember { mutableStateOf<ItemUnit?>(null) }
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
                            showCreateUnitDialog = true
                        }
                    },
                    modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                ) {
                    Icon(Icons.Default.Add, contentDescription = "إضافة وحدة جديدة", tint = MaterialTheme.colorScheme.primary)
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (errorMessage.isNotBlank()) {
                    Text(errorMessage, color = DangerRed, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                }

                if (units.isEmpty()) {
                    Text("لا توجد وحدات مخصصة مضافة حالياً. اضغط + لإضافة وحدة قياس.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                Column {
                                    Text(u.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        text = "تحتوي ${u.smallerUnitsCount.toInt()} ${u.smallerUnit} (سعة: ${u.smallerUnitCapacity.toInt()})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                Row {
                                    IconButton(
                                        onClick = {
                                            viewModel.checkPermission(
                                                action = DeviceAction.EDIT_OR_DELETE,
                                                onDenied = { errorMessage = "عذراً: هذا الجهاز فرعي ولا يملك صلاحية تعديل وحدات القياس." }
                                            ) {
                                                unitToRename = u
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "تعديل الاسم", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(
                                        onClick = {
                                            viewModel.checkPermission(
                                                action = DeviceAction.EDIT_OR_DELETE,
                                                onDenied = { errorMessage = "عذراً: هذا الجهاز فرعي ولا يملك صلاحية حذف وحدات القياس." }
                                            ) {
                                                coroutineScope.launch {
                                                    val hasTx = viewModel.checkUnitHasTransactions(u.name)
                                                    if (hasTx) {
                                                        errorMessage = "لا يمكن حذف وحدة (${u.name}) لأنها مستخدمة في أصناف أو حركات توريد وصرف!"
                                                    } else {
                                                        errorMessage = ""
                                                        unitToDelete = u
                                                    }
                                                }
                                            }
                                        }
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = DangerRed, modifier = Modifier.size(18.dp))
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

    // Add Unit Detailed Dialog
    if (showCreateUnitDialog) {
        AddUnitDetailedDialog(
            existingUnits = units,
            onDismiss = { showCreateUnitDialog = false },
            onSave = { unitEntity ->
                viewModel.saveUnit(unitEntity)
                showCreateUnitDialog = false
            }
        )
    }

    // Rename Unit Dialog (Name only, ratio locked)
    if (unitToRename != null) {
        var renamedName by remember { mutableStateOf(unitToRename!!.name) }
        var renameErr by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { unitToRename = null },
            title = { Text("تعديل اسم وحدة القياس", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "ملاحظة: يتم تعديل الاسم فقط دون تغيير نسبة التحويل السابقة حفاظاً على صحة الحسابات.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = renamedName,
                        onValueChange = { renamedName = it; renameErr = "" },
                        label = { Text("اسم الوحدة الجديد *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    if (renameErr.isNotBlank()) {
                        Text(renameErr, color = DangerRed, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (renamedName.isBlank()) {
                            renameErr = "يرجى كتابة اسم الوحدة"
                            return@Button
                        }
                        viewModel.updateUnitName(unitToRename!!.id, renamedName.trim())
                        unitToRename = null
                    }
                ) { Text("تحديث الاسم") }
            },
            dismissButton = {
                OutlinedButton(onClick = { unitToRename = null }) { Text("إلغاء") }
            }
        )
    }

    // Confirm Delete Unit Dialog
    if (unitToDelete != null) {
        SimpleConfirmDialog(
            title = "تأكيد حذف وحدة القياس",
            message = "هل أنت متأكد من حذف وحدة القياس: \"${unitToDelete!!.name}\"؟ لا توجد لها أي عمليات مسجلة.",
            confirmButtonText = "نعم، احذف الوحدة",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteUnit(unitToDelete!!)
                unitToDelete = null
            },
            onDismiss = { unitToDelete = null }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddUnitDetailedDialog(
    existingUnits: List<ItemUnit>,
    onDismiss: () -> Unit,
    onSave: (ItemUnit) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var smallerUnit by remember { mutableStateOf("حبة") }
    var countText by remember { mutableStateOf("12") }
    var capacityText by remember { mutableStateOf("1") }
    var smallerUnitDropdownExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }

    val smallerUnitOptions = remember(existingUnits) {
        (listOf("حبة", "متر", "لتر", "كيلو", "جرام") + existingUnits.map { it.name }).distinct()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة وحدة قياس وتحديد سعتها", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("اسم الوحدة (مثال: درزن، كرتون) *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Select smaller unit
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
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(
                        expanded = smallerUnitDropdownExpanded,
                        onDismissRequest = { smallerUnitDropdownExpanded = false }
                    ) {
                        smallerUnitOptions.forEach { opt ->
                            DropdownMenuItem(
                                text = { Text(opt) },
                                onClick = {
                                    smallerUnit = opt
                                    smallerUnitDropdownExpanded = false
                                }
                            )
                        }
                    }
                }

                // Mandatory: how many smaller units inside package
                OutlinedTextField(
                    value = countText,
                    onValueChange = {
                        countText = it.filter { ch -> ch.isDigit() }
                        error = ""
                    },
                    label = { Text("كم تحتوي العبوة من ($smallerUnit)؟ (إجباري) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                // Mandatory: capacity of smaller unit
                OutlinedTextField(
                    value = capacityText,
                    onValueChange = {
                        capacityText = it.filter { ch -> ch.isDigit() }
                        error = ""
                    },
                    label = { Text("سعة الـ ($smallerUnit) (إجباري) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                        ItemUnit(
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
