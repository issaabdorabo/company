package com.example.ui.screens

import android.Manifest
import android.accounts.Account
import android.accounts.AccountManager
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
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
import com.example.data.cloud.SupabaseAuthClient
import com.example.data.cloud.FirebaseAuthClient
import com.google.firebase.auth.FirebaseAuth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.status.SessionStatus
import com.example.data.model.BackupRecord
import com.example.ui.components.CloudBackupRadioPickerDialog
import com.example.ui.components.CloudSyncAndAuthCard
import com.example.ui.components.CreateCloudBackupDialog
import com.example.ui.components.CreateLocalBackupDialog
import com.example.ui.components.LocalBackupAndFileCard
import com.example.ui.components.LocalBackupSelectorDialog
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
import kotlinx.coroutines.CancellationException
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
    val devicePermissions by viewModel.devicePermissions.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val diagnosticsReport by viewModel.diagnosticsReport.collectAsState()
    val isRunningDiagnostics by viewModel.isRunningDiagnostics.collectAsState()
    val verifiedGoogleEmail by viewModel.verifiedGoogleEmail.collectAsState()
    val pendingConsentRequest by viewModel.pendingConsentRequest.collectAsState()
    val cloudBackupsState by viewModel.cloudBackupsState.collectAsState()
    val isCloudBackupBusy by viewModel.isCloudBackupBusy.collectAsState()
    val cloudSyncOperationLabel by viewModel.cloudSyncOperationLabel.collectAsState()
    val cloudAuthError by viewModel.cloudAuthError.collectAsState()
    val lastCloudBackupAt by viewModel.lastCloudBackupAt.collectAsState()
    val lastCloudBackupName by viewModel.lastCloudBackupName.collectAsState()

    var internetAvailable by remember { mutableStateOf(false) }
    var supabaseAuthStatus by remember { mutableStateOf("جارٍ التحقق من حساب Supabase…") }
    var isSupabaseAuthenticated by remember { mutableStateOf(false) }
    var isSupabaseConnecting by remember { mutableStateOf(false) }
    var isFirebaseAuthenticated by remember {
        mutableStateOf(FirebaseAuthClient.currentUserIdOrNull() != null)
    }

    DisposableEffect(Unit) {
        val listener = FirebaseAuth.AuthStateListener { auth ->
            isFirebaseAuthenticated = auth.currentUser != null
        }
        FirebaseAuthClient.auth.addAuthStateListener(listener)
        onDispose { FirebaseAuthClient.auth.removeAuthStateListener(listener) }
    }

    LaunchedEffect(Unit) {
        SupabaseAuthClient.client.auth.sessionStatus.collect { status ->
            when (status) {
                is SessionStatus.Authenticated -> {
                    isSupabaseAuthenticated = true
                    supabaseAuthStatus = "الحساب مرتبط: ${status.session.user?.email ?: "Google"}"
                }
                is SessionStatus.NotAuthenticated -> {
                    isSupabaseAuthenticated = false
                    supabaseAuthStatus = "لم يتم ربط حساب Google بعد"
                }
                SessionStatus.Initializing -> {
                    supabaseAuthStatus = "جارٍ استعادة جلسة الحساب…"
                }
                is SessionStatus.RefreshFailure -> {
                    isSupabaseAuthenticated = false
                    supabaseAuthStatus = "تعذر تحديث جلسة الحساب؛ تحقق من الاتصال"
                }
            }
        }
    }

    DisposableEffect(context) {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        fun updateInternetState() {
            val network = connectivityManager.activeNetwork
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            internetAvailable = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = updateInternetState()
            override fun onLost(network: Network) = updateInternetState()
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = updateInternetState()
        }
        updateInternetState()
        connectivityManager.registerDefaultNetworkCallback(callback)
        onDispose { runCatching { connectivityManager.unregisterNetworkCallback(callback) } }
    }

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

    val googleAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            if (pickedEmail.isNotBlank()) {
                viewModel.linkGoogleDriveAccount(context, pickedEmail)
            }
        } else {
            pendingCloudAction = null
        }
    }

    val googleConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onGoogleConsentResult(context, result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(pendingConsentRequest) {
        val req = pendingConsentRequest ?: return@LaunchedEffect
        runCatching {
            googleConsentLauncher.launch(req.intent)
        }.onFailure {
            viewModel.onGoogleConsentResult(context, false)
        }
    }

    LaunchedEffect(verifiedGoogleEmail) {
        if (verifiedGoogleEmail.isNotBlank()) {
            viewModel.refreshCloudBackupsFromDrive(context, silent = true)
            when (pendingCloudAction) {
                PendingCloudAction.CREATE -> {
                    pendingCloudAction = null
                    showCreateCloudBackupDialog = true
                }
                PendingCloudAction.RESTORE -> {
                    pendingCloudAction = null
                    showCloudRestoreSelectorDialog = true
                }
                null -> Unit
            }
        }
    }

    fun launchPhoneGoogleAccountPicker() {
        val currentAccount = verifiedGoogleEmail
            .takeIf { it.isNotBlank() }
            ?.let { Account(it, "com.google") }
        val intent = AccountManager.newChooseAccountIntent(
            currentAccount,
            null,
            arrayOf("com.google"),
            null,
            null,
            null,
            null
        )
        runCatching {
            googleAccountPickerLauncher.launch(intent)
        }.onFailure { err ->
            Toast.makeText(
                context,
                "تعذر فتح قائمة حسابات Google بالهاتف: ${err.localizedMessage ?: "تأكد من إضافة حساب Google في الجهاز"}",
                Toast.LENGTH_LONG
            ).show()
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
        if (devicePermissions.isMainDevice || devicePermissions.canBackup) {
            // 2. Simplified Cloud Sync & Authentication Card (Phone Account Picker + Google Drive consent)
            item {
                CloudSyncAndAuthCard(
                    verifiedGoogleEmail = verifiedGoogleEmail,
                    cloudBackupsState = cloudBackupsState,
                    isBusy = isCloudBackupBusy,
                    syncOperationLabel = cloudSyncOperationLabel,
                    authError = cloudAuthError,
                    lastSyncedAtMillis = lastCloudBackupAt,
                    lastSavedBackupName = lastCloudBackupName,
                    onAddGoogleAccount = {
                        pendingCloudAction = null
                        launchPhoneGoogleAccountPicker()
                    },
                    onDisconnectGoogleAccount = {
                        pendingCloudAction = null
                        viewModel.disconnectGoogleAccount()
                    },
                    onCreateCloudBackup = {
                        if (verifiedGoogleEmail.isNotBlank()) {
                            showCreateCloudBackupDialog = true
                        } else {
                            pendingCloudAction = PendingCloudAction.CREATE
                            Toast.makeText(context, "يرجى اختيار حساب Google أولاً", Toast.LENGTH_SHORT).show()
                            launchPhoneGoogleAccountPicker()
                        }
                    },
                    onOpenRestoreCloudBackup = {
                        if (verifiedGoogleEmail.isNotBlank()) {
                            viewModel.refreshCloudBackupsFromDrive(context, silent = false)
                            showCloudRestoreSelectorDialog = true
                        } else {
                            pendingCloudAction = PendingCloudAction.RESTORE
                            Toast.makeText(context, "يرجى اختيار حساب Google أولاً", Toast.LENGTH_SHORT).show()
                            launchPhoneGoogleAccountPicker()
                        }
                    }
                )
            }

            // 2a. Independent Local Backup & External JSON Export/Import Card
            item {
                LocalBackupAndFileCard(
                    localBackups = backups,
                    isBusy = isCloudBackupBusy,
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

        } else {
            item {
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("النسخ الاحتياطي والتصدير غير متاح", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                        Text("لم يمنحك المستخدم الرئيسي صلاحية النسخ الاحتياطي المحلي أو السحابي أو تصدير واستيراد JSON.", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        // Single Google sign-in action for the online collaboration identity.
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text("حساب التعاون عبر الإنترنت", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "اربط حساب Google مرة واحدة لتفعيل هوية المستخدم والتعاون عبر الإنترنت. هذا الحساب مستقل عن حساب Google Drive للنسخ الاحتياطي.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                if (!internetAvailable || isSupabaseConnecting || isSupabaseAuthenticated) return@Button
                                isSupabaseConnecting = true
                                coroutineScope.launch {
                                    try {
                                        SupabaseAuthClient.signInWithGoogle(context)
                                    } catch (cancelled: CancellationException) {
                                        throw cancelled
                                    } catch (error: Exception) {
                                        Toast.makeText(
                                            context,
                                            "تعذر ربط حساب التعاون: ${error.localizedMessage ?: "خطأ غير معروف"}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    } finally {
                                        isSupabaseConnecting = false
                                    }
                                }
                            },
                            enabled = internetAvailable && !isSupabaseAuthenticated && !isSupabaseConnecting,
                            modifier = Modifier.weight(1.2f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text(
                                when {
                                    isSupabaseAuthenticated -> "حساب التعاون مرتبط"
                                    isSupabaseConnecting -> "جارٍ ربط الحساب..."
                                    else -> "ربط حساب التعاون عبر Google"
                                },
                                fontSize = 12.sp
                            )
                        }
                        Column(
                            modifier = Modifier.weight(0.8f),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = if (internetAvailable) SuccessGreen.copy(alpha = 0.12f) else WarningOrange.copy(alpha = 0.12f)
                            ) {
                                Text(
                                    if (internetAvailable) "الإنترنت متصل" else "الإنترنت غير متصل",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (internetAvailable) SuccessGreen else WarningOrange,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (isFirebaseAuthenticated) SuccessGreen.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    if (isFirebaseAuthenticated) "Firebase مرتبط" else "Firebase غير مرتبط",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (isFirebaseAuthenticated) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            Surface(
                                shape = CircleShape,
                                color = if (isSupabaseAuthenticated) InfoBlue.copy(alpha = 0.12f) else MaterialTheme.colorScheme.surfaceVariant
                            ) {
                                Text(
                                    if (isSupabaseAuthenticated) "Supabase القديم مرتبط" else "Supabase القديم غير مرتبط",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = if (isSupabaseAuthenticated) InfoBlue else MaterialTheme.colorScheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Text(
                        supabaseAuthStatus,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (isSupabaseAuthenticated) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
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

    // Create Cloud Backup Name Dialog (Uploads to Google Drive for Verified Account)
    if (showCreateCloudBackupDialog) {
        CreateCloudBackupDialog(
            linkedEmail = verifiedGoogleEmail,
            onConfirmUpload = { chosenName ->
                viewModel.createCloudBackup(context, chosenName)
                showCreateCloudBackupDialog = false
            },
            onDismiss = { showCreateCloudBackupDialog = false }
        )
    }

    // Cloud Backup Radio Picker Dialog (Lists ALL Backups for Verified Account + Restore / Rename / Delete)
    if (showCloudRestoreSelectorDialog) {
        CloudBackupRadioPickerDialog(
            linkedEmail = verifiedGoogleEmail,
            cloudBackupsState = cloudBackupsState,
            isBusy = isCloudBackupBusy,
            onRefresh = { viewModel.refreshCloudBackupsFromDrive(context, silent = false) },
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
                viewModel.renameCloudBackup(context, target, updatedName)
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
                viewModel.restoreCloudBackup(context, target)
                cloudBackupToRestore = null
            },
            onDismiss = { cloudBackupToRestore = null }
        )
    }

    // Confirm Delete Specific Cloud Backup
    cloudBackupToDelete?.let { target ->
        SimpleConfirmDialog(
            title = "تأكيد حذف النسخة السحابية",
            message = "هل أنت متأكد من حذف النسخة السحابية \"${target.name}\" نهائياً من Google Drive؟ لا يمكن التراجع عن هذا الإجراء.",
            confirmButtonText = "نعم، احذف نهائياً",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteCloudBackup(context, target)
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
