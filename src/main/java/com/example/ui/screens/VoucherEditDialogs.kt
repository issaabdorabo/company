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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.VoucherWithItems
import com.example.data.model.Beneficiary
import com.example.data.model.Item
import com.example.data.model.Supplier
import com.example.data.model.Voucher
import com.example.data.model.VoucherItem
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseParty
import com.example.ui.components.SimpleConfirmDialog
import com.example.ui.theme.DangerRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.util.ReportGenerator
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoucherFormDialog(
    voucherType: VoucherType,
    initialVoucherWithItems: VoucherWithItems?,
    fixedSourceWarehouseId: Long? = null,
    fixedVoucherNumber: String? = null,
    warehouses: List<Warehouse>,
    items: List<Item>,
    beneficiaries: List<Beneficiary>,
    suppliers: List<Supplier> = emptyList(),
    parties: List<WarehouseParty> = emptyList(),
    stockMap: Map<Pair<Long, Long>, Double> = emptyMap(),
    preselectedLineItems: List<VoucherItem>? = null,
    onAddNewBeneficiary: ((Beneficiary, (Long) -> Unit) -> Unit)? = null,
    onAddNewSupplier: ((Supplier, (Long) -> Unit) -> Unit)? = null,
    onAddNewParty: ((WarehouseParty, (Long) -> Unit) -> Unit)? = null,
    onUpdateSupplier: ((oldName: String, newName: String) -> Unit)? = null,
    onDeleteSupplier: ((Supplier, (Boolean) -> Unit) -> Unit)? = null,
    checkSupplierHasTransactions: (suspend (String) -> Boolean)? = null,
    onUpdateParty: ((oldName: String, newName: String) -> Unit)? = null,
    onDeleteParty: ((WarehouseParty, (Boolean) -> Unit) -> Unit)? = null,
    checkPartyHasTransactions: (suspend (String) -> Boolean)? = null,
    onDismiss: () -> Unit,
    onSave: (Voucher, List<VoucherItem>) -> Unit
) {
    val context = LocalContext.current
    val initialV = initialVoucherWithItems?.voucher

    // Voucher Number is non-editable and auto-sequential
    val voucherNumber = remember {
        fixedVoucherNumber ?: initialV?.voucherNumber ?: when (voucherType) {
            VoucherType.INWARD -> "IN-${System.currentTimeMillis() % 100000}"
            VoucherType.TRANSFER -> "TR-${System.currentTimeMillis() % 100000}"
            VoucherType.DISBURSEMENT -> "OUT-${System.currentTimeMillis() % 100000}"
        }
    }

    var showDatePicker by remember { mutableStateOf(false) }
    var selectedDate by remember {
        mutableLongStateOf(initialV?.date ?: System.currentTimeMillis())
    }

    var sourceWarehouseId by remember {
        mutableStateOf(fixedSourceWarehouseId ?: initialV?.sourceWarehouseId ?: warehouses.firstOrNull()?.id)
    }

    var destWarehouseId by remember {
        mutableStateOf(
            initialV?.destinationWarehouseId ?: warehouses.getOrNull(1)?.id ?: warehouses.firstOrNull()?.id
        )
    }

    var selectedSupplierName by remember { mutableStateOf(initialV?.supplierName ?: suppliers.firstOrNull()?.name ?: "") }
    var selectedReceiverName by remember { mutableStateOf(initialV?.receiverName ?: parties.firstOrNull()?.name ?: "") }
    var selectedDisburserName by remember { mutableStateOf(initialV?.disburserName ?: parties.firstOrNull()?.name ?: "") }

    var selectedBeneficiaryId by remember {
        mutableStateOf(initialV?.beneficiaryId ?: beneficiaries.firstOrNull()?.id)
    }

    var notes by remember { mutableStateOf(initialV?.notes ?: "") }
    var attachmentUri by remember { mutableStateOf(initialV?.attachmentUri) }
    var attachmentType by remember { mutableStateOf(initialV?.attachmentType) }

    // Voucher Line Items state
    val lineItems = remember {
        mutableStateListOf<VoucherItem>().apply {
            if (preselectedLineItems != null && preselectedLineItems.isNotEmpty()) {
                addAll(preselectedLineItems)
            } else if (initialVoucherWithItems != null && initialVoucherWithItems.items.isNotEmpty()) {
                addAll(initialVoucherWithItems.items)
            } else if (items.isNotEmpty()) {
                val firstItem = items.first()
                add(
                    VoucherItem(
                        voucherId = 0L,
                        itemId = firstItem.id,
                        itemName = firstItem.name,
                        itemUnit = firstItem.unit,
                        packageUnit = firstItem.packageUnit,
                        packageQuantity = 0.0,
                        singleQuantity = 1.0,
                        unitsPerPackage = firstItem.unitsPerPackage,
                        quantity = 1.0,
                        unitPrice = firstItem.purchasePrice
                    )
                )
            }
        }
    }

    var errorMessage by remember { mutableStateOf("") }
    var showDeleteAttachmentDialog by remember { mutableStateOf(false) }
    var showAddBeneficiaryDialog by remember { mutableStateOf(false) }
    var showAddSupplierDialog by remember { mutableStateOf(false) }
    var showAddPartyDialog by remember { mutableStateOf(false) }

    // Media pickers
    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val copied = copyUriToInternalStorage(context, it, "img_${System.currentTimeMillis()}.jpg")
            attachmentUri = copied
            attachmentType = "IMAGE"
        }
    }

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val copied = copyUriToInternalStorage(context, it, "doc_${System.currentTimeMillis()}.pdf")
            attachmentUri = copied
            attachmentType = "PDF"
        }
    }

    val dialogTitle = when (voucherType) {
        VoucherType.INWARD -> if (initialV == null) "إضافة أمر توريد وارد جديد" else "تعديل أمر التوريد"
        VoucherType.TRANSFER -> if (initialV == null) "إضافة أمر تحويل بين المخازن" else "تعديل أمر التحويل"
        VoucherType.DISBURSEMENT -> if (initialV == null) "إضافة أمر صرف لجهة مستفيدة" else "تعديل أمر الصرف"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(text = dialogTitle, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Non-editable Auto-Sequential Voucher Number & Date
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = voucherNumber,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("رقم السند (تلقائي لا يتكرر) *") },
                        modifier = Modifier.weight(1.3f)
                    )

                    val dateStr = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()).format(Date(selectedDate))
                    OutlinedTextField(
                        value = dateStr,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("التاريخ (اضغط للاختيار) *") },
                        trailingIcon = {
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Default.CalendarMonth, contentDescription = "اختيار التاريخ", tint = MaterialTheme.colorScheme.primary)
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clickable { showDatePicker = true }
                    )
                }

                // Type-Specific Dropdowns
                when (voucherType) {
                    VoucherType.INWARD -> {
                        WarehouseDropdown(
                            label = "اسم المخزن المورد إليه *",
                            warehouses = warehouses,
                            selectedId = destWarehouseId,
                            onSelect = { destWarehouseId = it }
                        )

                        // Supplier Searchable Dropdown with + Add New Supplier, Edit, and Safe Delete
                        SearchableDropdownWithAdd(
                            label = "اسم المورد *",
                            items = suppliers.map { it.name }.sorted(),
                            selected = selectedSupplierName,
                            onSelect = { selectedSupplierName = it },
                            onAddNew = { showAddSupplierDialog = true },
                            onEdit = { oldName, newName ->
                                onUpdateSupplier?.invoke(oldName, newName)
                            },
                            onDelete = { name ->
                                val supp = suppliers.find { it.name == name }
                                if (supp != null) {
                                    onDeleteSupplier?.invoke(supp) {}
                                }
                            },
                            checkHasTransactions = { name ->
                                checkSupplierHasTransactions?.invoke(name) ?: false
                            }
                        )

                        // Warehouse Receiver Dropdown with Edit and Safe Delete
                        SearchableDropdownWithAdd(
                            label = "اسم المستلم في المخزن *",
                            items = parties.map { it.name }.sorted(),
                            selected = selectedReceiverName,
                            onSelect = { selectedReceiverName = it },
                            onAddNew = { showAddPartyDialog = true },
                            onEdit = { oldName, newName ->
                                onUpdateParty?.invoke(oldName, newName)
                            },
                            onDelete = { name ->
                                val pty = parties.find { it.name == name }
                                if (pty != null) {
                                    onDeleteParty?.invoke(pty) {}
                                }
                            },
                            checkHasTransactions = { name ->
                                checkPartyHasTransactions?.invoke(name) ?: false
                            }
                        )
                    }

                    VoucherType.TRANSFER -> {
                        WarehouseDropdown(
                            label = "مخزن الصرف (المصدر) *",
                            warehouses = warehouses,
                            selectedId = sourceWarehouseId,
                            enabled = fixedSourceWarehouseId == null,
                            onSelect = { sourceWarehouseId = it }
                        )

                        WarehouseDropdown(
                            label = "مخزن الاستلام (الوجهة) *",
                            warehouses = warehouses,
                            selectedId = destWarehouseId,
                            onSelect = { destWarehouseId = it }
                        )

                        SearchableDropdownWithAdd(
                            label = "اسم القائم بالصرف والتحويل *",
                            items = parties.map { it.name }.sorted(),
                            selected = selectedDisburserName,
                            onSelect = { selectedDisburserName = it },
                            onAddNew = { showAddPartyDialog = true },
                            onEdit = { oldName, newName ->
                                onUpdateParty?.invoke(oldName, newName)
                            },
                            onDelete = { name ->
                                val pty = parties.find { it.name == name }
                                if (pty != null) {
                                    onDeleteParty?.invoke(pty) {}
                                }
                            },
                            checkHasTransactions = { name ->
                                checkPartyHasTransactions?.invoke(name) ?: false
                            }
                        )

                        SearchableDropdownWithAdd(
                            label = "اسم المستلم في مخزن الوجهة *",
                            items = parties.map { it.name }.sorted(),
                            selected = selectedReceiverName,
                            onSelect = { selectedReceiverName = it },
                            onAddNew = { showAddPartyDialog = true },
                            onEdit = { oldName, newName ->
                                onUpdateParty?.invoke(oldName, newName)
                            },
                            onDelete = { name ->
                                val pty = parties.find { it.name == name }
                                if (pty != null) {
                                    onDeleteParty?.invoke(pty) {}
                                }
                            },
                            checkHasTransactions = { name ->
                                checkPartyHasTransactions?.invoke(name) ?: false
                            }
                        )
                    }

                    VoucherType.DISBURSEMENT -> {
                        WarehouseDropdown(
                            label = "مخزن الصرف *",
                            warehouses = warehouses,
                            selectedId = sourceWarehouseId,
                            enabled = fixedSourceWarehouseId == null,
                            onSelect = { sourceWarehouseId = it }
                        )

                        // Beneficiary entity dropdown (الجهة المستفيدة)
                        BeneficiaryEntityDropdown(
                            beneficiaries = beneficiaries,
                            selectedId = selectedBeneficiaryId,
                            onSelect = { selectedBeneficiaryId = it },
                            onAddNew = { showAddBeneficiaryDialog = true }
                        )

                        SearchableDropdownWithAdd(
                            label = "اسم القائم بالصرف *",
                            items = parties.map { it.name }.sorted(),
                            selected = selectedDisburserName,
                            onSelect = { selectedDisburserName = it },
                            onAddNew = { showAddPartyDialog = true },
                            onEdit = { oldName, newName ->
                                onUpdateParty?.invoke(oldName, newName)
                            },
                            onDelete = { name ->
                                val pty = parties.find { it.name == name }
                                if (pty != null) {
                                    onDeleteParty?.invoke(pty) {}
                                }
                            },
                            checkHasTransactions = { name ->
                                checkPartyHasTransactions?.invoke(name) ?: false
                            }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Line Items Section
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "أصناف السند (${lineItems.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    OutlinedButton(
                        onClick = {
                            val candidate = items.firstOrNull { i -> lineItems.none { it.itemId == i.id } } ?: items.firstOrNull()
                            if (candidate != null) {
                                lineItems.add(
                                    VoucherItem(
                                        voucherId = 0L,
                                        itemId = candidate.id,
                                        itemName = candidate.name,
                                        itemUnit = candidate.unit,
                                        packageUnit = candidate.packageUnit,
                                        packageQuantity = 0.0,
                                        singleQuantity = 1.0,
                                        unitsPerPackage = candidate.unitsPerPackage,
                                        quantity = 1.0,
                                        unitPrice = candidate.purchasePrice
                                    )
                                )
                            }
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إضافة صنف آخر", fontSize = 12.sp)
                    }
                }

                // Line items list with dual unit input & clean integers
                lineItems.forEachIndexed { index, lineItem ->
                    val originalQty = initialVoucherWithItems?.items?.find { it.itemId == lineItem.itemId }?.quantity ?: 0.0
                    val availableStock = if (voucherType != VoucherType.INWARD && sourceWarehouseId != null) {
                        (stockMap[Pair(sourceWarehouseId!!, lineItem.itemId)] ?: 0.0) + originalQty
                    } else null

                    DualUnitLineItemEditor(
                        index = index,
                        item = lineItem,
                        allItems = items,
                        availableStock = availableStock,
                        onUpdate = { updated -> lineItems[index] = updated },
                        onRemove = {
                            if (lineItems.size > 1) {
                                lineItems.removeAt(index)
                            }
                        },
                        canRemove = lineItems.size > 1
                    )
                }

                OutlinedTextField(
                    value = notes,
                    onValueChange = { notes = it },
                    label = { Text("ملاحظات إضافية (اختياري)") },
                    modifier = Modifier.fillMaxWidth(),
                    maxLines = 2
                )

                // Attachment Section
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "المرفقات (صورة أو مستند PDF)",
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
                                        text = if (attachmentType == "IMAGE") "تم حفظ مرفق الصورة" else "تم حفظ مرفق PDF",
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
                    // Validation: All fields mandatory except notes
                    if (voucherType == VoucherType.INWARD) {
                        if (destWarehouseId == null || selectedSupplierName.isBlank() || selectedReceiverName.isBlank()) {
                            errorMessage = "جميع الحقول إلزامية عدا الملاحظات (المخزن، المورد، والمستلم)"
                            return@Button
                        }
                        // Check if inward voucher edit reduces quantity below remaining stock in warehouse
                        if (initialV != null && destWarehouseId != null) {
                            for (item in lineItems) {
                                val oldQty = initialVoucherWithItems?.items?.find { it.itemId == item.itemId }?.quantity ?: 0.0
                                val currentStock = stockMap[Pair(destWarehouseId!!, item.itemId)] ?: 0.0
                                if (currentStock + (item.quantity - oldQty) < 0) {
                                    errorMessage = "لا يمكن تقليل كمية الصنف (${item.itemName}) لأن الرصيد المتبقي في المخزن سيصبح بالسالب بسبب عمليات صرف تمت سابقاً!"
                                    return@Button
                                }
                            }
                        }
                    } else if (voucherType == VoucherType.TRANSFER) {
                        if (sourceWarehouseId == null || destWarehouseId == null || selectedDisburserName.isBlank() || selectedReceiverName.isBlank()) {
                            errorMessage = "جميع الحقول إلزامية عدا الملاحظات"
                            return@Button
                        }
                        if (sourceWarehouseId == destWarehouseId) {
                            errorMessage = "لا يمكن أن يكون مخزن الصرف ومخزن الاستلام متطابقين"
                            return@Button
                        }
                    } else if (voucherType == VoucherType.DISBURSEMENT) {
                        if (sourceWarehouseId == null || selectedBeneficiaryId == null || selectedDisburserName.isBlank()) {
                            errorMessage = "جميع الحقول إلزامية عدا الملاحظات (مخزن الصرف، الجهة المستفيدة، القائم بالصرف)"
                            return@Button
                        }
                    }

                    // Validate stock availability for non-inward
                    if (voucherType != VoucherType.INWARD && sourceWarehouseId != null) {
                        for (item in lineItems) {
                            val originalQty = initialVoucherWithItems?.items?.find { it.itemId == item.itemId }?.quantity ?: 0.0
                            val available = (stockMap[Pair(sourceWarehouseId!!, item.itemId)] ?: 0.0) + originalQty
                            if (item.quantity > available) {
                                errorMessage = "الكمية المطلوبة للصنف (${item.itemName}) غير متوفرة! المتوفر: ${available.toInt()}"
                                return@Button
                            }
                        }
                    }

                    if (lineItems.isEmpty() || lineItems.any { it.quantity <= 0 }) {
                        errorMessage = "يرجى إدخال كمية صحيحة أكبر من صفر لجميع الأصناف"
                        return@Button
                    }

                    val beneficiaryName = beneficiaries.find { it.id == selectedBeneficiaryId }?.name ?: ""

                    val v = (initialV ?: Voucher(
                        voucherNumber = voucherNumber,
                        voucherType = voucherType
                    )).copy(
                        voucherNumber = voucherNumber,
                        voucherType = voucherType,
                        date = selectedDate,
                        sourceWarehouseId = sourceWarehouseId,
                        destinationWarehouseId = destWarehouseId,
                        supplierName = selectedSupplierName.trim(),
                        receiverName = selectedReceiverName.trim(),
                        beneficiaryId = selectedBeneficiaryId,
                        beneficiaryName = beneficiaryName,
                        disburserName = selectedDisburserName.trim(),
                        notes = notes.trim(),
                        attachmentUri = attachmentUri,
                    )

                    // Auto-persist new supplier if not already present
                    if (voucherType == VoucherType.INWARD && selectedSupplierName.isNotBlank()) {
                        if (suppliers.none { it.name.trim().equals(selectedSupplierName.trim(), ignoreCase = true) }) {
                            onAddNewSupplier?.invoke(Supplier(name = selectedSupplierName.trim()), {})
                        }
                    }

                    // Auto-persist new receiver if not already present
                    if (selectedReceiverName.isNotBlank()) {
                        if (parties.none { it.name.trim().equals(selectedReceiverName.trim(), ignoreCase = true) }) {
                            onAddNewParty?.invoke(WarehouseParty(name = selectedReceiverName.trim()), {})
                        }
                    }

                    // Auto-persist new disburser if not already present
                    if (selectedDisburserName.isNotBlank()) {
                        if (parties.none { it.name.trim().equals(selectedDisburserName.trim(), ignoreCase = true) }) {
                            onAddNewParty?.invoke(WarehouseParty(name = selectedDisburserName.trim()), {})
                        }
                    }

                    onSave(v, lineItems.toList())
                },
                modifier = Modifier.testTag("save_voucher_btn")
            ) {
                Text("حفظ السند")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        },
        shape = RoundedCornerShape(16.dp)
    )

    // Date Picker Dialog
    if (showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = selectedDate)
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let {
                            selectedDate = it
                        }
                        showDatePicker = false
                    }
                ) {
                    Text("تأكيد")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) {
                    Text("إلغاء")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Delete Attachment Confirmation Dialog
    if (showDeleteAttachmentDialog) {
        SimpleConfirmDialog(
            title = "تأكيد حذف المرفق",
            message = "هل أنت متأكد من حذف الملف المرفق مع هذا السند؟",
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

    // Inline Add Beneficiary Dialog
    if (showAddBeneficiaryDialog) {
        AddBeneficiaryQuickDialog(
            onDismiss = { showAddBeneficiaryDialog = false },
            onSave = { ben ->
                onAddNewBeneficiary?.invoke(ben) { newId ->
                    selectedBeneficiaryId = newId
                    showAddBeneficiaryDialog = false
                }
            }
        )
    }

    // Inline Add Supplier Dialog
    if (showAddSupplierDialog) {
        AddSupplierQuickDialog(
            onDismiss = { showAddSupplierDialog = false },
            onSave = { supp ->
                onAddNewSupplier?.invoke(supp) {
                    selectedSupplierName = supp.name
                    showAddSupplierDialog = false
                }
            }
        )
    }

    // Inline Add Party (Receiver/Disburser) Dialog
    if (showAddPartyDialog) {
        AddPartyQuickDialog(
            onDismiss = { showAddPartyDialog = false },
            onSave = { pty ->
                onAddNewParty?.invoke(pty) {
                    if (voucherType == VoucherType.INWARD) selectedReceiverName = pty.name
                    else selectedDisburserName = pty.name
                    showAddPartyDialog = false
                }
            }
        )
    }
}

/**
 * Dual Unit Line Item Editor: allows entering in (صندوق) and (حبة) simultaneously,
 * automatically showing the total count in (حبة) without decimals,
 * with stock shortage warning if availableStock is exceeded!
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DualUnitLineItemEditor(
    index: Int,
    item: VoucherItem,
    allItems: List<Item>,
    availableStock: Double?,
    onUpdate: (VoucherItem) -> Unit,
    onRemove: () -> Unit,
    canRemove: Boolean
) {
    val selectedItemCatalog = allItems.find { it.id == item.itemId }
    val packageUnit = selectedItemCatalog?.packageUnit ?: "صندوق"
    val singleUnit = selectedItemCatalog?.unit ?: "حبة"
    val unitsPerPackage = selectedItemCatalog?.unitsPerPackage ?: 1.0

    val isPackageApplicable = packageUnit != singleUnit && unitsPerPackage > 1.0

    var boxQtyText by remember(item.packageQuantity) {
        mutableStateOf(if (item.packageQuantity == 0.0) "" else item.packageQuantity.toInt().toString())
    }
    var singleQtyText by remember(item.singleQuantity) {
        mutableStateOf(if (item.singleQuantity == 0.0) "" else item.singleQuantity.toInt().toString())
    }

    val totalPieces = item.quantity
    val isExceedingStock = availableStock != null && totalPieces > availableStock

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isExceedingStock) DangerRed.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        ),
        border = if (isExceedingStock) CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(DangerRed)
        ) else null
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("الصنف #${index + 1}", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                if (canRemove) {
                    IconButton(onClick = onRemove, modifier = Modifier.size(22.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "حذف", tint = DangerRed, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Item dropdown showing only units entered for this item
            ItemDropdown(
                items = allItems,
                selectedId = item.itemId,
                onSelect = { selectedItem ->
                    val newUnitsPerPackage = selectedItem.unitsPerPackage
                    val newTotal = (item.packageQuantity * newUnitsPerPackage) + item.singleQuantity
                    onUpdate(
                        item.copy(
                            itemId = selectedItem.id,
                            itemName = selectedItem.name,
                            itemUnit = selectedItem.unit,
                            packageUnit = selectedItem.packageUnit,
                            unitsPerPackage = newUnitsPerPackage,
                            quantity = newTotal,
                            unitPrice = selectedItem.purchasePrice
                        )
                    )
                }
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Dual Unit Input: Box/Package quantity AND Single pieces quantity
            if (isPackageApplicable) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = boxQtyText,
                        onValueChange = {
                            val clean = it.filter { ch -> ch.isDigit() }
                            boxQtyText = clean
                            val boxes = clean.toDoubleOrNull() ?: 0.0
                            val pieces = singleQtyText.toDoubleOrNull() ?: 0.0
                            val total = (boxes * unitsPerPackage) + pieces
                            onUpdate(
                                item.copy(
                                    packageQuantity = boxes,
                                    quantity = total
                                )
                            )
                        },
                        label = { Text("عدد ($packageUnit)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = singleQtyText,
                        onValueChange = {
                            val clean = it.filter { ch -> ch.isDigit() }
                            singleQtyText = clean
                            val pieces = clean.toDoubleOrNull() ?: 0.0
                            val boxes = boxQtyText.toDoubleOrNull() ?: 0.0
                            val total = (boxes * unitsPerPackage) + pieces
                            onUpdate(
                                item.copy(
                                    singleQuantity = pieces,
                                    quantity = total
                                )
                            )
                        },
                        label = { Text("عدد ($singleUnit)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }
            } else {
                OutlinedTextField(
                    value = singleQtyText,
                    onValueChange = {
                        val clean = it.filter { ch -> ch.isDigit() }
                        singleQtyText = clean
                        val pieces = clean.toDoubleOrNull() ?: 0.0
                        onUpdate(
                            item.copy(
                                singleQuantity = pieces,
                                quantity = pieces
                            )
                        )
                    },
                    label = { Text("الكمية بالـ ($singleUnit) *") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Total in Single Units Badge & Stock Availability
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(6.dp))
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "الإجمالي: ${totalPieces.toInt()} $singleUnit",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )

                if (availableStock != null) {
                    Text(
                        text = "المتوفر في المخزن: ${availableStock.toInt()} $singleUnit",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isExceedingStock) DangerRed else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isExceedingStock) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }

            // Serial Number and Status badges if item has them
            if (item.serialNumber.isNotBlank() || item.itemStatus.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (item.serialNumber.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Text(
                                text = "الرقم المميز: ${item.serialNumber}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                    if (item.itemStatus.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Text(
                                text = "الحالة: ${item.itemStatus}",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Dedicated Receiver for this item
            OutlinedTextField(
                value = item.itemReceiverName,
                onValueChange = { onUpdate(item.copy(itemReceiverName = it)) },
                label = { Text("اسم المستلم لهذا الصنف (اختياري)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(6.dp))

            // Item-Level Attachment (Image or PDF)
            val context = LocalContext.current
            var showDeleteAttachmentConfirm by remember { mutableStateOf(false) }

            val itemImagePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    val copied = copyUriToInternalStorage(context, it, "item_${System.currentTimeMillis()}.jpg")
                    onUpdate(item.copy(attachmentUri = copied, attachmentType = "IMAGE"))
                }
            }

            val itemPdfPicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent()
            ) { uri: Uri? ->
                uri?.let {
                    val copied = copyUriToInternalStorage(context, it, "item_${System.currentTimeMillis()}.pdf")
                    onUpdate(item.copy(attachmentUri = copied, attachmentType = "PDF"))
                }
            }

            if (item.attachmentUri.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = { itemImagePicker.launch("image/*") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Image, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إرفاق صورة للصنف", fontSize = 10.sp)
                    }
                    OutlinedButton(
                        onClick = { itemPdfPicker.launch("application/pdf") },
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.Description, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("إرفاق PDF للصنف", fontSize = 10.sp)
                    }
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = if (item.attachmentType == "IMAGE") Icons.Default.Image else Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "مرفق الصنف (${if (item.attachmentType == "IMAGE") "صورة" else "PDF"})",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Row {
                        IconButton(
                            onClick = {
                                val file = File(item.attachmentUri!!)
                                if (file.exists()) {
                                    val mime = if (item.attachmentType == "IMAGE") "image/*" else "application/pdf"
                                    ReportGenerator.viewFile(context, file, mime)
                                }
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Visibility, contentDescription = "معاينة", modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = { showDeleteAttachmentConfirm = true },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "حذف", tint = DangerRed, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            if (showDeleteAttachmentConfirm) {
                SimpleConfirmDialog(
                    title = "تأكيد حذف المرفق",
                    message = "هل أنت متأكد من حذف مرفق هذا الصنف؟",
                    onConfirm = {
                        onUpdate(item.copy(attachmentUri = null, attachmentType = null))
                        showDeleteAttachmentConfirm = false
                    },
                    onDismiss = { showDeleteAttachmentConfirm = false }
                )
            }

            if (isExceedingStock) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = DangerRed, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "تنبيه: الكمية المدخلة تتجاوز الرصيد المتوفر في هذا المخزن!",
                        color = DangerRed,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchableDropdownWithAdd(
    label: String,
    items: List<String>,
    selected: String,
    onSelect: (String) -> Unit,
    onAddNew: () -> Unit,
    onEdit: ((oldName: String, newName: String) -> Unit)? = null,
    onDelete: ((name: String) -> Unit)? = null,
    checkHasTransactions: (suspend (String) -> Boolean)? = null
) {
    val coroutineScope = rememberCoroutineScope()
    var expanded by remember { mutableStateOf(false) }
    var searchInput by remember { mutableStateOf(selected) }

    var itemToEdit by remember { mutableStateOf<String?>(null) }
    var editNewName by remember { mutableStateOf("") }
    var itemToDelete by remember { mutableStateOf<String?>(null) }
    var deleteBlockedMessage by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(selected) {
        searchInput = selected
    }

    val filtered = remember(items, searchInput) {
        if (searchInput.isBlank()) items
        else items.filter { it.contains(searchInput, ignoreCase = true) }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = searchInput,
                    onValueChange = {
                        searchInput = it
                        onSelect(it)
                        expanded = true
                    },
                    label = { Text(label) },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth(),
                    singleLine = true
                )
                if (filtered.isNotEmpty()) {
                    ExposedDropdownMenu(
                        expanded = expanded,
                        onDismissRequest = { expanded = false }
                    ) {
                        filtered.forEach { item ->
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(item, modifier = Modifier.weight(1f), maxLines = 1)
                                        if (onEdit != null) {
                                            IconButton(
                                                onClick = {
                                                    itemToEdit = item
                                                    editNewName = item
                                                    expanded = false
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "تعديل",
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        if (onDelete != null) {
                                            IconButton(
                                                onClick = {
                                                    expanded = false
                                                    coroutineScope.launch {
                                                        val hasTx = checkHasTransactions?.invoke(item) ?: false
                                                        if (hasTx) {
                                                            deleteBlockedMessage = "لا يمكن حذف ($item) لوجود حركات وعمليات سابقة مرتبطة به!"
                                                        } else {
                                                            itemToDelete = item
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.size(28.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "حذف",
                                                    tint = DangerRed,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                    }
                                },
                                onClick = {
                                    searchInput = item
                                    onSelect(item)
                                    expanded = false
                                }
                            )
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Quick edit/delete for currently selected item if it exists in the list
        if (selected.isNotBlank() && items.contains(selected)) {
            if (onEdit != null) {
                IconButton(
                    onClick = {
                        itemToEdit = selected
                        editNewName = selected
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل الاسم",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            if (onDelete != null) {
                IconButton(
                    onClick = {
                        coroutineScope.launch {
                            val hasTx = checkHasTransactions?.invoke(selected) ?: false
                            if (hasTx) {
                                deleteBlockedMessage = "لا يمكن حذف ($selected) لوجود حركات وعمليات سابقة مرتبطة به!"
                            } else {
                                itemToDelete = selected
                            }
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف",
                        tint = DangerRed,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        IconButton(
            onClick = onAddNew,
            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape).size(40.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "إضافة جديد", tint = MaterialTheme.colorScheme.primary)
        }
    }

    // Edit Name Dialog
    if (itemToEdit != null) {
        AlertDialog(
            onDismissRequest = { itemToEdit = null },
            title = { Text("تعديل الاسم", fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = editNewName,
                    onValueChange = { editNewName = it },
                    label = { Text("الاسم الجديد *") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmed = editNewName.trim()
                        if (trimmed.isNotBlank()) {
                            onEdit?.invoke(itemToEdit!!, trimmed)
                            if (selected == itemToEdit) {
                                onSelect(trimmed)
                                searchInput = trimmed
                            }
                        }
                        itemToEdit = null
                    }
                ) {
                    Text("حفظ التعديل")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { itemToEdit = null }) {
                    Text("إلغاء")
                }
            }
        )
    }

    // Delete Confirmation Dialog
    if (itemToDelete != null) {
        SimpleConfirmDialog(
            title = "تأكيد الحذف",
            message = "هل أنت متأكد من حذف (${itemToDelete}) نهائياً؟ لن يمكن التراجع عن هذا الإجراء.",
            onConfirm = {
                val target = itemToDelete!!
                onDelete?.invoke(target)
                if (selected == target) {
                    onSelect("")
                    searchInput = ""
                }
                itemToDelete = null
            },
            onDismiss = { itemToDelete = null }
        )
    }

    // Blocked from Deletion Warning Dialog
    if (deleteBlockedMessage != null) {
        AlertDialog(
            onDismissRequest = { deleteBlockedMessage = null },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = WarningOrange)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("تعذر الحذف", fontWeight = FontWeight.Bold)
                }
            },
            text = { Text(deleteBlockedMessage!!) },
            confirmButton = {
                Button(onClick = { deleteBlockedMessage = null }) {
                    Text("فهمت ذلك")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BeneficiaryEntityDropdown(
    beneficiaries: List<Beneficiary>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    onAddNew: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = beneficiaries.find { it.id == selectedId }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.weight(1f)) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded },
                modifier = Modifier.fillMaxWidth()
            ) {
                OutlinedTextField(
                    value = current?.name ?: "اختر الجهة المستفيدة *",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("الجهة المستفيدة *") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    beneficiaries.forEach { ben ->
                        DropdownMenuItem(
                            text = { Text(ben.name) },
                            onClick = {
                                onSelect(ben.id)
                                expanded = false
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.width(6.dp))

        IconButton(
            onClick = onAddNew,
            modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape).size(42.dp)
        ) {
            Icon(Icons.Default.PersonAdd, contentDescription = "إضافة جهة مستفيدة", tint = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WarehouseDropdown(
    label: String,
    warehouses: List<Warehouse>,
    selectedId: Long?,
    enabled: Boolean = true,
    onSelect: (Long) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedWarehouse = warehouses.find { it.id == selectedId }

    ExposedDropdownMenuBox(
        expanded = if (enabled) expanded else false,
        onExpandedChange = { if (enabled) expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = selectedWarehouse?.name ?: "اختر المخزن",
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            trailingIcon = { if (enabled) ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        if (enabled) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                warehouses.forEach { w ->
                    DropdownMenuItem(
                        text = { Text(w.name) },
                        onClick = {
                            onSelect(w.id)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ItemDropdown(
    items: List<Item>,
    selectedId: Long,
    onSelect: (Item) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val current = items.find { it.id == selectedId }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth()
    ) {
        OutlinedTextField(
            value = current?.let { "${it.name} (${it.unit})" } ?: "اختر الصنف",
            onValueChange = {},
            readOnly = true,
            label = { Text("الصنف *") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth()
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            items.forEach { itm ->
                DropdownMenuItem(
                    text = { Text("${itm.name} (${itm.unit})") },
                    onClick = {
                        onSelect(itm)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AddBeneficiaryQuickDialog(
    onDismiss: () -> Unit,
    onSave: (Beneficiary) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var department by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة جهة مستفيدة جديدة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("اسم الجهة المستفيدة *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = department,
                    onValueChange = { department = it },
                    label = { Text("القسم أو الفرع") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("رقم الهاتف") },
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
                        error = "يرجى كتابة اسم الجهة المستفيدة"
                        return@Button
                    }
                    onSave(
                        Beneficiary(
                            name = name.trim(),
                            department = department.trim(),
                            phone = phone.trim()
                        )
                    )
                }
            ) { Text("إضافة") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun AddSupplierQuickDialog(
    onDismiss: () -> Unit,
    onSave: (Supplier) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة مورد جديد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("اسم المورد / الشركة *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("رقم الهاتف") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    label = { Text("العنوان") },
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
                        error = "يرجى كتابة اسم المورد"
                        return@Button
                    }
                    onSave(Supplier(name = name.trim(), phone = phone.trim(), address = address.trim()))
                }
            ) { Text("إضافة") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun AddPartyQuickDialog(
    onDismiss: () -> Unit,
    onSave: (WarehouseParty) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة اسم مستلم / قائم بالصرف", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; error = "" },
                    label = { Text("الاسم *") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = phone,
                    onValueChange = { phone = it },
                    label = { Text("رقم الهاتف") },
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
                        error = "يرجى كتابة الاسم"
                        return@Button
                    }
                    onSave(WarehouseParty(name = name.trim(), phone = phone.trim()))
                }
            ) { Text("إضافة") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

private fun copyUriToInternalStorage(context: Context, uri: Uri, fileName: String): String {
    val attachmentsDir = File(context.filesDir, "attachments")
    if (!attachmentsDir.exists()) attachmentsDir.mkdirs()
    val destFile = File(attachmentsDir, fileName)
    context.contentResolver.openInputStream(uri)?.use { input ->
        FileOutputStream(destFile).use { output ->
            input.copyTo(output)
        }
    }
    return destFile.absolutePath
}
