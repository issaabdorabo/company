package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.widget.Toast
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Login
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import com.example.BuildConfig
import com.example.data.model.BackupRecord
import com.example.ui.components.MultiDeviceControlSection
import com.example.ui.components.SimpleConfirmDialog
import com.example.ui.theme.AppTheme
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.NonCancellable
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
    val uiContext = remember(context) { context.findActivity() ?: context }
    val credentialManager = remember(uiContext) { CredentialManager.create(uiContext) }

    val auth = remember { Firebase.auth }
    var authUser by remember { mutableStateOf(auth.currentUser) }
    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            authUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    val currentTheme by viewModel.theme.collectAsState()
    val currentUserName by viewModel.userName.collectAsState()
    val currentOrgName by viewModel.orgName.collectAsState()
    val currentGoogleAccount by viewModel.googleAccount.collectAsState()
    val syncEnabled by viewModel.syncEnabled.collectAsState()
    val lastSyncTime by viewModel.lastSyncTime.collectAsState()
    val backups by viewModel.backups.collectAsState()
    val diagnosticsReport by viewModel.diagnosticsReport.collectAsState()
    val isRunningDiagnostics by viewModel.isRunningDiagnostics.collectAsState()

    val authenticatedEmail = authUser?.email ?: currentGoogleAccount
    var editUserName by remember(currentUserName) { mutableStateOf(currentUserName) }
    var editOrgName by remember(currentOrgName) { mutableStateOf(currentOrgName) }

    var isSigningIn by remember { mutableStateOf(false) }
    var authSignInError by remember { mutableStateOf<String?>(null) }

    // Dialog states
    var showCreateBackupDialog by remember { mutableStateOf(false) }
    var showRestoreSelectorDialog by remember { mutableStateOf(false) }
    var backupToRestore by remember { mutableStateOf<BackupRecord?>(null) }
    var backupToDelete by remember { mutableStateOf<BackupRecord?>(null) }
    var showSignOutConfirmDialog by remember { mutableStateOf(false) }

    val isOnline = isNetworkAvailable(context)

    fun launchGoogleVerification() {
        if (isSigningIn) return
        isSigningIn = true
        authSignInError = null
        coroutineScope.launch {
            when (val outcome = performGoogleSignIn(uiContext, credentialManager)) {
                is GoogleSignInOutcome.Success -> {
                    val active = auth.currentUser
                    authUser = active
                    if (active != null) {
                        viewModel.onAuthenticatedSessionStarted(active.uid, active.email)
                    }
                    authSignInError = null
                    Toast.makeText(context, "تم التحقق وتوثيق حساب Google بنجاح", Toast.LENGTH_SHORT).show()
                    viewModel.runSystemAndCloudDiagnostics(isNetworkAvailable(context))
                }
                is GoogleSignInOutcome.Cancelled -> {
                    authSignInError = outcome.reason
                }
                is GoogleSignInOutcome.Error -> {
                    authSignInError = outcome.message
                }
            }
            isSigningIn = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().testTag("settings_screen_root"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 0. Verification, Account Auth & System/Cloud Diagnostics Hub
        item {
            Card(
                modifier = Modifier.fillMaxWidth().testTag("verification_and_diagnostics_card"),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "شاشة التحقق والاختبارات",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "توثيق حساب Google وفحص قاعدة البيانات والربط السحابي",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Google Verification Status Box
                    Surface(
                        color = if (authUser != null) SuccessGreen.copy(alpha = 0.09f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                    Icon(
                                        imageVector = if (authUser != null) Icons.Default.CheckCircle else Icons.Default.AccountCircle,
                                        contentDescription = null,
                                        tint = if (authUser != null) SuccessGreen else MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(24.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Column {
                                        Text(
                                            text = if (authUser != null) "حساب Google موثق" else "الوضع المحلي (بدون توثيق سحابي)",
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = if (authUser != null) SuccessGreen else MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = if (authUser != null) {
                                                authenticatedEmail.ifBlank { authUser?.uid?.take(12).orEmpty() }
                                            } else {
                                                "يمكنك العمل محلياً أو التحقق بحساب Google للربط والمزامنة"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }

                            if (authUser == null) {
                                Button(
                                    onClick = { launchGoogleVerification() },
                                    enabled = !isSigningIn,
                                    modifier = Modifier.fillMaxWidth().testTag("google_signin_button"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    if (isSigningIn) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.onPrimary
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("جاري التحقق بحساب Google...")
                                    } else {
                                        Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text("التحقق وتسجيل الدخول بحساب Google")
                                    }
                                }
                            } else {
                                OutlinedButton(
                                    onClick = { showSignOutConfirmDialog = true },
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("verification_sign_out_btn")
                                ) {
                                    Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("تسجيل الخروج من الحساب الموثق")
                                }
                            }

                            if (!authSignInError.isNullOrBlank()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth().testTag("auth_error_card"),
                                    colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.1f)),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Row(modifier = Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Icon(Icons.Default.ErrorOutline, contentDescription = null, tint = DangerRed, modifier = Modifier.size(18.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = authSignInError ?: "",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = DangerRed
                                        )
                                    }
                                }
                            }
                        }
                    }

                    HorizontalDivider()

                    // System & Cloud Diagnostics Section
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.FactCheck, contentDescription = null, tint = InfoBlue, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "الاختبارات والتحقق من النظام والربط",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Button(
                        onClick = { viewModel.runSystemAndCloudDiagnostics(isNetworkAvailable(context)) },
                        enabled = !isRunningDiagnostics,
                        modifier = Modifier.fillMaxWidth().testTag("run_diagnostics_btn"),
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
                            Text("جاري تنفيذ الاختبارات والفحص...")
                        } else {
                            Icon(Icons.Default.FactCheck, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("تشغيل الاختبارات والتحقق الآن")
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
                                Surface(
                                    color = if (check.passed) SuccessGreen.copy(alpha = 0.08f) else WarningOrange.copy(alpha = 0.1f),
                                    shape = RoundedCornerShape(10.dp),
                                    modifier = Modifier.fillMaxWidth().testTag("diagnostic_item_$idx")
                                ) {
                                    Row(
                                        modifier = Modifier.padding(10.dp),
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        Icon(
                                            imageVector = if (check.passed) Icons.Default.CheckCircle else Icons.Default.ErrorOutline,
                                            contentDescription = null,
                                            tint = if (check.passed) SuccessGreen else WarningOrange,
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

        // 1. Multi-Device Unified Sync, Unique Passwords & Recovery Control Center
        item {
            MultiDeviceControlSection(
                viewModel = viewModel,
                isAuthenticated = authUser != null,
                onRequestGoogleSignIn = { launchGoogleVerification() }
            )
        }

        // 1. User & Organization Identity Profile
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(text = "بيانات المستخدم والمنشأة للتقارير", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = editUserName,
                        onValueChange = { editUserName = it },
                        label = { Text("اسم مستخدم التطبيق (يظهر في السجلات والتقارير)") },
                        modifier = Modifier.fillMaxWidth().testTag("settings_username_input"),
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

        // 2. Luxury Themes
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Palette, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(text = "مظهر التطبيق والثيمات الفاخرة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text = "اختر أحد الثيمات الفاخرة الستة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                    Column(modifier = Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                        Box(
                                            modifier = Modifier
                                                .size(28.dp)
                                                .background(themeColor, CircleShape)
                                                .border(2.dp, Color.White, CircleShape),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSelected) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
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

        // 3. Authenticated Google Account & Real-Time Firestore Sync
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.CloudSync, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                }
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(text = "حساب Google والمزامنة السحابية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(text = "المزامنة اللحظية والاسترداد التلقائي", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Switch(
                            checked = syncEnabled,
                            onCheckedChange = { viewModel.setSyncEnabled(it) }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Google Account Box
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Default.AccountCircle, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text("الحساب الموثق المتصل بالقاعدة:", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(
                                    text = if (authUser != null) authenticatedEmail.ifBlank { "حساب Google نشط" }
                                    else "غير متصل بحساب Google — يمكنك التوثيق من قسم التحقق أعلاه",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        if (authUser != null) {
                            OutlinedButton(
                                onClick = { showSignOutConfirmDialog = true },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("sign_out_btn")
                            ) {
                                Icon(Icons.Default.Logout, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("خروج", fontSize = 11.sp)
                            }
                        } else {
                            Button(
                                onClick = { launchGoogleVerification() },
                                enabled = !isSigningIn,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.testTag("sync_card_signin_btn")
                            ) {
                                Icon(Icons.Default.Login, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("توثيق", fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Icon(
                                imageVector = if (isOnline) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = if (isOnline) SuccessGreen else DangerRed,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            val syncText = if (lastSyncTime > 0L) dateFormat.format(Date(lastSyncTime)) else "الآن"
                            Text(
                                text = if (isOnline) "متصل بالإنترنت | آخر مزامنة: $syncText"
                                else "لا يوجد اتصال بالإنترنت (يتم الحفظ محلياً والرفع لاحقاً)",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (isOnline) MaterialTheme.colorScheme.onSurfaceVariant else DangerRed
                            )
                        }

                        Button(
                            onClick = {
                                val onlineNow = isNetworkAvailable(context)
                                if (authUser == null) {
                                    Toast.makeText(context, "يرجى التوثيق بحساب Google من قسم التحقق أولاً", Toast.LENGTH_SHORT).show()
                                    launchGoogleVerification()
                                } else if (onlineNow) {
                                    viewModel.performManualSync()
                                } else {
                                    Toast.makeText(context, "تنبيه: لا يوجد اتصال بالإنترنت حالياً، بياناتك محفوظة محلياً بالكامل", Toast.LENGTH_LONG).show()
                                }
                            },
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                        ) {
                            Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("مزامنة الآن", fontSize = 12.sp)
                        }
                    }
                }
            }
        }

        // 4. Multi-Backup Management (Local & Cloud Storage Options)
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.size(36.dp)) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.Backup, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                            }
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(text = "إدارة النسخ الاحتياطية المتعددة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Text(text = "حفظ واستعادة النسخ الاحتياطية في الهاتف أو السحابة", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { showCreateBackupDialog = true },
                            modifier = Modifier.weight(1f).testTag("create_backup_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("حفظ نسخة جديدة", fontSize = 12.sp)
                        }

                        Button(
                            onClick = { showRestoreSelectorDialog = true },
                            modifier = Modifier.weight(1f).testTag("restore_backup_btn"),
                            colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("استعادة نسخة", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Text(
                        text = "النسخ المحفوظة حالياً: ${backups.size} نسخة (اضغط \"استعادة نسخة\" لعرض واختيار النسخة)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // 5. Developer & About
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(text = "نظام مخزني الذكي للإدارة المخزنية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "الإصدار المعتمد ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(text = "طور بواسطة عيسى عبدربه  |  هاتف: 771438740", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    // Confirm Sign Out Dialog
    if (showSignOutConfirmDialog) {
        SimpleConfirmDialog(
            title = "تسجيل الخروج من حساب Google",
            message = "هل تريد تسجيل الخروج؟ بياناتك محفوظة بأمان في السحابة ويمكنك استردادها في أي وقت بتسجيل الدخول مجدداً.",
            confirmButtonText = "نعم، تسجيل الخروج",
            isDestructive = true,
            onConfirm = {
                showSignOutConfirmDialog = false
                coroutineScope.launch {
                    withContext(NonCancellable) {
                        try {
                            credentialManager.clearCredentialState(ClearCredentialStateRequest())
                        } catch (_: Exception) {
                        }
                        Firebase.auth.signOut()
                        viewModel.onSignedOut()
                    }
                }
            },
            onDismiss = { showSignOutConfirmDialog = false }
        )
    }

    // Create Backup Dialog: Choice to save in Phone OR in Cloud
    if (showCreateBackupDialog) {
        var backupName by remember {
            val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
            mutableStateOf("نسخة_$dateStr")
        }
        var saveLocation by remember { mutableStateOf("PHONE") } // "PHONE" or "CLOUD"

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

                    Text("اختر مكان حفظ النسخة:", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)

                    // Phone storage option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (saveLocation == "PHONE") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { saveLocation = "PHONE" }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = saveLocation == "PHONE", onClick = { saveLocation = "PHONE" })
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("حفظ في ذاكرة الهاتف", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text("تخزين محلي فوري وآمن", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }

                    // Cloud storage option
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                if (saveLocation == "CLOUD") MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                                else MaterialTheme.colorScheme.surface,
                                RoundedCornerShape(8.dp)
                            )
                            .clickable { saveLocation = "CLOUD" }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = saveLocation == "CLOUD", onClick = { saveLocation = "CLOUD" })
                        Spacer(modifier = Modifier.width(6.dp))
                        Icon(Icons.Default.Cloud, contentDescription = null, tint = InfoBlue)
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text("حفظ في التخزين السحابي / Google Drive", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (authUser != null) "على بريد: ${authenticatedEmail.ifBlank { "حساب Google نشط" }}" else "يتطلب التوثيق بحساب Google للرفع السحابي",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (saveLocation == "CLOUD" && !isOnline) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.15f)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Row(modifier = Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Warning, contentDescription = null, tint = WarningOrange, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "تنبيه: لا يوجد إنترنت حالياً. سيتم حفظ النسخة محلياً ومزامنتها تلقائياً مع السحابة فور عودة الاتصال دون فقدان أي بيانات.",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WarningOrange
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val isCloud = saveLocation == "CLOUD"
                        viewModel.createBackup(backupName.trim().ifBlank { "نسخة احتياطية" }, isCloud = isCloud)
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

    // Restore Backup Selector Dialog (Shows Local and Cloud Backups per Requirement 9)
    if (showRestoreSelectorDialog) {
        var restoreTab by remember { mutableStateOf("ALL") } // "ALL", "LOCAL", "CLOUD"
        val dateFormat = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

        val filteredBackups = remember(backups, restoreTab) {
            when (restoreTab) {
                "LOCAL" -> backups.filter { !it.isCloud }
                "CLOUD" -> backups.filter { it.isCloud }
                else -> backups
            }
        }

        AlertDialog(
            onDismissRequest = { showRestoreSelectorDialog = false },
            title = {
                Text("استعادة وإدارة النسخ الاحتياطية", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    // Filter Chips: Phone vs Cloud
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        FilterChip(
                            selected = restoreTab == "ALL",
                            onClick = { restoreTab = "ALL" },
                            label = { Text("الكل (${backups.size})") }
                        )
                        FilterChip(
                            selected = restoreTab == "LOCAL",
                            onClick = { restoreTab = "LOCAL" },
                            label = { Text("الهاتف") }
                        )
                        FilterChip(
                            selected = restoreTab == "CLOUD",
                            onClick = { restoreTab = "CLOUD" },
                            label = { Text("السحابية") }
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    if (filteredBackups.isEmpty()) {
                        Box(modifier = Modifier.fillMaxWidth().padding(20.dp), contentAlignment = Alignment.Center) {
                            Text("لا توجد نسخ احتياطية مسجلة في هذا القسم", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                                items(filteredBackups.size) { i ->
                                    val bkp = filteredBackups[i]
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        shape = RoundedCornerShape(10.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(modifier = Modifier.weight(1f)) {
                                                Row(verticalAlignment = Alignment.CenterVertically) {
                                                    Icon(
                                                        imageVector = if (bkp.isCloud) Icons.Default.Cloud else Icons.Default.PhoneAndroid,
                                                        contentDescription = null,
                                                        tint = if (bkp.isCloud) InfoBlue else MaterialTheme.colorScheme.primary,
                                                        modifier = Modifier.size(16.dp)
                                                    )
                                                    Spacer(modifier = Modifier.width(6.dp))
                                                    Text(text = bkp.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                                                }
                                                Text(text = dateFormat.format(Date(bkp.timestamp)), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
            message = "تحذير: هل أنت متأكد من استعادة النسخة: \"${backupToRestore!!.name}\"؟\nسيتم استرجاع جميع المخازن والأصناف والحركات المحفوظة في هذه النسخة.",
            confirmButtonText = "نعم، استعد البيانات الآن",
            isDestructive = true,
            onConfirm = {
                viewModel.restoreBackup(backupToRestore!!)
                backupToRestore = null
            },
            onDismiss = { backupToRestore = null }
        )
    }

    // Delete Backup Confirmation Dialog (With confirmation as requested in Requirement 9)
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

private fun isNetworkAvailable(context: Context): Boolean {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
    val activeNetwork = cm.activeNetwork ?: return false
    val capabilities = cm.getNetworkCapabilities(activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
