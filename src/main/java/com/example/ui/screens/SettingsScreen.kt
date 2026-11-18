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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import com.example.data.cloud.CloudBackupItem
import com.example.data.model.BackupRecord
import com.example.ui.components.BackupAndSyncSection
import com.example.ui.components.CloudRestoreSelectorDialog
import com.example.ui.components.CreateCloudBackupDialog
import com.example.ui.components.CreateLocalBackupDialog
import com.example.ui.components.LocalBackupSelectorDialog
import com.example.ui.components.MultiDeviceControlSection
import com.example.ui.components.RenameBackupDialog
import com.example.ui.components.SimpleConfirmDialog
import com.example.util.AppNotificationManager
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
    val cloudUser by viewModel.cloudUser.collectAsState()
    val cloudBackupsState by viewModel.cloudBackupsState.collectAsState()
    val isCloudBackupBusy by viewModel.isCloudBackupBusy.collectAsState()
    val cloudSyncOperationLabel by viewModel.cloudSyncOperationLabel.collectAsState()
    val cloudAuthError by viewModel.cloudAuthError.collectAsState()
    val lastCloudBackupAt by viewModel.lastCloudBackupAt.collectAsState()
    val lastCloudBackupEmail by viewModel.lastCloudBackupEmail.collectAsState()
    val lastCloudBackupName by viewModel.lastCloudBackupName.collectAsState()

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
    var showCloudRestoreSelectorDialog by remember { mutableStateOf(false) }
    var showRestoreSelectorDialog by remember { mutableStateOf(false) }
    var pendingCloudAction by remember { mutableStateOf<PendingCloudAction?>(null) }
    var backupToRestore by remember { mutableStateOf<BackupRecord?>(null) }
    var backupToDelete by remember { mutableStateOf<BackupRecord?>(null) }
    var localBackupToRename by remember { mutableStateOf<BackupRecord?>(null) }
    var cloudBackupToRestore by remember { mutableStateOf<CloudBackupItem?>(null) }
    var cloudBackupToRename by remember { mutableStateOf<CloudBackupItem?>(null) }
    var cloudBackupToDelete by remember { mutableStateOf<CloudBackupItem?>(null) }
    var pendingExternalJsonImport by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(cloudUser, isCloudBackupBusy, cloudAuthError) {
        if (cloudUser != null && pendingCloudAction != null) {
            when (pendingCloudAction) {
                PendingCloudAction.CREATE -> {
                    pendingCloudAction = null
                    showCreateCloudBackupDialog = true
                }
                PendingCloudAction.RESTORE -> {
                    pendingCloudAction = null
                    viewModel.refreshCloudBackupsFromServer(silent = true)
                    showCloudRestoreSelectorDialog = true
                }
                null -> Unit
            }
        } else if (cloudUser == null && !isCloudBackupBusy && !cloudAuthError.isNullOrBlank()) {
            pendingCloudAction = null
        }
    }

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

        // 2. Unified Cloud Backup & Local Backup/Sync Card
        item {
            BackupAndSyncSection(
                cloudUser = cloudUser,
                cloudBackupsState = cloudBackupsState,
                localBackups = backups,
                isBusy = isCloudBackupBusy,
                syncOperationLabel = cloudSyncOperationLabel,
                authError = cloudAuthError,
                lastSyncedAtMillis = lastCloudBackupAt,
                lastSavedEmail = lastCloudBackupEmail,
                lastSavedBackupName = lastCloudBackupName,
                onSignInGoogle = {
                    pendingCloudAction = null
                    viewModel.signInGoogleForCloudBackup(context)
                },
                onSignOutGoogle = {
                    pendingCloudAction = null
                    viewModel.signOutGoogleCloudBackup(context)
                },
                onManualCloudBackup = {
                    if (cloudUser != null) {
                        showCreateCloudBackupDialog = true
                    } else {
                        pendingCloudAction = PendingCloudAction.CREATE
                        Toast.makeText(
                            context,
                            "يرجى ربط البريد الإلكتروني أولاً لرفع النسخة السحابية",
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.signInGoogleForCloudBackup(context)
                    }
                },
                onOpenRestoreCloudBackup = {
                    if (cloudUser != null) {
                        viewModel.refreshCloudBackupsFromServer(silent = true)
                        showCloudRestoreSelectorDialog = true
                    } else {
                        pendingCloudAction = PendingCloudAction.RESTORE
                        Toast.makeText(
                            context,
                            "يرجى ربط البريد الإلكتروني أولاً لاستعادة النسخة السحابية",
                            Toast.LENGTH_SHORT
                        ).show()
                        viewModel.signInGoogleForCloudBackup(context)
                    }
                },
                onRefreshCloudBackups = { viewModel.refreshCloudBackupsFromServer(silent = false) },
                onRequestRestoreCloudBackup = { cloudBackupToRestore = it },
                onRequestRenameCloudBackup = { cloudBackupToRename = it },
                onRequestDeleteCloudBackup = { cloudBackupToDelete = it },
                onOpenCreateLocalBackup = { showCreateBackupDialog = true },
                onOpenManageLocalBackups = { showRestoreSelectorDialog = true },
                onExportJsonBackup = {
                    val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
                    exportBackupLauncher.launch("smart_inventory_backup_$dateStr.json")
                },
                onImportJsonBackup = {
                    importBackupLauncher.launch(arrayOf("application/json", "*/*"))
                }
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
        CreateLocalBackupDialog(
            onConfirmSave = { newName ->
                viewModel.createBackup(newName)
                showCreateBackupDialog = false
            },
            onDismiss = { showCreateBackupDialog = false }
        )
    }

    // Restore & Manage Local Backups Dialog (with Rename & Delete)
    if (showRestoreSelectorDialog) {
        LocalBackupSelectorDialog(
            backups = backups,
            onSelectRestore = { bkp ->
                backupToRestore = bkp
                showRestoreSelectorDialog = false
            },
            onSelectRename = { bkp -> localBackupToRename = bkp },
            onSelectShare = { bkp -> shareBackupFile(context, bkp) },
            onSelectDelete = { bkp -> backupToDelete = bkp },
            onDismiss = { showRestoreSelectorDialog = false }
        )
    }

    // Confirm Restore Local Backup Dialog
    if (backupToRestore != null) {
        SimpleConfirmDialog(
            title = "تأكيد استعادة النسخة المحلية",
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

    // Rename Local Backup Dialog
    localBackupToRename?.let { target ->
        RenameBackupDialog(
            title = "تعديل اسم النسخة المحلية",
            initialName = target.name,
            onConfirmRename = { updatedName ->
                viewModel.renameBackup(target, updatedName)
                localBackupToRename = null
            },
            onDismiss = { localBackupToRename = null }
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

    // Confirm Delete Local Backup Dialog
    if (backupToDelete != null) {
        SimpleConfirmDialog(
            title = "تأكيد حذف النسخة المحلية",
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

    // Create Cloud Backup Name Dialog (Editable Name + Linked Email Display)
    if (showCreateCloudBackupDialog) {
        val linkedEmail = cloudUser?.email?.ifBlank { cloudUser?.displayName.orEmpty() }
            ?: lastCloudBackupEmail
        CreateCloudBackupDialog(
            linkedEmail = linkedEmail,
            onConfirmUpload = { chosenName ->
                viewModel.createCloudBackup(chosenName)
                showCreateCloudBackupDialog = false
            },
            onDismiss = { showCreateCloudBackupDialog = false }
        )
    }

    // Cloud Backup Restore Selector Dialog (Specific Backup Selection, Rename & Delete)
    if (showCloudRestoreSelectorDialog) {
        val linkedEmail = cloudUser?.email?.ifBlank { cloudUser?.displayName.orEmpty() }
            ?: lastCloudBackupEmail
        CloudRestoreSelectorDialog(
            linkedEmail = linkedEmail,
            cloudBackupsState = cloudBackupsState,
            isBusy = isCloudBackupBusy,
            onRefresh = { viewModel.refreshCloudBackupsFromServer(silent = false) },
            onSelectRestore = { bkp ->
                cloudBackupToRestore = bkp
                showCloudRestoreSelectorDialog = false
            },
            onSelectRename = { bkp -> cloudBackupToRename = bkp },
            onSelectDelete = { bkp -> cloudBackupToDelete = bkp },
            onDismiss = { showCloudRestoreSelectorDialog = false }
        )
    }

    // Rename Cloud Backup Dialog
    cloudBackupToRename?.let { target ->
        RenameBackupDialog(
            title = "تعديل اسم النسخة السحابية",
            initialName = target.name,
            onConfirmRename = { updatedName ->
                viewModel.renameCloudBackup(target, updatedName)
                cloudBackupToRename = null
            },
            onDismiss = { cloudBackupToRename = null }
        )
    }

    // Confirm Restore Specific Cloud Backup
    cloudBackupToRestore?.let { target ->
        SimpleConfirmDialog(
            title = "تأكيد استعادة النسخة السحابية",
            message = "تحذير: هل تريد استعادة النسخة السحابية \"${target.name}\"؟\nسيتم استبدال كافة البيانات الحالية بالمخازن والأصناف والسندات المحفوظة في هذه النسخة.",
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

private enum class PendingCloudAction {
    CREATE,
    RESTORE
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
