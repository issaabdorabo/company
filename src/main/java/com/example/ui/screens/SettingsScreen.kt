package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FactCheck
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.model.BackupRecord
import com.example.ui.components.MultiDeviceControlSection
import com.example.ui.components.SimpleConfirmDialog
import com.example.ui.theme.AppTheme
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.DiagnosticStatus
import com.example.ui.viewmodel.InventoryViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun SettingsScreen(
    viewModel: InventoryViewModel
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val currentTheme by viewModel.theme.collectAsState()
    val currentUserName by viewModel.userName.collectAsState()
    val currentOrgName by viewModel.orgName.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val diagnosticsReport by viewModel.diagnosticsReport.collectAsState()
    val isRunningDiagnostics by viewModel.isRunningDiagnostics.collectAsState()

    var editUserName by remember(currentUserName) { mutableStateOf(currentUserName) }
    var editOrgName by remember(currentOrgName) { mutableStateOf(currentOrgName) }

    // Dialog states
    var showCreateBackupDialog by remember { mutableStateOf(false) }
    var showRestoreSelectorDialog by remember { mutableStateOf(false) }
    var backupToRestore by remember { mutableStateOf<BackupRecord?>(null) }
    var backupToDelete by remember { mutableStateOf<BackupRecord?>(null) }
    var pendingExternalJsonImport by remember { mutableStateOf<String?>(null) }

    // Zero-permission SAF Export / Import for Reinstall Data Recovery (No Google Auth required)
    val exportBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                runCatching {
                    val json = viewModel.exportBackupJsonForFile()
                    withContext(Dispatchers.IO) {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(json.toByteArray(Charsets.UTF_8))
                        }
                    }
                }.onSuccess {
                    Toast.makeText(context, "تم حفظ ملف النسخة للاسترداد بنجاح", Toast.LENGTH_SHORT).show()
                }.onFailure { err ->
                    Toast.makeText(context, "تعذر حفظ الملف: ${err.localizedMessage}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importBackupLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            coroutineScope.launch {
                val raw = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    }.getOrNull()
                }
                if (raw.isNullOrBlank()) {
                    Toast.makeText(context, "تعذر قراءة الملف المختار", Toast.LENGTH_SHORT).show()
                } else {
                    pendingExternalJsonImport = raw
                }
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("settings_screen_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Multi-Device Pairing & Sync (Main <-> Sub over LAN, Zero Google Auth)
        item {
            MultiDeviceControlSection(viewModel = viewModel)
        }

        // 2. User & Organization Identity Profile
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Person,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "بيانات المستخدم والمنشأة للتقارير",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = editUserName,
                        onValueChange = { editUserName = it },
                        label = { Text("اسم مستخدم التطبيق (يظهر في السجلات والتقارير)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("settings_username_input"),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedTextField(
                        value = editOrgName,
                        onValueChange = { editOrgName = it },
                        label = { Text("اسم المؤسسة / المنشأة") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(10.dp))

                    Button(
                        onClick = {
                            viewModel.setUserName(editUserName.trim().ifBlank { "عيسى عبدربه" })
                            viewModel.setOrgName(editOrgName.trim().ifBlank { "المؤسسة العامة للتوريد والمخازن" })
                            Toast.makeText(context, "تم حفظ بيانات المستخدم بنجاح", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.align(Alignment.End),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("حفظ التغييرات")
                    }
                }
            }
        }

        // 3. Luxury Themes
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Palette,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "مظهر التطبيق والثيمات الفاخرة",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "اختر أحد الثيمات الفاخرة الستة",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val themes = AppTheme.values()
                    val chunkedThemes = themes.toList().chunked(3)
                    chunkedThemes.forEach { rowThemes ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            rowThemes.forEach { thm ->
                                val isSelected = currentTheme == thm
                                val themeColor = Color(thm.primaryColorHex)
                                Card(
                                    modifier = Modifier
                                        .weight(1f)
                                        .clickable { viewModel.setTheme(thm) }
                                        .border(
                                            width = if (isSelected) 2.dp else 1.dp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                            shape = RoundedCornerShape(12.dp)
                                        ),
                                    colors = CardDefaults.cardColors(
                                        containerColor = if (isSelected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
                                        else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(themeColor, CircleShape)
                                                .border(2.dp, Color.White, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.height(6.dp))
                                        Text(
                                            text = thm.titleAr,
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }

        // 4. Local Backup & File Export/Import (For App Reinstall Recovery without Google Auth)
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Default.Backup,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "النسخ الاحتياطي واسترداد البيانات",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "حفظ وتصدير ملف استرداد خارجي يرجع كل بياناتك عند حذف وإعادة تثبيت التطبيق",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showCreateBackupDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("create_backup_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("حفظ نسخة داخلية", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showRestoreSelectorDialog = true },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("restore_backup_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("النسخ المحفوظة", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                                exportBackupLauncher.launch("smart_inventory_backup_$dateStr.json")
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("export_backup_file_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تصدير لملف خارجي", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = {
                                importBackupLauncher.launch(arrayOf("application/json", "*/*"))
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("import_backup_file_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("استرداد من ملف", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "النسخ الداخلية: ${backups.size} نسخة • التصدير الخارجي يحفظ ملف JSON على ذاكرة الهاتف أو التنزيلات لاستعادته حتى بعد حذف التطبيق.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5. Local Database & Direct Pairing Integrity Check (No Auth / No Fingerprint)
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("local_database_check_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.AutoMirrored.Filled.FactCheck,
                            contentDescription = null,
                            tint = InfoBlue,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "فحص سلامة قاعدة البيانات وخادم الربط",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { viewModel.runLocalDatabaseCheck() },
                        enabled = !isRunningDiagnostics,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("run_diagnostics_btn"),
                        colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isRunningDiagnostics) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري الفحص...")
                        } else {
                            Icon(
                                Icons.AutoMirrored.Filled.FactCheck,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("فحص النظام والربط الآن")
                        }
                    }

                    diagnosticsReport?.let { report ->
                        val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "نتائج الفحص (${timeFmt.format(Date(report.checkedAtMillis))})",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
                            report.checks.forEachIndexed { idx, check ->
                                val statusColor = when (check.status) {
                                    DiagnosticStatus.PASS -> SuccessGreen
                                    DiagnosticStatus.INFO -> InfoBlue
                                    DiagnosticStatus.WARNING -> WarningOrange
                                    DiagnosticStatus.FAIL -> DangerRed
                                }
                                val statusIcon = when (check.status) {
                                    DiagnosticStatus.PASS -> Icons.Default.CheckCircle
                                    DiagnosticStatus.INFO -> Icons.Default.Info
                                    DiagnosticStatus.WARNING, DiagnosticStatus.FAIL -> Icons.Default.ErrorOutline
                                }
                                Surface(
                                    color = statusColor.copy(alpha = 0.09f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("diagnostic_item_$idx")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            imageVector = statusIcon,
                                            contentDescription = null,
                                            tint = statusColor,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(
                                                text = check.title,
                                                style = MaterialTheme.typography.bodySmall,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text(
                                                text = check.detail,
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 6. Developer & About
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "نظام مخزني الذكي للإدارة المخزنية",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "الإصدار المعتمد ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "طور بواسطة عيسى عبدربه  |  هاتف: 771438740",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }

    // Create Local Backup Dialog
    if (showCreateBackupDialog) {
        var backupName by remember {
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            mutableStateOf("نسخة_$dateStr")
        }

        AlertDialog(
            onDismissRequest = { showCreateBackupDialog = false },
            title = { Text("حفظ نسخة احتياطية جديدة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = backupName,
                        onValueChange = { backupName = it },
                        label = { Text("اسم النسخة الاحتياطية *") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Text(
                        text = "سيتم حفظ النسخة كاملة على الهاتف مع إمكانية مشاركتها كملف في أي وقت.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createBackup(backupName.trim().ifBlank { "نسخة احتياطية" })
                        showCreateBackupDialog = false
                    }
                ) {
                    Text("حفظ النسخة")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateBackupDialog = false }) { Text("إلغاء") }
            }
        )
    }

    // Restore Backup Selector Dialog
    if (showRestoreSelectorDialog) {
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

        AlertDialog(
            onDismissRequest = { showRestoreSelectorDialog = false },
            title = {
                Text("استعادة وإدارة النسخ الاحتياطية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    if (backups.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            Text("لا توجد نسخ احتياطية محفوظة حتى الآن", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(320.dp)
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                .padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(backups.size) { i ->
                                    val bkp = backups[i]
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = Icons.Default.PhoneAndroid,
                                                        contentDescription = null,
                                                        tint = MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(text = bkp.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                                }
                                                Text(
                                                    text = dateFormat.format(Date(bkp.timestamp)),
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "مخازن: ${bkp.warehouseCount} | أصناف: ${bkp.itemCount} | حركات: ${bkp.voucherCount}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            Row {
                                                IconButton(
                                                    onClick = {
                                                        backupToRestore = bkp
                                                        showRestoreSelectorDialog = false
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Restore, contentDescription = "استعادة", tint = SuccessGreen)
                                                }
                                                IconButton(
                                                    onClick = {
                                                        shareBackupFile(context, bkp)
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Share, contentDescription = "مشاركة", tint = MaterialTheme.colorScheme.primary)
                                                }
                                                IconButton(
                                                    onClick = {
                                                        backupToDelete = bkp
                                                    }
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "حذف", tint = DangerRed)
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
                Button(onClick = { showRestoreSelectorDialog = false }) { Text("إغلاق") }
            }
        )
    }

    // Restore Backup Confirmation Dialog
    if (backupToRestore != null) {
        SimpleConfirmDialog(
            title = "تأكيد استعادة النسخة الاحتياطية",
            message = "تحذير: هل أنت متأكد من استعادة النسخة: \"${backupToRestore!!.name}\"؟\nسيتم استبدال البيانات الحالية بكافة المخازن والأصناف والحركات المحفوظة في هذه النسخة.",
            confirmButtonText = "نعم، استعد البيانات الآن",
            isDestructive = true,
            onConfirm = {
                viewModel.restoreBackup(backupToRestore!!)
                backupToRestore = null
            },
            onDismiss = { backupToRestore = null }
        )
    }

    // Confirm External JSON Import
    if (pendingExternalJsonImport != null) {
        SimpleConfirmDialog(
            title = "تأكيد استرداد البيانات من الملف",
            message = "سيتم استبدال البيانات الحالية بكافة المخازن والأصناف والسندات الموجودة داخل هذا الملف. هل تريد المتابعة؟",
            confirmButtonText = "نعم، استرد الملف الآن",
            isDestructive = true,
            onConfirm = {
                viewModel.importBackupJsonFromFile(pendingExternalJsonImport!!)
                pendingExternalJsonImport = null
            },
            onDismiss = { pendingExternalJsonImport = null }
        )
    }

    // Delete Backup Confirmation Dialog
    if (backupToDelete != null) {
        SimpleConfirmDialog(
            title = "تأكيد حذف النسخة الاحتياطية",
            message = "هل أنت متأكد من حذف النسخة الاحتياطية \"${backupToDelete!!.name}\" نهائياً؟",
            confirmButtonText = "نعم، احذف النسخة",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteBackup(backupToDelete!!)
                backupToDelete = null
            },
            onDismiss = { backupToDelete = null }
        )
    }
}

private fun shareBackupFile(context: Context, backup: BackupRecord) {
    val backupsDir = File(context.cacheDir, "backups")
    if (!backupsDir.exists()) backupsDir.mkdirs()
    val file = File(backupsDir, "${backup.name}.json")
    file.writeText(backup.backupJson, Charsets.UTF_8)

    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file
    )
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/json"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "مشاركة النسخة الاحتياطية"))
}
