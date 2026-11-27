package com.example.ui.components

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import com.example.data.cloud.FirebaseAuthClient
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.RegisteredFirebaseAccount
import com.example.data.cloud.WarehousePermission
import com.example.data.model.Warehouse
import com.example.ui.theme.DangerRed
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MultiDeviceControlSection(
    viewModel: InventoryViewModel
) {
    val context = LocalContext.current
    val googleAccountPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            runCatching { FirebaseAuthClient.googleIdTokenFromSignInResult(result.data) }
                .onSuccess { idToken -> viewModel.signInForDeviceLinkWithGoogleToken(idToken) }
                .onFailure { failure ->
                    Toast.makeText(
                        context,
                        "تعذر اختيار حساب Google: ${failure.localizedMessage ?: "حاول مرة أخرى"}",
                        Toast.LENGTH_LONG
                    ).show()
                }
        }
    }
    val openGoogleAccountPicker = {
        runCatching { FirebaseAuthClient.googleAccountPickerIntent(context) }
            .onSuccess { intent -> googleAccountPicker.launch(intent) }
            .onFailure { failure ->
                Toast.makeText(
                    context,
                    "تعذر فتح قائمة حسابات Google: ${failure.localizedMessage ?: "تحقق من خدمات Google"}",
                    Toast.LENGTH_LONG
                ).show()
            }
    }
    val isMainDevice by viewModel.isMainDevice.collectAsState()
    val devicePermissions by viewModel.devicePermissions.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val isSubConnected by viewModel.isSubConnected.collectAsState()
    val isFirebaseReady by viewModel.isCloudSyncEnabled.collectAsState()
    val linkedSubDevices by viewModel.linkedSubDevices.collectAsState()
    val availableWarehouses by viewModel.warehouses.collectAsState()
    val isPairingInProgress by viewModel.isPairingInProgress.collectAsState()
    val pairingError by viewModel.pairingError.collectAsState()
    val isSyncingNow by viewModel.isSyncingNow.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()
    val lastSyncError by viewModel.lastSyncError.collectAsState()
    val firebaseEmail by viewModel.firebaseSignedInEmail.collectAsState()
    val firebaseAuthBusy by viewModel.firebaseAuthBusy.collectAsState()
    val firebaseAccounts by viewModel.firebaseAccounts.collectAsState()

    var showCreateSubDialog by remember { mutableStateOf(false) }
    var deviceToKick by remember { mutableStateOf<LinkedSubDevice?>(null) }
    var showUnlinkConfirmDialog by remember { mutableStateOf(false) }
    var accountToApprove by remember { mutableStateOf<RegisteredFirebaseAccount?>(null) }

    LaunchedEffect(isMainDevice, firebaseEmail, firebaseAuthBusy) {
        if (isMainDevice && firebaseEmail.isNotBlank() && !firebaseAuthBusy) {
            while (true) {
                viewModel.refreshFirebaseAccounts()
                delay(15_000L)
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth().testTag("multi_device_control_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Devices, contentDescription = "إدارة الأجهزة",
                            tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("ربط الأجهزة عبر Firebase", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "تسجيل الدخول بالبريد الإلكتروني ثم اعتماد الجهاز الفرعي من الجهاز الرئيسي. يعمل عبر الإنترنت حتى مع اختلاف الشبكات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isMainDevice) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (isMainDevice) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier.weight(1f).clickable(enabled = !isSubConnected) {
                        if (!isMainDevice && !isSubConnected) viewModel.switchToMainDeviceMode()
                    }.testTag("mode_main_device_btn")
                ) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (isSubConnected) "🔒 جهاز فرعي مرتبط" else "👑 الجهاز الرئيسي",
                            fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            color = if (isMainDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        Text("الإدارة والصلاحيات", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (!isMainDevice) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (!isMainDevice) BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary) else null,
                    modifier = Modifier.weight(1f).clickable(enabled = !isMainDevice || linkedSubDevices.isEmpty()) {
                        if (isMainDevice && linkedSubDevices.isEmpty()) viewModel.switchToSubDeviceDraftMode()
                    }.testTag("mode_sub_device_btn")
                ) {
                    Column(Modifier.padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("🔗 جهاز فرعي", fontWeight = FontWeight.Bold, fontSize = 13.sp,
                            color = if (!isMainDevice) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface)
                        Text("يعمل بصلاحيات المالك", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            if (isMainDevice) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (firebaseEmail.isNotBlank() && isFirebaseReady) SuccessGreen.copy(alpha = 0.08f) else WarningOrange.copy(alpha = 0.1f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            if (firebaseEmail.isNotBlank() && isFirebaseReady) "حساب Firebase الرئيسي متصل"
                            else "سجّل الدخول بحساب المستخدم الرئيسي",
                            fontWeight = FontWeight.Bold,
                            color = if (firebaseEmail.isNotBlank() && isFirebaseReady) SuccessGreen else WarningOrange
                        )
                        if (firebaseEmail.isNotBlank()) {
                            Text(firebaseEmail, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                        } else {
                            Text("يستخدم هذا الحساب لاعتماد حسابات الأجهزة الفرعية والتحكم في صلاحياتها.",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(
                            onClick = { openGoogleAccountPicker() },
                            enabled = !firebaseAuthBusy,
                            modifier = Modifier.fillMaxWidth().testTag("firebase_device_signin_btn")
                        ) {
                            if (firebaseAuthBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(if (firebaseEmail.isBlank()) "تسجيل الدخول عبر Google / Firebase" else "تحديث جلسة Firebase")
                        }
                    }
                }

                Button(
                    onClick = { showCreateSubDialog = true },
                    enabled = firebaseEmail.isNotBlank() && isFirebaseReady && !firebaseAuthBusy,
                    modifier = Modifier.fillMaxWidth().testTag("add_sub_device_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("إضافة جهاز فرعي وتحديد صلاحياته")
                }

                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("حسابات Firebase التي سجلت الدخول", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { viewModel.refreshFirebaseAccounts() },
                        enabled = firebaseEmail.isNotBlank() && !firebaseAuthBusy) {
                        Icon(Icons.Default.Refresh, contentDescription = "تحديث قائمة الحسابات")
                    }
                }
                Text(
                    "سجّل المستخدم الفرعي الدخول من جهازه أولاً. سيظهر بريده هنا، ثم اختر الحساب وحدد الجهاز الذي سيستخدمه.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (firebaseAccounts.isEmpty()) {
                    Text(
                        if (firebaseEmail.isBlank()) "يجب تسجيل دخول المستخدم الرئيسي أولاً."
                        else "لا توجد حسابات أخرى مسجلة حالياً. اطلب من المستخدم الفرعي تسجيل الدخول بحسابه على جهازه.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    firebaseAccounts.forEach { account ->
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Column(Modifier.weight(1f)) {
                                    Text(account.displayName.ifBlank { "مستخدم Firebase" }, fontWeight = FontWeight.Bold)
                                    Text(account.email, style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                OutlinedButton(
                                    onClick = { accountToApprove = account },
                                    enabled = !firebaseAuthBusy && linkedSubDevices.any {
                                        !it.isKicked && !it.wasUnlinked && !it.isClaimed
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                ) { Text("اعتماد", fontSize = 12.sp) }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Text("الأجهزة الفرعية (${linkedSubDevices.size})", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                if (linkedSubDevices.isEmpty()) {
                    Text("أنشئ جهازاً فرعياً أولاً، ثم امنح صلاحيات المخازن لحسابه.", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    linkedSubDevices.forEach { slot ->
                        SubDeviceManagementCard(
                            slot = slot,
                            warehouses = availableWarehouses,
                            onUpdatePermissions = { add, inward, disburse, editLegacy, backup, allWarehouses, grants, editInventory, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalog ->
                                viewModel.updateSubDevicePermissions(slot.id, add, inward, disburse, editLegacy, backup,
                                    allWarehouses, grants, editInventory, deleteItem, editIssue, deleteIssue, editSupply,
                                    deleteSupply, editCatalog)
                            },
                            onKick = { deviceToKick = slot },
                            onReactivate = { viewModel.reactivateSubDevice(slot.id) }
                        )
                    }
                }
            } else {
                if (!isSubConnected) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("ربط هذا الجهاز بحساب Firebase", fontWeight = FontWeight.Bold)
                            Text(
                                "سجّل الدخول بالبريد الإلكتروني الذي سيعتمده المستخدم الرئيسي. لا يحتاج الجهازان إلى شبكة Wi-Fi واحدة.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (firebaseEmail.isNotBlank()) {
                                Text("الحساب الحالي: $firebaseEmail", fontWeight = FontWeight.SemiBold)
                                OutlinedButton(
                                    onClick = { viewModel.checkApprovedFirebaseInvite() },
                                    enabled = !isPairingInProgress && !firebaseAuthBusy,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    if (isPairingInProgress) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("التحقق من اعتماد الحساب")
                                }
                                Text("سيكتمل الربط تلقائياً بعد اعتماد هذا البريد من الجهاز الرئيسي.",
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                            } else {
                                Button(
                                    onClick = { openGoogleAccountPicker() },
                                    enabled = !firebaseAuthBusy,
                                    modifier = Modifier.fillMaxWidth().testTag("firebase_device_signin_btn")
                                ) {
                                    if (firebaseAuthBusy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                                    else Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(8.dp))
                                    Text("تسجيل الدخول بحساب Google")
                                }
                            }
                            pairingError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                        }
                    }
                } else {
                    val lastSyncLabel = if (lastSyncAt > 0L) {
                        SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()).format(Date(lastSyncAt))
                    } else "الآن"
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SuccessGreen.copy(alpha = 0.09f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = SuccessGreen)
                                Spacer(Modifier.width(8.dp))
                                Text("الجهاز مرتبط بالجهاز الرئيسي عبر Firebase", fontWeight = FontWeight.Bold, color = SuccessGreen)
                            }
                            Text("الحساب: $firebaseEmail", style = MaterialTheme.typography.bodySmall)
                            Text("اسم الجهاز: $deviceName • آخر مزامنة: $lastSyncLabel", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    Text("الصلاحيات الممنوحة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        PermissionBadge("الاطلاع على المخازن", devicePermissions.canViewAllWarehouses || devicePermissions.warehousePermissions.any { it.canView })
                        PermissionBadge("إضافة أصناف ومخازن", devicePermissions.canAddItems)
                        PermissionBadge("تسجيل الوارد", devicePermissions.canInward || devicePermissions.warehousePermissions.any { it.canView && it.canInward })
                        PermissionBadge("صرف المخزون", devicePermissions.canDisburse || devicePermissions.warehousePermissions.any { it.canView && it.canDisburse })
                        PermissionBadge("التحويل المخزني", devicePermissions.warehousePermissions.any { it.canView && it.canTransfer })
                        PermissionBadge("تعديل الأرصدة", devicePermissions.warehousePermissions.any { it.canView && it.canAdjust })
                        PermissionBadge("تعديل بيانات المخزون", devicePermissions.canEditInventory || devicePermissions.warehousePermissions.any { it.canView && it.canEditInventory })
                        PermissionBadge("حذف الأصناف", devicePermissions.canDeleteItem || devicePermissions.warehousePermissions.any { it.canView && it.canDeleteItem })
                        PermissionBadge("تقارير المخازن", devicePermissions.warehousePermissions.any { it.canView && it.canReports })
                        PermissionBadge("تعديل أوامر الصرف", devicePermissions.canEditIssueVoucher)
                        PermissionBadge("حذف أوامر الصرف", devicePermissions.canDeleteIssueVoucher)
                        PermissionBadge("تعديل أوامر التوريد", devicePermissions.canEditSupplyVoucher)
                        PermissionBadge("حذف أوامر التوريد", devicePermissions.canDeleteSupplyVoucher)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                        Button(onClick = { viewModel.syncNow() }, enabled = !isSyncingNow,
                            modifier = Modifier.weight(1f).testTag("sync_now_btn"), shape = RoundedCornerShape(10.dp)) {
                            if (isSyncingNow) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = Color.White)
                            else Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("مزامنة الآن")
                        }
                        OutlinedButton(onClick = { showUnlinkConfirmDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            modifier = Modifier.weight(1f).testTag("unlink_device_btn"), shape = RoundedCornerShape(10.dp)) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("فك الارتباط")
                        }
                    }
                    lastSyncError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                }
            }
        }
    }

    if (showCreateSubDialog) {
        CreateSubDeviceDialog(
            warehouses = availableWarehouses,
            existingDeviceNames = linkedSubDevices.map { it.deviceName },
            onDismiss = { showCreateSubDialog = false },
            onCreate = { name, canAdd, canInward, canDisburse, legacyEdit, canBackup, allWarehouses, grants, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalog ->
                viewModel.createSubDeviceSlot(name, canAdd, canInward, canDisburse, legacyEdit, canBackup, allWarehouses,
                    grants, false, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalog)
                showCreateSubDialog = false
            }
        )
    }

    accountToApprove?.let { account ->
        val availableSlots = linkedSubDevices.filter { !it.isKicked && !it.wasUnlinked && !it.isClaimed }
        AlertDialog(
            onDismissRequest = { accountToApprove = null },
            title = { Text("اعتماد حساب ${account.email}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("اختر الجهاز الفرعي الذي سيُربط بهذا الحساب. ستُطبّق صلاحيات المخازن المحفوظة للجهاز المختار.")
                    if (availableSlots.isEmpty()) {
                        Text("لا توجد أجهزة فرعية جاهزة. أنشئ جهازاً فرعياً أو أعد تفعيل جهاز أولاً.",
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                    availableSlots.forEach { slot ->
                        OutlinedButton(
                            onClick = {
                                viewModel.approveFirebaseAccount(account, slot.id)
                                accountToApprove = null
                            },
                            enabled = !firebaseAuthBusy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text(slot.deviceName) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { accountToApprove = null }) { Text("إغلاق") } }
        )
    }

    deviceToKick?.let { target ->
        SimpleConfirmDialog(
            title = "طرد الجهاز الفرعي وإيقافه",
            message = "سيتم إبطال جلسة الجهاز. عند وصول أمر الإيقاف سيُمسح محتواه المحلي، مع الاحتفاظ ببياناته وسجل عملياته على الجهاز الرئيسي.",
            confirmButtonText = "نعم، اطرد المستخدم",
            isDestructive = true,
            onConfirm = { viewModel.kickSubDevice(target.id); deviceToKick = null },
            onDismiss = { deviceToKick = null }
        )
    }
    if (showUnlinkConfirmDialog) {
        SimpleConfirmDialog(
            title = "فك الارتباط بالجهاز الرئيسي",
            message = "سيتم فك ارتباط هذا الجهاز ومسح بياناته المحلية وسجل عملياته. ستبقى بيانات الجهاز الرئيسي كما هي.",
            confirmButtonText = "نعم، فك الارتباط",
            isDestructive = true,
            onConfirm = { viewModel.unlinkSubDevice(); showUnlinkConfirmDialog = false },
            onDismiss = { showUnlinkConfirmDialog = false }
        )
    }
}

@Composable
private fun PermissionBadge(label: String, granted: Boolean) {
    val bg = if (granted) SuccessGreen.copy(alpha = 0.14f) else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (granted) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = bg
    ) {
        Text(
            text = if (granted) "✓ $label" else "✕ $label",
            fontSize = 11.sp,
            fontWeight = if (granted) FontWeight.Bold else FontWeight.Normal,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

@Composable
private fun SubDeviceManagementCard(
    slot: LinkedSubDevice,
    warehouses: List<Warehouse>,
    onUpdatePermissions: (Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, List<WarehousePermission>, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
    onKick: () -> Unit,
    onReactivate: () -> Unit
) {
    var canBackup by remember(slot.id) { mutableStateOf(slot.canBackup) }
    var canViewAll by remember(slot.id) { mutableStateOf(slot.canViewAllWarehouses) }
    var grants by remember(slot.id) { mutableStateOf(slot.warehousePermissions) }
    var editCatalogItems by remember(slot.id) { mutableStateOf(slot.canEditCatalogItems) }
    var deleteItem by remember(slot.id) { mutableStateOf(slot.canDeleteItem) }
    var editIssue by remember(slot.id) { mutableStateOf(slot.canEditIssueVoucher) }
    var deleteIssue by remember(slot.id) { mutableStateOf(slot.canDeleteIssueVoucher) }
    var editSupply by remember(slot.id) { mutableStateOf(slot.canEditSupplyVoucher) }
    var deleteSupply by remember(slot.id) { mutableStateOf(slot.canDeleteSupplyVoucher) }
    var editGeneral by remember(slot.id) { mutableStateOf(slot.canEditDelete) }
    var expanded by remember(slot.id) { mutableStateOf(false) }

    val isOnline = slot.isClaimed && !slot.isKicked && !slot.wasUnlinked &&
        System.currentTimeMillis() - slot.lastSeenAt < 15_000L
    val statusText = when {
        slot.wasUnlinked -> "تم فك الارتباط – جاهز لإعادة الاتصال"
        slot.isKicked -> "موقوف"
        isOnline -> "متصل الآن"
        slot.isClaimed -> "غير متصل"
        else -> "بانتظار الربط"
    }
    val statusColor = when {
        slot.isKicked -> DangerRed
        isOnline -> SuccessGreen
        slot.isClaimed -> WarningOrange
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusBackground = statusColor.copy(alpha = 0.12f)
    val lastSeenText = if (slot.lastSeenAt > 0L) {
        "آخر اتصال: " + SimpleDateFormat("dd/MM/yyyy  hh:mm a", Locale.getDefault()).format(Date(slot.lastSeenAt))
    } else {
        "لم يتصل بعد"
    }
    val publish: () -> Unit = {
        onUpdatePermissions(
            slot.canAddItems, slot.canInward, slot.canDisburse, editGeneral,
            canBackup, canViewAll, grants, false, deleteItem, editIssue,
            deleteIssue, editSupply, deleteSupply, editCatalogItems
        )
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(
            1.dp,
            if (slot.isKicked) DangerRed.copy(alpha = 0.35f)
            else if (isOnline) SuccessGreen.copy(alpha = 0.35f)
            else MaterialTheme.colorScheme.outlineVariant
        ),
        shadowElevation = if (expanded) 3.dp else 1.dp,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sub_device_card_${slot.id}")
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(15.dp),
                    color = statusBackground,
                    modifier = Modifier.size(48.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = statusColor,
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = slot.deviceName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(50),
                            color = statusBackground
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = statusColor,
                                    modifier = Modifier.size(7.dp)
                                ) {}
                                Text(
                                    text = statusText,
                                    color = statusColor,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    Text(
                        text = lastSeenText,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1
                    )
                }
                Icon(
                    imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "طي التفاصيل" else "عرض التفاصيل",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(19.dp)
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (!slot.isKicked && !slot.wasUnlinked) {
                    OutlinedButton(
                        onClick = onKick,
                        modifier = Modifier.weight(1f).testTag("kick_device_btn_${slot.id}"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("إيقاف الجهاز", fontSize = 12.sp)
                    }
                } else {
                    Button(
                        onClick = onReactivate,
                        modifier = Modifier.weight(1f).testTag("reactivate_device_btn_${slot.id}"),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (slot.wasUnlinked) "إعادة الاتصال" else "إعادة التفعيل", fontSize = 12.sp)
                    }
                }
            }

            if (expanded && !slot.isKicked && !slot.wasUnlinked) {
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text(
                    "إعدادات وصلاحيات الجهاز",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                PermissionSwitchRow("إضافة أصناف ومخازن إلى الدليل", slot.canAddItems) {
                    onUpdatePermissions(it, slot.canInward, slot.canDisburse, editGeneral, canBackup, canViewAll, grants, false, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalogItems)
                }
                PermissionSwitchRow("تعديل بيانات الأصناف داخل الدليل", editCatalogItems) { editCatalogItems = it; publish() }
                PermissionSwitchRow("تعديل وحذف البيانات العامة الأخرى (الموردون والجهات والوحدات)", editGeneral) { editGeneral = it; publish() }
                Text("صلاحيات المخزون والأصناف", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                PermissionSwitchRow("حذف صنف من الدليل", deleteItem) { deleteItem = it; publish() }
                Text("أوامر الصرف والتحويل", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                PermissionSwitchRow("تعديل أمر الصرف / التحويل", editIssue) { editIssue = it; publish() }
                PermissionSwitchRow("حذف أمر الصرف / التحويل", deleteIssue) { deleteIssue = it; publish() }
                Text("أوامر التوريد", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                PermissionSwitchRow("تعديل أمر التوريد", editSupply) { editSupply = it; publish() }
                PermissionSwitchRow("حذف أمر التوريد", deleteSupply) { deleteSupply = it; publish() }
                PermissionSwitchRow("السماح بالنسخ الاحتياطي والتصدير المحلي وعبر Google Drive وJSON", canBackup) { canBackup = it; publish() }
                WarehousePermissionsEditor(
                    warehouses = warehouses,
                    allWarehouses = canViewAll,
                    grants = grants,
                    onAllWarehousesChange = { canViewAll = it; publish() },
                    onGrantsChange = { grants = it; publish() }
                )
            }
        }
    }
}

@Composable
private fun WarehousePermissionsEditor(
    warehouses: List<Warehouse>,
    allWarehouses: Boolean,
    grants: List<WarehousePermission>,
    onAllWarehousesChange: (Boolean) -> Unit,
    onGrantsChange: (List<WarehousePermission>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        HorizontalDivider()
        Text("صلاحيات المخازن", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelLarge)
        PermissionSwitchRow("إظهار جميع المخازن", allWarehouses, onAllWarehousesChange)
        if (warehouses.isEmpty()) {
            Text("لا توجد مخازن مسجلة حالياً.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        warehouses.forEach { warehouse ->
            val current = grants.firstOrNull { it.warehouseId == warehouse.id } ?: WarehousePermission(warehouseId = warehouse.id, canView = allWarehouses)
            var warehouseExpanded by remember(warehouse.id) { mutableStateOf(false) }
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 8.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.45f), RoundedCornerShape(8.dp)).padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Row(modifier = Modifier.fillMaxWidth().clickable { warehouseExpanded = !warehouseExpanded }, horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(warehouse.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
                    Text(if (warehouseExpanded) "▴" else "▾", color = MaterialTheme.colorScheme.primary)
                }
                if (warehouseExpanded) {
                if (!allWarehouses) {
                    PermissionSwitchRow("إظهار هذا المخزن", current.canView) { enabled ->
                        val updated = current.copy(canView = enabled, canWorkOffline = if (enabled) current.canWorkOffline else false)
                        onGrantsChange((grants.filterNot { it.warehouseId == warehouse.id } + updated))
                    }
                }
                val visible = allWarehouses || current.canView
                fun update(transform: (WarehousePermission) -> WarehousePermission) {
                    val updated = transform(current).copy(canView = visible)
                    onGrantsChange(grants.filterNot { it.warehouseId == warehouse.id } + updated)
                }
                PermissionSwitchRow("التوريد", current.canInward) { enabled -> update { it.copy(canInward = enabled) } }
                PermissionSwitchRow("الصرف", current.canDisburse) { enabled -> update { it.copy(canDisburse = enabled) } }
                PermissionSwitchRow("التحويل المخزني", current.canTransfer) { enabled -> update { it.copy(canTransfer = enabled) } }
                PermissionSwitchRow("تعديل الأرصدة والتعديلات المخزنية", current.canAdjust) { enabled -> update { it.copy(canAdjust = enabled) } }
                PermissionSwitchRow("التعديلات العامة داخل المخزن", current.canEdit) { enabled -> update { it.copy(canEdit = enabled) } }
                PermissionSwitchRow("تعديل بيانات المخزون والأصناف في هذا المخزن", current.canEditInventory) { enabled -> update { it.copy(canEditInventory = enabled) } }
                PermissionSwitchRow("حذف الأصناف المرتبطة بهذا المخزن", current.canDeleteItem) { enabled -> update { it.copy(canDeleteItem = enabled) } }
                PermissionSwitchRow("تقارير هذا المخزن", current.canReports) { enabled -> update { it.copy(canReports = enabled) } }
                if (visible) {
                    PermissionSwitchRow("تخصيص هذا الجهاز للعمل دون اتصال (جهاز واحد فقط لكل مخزن)", current.canWorkOffline) { enabled ->
                        update { it.copy(canWorkOffline = enabled) }
                    }
                }
                }
            }
        }
    }
}

@Composable
private fun PermissionSwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(text = label, style = MaterialTheme.typography.bodySmall)
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CreateSubDeviceDialog(
    warehouses: List<Warehouse>,
    existingDeviceNames: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean, List<WarehousePermission>, Boolean, Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var canAdd by remember { mutableStateOf(false) }
    var canInward by remember { mutableStateOf(false) }
    var canDisburse by remember { mutableStateOf(false) }
    var canEditCatalogItems by remember { mutableStateOf(false) }
    var canDeleteItem by remember { mutableStateOf(false) }
    var canEditIssue by remember { mutableStateOf(false) }
    var canDeleteIssue by remember { mutableStateOf(false) }
    var canEditSupply by remember { mutableStateOf(false) }
    var canDeleteSupply by remember { mutableStateOf(false) }
    var canEditGeneral by remember { mutableStateOf(false) }
    var canBackup by remember { mutableStateOf(false) }
    var allWarehouses by remember { mutableStateOf(false) }
    var grants by remember { mutableStateOf(emptyList<WarehousePermission>()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("إضافة جهاز فرعي جديد", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val duplicateName = name.trim().isNotBlank() && existingDeviceNames.any { it.trim().equals(name.trim(), ignoreCase = true) }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("اسم الجهاز / الموظف") }, modifier = Modifier.fillMaxWidth().testTag("new_sub_device_name_input"), singleLine = true, isError = duplicateName)
                if (duplicateName) Text("هذا الاسم مستخدم لجهاز مسجل مسبقاً. اختر اسماً مختلفاً.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
                Text("الصلاحيات العامة", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                PermissionSwitchRow("إضافة أصناف ومخازن إلى الدليل", canAdd) { canAdd = it }
                PermissionSwitchRow("تعديل بيانات الأصناف داخل الدليل", canEditCatalogItems) { canEditCatalogItems = it }
                PermissionSwitchRow("تعديل وحذف البيانات العامة الأخرى (الموردون والجهات والوحدات)", canEditGeneral) { canEditGeneral = it }
                Text("صلاحيات المخزون والأصناف داخل المخازن", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                PermissionSwitchRow("حذف صنف من الدليل", canDeleteItem) { canDeleteItem = it }
                Text("صلاحيات أوامر الصرف", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                PermissionSwitchRow("تعديل أمر الصرف / التحويل", canEditIssue) { canEditIssue = it }
                PermissionSwitchRow("حذف أمر الصرف / التحويل", canDeleteIssue) { canDeleteIssue = it }
                Text("صلاحيات أوامر التوريد", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                PermissionSwitchRow("تعديل أمر التوريد", canEditSupply) { canEditSupply = it }
                PermissionSwitchRow("حذف أمر التوريد", canDeleteSupply) { canDeleteSupply = it }
                PermissionSwitchRow("النسخ الاحتياطي عبر Google Drive وتصدير JSON", canBackup) { canBackup = it }
                WarehousePermissionsEditor(warehouses, allWarehouses, grants, { allWarehouses = it }, { grants = it })
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, canAdd, canInward, canDisburse, canEditGeneral, canBackup, allWarehouses, grants, canDeleteItem, canEditIssue, canDeleteIssue, canEditSupply, canDeleteSupply, canEditCatalogItems) }, enabled = name.trim().isNotBlank() && !existingDeviceNames.any { it.trim().equals(name.trim(), ignoreCase = true) }, modifier = Modifier.testTag("confirm_create_sub_device_btn")) {
                Text("حفظ الجهاز الفرعي")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

