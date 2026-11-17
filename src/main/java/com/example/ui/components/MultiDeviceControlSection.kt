package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.WifiFind
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableLongStateOf
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
import com.example.data.cloud.CreatedDeviceCredentials
import com.example.data.cloud.LinkedSubDevice
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiDeviceControlSection(
    viewModel: InventoryViewModel
) {
    val context = LocalContext.current

    val isMainDevice by viewModel.isMainDevice.collectAsState()
    val devicePermissions by viewModel.devicePermissions.collectAsState()
    val workspaceCode by viewModel.workspaceCode.collectAsState()
    val mainHostUrl by viewModel.mainHostUrl.collectAsState()
    val deviceName by viewModel.deviceName.collectAsState()
    val isSubConnected by viewModel.isSubConnected.collectAsState()
    val linkedSubDevices by viewModel.linkedSubDevices.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val discoveredMainDevices by viewModel.discoveredMainDevices.collectAsState()
    val createdCredentials by viewModel.createdDeviceCredentials.collectAsState()
    val isPairingInProgress by viewModel.isPairingInProgress.collectAsState()
    val pairingError by viewModel.pairingError.collectAsState()
    val isSyncingNow by viewModel.isSyncingNow.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()
    val lastSyncError by viewModel.lastSyncError.collectAsState()
    val pairingLockedUntil by viewModel.pairingLockedUntil.collectAsState()

    var showCreateSubDialog by remember { mutableStateOf(false) }
    var deviceToKick by remember { mutableStateOf<LinkedSubDevice?>(null) }
    var deviceToRemove by remember { mutableStateOf<LinkedSubDevice?>(null) }
    var showUnlinkConfirmDialog by remember { mutableStateOf(false) }
    var showSwitchToSubConfirm by remember { mutableStateOf(false) }

    // Sub-device pairing inputs
    var hostInput by remember(mainHostUrl) { mutableStateOf(mainHostUrl.removePrefix("http://")) }
    var codeInput by remember { mutableStateOf("") }
    var passwordInput by remember { mutableStateOf("") }

    // Cooldown countdown ticker
    var nowMillis by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(pairingLockedUntil) {
        while (pairingLockedUntil > System.currentTimeMillis()) {
            nowMillis = System.currentTimeMillis()
            delay(1000L)
        }
        nowMillis = System.currentTimeMillis()
    }
    val remainingLockSeconds = ((pairingLockedUntil - nowMillis) / 1000L).coerceAtLeast(0L)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("multi_device_control_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = "إدارة تعدد الأجهزة",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "ربط الأجهزة والمزامنة المباشرة (رئيسي ↔ فرعي)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "ربط مباشر عبر Wi-Fi أو نقطة الاتصال بدون تسجيل دخول Google أو قيود مفاتيح",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Role Switcher
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isMainDevice) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (isMainDevice) BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary) else null,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (!isMainDevice) {
                                if (isSubConnected) {
                                    showUnlinkConfirmDialog = true
                                } else {
                                    viewModel.switchToMainDeviceMode()
                                }
                            }
                        }
                        .testTag("mode_main_device_btn")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "👑 جهاز رئيسي",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (isMainDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "كامل الصلاحيات والإدارة",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (!isMainDevice) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    border = if (!isMainDevice) BorderStroke(1.5.dp, MaterialTheme.colorScheme.secondary) else null,
                    modifier = Modifier
                        .weight(1f)
                        .clickable {
                            if (isMainDevice) {
                                if (linkedSubDevices.any { !it.isKicked }) {
                                    showSwitchToSubConfirm = true
                                } else {
                                    viewModel.switchToSubDeviceDraftMode()
                                }
                            }
                        }
                        .testTag("mode_sub_device_btn")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "🔗 جهاز فرعي",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = if (!isMainDevice) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "يتبع الجهاز الرئيسي بصلاحيات",
                            fontSize = 10.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (isMainDevice) {
                // MAIN DEVICE CONTROLS
                val displayCode = workspaceCode.ifBlank { serverStatus.workspaceCode }
                val primaryAddr = serverStatus.primaryAddress
                val hasLanIp = serverStatus.hostAddresses.isNotEmpty()

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (serverStatus.isRunning && hasLanIp) {
                        SuccessGreen.copy(alpha = 0.08f)
                    } else {
                        WarningOrange.copy(alpha = 0.1f)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Router,
                                    contentDescription = null,
                                    tint = if (serverStatus.isRunning && hasLanIp) SuccessGreen else WarningOrange,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (serverStatus.isRunning && hasLanIp) {
                                        "خادم الربط المباشر نشط وجاهز"
                                    } else {
                                        "فعّل Wi-Fi أو نقطة الاتصال ليظهر عنوان الربط"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (serverStatus.isRunning && hasLanIp) SuccessGreen else WarningOrange
                                )
                            }
                            IconButton(
                                onClick = { viewModel.refreshMainServerInfo() },
                                modifier = Modifier
                                    .size(32.dp)
                                    .testTag("refresh_lan_server_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "تحديث عنوان الشبكة",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "عنوان الجهاز (IP:Port)",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = primaryAddr,
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.weight(1f)
                            ) {
                                Column(modifier = Modifier.padding(8.dp)) {
                                    Text(
                                        text = "رمز قاعدة البيانات",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = displayCode.ifBlank { "---" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    copyToClipboard(
                                        context,
                                        "عنوان الجهاز الرئيسي: $primaryAddr\nرمز القاعدة: $displayCode"
                                    )
                                    Toast.makeText(context, "تم نسخ عنوان الجهاز ورمز القاعدة", Toast.LENGTH_SHORT).show()
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("copy_main_info_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("نسخ بيانات الربط", fontSize = 11.sp)
                            }
                            OutlinedButton(
                                onClick = { viewModel.regenerateWorkspaceCode() },
                                modifier = Modifier.testTag("regen_workspace_code_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("تحديث الرمز", fontSize = 11.sp)
                            }
                        }
                    }
                }

                Button(
                    onClick = { showCreateSubDialog = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("add_sub_device_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("إضافة جهاز فرعي وإنشاء كلمة مرور منفردة")
                }

                // Linked Sub-Devices List
                HorizontalDivider()
                Text(
                    text = "الأجهزة الفرعية المسجلة (${linkedSubDevices.size})",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )

                if (linkedSubDevices.isEmpty()) {
                    Text(
                        text = "لم يتم إنشاء أي أجهزة فرعية بعد. اضغط على الزر أعلاه لتوليد كلمة مرور منفردة لجهاز جديد.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    linkedSubDevices.forEach { slot ->
                        SubDeviceManagementCard(
                            slot = slot,
                            onUpdatePermissions = { add, inward, disburse, edit ->
                                viewModel.updateSubDevicePermissions(slot.id, add, inward, disburse, edit)
                            },
                            onKick = { deviceToKick = slot },
                            onRemove = { deviceToRemove = slot }
                        )
                    }
                }
            } else {
                // SUB-DEVICE MODE
                if (!isSubConnected) {
                    // Nearby Auto-Discovered Main Devices
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = InfoBlue.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.WifiFind,
                                    contentDescription = null,
                                    tint = InfoBlue,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "الأجهزة الرئيسية المكتشفة تلقائياً على الشبكة",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = InfoBlue
                                )
                            }

                            if (discoveredMainDevices.isEmpty()) {
                                Text(
                                    text = "جاري البحث على نفس شبكة Wi-Fi / نقطة الاتصال... يمكنك أيضاً إدخال عنوان IP والرمز يدوياً بالأسفل.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            } else {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    discoveredMainDevices.forEach { found ->
                                        AssistChip(
                                            onClick = {
                                                hostInput = found.hostAddress
                                                codeInput = found.workspaceCode
                                            },
                                            label = {
                                                Text("${found.workspaceCode} (${found.hostAddress})")
                                            },
                                            leadingIcon = {
                                                Icon(
                                                    Icons.Default.Link,
                                                    contentDescription = null,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                            },
                                            modifier = Modifier.testTag("discovered_main_chip_${found.workspaceCode}")
                                        )
                                    }
                                }
                            }
                        }
                    }

                    OutlinedTextField(
                        value = hostInput,
                        onValueChange = { hostInput = it },
                        label = { Text("عنوان الجهاز الرئيسي (مثال: 192.168.43.1:8765)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sub_host_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.uppercase() },
                        label = { Text("رمز قاعدة البيانات (مثال: INV-XXXXXX)") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sub_code_input"),
                        singleLine = true
                    )

                    OutlinedTextField(
                        value = passwordInput,
                        onValueChange = { passwordInput = it },
                        label = { Text("كلمة المرور المنفردة الممنوحة من الجهاز الرئيسي") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("sub_password_input"),
                        singleLine = true
                    )

                    if (remainingLockSeconds > 0L) {
                        Surface(
                            color = DangerRed.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "محاولات التحقق مقفلة مؤقتاً لمدة $remainingLockSeconds ثانية.",
                                color = DangerRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    } else if (!pairingError.isNullOrBlank()) {
                        Surface(
                            color = DangerRed.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = pairingError!!,
                                color = DangerRed,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = {
                                viewModel.pairWithMainDevice(
                                    hostAddress = hostInput,
                                    workspaceCodeInput = codeInput,
                                    passwordInput = passwordInput
                                )
                            },
                            enabled = !isPairingInProgress && remainingLockSeconds == 0L,
                            modifier = Modifier
                                .weight(1.4f)
                                .testTag("pair_device_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isPairingInProgress) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("جاري الربط...")
                            } else {
                                Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("ربط ومزامنة الآن")
                            }
                        }

                        OutlinedButton(
                            onClick = { viewModel.switchToMainDeviceMode() },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("cancel_sub_mode_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("إلغاء / رئيسي", fontSize = 12.sp)
                        }
                    }
                } else {
                    // CONNECTED SUB-DEVICE STATUS
                    val timeFmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
                    val lastSyncLabel = if (lastSyncAt > 0L) timeFmt.format(Date(lastSyncAt)) else "الآن"

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = SuccessGreen.copy(alpha = 0.09f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    contentDescription = null,
                                    tint = SuccessGreen,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "مرتبط كجهاز فرعي (${deviceName.ifBlank { "جهاز فرعي" }})",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = SuccessGreen
                                )
                            }
                            Text(
                                text = "الجهاز الرئيسي: ${mainHostUrl.removePrefix("http://")} • رمز القاعدة: $workspaceCode",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = "آخر مزامنة: $lastSyncLabel",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Bold
                            )
                            if (!lastSyncError.isNullOrBlank()) {
                                Text(
                                    text = lastSyncError!!,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = WarningOrange
                                )
                            }
                        }
                    }

                    // Granted permissions summary
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "صلاحياتك المفعلة حالياً من الجهاز الرئيسي:",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PermissionBadge("الاطلاع والتقارير", true)
                                PermissionBadge("إضافة أصناف ومخازن", devicePermissions.canAddItems)
                                PermissionBadge("تسجيل الوارد", devicePermissions.canInward)
                                PermissionBadge("صرف وتحويل", devicePermissions.canDisburse)
                                PermissionBadge("تعديل وحذف", devicePermissions.canEditDelete)
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Button(
                            onClick = { viewModel.syncNow() },
                            enabled = !isSyncingNow,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("sync_now_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            if (isSyncingNow) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = Color.White
                                )
                            } else {
                                Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                            }
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("مزامنة الآن")
                        }

                        OutlinedButton(
                            onClick = { showUnlinkConfirmDialog = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            modifier = Modifier
                                .weight(1f)
                                .testTag("unlink_device_btn"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("فك الارتباط")
                        }
                    }
                }
            }
        }
    }

    // Dialog: Create new Sub-Device Slot
    if (showCreateSubDialog) {
        CreateSubDeviceDialog(
            onDismiss = { showCreateSubDialog = false },
            onCreate = { name, canAdd, canInward, canDisburse, canEdit ->
                viewModel.createSubDeviceSlot(name, canAdd, canInward, canDisburse, canEdit)
                showCreateSubDialog = false
            }
        )
    }

    // Dialog: Show Generated Per-Device Credentials
    createdCredentials?.let { creds ->
        CreatedCredentialsDialog(
            credentials = creds,
            onCopyAll = {
                val text = buildString {
                    appendLine("بيانات ربط الجهاز الفرعي (${creds.deviceName}):")
                    appendLine("عنوان الجهاز الرئيسي: ${creds.hostAddress}")
                    appendLine("رمز القاعدة: ${creds.workspaceCode}")
                    appendLine("كلمة المرور المنفردة: ${creds.plainPassword}")
                }
                copyToClipboard(context, text)
                Toast.makeText(context, "تم نسخ كامل بيانات الربط", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { viewModel.dismissCreatedDeviceCredentials() }
        )
    }

    // Confirm Kick Sub-Device
    deviceToKick?.let { target ->
        SimpleConfirmDialog(
            title = "طرد الجهاز الفرعي وإيقافه",
            message = "هل تريد طرد الجهاز \"${target.deviceName}\" وإلغاء ارتباطه نهائياً؟\nسيتم إبطال صلاحيته ومسح البيانات المتزامنة من جهازه فوراً عند الاتصال.",
            confirmButtonText = "نعم، اطرد المستخدم",
            isDestructive = true,
            onConfirm = {
                viewModel.kickSubDevice(target.id)
                deviceToKick = null
            },
            onDismiss = { deviceToKick = null }
        )
    }

    // Confirm Remove Slot
    deviceToRemove?.let { target ->
        SimpleConfirmDialog(
            title = "حذف سجل الجهاز",
            message = "هل تريد حذف سجل \"${target.deviceName}\" من القائمة؟",
            confirmButtonText = "نعم، احذف السجل",
            isDestructive = true,
            onConfirm = {
                viewModel.removeSubDeviceSlot(target.id)
                deviceToRemove = null
            },
            onDismiss = { deviceToRemove = null }
        )
    }

    // Confirm Unlink Sub-Device
    if (showUnlinkConfirmDialog) {
        SimpleConfirmDialog(
            title = "فك الارتباط بالجهاز الرئيسي",
            message = "هل تريد فك ارتباط هذا الجهاز الفرعي والعودة إلى الوضع الرئيسي المستقل؟",
            confirmButtonText = "نعم، فك الارتباط",
            isDestructive = true,
            onConfirm = {
                viewModel.unlinkSubDevice()
                showUnlinkConfirmDialog = false
            },
            onDismiss = { showUnlinkConfirmDialog = false }
        )
    }

    // Confirm Switch Main -> Sub when devices are registered
    if (showSwitchToSubConfirm) {
        SimpleConfirmDialog(
            title = "التحويل إلى وضع الجهاز الفرعي",
            message = "هذا الجهاز يعمل حالياً كجهاز رئيسي ولديه أجهزة فرعية مسجلة. التحويل إلى جهاز فرعي سيوقف خادم الربط على هذا الهاتف أثناء عمله كفرعي.",
            confirmButtonText = "متابعة كجهاز فرعي",
            onConfirm = {
                viewModel.switchToSubDeviceDraftMode()
                showSwitchToSubConfirm = false
            },
            onDismiss = { showSwitchToSubConfirm = false }
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
    onUpdatePermissions: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onKick: () -> Unit,
    onRemove: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(
            1.dp,
            if (slot.isKicked) DangerRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant
        ),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("sub_device_card_${slot.id}")
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = slot.deviceName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val statusText = when {
                        slot.isKicked -> "🚫 مطرود وموقوف"
                        slot.isClaimed -> "🟢 مرتبط بجهاز فعلي"
                        else -> "🟡 بانتظار ربط الجهاز الفرعي"
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = when {
                            slot.isKicked -> DangerRed
                            slot.isClaimed -> SuccessGreen
                            else -> WarningOrange
                        }
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!slot.isKicked) {
                        OutlinedButton(
                            onClick = onKick,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            modifier = Modifier.testTag("kick_device_btn_${slot.id}"),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("طرد", fontSize = 11.sp)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                    IconButton(
                        onClick = onRemove,
                        modifier = Modifier.testTag("remove_device_btn_${slot.id}")
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = "حذف سجل الجهاز", tint = DangerRed)
                    }
                }
            }

            if (!slot.isKicked) {
                PermissionSwitchRow("إضافة أصناف ومخازن", slot.canAddItems) {
                    onUpdatePermissions(it, slot.canInward, slot.canDisburse, slot.canEditDelete)
                }
                PermissionSwitchRow("تسجيل سندات الوارد", slot.canInward) {
                    onUpdatePermissions(slot.canAddItems, it, slot.canDisburse, slot.canEditDelete)
                }
                PermissionSwitchRow("صرف وتحويل المخزون", slot.canDisburse) {
                    onUpdatePermissions(slot.canAddItems, slot.canInward, it, slot.canEditDelete)
                }
                PermissionSwitchRow("التعديل والحذف", slot.canEditDelete) {
                    onUpdatePermissions(slot.canAddItems, slot.canInward, slot.canDisburse, it)
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
    onDismiss: () -> Unit,
    onCreate: (String, Boolean, Boolean, Boolean, Boolean) -> Unit
) {
    var name by remember { mutableStateOf("") }
    var canAdd by remember { mutableStateOf(false) }
    var canInward by remember { mutableStateOf(false) }
    var canDisburse by remember { mutableStateOf(true) }
    var canEdit by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "إضافة جهاز فرعي جديد",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("اسم الجهاز / الموظف (مثال: أمين مخزن عدن)") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("new_sub_device_name_input"),
                    singleLine = true
                )
                Text(
                    text = "حدد الصلاحيات المسموحة لهذا الجهاز:",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
                PermissionSwitchRow("إضافة أصناف ومخازن", canAdd) { canAdd = it }
                PermissionSwitchRow("تسجيل الوارد", canInward) { canInward = it }
                PermissionSwitchRow("صرف وتحويل المخزون", canDisburse) { canDisburse = it }
                PermissionSwitchRow("التعديل والحذف", canEdit) { canEdit = it }
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(name, canAdd, canInward, canDisburse, canEdit) },
                modifier = Modifier.testTag("confirm_create_sub_device_btn")
            ) {
                Text("إنشاء كلمة المرور")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
private fun CreatedCredentialsDialog(
    credentials: CreatedDeviceCredentials,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "تم إنشاء بيانات الربط للجهاز (${credentials.deviceName})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = "أعطِ هذه البيانات للجهاز الفرعي (صالحة لجهاز واحد فقط):",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CredentialLine("عنوان الجهاز الرئيسي", credentials.hostAddress)
                CredentialLine("رمز قاعدة البيانات", credentials.workspaceCode)
                CredentialLine("كلمة المرور المنفردة", credentials.plainPassword)
                Text(
                    text = "ملاحظة أمنية: يتم تشفير كلمة المرور بالبصمة الملحية (SHA-256) ولن تظهر مرة أخرى بعد إغلاق النافذة.",
                    style = MaterialTheme.typography.labelSmall,
                    color = WarningOrange
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onCopyAll,
                modifier = Modifier.testTag("copy_created_credentials_btn")
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("نسخ كامل البيانات")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}

@Composable
private fun CredentialLine(label: String, value: String) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Text(text = label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(text = value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

private fun copyToClipboard(context: Context, text: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    clipboard?.setPrimaryClip(ClipData.newPlainText("SmartInventoryPairing", text))
}
