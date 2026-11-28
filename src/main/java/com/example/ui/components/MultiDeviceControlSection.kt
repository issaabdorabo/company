package com.example.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.QrCodeScanner
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.cloud.CreatedDeviceCredentials
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.WarehousePermission
import com.example.data.model.Warehouse
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import com.example.util.QrPairingHelper
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
    val availableWarehouses by viewModel.warehouses.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val discoveredMainDevices by viewModel.discoveredMainDevices.collectAsState()
    val createdCredentials by viewModel.createdDeviceCredentials.collectAsState()
    val sessionPairingCredentials by viewModel.sessionPairingCredentials.collectAsState()
    val isPairingInProgress by viewModel.isPairingInProgress.collectAsState()
    val pairingError by viewModel.pairingError.collectAsState()
    val isSyncingNow by viewModel.isSyncingNow.collectAsState()
    val lastSyncAt by viewModel.lastSyncAt.collectAsState()
    val lastSyncError by viewModel.lastSyncError.collectAsState()
    val pairingLockedUntil by viewModel.pairingLockedUntil.collectAsState()

    var showCreateSubDialog by remember { mutableStateOf(false) }
    var showQrScanner by remember { mutableStateOf(false) }
    var deviceToKick by remember { mutableStateOf<LinkedSubDevice?>(null) }
    var showUnlinkConfirmDialog by remember { mutableStateOf(false) }

    // Sub-device pairing inputs
    var hostInput by remember { mutableStateOf("CLOUD") }
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
                        .clickable(enabled = !isSubConnected) {
                            if (!isMainDevice && !isSubConnected) viewModel.switchToMainDeviceMode()
                        }
                        .testTag("mode_main_device_btn")
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = if (isSubConnected) "🔒 الجهاز الرئيسي (مقفل أثناء الارتباط)" else "👑 جهاز رئيسي",
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
                        .clickable(enabled = !isMainDevice || linkedSubDevices.isEmpty()) {
                            if (isMainDevice && linkedSubDevices.isEmpty()) viewModel.switchToSubDeviceDraftMode()
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
                val primaryAddr = "Firebase Firestore"
                val hasLanIp = true

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (lastSyncError.isNullOrBlank()) {
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
                                        "المزامنة السحابية عبر الإنترنت جاهزة"
                                    } else {
                                        "الربط يعمل عبر الإنترنت ولا يحتاج شبكة أو نقطة اتصال مشتركة"
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
                                    contentDescription = "تحديث حالة السحابة",
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
                                        text = "وسيلة الربط",
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
                                        "الخدمة السحابية: Firebase Firestore\nرمز مساحة العمل: $displayCode"
                                    )
                                    Toast.makeText(context, "تم نسخ رمز مساحة العمل", Toast.LENGTH_SHORT).show()
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
                                Text("تحديث حالة السحابة", fontSize = 11.sp)
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
                            hasQrAvailable = !slot.isClaimed && !slot.isKicked && sessionPairingCredentials.containsKey(slot.id),
                            onShowQr = { viewModel.showSessionCredentialsQr(slot.id) },
                            warehouses = availableWarehouses,
                            onUpdatePermissions = { add, inward, disburse, editLegacy, backup, allWarehouses, grants, editInventory, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalog ->
                                viewModel.updateSubDevicePermissions(slot.id, add, inward, disburse, editLegacy, backup, allWarehouses, grants, editInventory, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalog)
                            },
                            onKick = { deviceToKick = slot },
                            onReactivate = { viewModel.reactivateSubDevice(slot.id) }
                        )
                    }
                }
            } else {
                // SUB-DEVICE MODE
                if (!isSubConnected) {
                    // Quick Camera QR Scan Button
                    Button(
                        onClick = { showQrScanner = true },
                        enabled = !isPairingInProgress && remainingLockSeconds == 0L,
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("scan_pairing_qr_btn"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.QrCodeScanner, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("مسح باركود الربط بالكاميرا (ربط تلقائي فوري)", fontWeight = FontWeight.Bold)
                    }

                    // Cloud pairing is independent of local Wi-Fi, IP addresses, or hotspot discovery.
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = InfoBlue.copy(alpha = 0.08f),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "الربط يتم من خلال Firebase عبر الإنترنت؛ يمكن أن يكون الجهازان في مدينتين أو على شبكتين مختلفتين.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(12.dp)
                        )
                    }

                    OutlinedTextField(
                        value = codeInput,
                        onValueChange = { codeInput = it.uppercase() },
                        label = { Text("رمز مساحة العمل (INV-XXXXXX)") },
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
                                text = "الاتصال السحابي: Firebase Firestore • مساحة العمل: $workspaceCode",
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
                                PermissionBadge("تعديل الأصناف داخل الدليل", devicePermissions.canEditCatalogItems)
                                PermissionBadge("حذف الأصناف", devicePermissions.canDeleteItem)
                                PermissionBadge("تعديل أوامر الصرف", devicePermissions.canEditIssueVoucher)
                                PermissionBadge("حذف أوامر الصرف", devicePermissions.canDeleteIssueVoucher)
                                PermissionBadge("تعديل أوامر التوريد", devicePermissions.canEditSupplyVoucher)
                                PermissionBadge("حذف أوامر التوريد", devicePermissions.canDeleteSupplyVoucher)
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
            warehouses = availableWarehouses,
            existingDeviceNames = linkedSubDevices.map { it.deviceName },
            onDismiss = { showCreateSubDialog = false },
            onCreate = { name, canAdd, canInward, canDisburse, legacyEdit, canBackup, allWarehouses, grants, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalog ->
                viewModel.createSubDeviceSlot(name, canAdd, canInward, canDisburse, legacyEdit, canBackup, allWarehouses, grants, false, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalog)
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
                    appendLine("طريقة الربط: Firebase Firestore عبر الإنترنت")
                    appendLine("رمز مساحة العمل: ${creds.workspaceCode}")
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
            message = "سيتم إبطال جلسة الجهاز فوراً. إذا كان متصلاً فسيستلم أمر المسح في دورة المزامنة التالية وتُحذف بياناته المحلية، مع الاحتفاظ بكل بياناته وسجلاته على الجهاز الرئيسي لإعادتها عند إعادة الربط. إذا كان غير متصل فسيُمسح عند أول اتصال لاحق.",
            confirmButtonText = "نعم، اطرد المستخدم",
            isDestructive = true,
            onConfirm = {
                viewModel.kickSubDevice(target.id)
                deviceToKick = null
            },
            onDismiss = { deviceToKick = null }
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

    // QR Scanner Dialog for Sub-Device
    if (showQrScanner) {
        QrScannerDialog(
            onQrScanned = { payload ->
                showQrScanner = false
                hostInput = payload.host
                codeInput = payload.code
                passwordInput = payload.pwd
                Toast.makeText(context, "تمت قراءة باركود الربط، جاري الاتصال...", Toast.LENGTH_SHORT).show()
                viewModel.pairWithMainDevice(
                    hostAddress = payload.host,
                    workspaceCodeInput = payload.code,
                    passwordInput = payload.pwd
                )
            },
            onInvalidQrDetected = {
                Toast.makeText(context, "هذا الباركود لا يخص ربط مخزني الذكي", Toast.LENGTH_SHORT).show()
            },
            onDismiss = { showQrScanner = false }
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
    hasQrAvailable: Boolean,
    onShowQr: () -> Unit,
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
    val publish: () -> Unit = {
        onUpdatePermissions(slot.canAddItems, slot.canInward, slot.canDisburse, editGeneral, canBackup, canViewAll, grants, false, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalogItems)
    }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
        border = BorderStroke(1.dp, if (slot.isKicked) DangerRed.copy(alpha = 0.4f) else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().testTag("sub_device_card_${slot.id}")
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Column(modifier = Modifier.weight(1f).clickable { expanded = !expanded }) {
                    Text(slot.deviceName + if (expanded) "  ▴" else "  ▾", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    val isOnline = slot.isClaimed && !slot.isKicked &&
                        System.currentTimeMillis() - slot.lastSeenAt < 15_000L
                    val statusText = when {
                        slot.isKicked -> "🚫 مطرود وموقوف"
                        isOnline -> "🟢 متصل الآن"
                        slot.isClaimed -> "🟠 مرتبط لكن غير متصل حالياً"
                        slot.wasUnlinked -> "⚪ تم فك الارتباط بواسطة المستخدم الفرعي"
                        else -> "🟡 بانتظار ربط الجهاز الفرعي"
                    }
                    Text(statusText, style = MaterialTheme.typography.labelSmall, color = when {
                        slot.isKicked -> DangerRed
                        isOnline -> SuccessGreen
                        else -> WarningOrange
                    })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasQrAvailable) IconButton(onClick = onShowQr, modifier = Modifier.testTag("show_device_qr_btn_${slot.id}")) {
                        Icon(Icons.Default.QrCode2, contentDescription = "عرض باركود الربط", tint = MaterialTheme.colorScheme.primary)
                    }
                    if (!slot.isKicked) {
                        OutlinedButton(onClick = onKick, colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed), modifier = Modifier.testTag("kick_device_btn_${slot.id}"), shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("طرد", fontSize = 11.sp)
                        }
                    } else {
                        OutlinedButton(onClick = onReactivate, modifier = Modifier.testTag("reactivate_device_btn_${slot.id}"), shape = RoundedCornerShape(8.dp)) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(4.dp)); Text("إعادة الربط", fontSize = 11.sp)
                        }
                    }
                }
            }
            if (expanded && !slot.isKicked) {
                PermissionSwitchRow("إضافة أصناف ومخازن إلى الدليل", slot.canAddItems) { onUpdatePermissions(it, slot.canInward, slot.canDisburse, editGeneral, canBackup, canViewAll, grants, false, deleteItem, editIssue, deleteIssue, editSupply, deleteSupply, editCatalogItems) }
                PermissionSwitchRow("تعديل بيانات الأصناف داخل الدليل", editCatalogItems) { editCatalogItems = it; publish() }
                PermissionSwitchRow("تعديل وحذف البيانات العامة الأخرى (الموردون والجهات والوحدات)", editGeneral) { editGeneral = it; publish() }
                Text("صلاحيات المخزون والأصناف داخل المخازن", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                PermissionSwitchRow("حذف صنف من الدليل", deleteItem) { deleteItem = it; publish() }
                Text("صلاحيات أوامر الصرف", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                PermissionSwitchRow("تعديل أمر الصرف / التحويل", editIssue) { editIssue = it; publish() }
                PermissionSwitchRow("حذف أمر الصرف / التحويل", deleteIssue) { deleteIssue = it; publish() }
                Text("صلاحيات أوامر التوريد", style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                PermissionSwitchRow("تعديل أمر التوريد", editSupply) { editSupply = it; publish() }
                PermissionSwitchRow("حذف أمر التوريد", deleteSupply) { deleteSupply = it; publish() }
                PermissionSwitchRow("السماح بالنسخ الاحتياطي والتصدير المحلي والسحابي وJSON", canBackup) { canBackup = it; publish() }
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
                        val updated = current.copy(canView = enabled)
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
                PermissionSwitchRow("النسخ الاحتياطي المحلي والسحابي وتصدير JSON", canBackup) { canBackup = it }
                WarehousePermissionsEditor(warehouses, allWarehouses, grants, { allWarehouses = it }, { grants = it })
            }
        },
        confirmButton = {
            Button(onClick = { onCreate(name, canAdd, canInward, canDisburse, canEditGeneral, canBackup, allWarehouses, grants, canDeleteItem, canEditIssue, canDeleteIssue, canEditSupply, canDeleteSupply, canEditCatalogItems) }, enabled = name.trim().isNotBlank() && !existingDeviceNames.any { it.trim().equals(name.trim(), ignoreCase = true) }, modifier = Modifier.testTag("confirm_create_sub_device_btn")) {
                Text("إنشاء كلمة المرور")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("إلغاء") } }
    )
}

@Composable
private fun CreatedCredentialsDialog(
    credentials: CreatedDeviceCredentials,
    onCopyAll: () -> Unit,
    onDismiss: () -> Unit
) {
    val qrBitmap = remember(credentials) {
        val payload = QrPairingHelper.encodePairingPayload(
            hostAddress = credentials.hostAddress,
            workspaceCode = credentials.workspaceCode,
            plainPassword = credentials.plainPassword
        )
        QrPairingHelper.generateQrBitmap(payload, sizePx = 520)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.VpnKey,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                text = "باركود وبيانات ربط (${credentials.deviceName})",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White,
                    border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
                    modifier = Modifier.padding(vertical = 2.dp)
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "باركود ربط الجهاز الفرعي",
                            modifier = Modifier
                                .size(190.dp)
                                .testTag("pairing_qr_code_image")
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "امسح الباركود من أي شبكة إنترنت لإتمام الربط",
                            style = MaterialTheme.typography.labelSmall,
                            color = Color(0xFF0F172A),
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                CredentialLine("طريقة الاتصال", "Firebase Firestore عبر الإنترنت")
                CredentialLine("رمز قاعدة البيانات", credentials.workspaceCode)
                CredentialLine("كلمة المرور المنفردة", credentials.plainPassword)
                Text(
                    text = "تنتهي صلاحية دعوة الربط بعد 30 دقيقة، وتُستخدم مرة واحدة فقط.",
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
                Text("نسخ البيانات")
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
