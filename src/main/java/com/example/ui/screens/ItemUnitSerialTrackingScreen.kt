package com.example.ui.screens

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.example.data.model.ItemUnitSerialRecord
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseItemStock
import com.example.ui.components.SubDeviceRestrictedDialog
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.DeviceAction
import com.example.ui.viewmodel.InventoryViewModel
import com.example.util.ReportGenerator
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemUnitSerialTrackingScreen(
    warehouse: Warehouse,
    itemStock: WarehouseItemStock,
    viewModel: InventoryViewModel,
    onBack: () -> Unit,
    onDisburseSelected: (List<ItemUnitSerialRecord>) -> Unit
) {
    val context = LocalContext.current
    BackHandler { onBack() }

    val appUserName by viewModel.userName.collectAsState()
    val orgName by viewModel.orgName.collectAsState()

    val totalCount = itemStock.currentStock.toInt().coerceAtLeast(1)

    // Serials State List
    val serialsList = remember { mutableStateListOf<ItemUnitSerialRecord>() }
    val selectedIndices = remember { mutableStateListOf<Int>() }

    // Custom status options: default options + user added
    val statusOptions = remember {
        mutableStateListOf("موجود", "مفقود", "مدمر", "عاطل")
    }

    var showAddStatusDialog by remember { mutableStateOf(false) }
    var showExportDialog by remember { mutableStateOf(false) }
    var restrictedActionName by remember { mutableStateOf<String?>(null) }

    // Load existing serial records from DB
    LaunchedEffect(warehouse.id, itemStock.itemId, totalCount) {
        val existing = viewModel.getSerialsList(warehouse.id, itemStock.itemId)
        val existingMap = existing.associateBy { it.unitIndex }

        serialsList.clear()
        for (i in 1..totalCount) {
            val rec = existingMap[i] ?: ItemUnitSerialRecord(
                warehouseId = warehouse.id,
                itemId = itemStock.itemId,
                unitIndex = i,
                serialNumber = "",
                status = "موجود",
                notes = ""
            )
            serialsList.add(rec)
            if (rec.status.isNotBlank() && !statusOptions.contains(rec.status)) {
                statusOptions.add(rec.status)
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "تفصيل وترقيم: ${itemStock.itemName}",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "المخزن: ${warehouse.name} | الكمية: ${itemStock.currentStock.toInt()} ${itemStock.itemUnit}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    IconButton(onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.EDIT_OR_DELETE,
                            onDenied = { restrictedActionName = "حفظ وتعديل الأرقام المميزة" }
                        ) {
                            viewModel.saveSerials(serialsList.toList()) {
                                Toast.makeText(context, "تم حفظ بيانات الترقيم بنجاح", Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) {
                        Icon(Icons.Default.Save, contentDescription = "حفظ البيانات", tint = MaterialTheme.colorScheme.primary)
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
            // Action bar: Disburse Selected, Save, Export & Preview
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Button to disburse selected units
                Button(
                    onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.DISBURSE_OR_TRANSFER,
                            onDenied = { restrictedActionName = "صرف الوحدات المحددة" }
                        ) {
                            val selectedRecords = serialsList.filter { selectedIndices.contains(it.unitIndex) }
                            if (selectedRecords.isNotEmpty()) {
                                onDisburseSelected(selectedRecords)
                            } else {
                                Toast.makeText(context, "يرجى تحديد عنصر واحد على الأقل للصرف", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    enabled = selectedIndices.isNotEmpty(),
                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1.2f)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (selectedIndices.isEmpty()) "صرف محدد" else "صرف المحدد (${selectedIndices.size})",
                        fontSize = 11.sp
                    )
                }

                // Button to save
                Button(
                    onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.EDIT_OR_DELETE,
                            onDenied = { restrictedActionName = "حفظ وتعديل الأرقام المميزة" }
                        ) {
                            viewModel.saveSerials(serialsList.toList()) {
                                Toast.makeText(context, "تم حفظ البيانات بنجاح", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("حفظ الترقيم", fontSize = 11.sp)
                }
            }

            // Quick select all / unselect all
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "العناصر المحددة: ${selectedIndices.size} من أصل $totalCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    TextButton(onClick = {
                        selectedIndices.clear()
                        for (i in 1..totalCount) selectedIndices.add(i)
                    }) {
                        Text("تحديد الكل", fontSize = 11.sp)
                    }
                    TextButton(onClick = { selectedIndices.clear() }) {
                        Text("إلغاء التحديد", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Unit serial rows list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(bottom = 20.dp)
            ) {
                itemsIndexed(serialsList) { index, serialRec ->
                    val unitNumber = index + 1
                    val isSelected = selectedIndices.contains(unitNumber)

                    UnitSerialCard(
                        unitNumber = unitNumber,
                        itemName = itemStock.itemName,
                        serialRecord = serialRec,
                        statusOptions = statusOptions,
                        isSelected = isSelected,
                        onToggleSelect = {
                            if (isSelected) selectedIndices.remove(unitNumber)
                            else selectedIndices.add(unitNumber)
                        },
                        onUpdate = { updated ->
                            serialsList[index] = updated
                        },
                        onAddNewStatus = { showAddStatusDialog = true }
                    )
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

    // Add New Custom Status Dialog
    if (showAddStatusDialog) {
        var newStatusName by remember { mutableStateOf("") }
        var statusErr by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddStatusDialog = false },
            title = { Text("إضافة حالة جديدة للصنف", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newStatusName,
                    onValueChange = { newStatusName = it; statusErr = "" },
                    label = { Text("اسم الحالة (مثال: قيد الصيانة، محجوز، تالف...) *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newStatusName.isBlank()) {
                            statusErr = "يرجى إدخال اسم الحالة"
                            return@Button
                        }
                        val trimmed = newStatusName.trim()
                        if (!statusOptions.contains(trimmed)) {
                            statusOptions.add(trimmed)
                        }
                        showAddStatusDialog = false
                    }
                ) { Text("إضافة") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showAddStatusDialog = false }) { Text("إلغاء") }
            }
        )
    }

    // Export & Preview Dialog with column selection
    if (showExportDialog) {
        ItemSerialsExportDialog(
            warehouseName = warehouse.name,
            itemName = itemStock.itemName,
            itemUnit = itemStock.itemUnit,
            appUserName = appUserName,
            orgName = orgName,
            serials = serialsList.toList(),
            onDismiss = { showExportDialog = false }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UnitSerialCard(
    unitNumber: Int,
    itemName: String,
    serialRecord: ItemUnitSerialRecord,
    statusOptions: List<String>,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onUpdate: (ItemUnitSerialRecord) -> Unit,
    onAddNewStatus: () -> Unit
) {
    var serialInput by remember(serialRecord.serialNumber) { mutableStateOf(serialRecord.serialNumber) }
    var notesInput by remember(serialRecord.notes) { mutableStateOf(serialRecord.notes) }
    var statusDropdownExpanded by remember { mutableStateOf(false) }

    val statusColor = when (serialRecord.status) {
        "موجود" -> SuccessGreen
        "مفقود" -> WarningOrange
        "مدمر" -> DangerRed
        "عاطل" -> DangerRed
        else -> MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // Row 1: Checkbox, Index #, Item Name, Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Surface(
                        shape = CircleShape,
                        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f),
                        modifier = Modifier.size(28.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(
                                text = "$unitNumber",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = itemName,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Surface(
                    shape = RoundedCornerShape(4.dp),
                    color = statusColor.copy(alpha = 0.15f)
                ) {
                    Text(
                        text = serialRecord.status.ifBlank { "موجود" },
                        color = statusColor,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            // Row 2: Serial Input & Status Dropdown
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = serialInput,
                    onValueChange = {
                        serialInput = it
                        onUpdate(serialRecord.copy(serialNumber = it.trim()))
                    },
                    label = { Text("الرقم المميز / السيريال") },
                    placeholder = { Text("SN-000$unitNumber") },
                    modifier = Modifier.weight(1.2f),
                    singleLine = true
                )

                // Status Dropdown
                Box(modifier = Modifier.weight(1f)) {
                    ExposedDropdownMenuBox(
                        expanded = statusDropdownExpanded,
                        onExpandedChange = { statusDropdownExpanded = !statusDropdownExpanded },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = serialRecord.status.ifBlank { "موجود" },
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("الحالة") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = statusDropdownExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(
                            expanded = statusDropdownExpanded,
                            onDismissRequest = { statusDropdownExpanded = false }
                        ) {
                            statusOptions.forEach { opt ->
                                DropdownMenuItem(
                                    text = { Text(opt) },
                                    onClick = {
                                        onUpdate(serialRecord.copy(status = opt))
                                        statusDropdownExpanded = false
                                    }
                                )
                            }
                            DropdownMenuItem(
                                text = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("+ إضافة حالة جديدة", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                                    }
                                },
                                onClick = {
                                    statusDropdownExpanded = false
                                    onAddNewStatus()
                                }
                            )
                        }
                    }
                }
            }

            // Row 3: Notes field
            OutlinedTextField(
                value = notesInput,
                onValueChange = {
                    notesInput = it
                    onUpdate(serialRecord.copy(notes = it))
                },
                label = { Text("ملاحظة على هذه الوحدة (اختياري)") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
        }
    }
}

@Composable
private fun ItemSerialsExportDialog(
    warehouseName: String,
    itemName: String,
    itemUnit: String,
    appUserName: String,
    orgName: String,
    serials: List<ItemUnitSerialRecord>,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current

    val showSerial = remember { mutableStateOf(true) }
    val showItemName = remember { mutableStateOf(true) }
    val showStatus = remember { mutableStateOf(true) }
    val showNotes = remember { mutableStateOf(true) }

    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    val dateStr = dateFormat.format(Date())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("معاينة وتصدير ترقيم الوحدات", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "صنف: $itemName | مخزن: $warehouseName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )

                Text("تخصيص أعمدة التصدير والمعاينة:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showSerial.value, onCheckedChange = { showSerial.value = it })
                        Text("الرقم المميز", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showItemName.value, onCheckedChange = { showItemName.value = it })
                        Text("اسم الصنف", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showStatus.value, onCheckedChange = { showStatus.value = it })
                        Text("الحالة", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showNotes.value, onCheckedChange = { showNotes.value = it })
                        Text("الملاحظة", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Table preview
                val horizontalScroll = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(horizontalScroll)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column {
                        Row(modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(6.dp)) {
                            Text("م", modifier = Modifier.width(36.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showItemName.value) Text("الصنف", modifier = Modifier.width(110.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showSerial.value) Text("الرقم المميز", modifier = Modifier.width(110.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showStatus.value) Text("الحالة", modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showNotes.value) Text("الملاحظات", modifier = Modifier.width(120.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        serials.forEachIndexed { idx, s ->
                            Row(modifier = Modifier.padding(6.dp)) {
                                Text("${s.unitIndex}", modifier = Modifier.width(36.dp), fontSize = 11.sp)
                                if (showItemName.value) Text(itemName, modifier = Modifier.width(110.dp), fontSize = 11.sp)
                                if (showSerial.value) Text(s.serialNumber.ifBlank { "-" }, modifier = Modifier.width(110.dp), fontSize = 11.sp)
                                if (showStatus.value) Text(s.status, modifier = Modifier.width(80.dp), fontSize = 11.sp)
                                if (showNotes.value) Text(s.notes.ifBlank { "-" }, modifier = Modifier.width(120.dp), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val reportRows = serials.map { s ->
                ReportGenerator.ReportRowData(
                    voucherNumber = s.serialNumber.ifBlank { "SN-${s.unitIndex}" },
                    warehouseName = warehouseName,
                    itemName = itemName,
                    itemUnit = itemUnit,
                    beneficiaryName = s.status,
                    receiverName = "-",
                    disburserName = "-",
                    voucherDate = dateStr,
                    quantity = 1.0,
                    totalQuantity = 1.0,
                    minStockLevel = 0.0,
                    unitPrice = 0.0,
                    totalValue = 0.0
                )
            }

            val selectedCols = mutableSetOf(ReportGenerator.ReportColumn.SERIAL).apply {
                if (showSerial.value) add(ReportGenerator.ReportColumn.VOUCHER_NUMBER)
                if (showItemName.value) add(ReportGenerator.ReportColumn.ITEM_NAME)
                if (showStatus.value) add(ReportGenerator.ReportColumn.BENEFICIARY) // used for status in this export
                add(ReportGenerator.ReportColumn.WAREHOUSE)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        val pdf = ReportGenerator.generatePdf(
                            context = context,
                            title = "تقرير ترقيم وحدات: $itemName",
                            warehouseName = warehouseName,
                            appUserName = appUserName,
                            orgName = orgName,
                            rows = reportRows,
                            selectedColumns = selectedCols
                        )
                        ReportGenerator.viewFile(context, pdf, "application/pdf")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) { Text("معاينة", fontSize = 11.sp) }

                Button(
                    onClick = {
                        val pdf = ReportGenerator.generatePdf(
                            context = context,
                            title = "تقرير ترقيم وحدات: $itemName",
                            warehouseName = warehouseName,
                            appUserName = appUserName,
                            orgName = orgName,
                            rows = reportRows,
                            selectedCols
                        )
                        ReportGenerator.shareFile(context, pdf, "application/pdf", "مشاركة ترقيم الوحدات PDF")
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) { Text("PDF", fontSize = 11.sp) }

                Button(
                    onClick = {
                        val excel = ReportGenerator.generateExcel(
                            context = context,
                            title = "تقرير ترقيم وحدات: $itemName",
                            warehouseName = warehouseName,
                            appUserName = appUserName,
                            rows = reportRows,
                            selectedColumns = selectedCols
                        )
                        ReportGenerator.shareFile(context, excel, "text/csv", "مشاركة ترقيم الوحدات Excel")
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
