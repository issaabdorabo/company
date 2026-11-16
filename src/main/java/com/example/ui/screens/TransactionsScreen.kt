package com.example.ui.screens

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TableChart
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.VoucherWithItems
import com.example.data.model.VoucherType
import com.example.data.model.Warehouse
import com.example.data.model.WarehouseItemStock
import com.example.data.repository.StockSnapshot
import com.example.ui.components.SimpleConfirmDialog
import com.example.ui.components.SubDeviceRestrictedDialog
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.DeviceAction
import com.example.ui.viewmodel.InventoryViewModel
import com.example.util.ReportGenerator
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun TransactionsScreen(
    viewModel: InventoryViewModel,
    snapshot: StockSnapshot?,
    initialTypeFilter: VoucherType? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedTypeFilter by remember { mutableStateOf<VoucherType?>(initialTypeFilter) }
    var selectedWarehouseFilterId by remember { mutableStateOf<Long?>(null) }
    var searchQuery by remember { mutableStateOf("") }

    // Dialog states for creation
    var activeVoucherTypeForCreate by remember { mutableStateOf<VoucherType?>(null) }
    var nextVoucherNumForCreate by remember { mutableStateOf("") }
    var voucherToEdit by remember { mutableStateOf<VoucherWithItems?>(null) }

    // Deletion states
    var voucherToDelete by remember { mutableStateOf<VoucherWithItems?>(null) }
    var attachmentToDeleteVoucherId by remember { mutableStateOf<Long?>(null) }

    // Voucher Export & Preview Dialog
    var voucherToExport by remember { mutableStateOf<VoucherWithItems?>(null) }
    var restrictedActionName by remember { mutableStateOf<String?>(null) }

    val vouchers = snapshot?.vouchers ?: emptyList()
    val warehouses = snapshot?.warehouses ?: emptyList()
    val items = snapshot?.items ?: emptyList()
    val beneficiaries by viewModel.beneficiaries.collectAsState()
    val suppliers by viewModel.suppliers.collectAsState()
    val parties by viewModel.parties.collectAsState()
    val appUserName by viewModel.userName.collectAsState()
    val orgName by viewModel.orgName.collectAsState()

    // Map of Pair(warehouseId, itemId) -> stock
    val stockMap = remember(snapshot) {
        val map = mutableMapOf<Pair<Long, Long>, Double>()
        snapshot?.warehouseItemStocks?.forEach {
            map[Pair(it.warehouseId, it.itemId)] = it.currentStock
        }
        map
    }

    val filteredVouchers = remember(vouchers, selectedTypeFilter, selectedWarehouseFilterId, searchQuery) {
        vouchers.filter { vWithItems ->
            val v = vWithItems.voucher
            val matchesType = selectedTypeFilter == null || v.voucherType == selectedTypeFilter
            val matchesWarehouse = selectedWarehouseFilterId == null ||
                v.sourceWarehouseId == selectedWarehouseFilterId ||
                v.destinationWarehouseId == selectedWarehouseFilterId

            val matchesSearch = searchQuery.isBlank() ||
                v.voucherNumber.contains(searchQuery, ignoreCase = true) ||
                v.supplierName.contains(searchQuery, ignoreCase = true) ||
                v.beneficiaryName.contains(searchQuery, ignoreCase = true) ||
                v.notes.contains(searchQuery, ignoreCase = true) ||
                vWithItems.items.any { it.itemName.contains(searchQuery, ignoreCase = true) }

            matchesType && matchesWarehouse && matchesSearch
        }
    }

    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // Quick Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.INWARD,
                            onDenied = { restrictedActionName = "تسجيل فواتير الوارد والتوريد" }
                        ) {
                            coroutineScope.launch {
                                nextVoucherNumForCreate = viewModel.getNextVoucherNumber(VoucherType.INWARD)
                                activeVoucherTypeForCreate = VoucherType.INWARD
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    modifier = Modifier.weight(1f).testTag("new_inward_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ArrowDownward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("وارد جديد", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.DISBURSE_OR_TRANSFER,
                            onDenied = { restrictedActionName = "صرف الأصناف للجهات المستفيدة" }
                        ) {
                            coroutineScope.launch {
                                nextVoucherNumForCreate = viewModel.getNextVoucherNumber(VoucherType.DISBURSEMENT)
                                activeVoucherTypeForCreate = VoucherType.DISBURSEMENT
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                    modifier = Modifier.weight(1f).testTag("new_disbursement_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.ArrowUpward, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("صرف جهة", fontSize = 12.sp)
                }

                Button(
                    onClick = {
                        viewModel.checkPermission(
                            action = DeviceAction.DISBURSE_OR_TRANSFER,
                            onDenied = { restrictedActionName = "إجراء تحويل مخزني" }
                        ) {
                            coroutineScope.launch {
                                nextVoucherNumForCreate = viewModel.getNextVoucherNumber(VoucherType.TRANSFER)
                                activeVoucherTypeForCreate = VoucherType.TRANSFER
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                    modifier = Modifier.weight(1f).testTag("new_transfer_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("تحويل مخزني", fontSize = 12.sp)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Search input
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("بحث برقم السند، المورد، الجهة المستفيدة، أو الصنف...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier.fillMaxWidth().testTag("vouchers_search_input"),
                shape = RoundedCornerShape(12.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Filter Chips by Voucher Type
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    FilterChip(
                        selected = selectedTypeFilter == null,
                        onClick = { selectedTypeFilter = null },
                        label = { Text("الكل (${vouchers.size})") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedTypeFilter == VoucherType.INWARD,
                        onClick = { selectedTypeFilter = VoucherType.INWARD },
                        label = { Text("أوامر الوارد") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedTypeFilter == VoucherType.DISBURSEMENT,
                        onClick = { selectedTypeFilter = VoucherType.DISBURSEMENT },
                        label = { Text("صرف الجهات المستفيدة") }
                    )
                }
                item {
                    FilterChip(
                        selected = selectedTypeFilter == VoucherType.TRANSFER,
                        onClick = { selectedTypeFilter = VoucherType.TRANSFER },
                        label = { Text("التحويلات بين المخازن") }
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (filteredVouchers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize().weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isEmpty()) "لا توجد حركات مسجلة بهذا الفلتر" else "لا توجد نتائج مطابقة للبحث",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(filteredVouchers, key = { it.voucher.id }) { vWithItems ->
                        ExpandableVoucherCard(
                            vWithItems = vWithItems,
                            warehouses = warehouses,
                            dateFormat = dateFormat,
                            onEdit = {
                                viewModel.checkPermission(
                                    action = DeviceAction.EDIT_OR_DELETE,
                                    onDenied = { restrictedActionName = "تعديل السندات والحركات" }
                                ) {
                                    voucherToEdit = vWithItems
                                }
                            },
                            onDelete = {
                                viewModel.checkPermission(
                                    action = DeviceAction.EDIT_OR_DELETE,
                                    onDenied = { restrictedActionName = "حذف السندات والحركات" }
                                ) {
                                    voucherToDelete = vWithItems
                                }
                            },
                            onExport = { voucherToExport = vWithItems },
                            onDeleteAttachment = {
                                viewModel.checkPermission(
                                    action = DeviceAction.EDIT_OR_DELETE,
                                    onDenied = { restrictedActionName = "حذف مرفقات السندات" }
                                ) {
                                    attachmentToDeleteVoucherId = vWithItems.voucher.id
                                }
                            },
                            onViewAttachment = { path, type ->
                                val file = File(path)
                                if (file.exists()) {
                                    val mime = if (type == "IMAGE") "image/*" else "application/pdf"
                                    ReportGenerator.viewFile(context, file, mime)
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

    // Dialog for creating new voucher
    if (activeVoucherTypeForCreate != null) {
        VoucherFormDialog(
            voucherType = activeVoucherTypeForCreate!!,
            initialVoucherWithItems = null,
            fixedVoucherNumber = nextVoucherNumForCreate,
            warehouses = warehouses,
            items = items,
            beneficiaries = beneficiaries,
            suppliers = suppliers,
            parties = parties,
            stockMap = stockMap,
            onAddNewBeneficiary = { ben, onDone -> viewModel.saveBeneficiary(ben, onDone) },
            onAddNewSupplier = { supp, onDone -> viewModel.saveSupplier(supp, onDone) },
            onAddNewParty = { pty, onDone -> viewModel.saveParty(pty, onDone) },
            onUpdateSupplier = { oldName, newName -> viewModel.updateSupplierName(oldName, newName) },
            onDeleteSupplier = { supp, onResult -> viewModel.deleteSupplier(supp, onResult) },
            checkSupplierHasTransactions = { name -> viewModel.checkSupplierHasTransactions(name) },
            onUpdateParty = { oldName, newName -> viewModel.updatePartyName(oldName, newName) },
            onDeleteParty = { pty, onResult -> viewModel.deleteParty(pty, onResult) },
            checkPartyHasTransactions = { name -> viewModel.checkPartyHasTransactions(name) },
            onDismiss = { activeVoucherTypeForCreate = null },
            onSave = { voucher, lineItems ->
                viewModel.saveVoucher(voucher, lineItems) {
                    activeVoucherTypeForCreate = null
                }
            }
        )
    }

    // Dialog for editing existing voucher
    if (voucherToEdit != null) {
        VoucherFormDialog(
            voucherType = voucherToEdit!!.voucher.voucherType,
            initialVoucherWithItems = voucherToEdit,
            fixedVoucherNumber = voucherToEdit!!.voucher.voucherNumber,
            warehouses = warehouses,
            items = items,
            beneficiaries = beneficiaries,
            suppliers = suppliers,
            parties = parties,
            stockMap = stockMap,
            onAddNewBeneficiary = { ben, onDone -> viewModel.saveBeneficiary(ben, onDone) },
            onAddNewSupplier = { supp, onDone -> viewModel.saveSupplier(supp, onDone) },
            onAddNewParty = { pty, onDone -> viewModel.saveParty(pty, onDone) },
            onUpdateSupplier = { oldName, newName -> viewModel.updateSupplierName(oldName, newName) },
            onDeleteSupplier = { supp, onResult -> viewModel.deleteSupplier(supp, onResult) },
            checkSupplierHasTransactions = { name -> viewModel.checkSupplierHasTransactions(name) },
            onUpdateParty = { oldName, newName -> viewModel.updatePartyName(oldName, newName) },
            onDeleteParty = { pty, onResult -> viewModel.deleteParty(pty, onResult) },
            checkPartyHasTransactions = { name -> viewModel.checkPartyHasTransactions(name) },
            onDismiss = { voucherToEdit = null },
            onSave = { voucher, lineItems ->
                viewModel.saveVoucher(voucher, lineItems) {
                    voucherToEdit = null
                }
            }
        )
    }

    // Single Voucher Export & Column Selection Dialog
    if (voucherToExport != null) {
        SingleVoucherExportDialog(
            voucherWithItems = voucherToExport!!,
            warehouses = warehouses,
            appUserName = appUserName,
            orgName = orgName,
            onDismiss = { voucherToExport = null }
        )
    }

    // Confirm Voucher Deletion / Cancellation Dialog
    if (voucherToDelete != null) {
        val v = voucherToDelete!!.voucher
        val actionTitle = when (v.voucherType) {
            VoucherType.INWARD -> "حذف أمر التوريد"
            VoucherType.TRANSFER -> "إلغاء وحذف أمر التحويل"
            VoucherType.DISBURSEMENT -> "إلغاء وحذف أمر الصرف"
        }
        val confirmMessage = if (v.voucherType == VoucherType.DISBURSEMENT) {
            "هل أنت متأكد من إلغاء وحذف أمر الصرف \"${v.voucherNumber}\"؟ سيتم إعادة الكميات والوحدات المرقمة مع كامل بياناتها (الرقم المميز والحالة والملاحظات) إلى رصيد المخزن فورياً."
        } else {
            "هل أنت متأكد من حذف السند \"${v.voucherNumber}\"؟ سيتم تعديل رصيد المخزون فورياً بناءً على الحذف."
        }
        SimpleConfirmDialog(
            title = "تأكيد $actionTitle",
            message = confirmMessage,
            confirmButtonText = "نعم، احذف وأعد المخزون",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteVoucher(v.id)
                voucherToDelete = null
            },
            onDismiss = { voucherToDelete = null }
        )
    }

    // Confirm Attachment Deletion Dialog
    if (attachmentToDeleteVoucherId != null) {
        SimpleConfirmDialog(
            title = "تأكيد حذف المرفق",
            message = "هل أنت متأكد من حذف الملف المرفق مع هذا السند؟",
            confirmButtonText = "نعم، احذف المرفق",
            isDestructive = true,
            onConfirm = {
                viewModel.removeAttachment(attachmentToDeleteVoucherId!!)
                attachmentToDeleteVoucherId = null
            },
            onDismiss = { attachmentToDeleteVoucherId = null }
        )
    }
}

/**
 * Expandable Voucher Card: clicking the card toggles an expandable view
 * showing all item lines and full transaction details, with action buttons to Export PDF & Excel!
 */
@Composable
private fun ExpandableVoucherCard(
    vWithItems: VoucherWithItems,
    warehouses: List<Warehouse>,
    dateFormat: SimpleDateFormat,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    onDeleteAttachment: () -> Unit,
    onViewAttachment: (String, String) -> Unit
) {
    val v = vWithItems.voucher
    var isExpanded by remember { mutableStateOf(false) }

    val badgeColor = when (v.voucherType) {
        VoucherType.INWARD -> SuccessGreen
        VoucherType.TRANSFER -> InfoBlue
        VoucherType.DISBURSEMENT -> WarningOrange
    }
    val typeIcon = when (v.voucherType) {
        VoucherType.INWARD -> Icons.Default.ArrowDownward
        VoucherType.TRANSFER -> Icons.Default.SwapHoriz
        VoucherType.DISBURSEMENT -> Icons.Default.ArrowUpward
    }

    val srcWarehouse = warehouses.find { it.id == v.sourceWarehouseId }?.name ?: "مخزن غير محدد"
    val destWarehouse = warehouses.find { it.id == v.destinationWarehouseId }?.name ?: "مخزن غير محدد"

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { isExpanded = !isExpanded }
            .testTag("voucher_card_${v.id}"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            // Top Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = CircleShape,
                        color = badgeColor.copy(alpha = 0.15f),
                        modifier = Modifier.size(38.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(typeIcon, contentDescription = null, tint = badgeColor, modifier = Modifier.size(20.dp))
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = v.voucherNumber,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(4.dp),
                                color = badgeColor.copy(alpha = 0.15f)
                            ) {
                                Text(
                                    text = v.voucherType.titleAr,
                                    color = badgeColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = dateFormat.format(Date(v.date)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onExport) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = "تصدير ومعاينة", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "تعديل", tint = MaterialTheme.colorScheme.primary)
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف", tint = DangerRed)
                    }
                    IconButton(onClick = { isExpanded = !isExpanded }) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "تفاصيل العملية",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Parties & Warehouses Overview
            when (v.voucherType) {
                VoucherType.INWARD -> {
                    Text(
                        text = "المخزن: $destWarehouse | المورد: ${v.supplierName.ifBlank { "غير محدد" }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        softWrap = true
                    )
                }
                VoucherType.TRANSFER -> {
                    Text(
                        text = "من: $srcWarehouse ⬅️ إلى: $destWarehouse",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        softWrap = true
                    )
                }
                VoucherType.DISBURSEMENT -> {
                    Text(
                        text = "مخزن الصرف: $srcWarehouse | الجهة المستفيدة: ${v.beneficiaryName.ifBlank { "غير محددة" }}",
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary,
                        softWrap = true
                    )
                }
            }

            // Summary of line items count
            Text(
                text = "عدد الأصناف: ${vWithItems.items.size} | إجمالي الكمية: ${vWithItems.items.sumOf { it.quantity }.toInt()} حبة",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Expandable Detailed Section
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    Text(
                        text = "تفاصيل الأصناف والكميات بالعملية:",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.height(4.dp))

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        vWithItems.items.forEachIndexed { idx, itm ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${idx + 1}. ${itm.itemName}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold,
                                        softWrap = true
                                    )
                                    val pkgDetail = if (itm.packageQuantity > 0) {
                                        "${itm.packageQuantity.toInt()} ${itm.packageUnit} + ${itm.singleQuantity.toInt()} ${itm.itemUnit}"
                                    } else {
                                        "${itm.quantity.toInt()} ${itm.itemUnit}"
                                    }
                                    Text(
                                        text = "الكمية: $pkgDetail (المجموع: ${itm.quantity.toInt()} حبة)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = badgeColor
                                    )
                                    val unitMeta = listOfNotNull(
                                        itm.serialNumber.takeIf { it.isNotBlank() }?.let { "الرقم المميز: $it" },
                                        itm.itemStatus.takeIf { it.isNotBlank() }?.let { "الحالة: $it" },
                                        itm.notes.takeIf { it.isNotBlank() }?.let { "ملاحظة: $it" }
                                    ).joinToString(" | ")
                                    if (unitMeta.isNotBlank()) {
                                        Text(
                                            text = unitMeta,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            softWrap = true
                                        )
                                    }
                                }
                                if (itm.unitPrice > 0) {
                                    Text(
                                        text = "%.2f $".format(itm.quantity * itm.unitPrice),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    // Parties Details in expanded view
                    Spacer(modifier = Modifier.height(6.dp))
                    if (v.receiverName.isNotBlank() || v.disburserName.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            if (v.disburserName.isNotBlank()) {
                                Text(
                                    text = "القائم بالصرف: ${v.disburserName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (v.receiverName.isNotBlank()) {
                                Text(
                                    text = "المستلم: ${v.receiverName}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    if (v.notes.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "ملاحظات: ${v.notes}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Export voucher button inside expanded view
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = onExport,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("معاينة وتصدير السند (PDF / Excel)", fontSize = 12.sp)
                    }
                }
            }

            // Attachment view / delete bar
            if (!v.attachmentUri.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable {
                            onViewAttachment(v.attachmentUri, v.attachmentType ?: "IMAGE")
                        }
                    ) {
                        Icon(
                            imageVector = if (v.attachmentType == "PDF") Icons.Default.Description else Icons.Default.Image,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "مرفق (${if (v.attachmentType == "PDF") "مستند PDF" else "صورة"}) - انقر للمعاينة",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    IconButton(
                        onClick = onDeleteAttachment,
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف المرفق",
                            tint = DangerRed,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Single Voucher Export & Preview Dialog with column selection checkboxes
 */
@Composable
private fun SingleVoucherExportDialog(
    voucherWithItems: VoucherWithItems,
    warehouses: List<Warehouse>,
    appUserName: String,
    orgName: String,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val v = voucherWithItems.voucher

    // Columns that can be shown/hidden
    val showVoucherNum = remember { mutableStateOf(true) }
    val showItemName = remember { mutableStateOf(true) }
    val showUnit = remember { mutableStateOf(true) }
    val showWarehouse = remember { mutableStateOf(true) }
    val showBeneficiary = remember { mutableStateOf(v.beneficiaryName.isNotBlank()) }
    val showTotalPieces = remember { mutableStateOf(true) }
    val showUnitPrice = remember { mutableStateOf(true) }
    val showTotalPrice = remember { mutableStateOf(true) }

    val destWarehouse = warehouses.find { it.id == v.destinationWarehouseId }?.name ?: ""
    val srcWarehouse = warehouses.find { it.id == v.sourceWarehouseId }?.name ?: ""
    val warehouseName = when (v.voucherType) {
        VoucherType.INWARD -> destWarehouse
        VoucherType.TRANSFER -> "من $srcWarehouse إلى $destWarehouse"
        VoucherType.DISBURSEMENT -> srcWarehouse
    }

    val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "معاينة وتصدير السند: ${v.voucherNumber}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Header details
                Text(
                    text = "${v.voucherType.titleAr} - $warehouseName",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = "التاريخ: ${dateFormat.format(Date(v.date))} | المستخدم: $appUserName",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(4.dp))

                // Column Selection Checkboxes
                Text("تخصيص أعمدة المعاينة والتصدير:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showVoucherNum.value, onCheckedChange = { showVoucherNum.value = it })
                        Text("رقم الحركة", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showItemName.value, onCheckedChange = { showItemName.value = it })
                        Text("اسم الصنف", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showWarehouse.value, onCheckedChange = { showWarehouse.value = it })
                        Text("المخزن", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showUnit.value, onCheckedChange = { showUnit.value = it })
                        Text("الوحدة", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showTotalPieces.value, onCheckedChange = { showTotalPieces.value = it })
                        Text("إجمالي الكمية", style = MaterialTheme.typography.labelSmall)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showUnitPrice.value, onCheckedChange = { showUnitPrice.value = it })
                        Text("السعر", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Row(modifier = Modifier.fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                        Checkbox(checked = showTotalPrice.value, onCheckedChange = { showTotalPrice.value = it })
                        Text("الإجمالي", style = MaterialTheme.typography.labelSmall)
                    }
                    if (v.beneficiaryName.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(checked = showBeneficiary.value, onCheckedChange = { showBeneficiary.value = it })
                            Text("الجهة المستفيدة", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                // Preview Table
                val scrollState = rememberScrollState()
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(scrollState)
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    Column {
                        // Header
                        Row(modifier = Modifier.background(MaterialTheme.colorScheme.primaryContainer).padding(6.dp)) {
                            Text("م", modifier = Modifier.width(36.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showVoucherNum.value) Text("رقم الحركة", modifier = Modifier.width(90.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showItemName.value) Text("الصنف", modifier = Modifier.width(130.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showUnit.value) Text("الوحدة", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showWarehouse.value) Text("المخزن", modifier = Modifier.width(110.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showTotalPieces.value) Text("الكمية", modifier = Modifier.width(80.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showUnitPrice.value) Text("السعر", modifier = Modifier.width(60.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                            if (showTotalPrice.value) Text("الإجمالي", modifier = Modifier.width(70.dp), fontWeight = FontWeight.Bold, fontSize = 11.sp)
                        }

                        // Rows
                        voucherWithItems.items.forEachIndexed { idx, itm ->
                            Row(modifier = Modifier.padding(6.dp)) {
                                Text("${idx + 1}", modifier = Modifier.width(36.dp), fontSize = 11.sp)
                                if (showVoucherNum.value) Text(v.voucherNumber, modifier = Modifier.width(90.dp), fontSize = 11.sp)
                                if (showItemName.value) Text(itm.itemName, modifier = Modifier.width(130.dp), fontSize = 11.sp)
                                if (showUnit.value) Text(itm.itemUnit, modifier = Modifier.width(60.dp), fontSize = 11.sp)
                                if (showWarehouse.value) Text(warehouseName, modifier = Modifier.width(110.dp), fontSize = 11.sp)
                                if (showTotalPieces.value) Text("${itm.quantity.toInt()} حبة", modifier = Modifier.width(80.dp), fontSize = 11.sp)
                                if (showUnitPrice.value) Text("%.1f".format(itm.unitPrice), modifier = Modifier.width(60.dp), fontSize = 11.sp)
                                if (showTotalPrice.value) Text("%.1f".format(itm.quantity * itm.unitPrice), modifier = Modifier.width(70.dp), fontSize = 11.sp)
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            val buildSelectedColumns = {
                mutableSetOf(ReportGenerator.ReportColumn.SERIAL).apply {
                    if (showVoucherNum.value) add(ReportGenerator.ReportColumn.VOUCHER_NUMBER)
                    if (showItemName.value) add(ReportGenerator.ReportColumn.ITEM_NAME)
                    if (showUnit.value) add(ReportGenerator.ReportColumn.ITEM_UNIT)
                    if (showWarehouse.value) add(ReportGenerator.ReportColumn.WAREHOUSE)
                    if (showBeneficiary.value && v.beneficiaryName.isNotBlank()) add(ReportGenerator.ReportColumn.BENEFICIARY)
                    if (showTotalPieces.value) add(ReportGenerator.ReportColumn.TOTAL_QUANTITY)
                    if (showUnitPrice.value) add(ReportGenerator.ReportColumn.PURCHASE_PRICE)
                    if (showTotalPrice.value) add(ReportGenerator.ReportColumn.TOTAL_VALUE)
                }
            }
            val reportRows = voucherWithItems.items.map { vi ->
                ReportGenerator.ReportRowData(
                    voucherNumber = v.voucherNumber,
                    warehouseName = warehouseName,
                    itemName = vi.itemName,
                    itemUnit = vi.itemUnit,
                    beneficiaryName = v.beneficiaryName,
                    receiverName = v.receiverName,
                    disburserName = v.disburserName,
                    voucherDate = dateFormat.format(Date(v.date)),
                    quantity = vi.quantity,
                    totalQuantity = vi.quantity,
                    minStockLevel = 0.0,
                    unitPrice = vi.unitPrice,
                    totalValue = vi.quantity * vi.unitPrice
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = {
                        val pdf = ReportGenerator.generatePdf(
                            context = context,
                            title = "${v.voucherType.titleAr} - ${v.voucherNumber}",
                            warehouseName = warehouseName,
                            appUserName = appUserName,
                            orgName = orgName,
                            rows = reportRows,
                            selectedColumns = buildSelectedColumns()
                        )
                        ReportGenerator.viewFile(context, pdf, "application/pdf")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text("معاينة مباشرة", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        val pdf = ReportGenerator.generatePdf(
                            context = context,
                            title = "${v.voucherType.titleAr} - ${v.voucherNumber}",
                            warehouseName = warehouseName,
                            appUserName = appUserName,
                            orgName = orgName,
                            rows = reportRows,
                            selectedColumns = buildSelectedColumns()
                        )
                        ReportGenerator.shareFile(context, pdf, "application/pdf", "مشاركة السند PDF")
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed)
                ) {
                    Text("مشاركة PDF", fontSize = 11.sp)
                }

                Button(
                    onClick = {
                        val excel = ReportGenerator.generateExcel(
                            context = context,
                            title = "${v.voucherType.titleAr} - ${v.voucherNumber}",
                            warehouseName = warehouseName,
                            appUserName = appUserName,
                            rows = reportRows,
                            selectedColumns = buildSelectedColumns()
                        )
                        ReportGenerator.shareFile(context, excel, "text/csv", "مشاركة السند Excel")
                        onDismiss()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen)
                ) {
                    Text("مشاركة Excel", fontSize = 11.sp)
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}
