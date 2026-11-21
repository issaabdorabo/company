package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.model.OperationAuditLog
import com.example.ui.viewmodel.InventoryViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AuditLogScreen(viewModel: InventoryViewModel) {
    val logs by viewModel.operationAuditLogs.collectAsState()
    var search by remember { mutableStateOf("") }
    var userFilter by remember { mutableStateOf("") }
    var typeFilter by remember { mutableStateOf("ALL") }
    var fromDate by remember { mutableStateOf<Long?>(null) }
    var toDate by remember { mutableStateOf<Long?>(null) }
    var showFromPicker by remember { mutableStateOf(false) }
    var showToPicker by remember { mutableStateOf(false) }
    val dateTimeFormat = remember { SimpleDateFormat("yyyy/MM/dd • HH:mm:ss", Locale.getDefault()) }
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd", Locale.getDefault()) }
    val types = remember(logs) { logs.map { it.operationType }.filter { it.isNotBlank() }.distinct().sorted() }

    val filteredLogs = remember(logs, search, userFilter, typeFilter, fromDate, toDate) {
        logs.filter { log ->
            val queryMatch = search.isBlank() || listOf(log.title, log.details, log.actorName, log.deviceName, log.operationType)
                .any { it.contains(search.trim(), ignoreCase = true) }
            val userMatch = userFilter.isBlank() || log.actorName.contains(userFilter.trim(), ignoreCase = true)
            val typeMatch = typeFilter == "ALL" || log.operationType == typeFilter
            val fromMatch = fromDate == null || log.timestamp >= (fromDate!! / 86_400_000L) * 86_400_000L
            val toMatch = toDate == null || log.timestamp < ((toDate!! / 86_400_000L) + 1L) * 86_400_000L
            queryMatch && userMatch && typeMatch && fromMatch && toMatch
        }.sortedByDescending { it.timestamp }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Card(shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FilterAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(8.dp))
                        Text("بحث وتصفية سجل العمليات", fontWeight = FontWeight.Bold)
                    }
                    OutlinedTextField(
                        value = search,
                        onValueChange = { search = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("بحث في تفاصيل العملية") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) }
                    )
                    OutlinedTextField(
                        value = userFilter,
                        onValueChange = { userFilter = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        label = { Text("المستخدم") }
                    )
                    Text("نوع العملية", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    androidx.compose.foundation.lazy.LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        item { FilterChip(selected = typeFilter == "ALL", onClick = { typeFilter = "ALL" }, label = { Text("الكل") }) }
                        items(types) { type ->
                            FilterChip(selected = typeFilter == type, onClick = { typeFilter = type }, label = { Text(operationTypeLabel(type)) })
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { showFromPicker = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(fromDate?.let { "من ${dateFormat.format(Date(it))}" } ?: "من تاريخ")
                        }
                        Button(onClick = { showToPicker = true }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.CalendarMonth, contentDescription = null)
                            Spacer(Modifier.width(4.dp))
                            Text(toDate?.let { "إلى ${dateFormat.format(Date(it))}" } ?: "إلى تاريخ")
                        }
                    }
                    TextButton(onClick = { search = ""; userFilter = ""; typeFilter = "ALL"; fromDate = null; toDate = null }) {
                        Text("مسح جميع عوامل التصفية")
                    }
                    Text("النتائج: ${filteredLogs.size} من ${logs.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (filteredLogs.isEmpty()) {
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text("لا توجد عمليات مطابقة لعوامل البحث المحددة.", modifier = Modifier.padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } else {
            items(filteredLogs, key = { it.id }) { log -> AuditLogCard(log, dateTimeFormat) }
        }
    }

    if (showFromPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = fromDate ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showFromPicker = false },
            confirmButton = { TextButton(onClick = { fromDate = state.selectedDateMillis; showFromPicker = false }) { Text("تحديد") } },
            dismissButton = { TextButton(onClick = { showFromPicker = false }) { Text("إلغاء") } }
        ) { DatePicker(state = state) }
    }
    if (showToPicker) {
        val state = rememberDatePickerState(initialSelectedDateMillis = toDate ?: System.currentTimeMillis())
        DatePickerDialog(
            onDismissRequest = { showToPicker = false },
            confirmButton = { TextButton(onClick = { toDate = state.selectedDateMillis; showToPicker = false }) { Text("تحديد") } },
            dismissButton = { TextButton(onClick = { showToPicker = false }) { Text("إلغاء") } }
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun AuditLogCard(log: OperationAuditLog, dateTimeFormat: SimpleDateFormat) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(log.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(dateTimeFormat.format(Date(log.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text("المستخدم: ${log.actorName}  •  ${log.deviceName}", style = MaterialTheme.typography.bodySmall)
            Text("نوع العملية: ${operationTypeLabel(log.operationType)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
            if (log.details.isNotBlank()) Text(log.details, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

private fun operationTypeLabel(type: String): String = when (type) {
    "INWARD" -> "توريد"
    "DISBURSEMENT" -> "صرف"
    "DISBURSE_OR_TRANSFER" -> "صرف / تحويل"
    "TRANSFER" -> "تحويل مخزني"
    "ADJUST_STOCK" -> "تعديل المخزون"
    "EDIT_OR_DELETE" -> "تعديل / حذف"
    "ADD_OR_MANAGE_CATALOG" -> "إضافة / إدارة الدليل"
    "VIEW_REPORTS" -> "تقارير"
    "BACKUP_RESTORE" -> "استعادة نسخة احتياطية"
    "PAIR" -> "ربط جهاز"
    "GENERAL" -> "عملية عامة"
    else -> type.ifBlank { "غير محدد" }
}
