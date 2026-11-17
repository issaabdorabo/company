package com.example.ui.screens

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.BuildConfig
import com.example.data.cloud.CloudAuthUser
import com.example.data.cloud.CloudBackupItem
import com.example.data.model.BackupRecord
import com.example.ui.components.MultiDeviceControlSection
import com.example.ui.components.SimpleConfirmDialog
import com.example.util.AppNotificationManager
import com.example.ui.theme.AppTheme
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.CloudBackupUiState
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
    val cloudUser by viewModel.cloudUser.collectAsState()
    val cloudBackupsState by viewModel.cloudBackupsState.collectAsState()
    val isCloudBackupBusy by viewModel.isCloudBackupBusy.collectAsState()
    val cloudAuthError by viewModel.cloudAuthError.collectAsState()

    val lifecycleOwner = LocalLifecycleOwner.current
    var notificationsEnabled by remember {
        mutableStateOf(AppNotificationManager.hasNotificationPermission(context))
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationsEnabled = AppNotificationManager.hasNotificationPermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationsEnabled = granted || AppNotificationManager.hasNotificationPermission(context)
    }

    LaunchedEffect(Unit) {
        viewModel.attemptSilentCloudReconnect(context)
    }

    var editUserName by remember(currentUserName) { mutableStateOf(currentUserName) }
    var editOrgName by remember(currentOrgName) { mutableStateOf(currentOrgName) }

    // Dialog states
    var showCreateBackupDialog by remember { mutableStateOf(false) }
    var showCreateCloudBackupDialog by remember { mutableStateOf(false) }
    var showRestoreSelectorDialog by remember { mutableStateOf(false) }
    var backupToRestore by remember { mutableStateOf<BackupRecord?>(null) }
    var backupToDelete by remember { mutableStateOf<BackupRecord?>(null) }
    var cloudBackupToRestore by remember { mutableStateOf<CloudBackupItem?>(null) }
    var cloudBackupToDelete by remember { mutableStateOf<CloudBackupItem?>(null) }
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

        // 2. Durable Cloud Backup & Reinstall Recovery (Firestore + Google Sign-In)
        item {
            CloudBackupSection(
                cloudUser = cloudUser,
                cloudBackupsState = cloudBackupsState,
                isBusy = isCloudBackupBusy,
                authError = cloudAuthError,
                onSignInGoogle = { viewModel.signInGoogleForCloudBackup(context) },
                onSignOutGoogle = { viewModel.signOutGoogleCloudBackup(context) },
                onCreateCloudBackup = { showCreateCloudBackupDialog = true },
                onRefreshCloudBackups = { viewModel.refreshCloudBackupsFromServer(silent = false) },
                onRequestRestoreCloudBackup = { cloudBackupToRestore = it },
                onRequestDeleteCloudBackup = { cloudBackupToDelete = it }
            )
        }

        // 3. User & Organization Identity Profile
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

        // 2b. Phone & Main Device Sub-Device Notifications Card
        item {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("notifications_settings_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            shape = CircleShape,
                            color = (if (notificationsEnabled) SuccessGreen else WarningOrange).copy(alpha = 0.14f),
                            modifier = Modifier.size(38.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (notificationsEnabled) Icons.Default.NotificationsActive else Icons.Default.NotificationsOff,
                                    contentDescription = null,
                                    tint = if (notificationsEnabled) SuccessGreen else WarningOrange,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "إشعارات شاشة الهاتف والتنبيهات الفورية",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = if (notificationsEnabled) {
                                    "مفعلة — تظهر جميع عمليات التوريد والصرف والمخازن وحركات الأجهزة الفرعية في شريط الإشعارات"
                                } else {
                                    "غير مفعلة حالياً — فعّل الإشعارات لاستلام تنبيهات فورية على شاشة الهاتف"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = if (notificationsEnabled) SuccessGreen else WarningOrange
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (!notificationsEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            Button(
                                onClick = {
                                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("enable_notifications_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("تفعيل الإشعارات", fontSize = 12.sp)
                            }
                        }
                        OutlinedButton(
                            onClick = {
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                } else {
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("open_notification_settings_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("إعدادات الإشعارات بالهاتف", fontSize = 12.sp)
                        }
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

    // Create Cloud Backup Name Dialog
    if (showCreateCloudBackupDialog) {
        var cloudBackupName by remember {
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            mutableStateOf("سحابة_$dateStr")
        }
        AlertDialog(
            onDismissRequest = { showCreateCloudBackupDialog = false },
            title = {
                Text(
                    text = "رفع نسخة احتياطية سحابية",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = cloudBackupName,
                        onValueChange = { cloudBackupName = it },
                        label = { Text("اسم النسخة السحابية *") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("cloud_backup_name_input"),
                        singleLine = true
                    )
                    Text(
                        text = "سيتم تخزين هذه النسخة سحابياً على حساب Google ولن تُحذف حتى عند إزالة التطبيق وإعادة تثبيته.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.createCloudBackup(cloudBackupName.trim().ifBlank { "نسخة_سحابية" })
                        showCreateCloudBackupDialog = false
                    },
                    modifier = Modifier.testTag("confirm_upload_cloud_backup_btn")
                ) {
                    Text("رفع للسحابة")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateCloudBackupDialog = false }) { Text("إلغاء") }
            }
        )
    }

    // Confirm Restore Specific Cloud Backup
    cloudBackupToRestore?.let { target ->
        SimpleConfirmDialog(
            title = "تأكيد استعادة النسخة السحابية",
            message = "هل تريد استعادة النسخة السحابية \"${target.name}\"؟\nسيتم استبدال البيانات الحالية بكافة المخازن والأصناف والسندات المحفوظة في هذه النسخة.",
            confirmButtonText = "نعم، استعد هذه النسخة",
            isDestructive = true,
            onConfirm = {
                viewModel.restoreCloudBackup(target)
                cloudBackupToRestore = null
            },
            onDismiss = { cloudBackupToRestore = null }
        )
    }

    // Confirm Delete Specific Cloud Backup
    cloudBackupToDelete?.let { target ->
        SimpleConfirmDialog(
            title = "تأكيد حذف النسخة السحابية",
            message = "هل أنت متأكد من حذف النسخة السحابية \"${target.name}\" نهائياً من السحابة؟ لا يمكن التراجع عن هذا الإجراء.",
            confirmButtonText = "نعم، احذف من السحابة",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteCloudBackup(target)
                cloudBackupToDelete = null
            },
            onDismiss = { cloudBackupToDelete = null }
        )
    }
}

@Composable
private fun CloudBackupSection(
    cloudUser: CloudAuthUser?,
    cloudBackupsState: CloudBackupUiState,
    isBusy: Boolean,
    authError: String?,
    onSignInGoogle: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onCreateCloudBackup: () -> Unit,
    onRefreshCloudBackups: () -> Unit,
    onRequestRestoreCloudBackup: (CloudBackupItem) -> Unit,
    onRequestDeleteCloudBackup: (CloudBackupItem) -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("cloud_backup_section_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = InfoBlue.copy(alpha = 0.14f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (cloudUser != null) Icons.Default.CloudDone else Icons.Default.CloudOff,
                            contentDescription = null,
                            tint = InfoBlue,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "النسخ الاحتياطي السحابي والاستعادة بعد إعادة التثبيت",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "لا تُحذف عند مسح التطبيق — اربط حساب Google لحفظ واستعادة نسخك السحابية في أي وقت",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!authError.isNullOrBlank()) {
                Surface(
                    color = DangerRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = authError,
                        color = DangerRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            if (cloudUser == null) {
                Button(
                    onClick = onSignInGoogle,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_google_signin_btn"),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("جاري الاتصال بحساب Google...")
                    } else {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("ربط حساب Google للنسخ السحابي والاستعادة")
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SuccessGreen.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "حساب النسخ السحابي متصل",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            Text(
                                text = cloudUser.email.ifBlank { cloudUser.displayName },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                        OutlinedButton(
                            onClick = onSignOutGoogle,
                            enabled = !isBusy,
                            modifier = Modifier.testTag("cloud_google_signout_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(15.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("فصل", fontSize = 11.sp)
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onCreateCloudBackup,
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1.4f)
                            .testTag("create_cloud_backup_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = Color.White
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("جاري الرفع...", fontSize = 12.sp)
                        } else {
                            Icon(Icons.Default.CloudUpload, contentDescription = "عمل نسخة احتياطية سحابية", modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("عمل نسخة احتياطية سحابية", fontSize = 12.sp)
                        }
                    }

                    OutlinedButton(
                        onClick = onRefreshCloudBackups,
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .testTag("refresh_cloud_backups_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث النسخ من السحابة", modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("جلب النسخ", fontSize = 12.sp)
                    }
                }

                HorizontalDivider()

                when (cloudBackupsState) {
                    is CloudBackupUiState.Loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري جلب النسخ السحابية...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is CloudBackupUiState.Error -> {
                        Text(
                            text = cloudBackupsState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerRed
                        )
                    }
                    is CloudBackupUiState.SignedOut -> Unit
                    is CloudBackupUiState.Ready -> {
                        val list = cloudBackupsState.backups
                        Text(
                            text = "النسخ السحابية المحفوظة (${list.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (list.isEmpty()) {
                            Text(
                                text = "لا توجد نسخ سحابية مرفوعة على هذا الحساب حتى الآن.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                list.forEach { bkp ->
                                    val ts = (bkp.createdAt ?: bkp.updatedAt)?.toDate()
                                    val dateStr = if (ts != null) dateFormat.format(ts) else "الآن"
                                    val sizeKb = (bkp.sizeBytes / 1024L).coerceAtLeast(1L)

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("cloud_backup_card_${bkp.id}")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.Top
                                            ) {
                                                Column(modifier = Modifier.weight(1f)) {
                                                    Text(
                                                        text = bkp.name,
                                                        style = MaterialTheme.typography.titleSmall,
                                                        fontWeight = FontWeight.Bold
                                                    )
                                                    Text(
                                                        text = "$dateStr • $sizeKb كيلوبايت",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    Text(
                                                        text = "مخازن: ${bkp.warehouseCount} | أصناف: ${bkp.itemCount} | سندات: ${bkp.voucherCount}",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                }
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                Button(
                                                    onClick = { onRequestRestoreCloudBackup(bkp) },
                                                    enabled = !isBusy,
                                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .testTag("restore_cloud_backup_${bkp.id}"),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("استعادة النسخة", fontSize = 11.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = { onRequestDeleteCloudBackup(bkp) },
                                                    enabled = !isBusy,
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                                    modifier = Modifier.testTag("delete_cloud_backup_${bkp.id}"),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "حذف النسخة السحابية", modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("حذف", fontSize = 11.sp)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
