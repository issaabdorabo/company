package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.model.OperationAuditLog
import com.example.ui.viewmodel.InventoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun AuditLogScreen(viewModel: InventoryViewModel, isMainDevice: Boolean = true, currentDeviceName: String = "") {
    val allLogs by viewModel.operationAuditLogs.collectAsState()
    val logs = if (isMainDevice) allLogs else allLogs.filter {
        it.deviceName == currentDeviceName || it.deviceName.endsWith(" • سحابي")
    }
    var search by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("ALL") }
    val selectedDevices = remember { mutableStateListOf<String>() }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var expandedId by remember { mutableStateOf<Long?>(null) }
    var fromDate by remember { mutableStateOf<Long?>(null) }
    var toDate by remember { mutableStateOf<Long?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    val dateTimeFormat = remember { SimpleDateFormat("yyyy/MM/dd • HH:mm:ss", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    val types = remember(logs) { logs.map { it.operationType }.filter { it.isNotBlank() }.distinct().sorted() }
    val devices = remember(logs) { logs.map { it.deviceName }.filter { it.isNotBlank() }.distinct().sorted() }

    val filteredLogs = remember(logs, search, selectedDevices.toList(), typeFilter, fromDate, toDate) {
        logs.filter { log ->
            val queryMatch = search.isBlank() || listOf(log.title, log.details, log.actorName, log.deviceName, log.operationType).any { it.contains(search.trim(), true) }
            val deviceMatch = selectedDevices.isEmpty() || log.deviceName in selectedDevices
            val typeMatch = typeFilter == "ALL" || log.operationType == typeFilter
            val fromMatch = fromDate == null || log.timestamp >= (fromDate!! / 86_400_000L) * 86_400_000L
            val toMatch = toDate == null || log.timestamp < ((toDate!! / 86_400_000L) + 1L) * 86_400_000L
            queryMatch && deviceMatch && typeMatch && fromMatch && toMatch
        }.sortedByDescending { it.timestamp }
    }

    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FilterAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("بحث وتصفية سجل العمليات", fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(value = search, onValueChange = { search = it }, modifier = Modifier.fillMaxWidth(), singleLine = true, label = { Text("بحث في تفاصيل العملية") }, leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) })
                    Text("تصفية حسب الجهاز المنفذ", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(selected = selectedDevices.isEmpty(), onClick = { selectedDevices.clear() }, label = { Text("كل الأجهزة") }) }
                        items(devices) { device -> FilterChip(selected = device in selectedDevices, onClick = { if (device in selectedDevices) selectedDevices.remove(device) else selectedDevices.add(device) }, label = { Text(device) }) }
                    }
                    Text("نوع العملية", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(selected = typeFilter == "ALL", onClick = { typeFilter = "ALL" }, label = { Text("الكل") }) }
                        items(types) { type -> FilterChip(selected = typeFilter == type, onClick = { typeFilter = type }, label = { Text(operationTypeLabel(type)) }) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { showFromPicker = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CalendarMonth, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(fromDate?.let { "من ${dateFormat.format(Date(it))}" } ?: "من تاريخ") }
                        Button(onClick = { showToPicker = true }, modifier = Modifier.weight(1f)) { Icon(Icons.Default.CalendarMonth, contentDescription = null); Spacer(Modifier.width(4.dp)); Text(toDate?.let { "إلى ${dateFormat.format(Date(it))}" } ?: "إلى تاريخ") }
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("النتائج: ${filteredLogs.size} من ${logs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                        TextButton(onClick = { search = ""; selectedDevices.clear(); typeFilter = "ALL"; fromDate = null; toDate = null }) { Text("مسح الفلاتر") }
                    }
                    if (selectedIds.isNotEmpty()) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("تم تحديد ${selectedIds.size}", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                            OutlinedButton(onClick = { selectedIds.clear() }) { Text("إلغاء التحديد") }
                            Button(onClick = { selectedIds.clear(); selectedIds.addAll(filteredLogs.map { it.id }) }) { Text("تحديد الكل") }
                            Button(onClick = { viewModel.deleteOperationLogs(selectedIds.toList()); selectedIds.clear() }) { Icon(Icons.Default.Delete, contentDescription = null); Text("حذف") }
                        }
                    } else {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("اضغط مطولاً على بطاقة لتحديدها", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Button(onClick = { selectedIds.clear(); selectedIds.addAll(filteredLogs.map { it.id }) }, enabled = filteredLogs.isNotEmpty()) { Text("تحديد الكل") }
                        }
                    }
                }
            }
        }
        if (filteredLogs.isEmpty()) item { Card(modifier = Modifier.fillMaxWidth()) { Text("لا توجد عمليات مطابقة لعوامل البحث المحددة.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) } }
        else items(filteredLogs, key = { it.id }) { log ->
            val selected = log.id in selectedIds
            val expanded = expandedId == log.id
            Card(
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = {
                        if (selectedIds.isNotEmpty()) {
                            if (selected) selectedIds.remove(log.id) else selectedIds.add(log.id)
                        } else {
                            expandedId = if (expanded) null else log.id
                        }
                    },
                    onLongClick = {
                        if (selected) selectedIds.remove(log.id) else selectedIds.add(log.id)
                    }
                ),
                shape = RoundedCornerShape(16.dp),
                border = androidx.compose.foundation.BorderStroke(
                    if (selected) 2.dp else 1.dp,
                    if (selected) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.75f)
                ),
                elevation = CardDefaults.cardElevation(
                    defaultElevation = if (expanded) 4.dp else 2.dp,
                    pressedElevation = 5.dp
                ),
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        androidx.compose.material3.Surface(
                            shape = RoundedCornerShape(11.dp),
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(38.dp)
                        ) {
                            androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.FilterAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(
                                log.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${operationTypeLabel(log.operationType)} • ${log.deviceName.ifBlank { "جهاز غير محدد" }} • ${dateTimeFormat.format(Date(log.timestamp))}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        if (selected) {
                            Text("✓", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        } else {
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                contentDescription = if (expanded) "طي التفاصيل" else "عرض التفاصيل",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                    AnimatedVisibility(
                        visible = expanded,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            androidx.compose.material3.HorizontalDivider(
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.65f)
                            )
                            Text(
                                "نفّذها: ${log.actorName.ifBlank { "مستخدم غير محدد" }}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "تفاصيل العملية",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                log.details.ifBlank { "لم يتم حفظ تفاصيل إضافية لهذه العملية." },
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                "معرّف العملية: ${log.id}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showFromPicker) { val state = rememberDatePickerState(initialSelectedDateMillis = fromDate ?: System.currentTimeMillis()); DatePickerDialog(onDismissRequest = { showFromPicker = false }, confirmButton = { TextButton(onClick = { fromDate = state.selectedDateMillis; showFromPicker = false }) { Text("تحديد") } }, dismissButton = { TextButton(onClick = { showFromPicker = false }) { Text("إلغاء") } }) { DatePicker(state = state) } }
    if (showToPicker) { val state = rememberDatePickerState(initialSelectedDateMillis = toDate ?: System.currentTimeMillis()); DatePickerDialog(onDismissRequest = { showToPicker = false }, confirmButton = { TextButton(onClick = { toDate = state.selectedDateMillis; showToPicker = false }) { Text("تحديد") } }, dismissButton = { TextButton(onClick = { showToPicker = false }) { Text("إلغاء") } }) { DatePicker(state = state) } }
}

private fun operationTypeLabel(type: String): String = when (type) {
    "INWARD" -> "توريد"; "DISBURSEMENT" -> "صرف"; "DISBURSE_OR_TRANSFER" -> "صرف / تحويل"; "TRANSFER" -> "تحويل مخزني"; "ADJUST_STOCK" -> "تعديل المخزون"; "EDIT_OR_DELETE" -> "تعديل / حذف بيانات عامة"; "EDIT_INVENTORY" -> "تعديل المخزون"; "EDIT_SERIALS" -> "تعديل الأرقام التسلسلية"; "DELETE_ITEM" -> "حذف صنف"; "EDIT_ISSUE_VOUCHER" -> "تعديل أمر صرف"; "DELETE_ISSUE_VOUCHER" -> "حذف أمر صرف"; "EDIT_SUPPLY_VOUCHER" -> "تعديل أمر توريد"; "DELETE_SUPPLY_VOUCHER" -> "حذف أمر توريد"; "ADD_OR_MANAGE_CATALOG" -> "إضافة / إدارة الدليل"; "VIEW_REPORTS" -> "تقارير"; "BACKUP_RESTORE" -> "استعادة نسخة احتياطية"; "PAIR" -> "ربط جهاز"; "UNLINK" -> "فك ارتباط جهاز"; "KICK_DEVICE" -> "طرد جهاز"; "GENERAL" -> "عملية عامة"; else -> type.ifBlank { "غير محدد" }
}
