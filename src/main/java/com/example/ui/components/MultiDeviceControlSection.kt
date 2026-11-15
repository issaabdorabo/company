package com.example.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.AddModerator
import androidx.compose.material.icons.filled.AdminPanelSettings
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VerifiedUser
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.cloud.DevicePermissions
import com.example.data.cloud.LinkedSubDevice
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MultiDeviceControlSection(
    viewModel: InventoryViewModel,
    isAuthenticated: Boolean = true,
    onRequestGoogleSignIn: () -> Unit = {}
) {
    val context = LocalContext.current
    @Suppress("DEPRECATION")
    val clipboard = LocalClipboardManager.current

    val isMainDevice by viewModel.isMainDevice.collectAsState()
    val workspaceId by viewModel.workspaceId.collectAsState()
    val workspaceCode by viewModel.workspaceCode.collectAsState()
    val devicePermissions by viewModel.devicePermissions.collectAsState()
    val linkedDevices by viewModel.linkedDevices.collectAsState()
    val createdCredentials by viewModel.lastCreatedDeviceCredentials.collectAsState()
    val pairingError by viewModel.pairingError.collectAsState()
    val pairingLockedUntil by viewModel.pairingLockedUntil.collectAsState()
    val isSyncingCloud by viewModel.isSyncingCloud.collectAsState()

    var showCreateSubDeviceDialog by remember { mutableStateOf(false) }
    var deviceToKick by remember { mutableStateOf<LinkedSubDevice?>(null) }
    var deviceToDeleteSlot by remember { mutableStateOf<LinkedSubDevice?>(null) }
    var showConfirmSwitchToMain by remember { mutableStateOf(false) }
    var showConfirmCloudRestore by remember { mutableStateOf(false) }

    // Pairing form state for SUB mode
    var inputWsCode by remember(workspaceCode) { mutableStateOf(workspaceCode) }
    var inputDevicePwd by remember { mutableStateOf("") }
    var showPasswordPlain by remember { mutableStateOf(false) }
    var showPairingForm by remember {
        mutableStateOf(!isMainDevice && workspaceId.isBlank())
    }

    LaunchedEffect(isMainDevice, workspaceId) {
        if (isMainDevice || workspaceId.isNotBlank()) {
            showPairingForm = false
        } else {
            showPairingForm = true
        }
    }

    // Live countdown for lockout
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
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Devices,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "نظام الربط الموحد والأجهزة الفرعية",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "تحكم كامل بتشغيل الجهاز كرئيسي أو ربطه كفرعي بكلمة مرور منفردة",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mode Selector: Main vs Sub
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModeOptionTile(
                    title = "جهاز رئيسي",
                    subtitle = "تحكم وصلاحيات كاملة",
                    selected = isMainDevice,
                    icon = Icons.Default.AdminPanelSettings,
                    onClick = {
                        if (!isMainDevice) {
                            if (workspaceId.isNotBlank()) {
                                showConfirmSwitchToMain = true
                            } else {
                                viewModel.clearPairingError()
                                showPairingForm = false
                                viewModel.switchToMainDeviceMode()
                            }
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("mode_main_btn")
                )
                ModeOptionTile(
                    title = "جهاز فرعي",
                    subtitle = "ربط بقاعدة موحدة",
                    selected = !isMainDevice,
                    icon = Icons.Default.Link,
                    onClick = {
                        if (isMainDevice) {
                            viewModel.setDeviceRole("SUB")
                            showPairingForm = true
                        }
                    },
                    modifier = Modifier.weight(1f).testTag("mode_sub_btn")
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (isMainDevice) {
                // MAIN DEVICE CONTROLS
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "رمز القاعدة السحابية الموحدة الخاص بك:",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = when {
                                    workspaceCode.isNotBlank() -> workspaceCode
                                    !isAuthenticated -> "يتطلب التحقق بحساب Google أعلاه"
                                    else -> "جاري التوليد التلقائي..."
                                },
                                style = if (workspaceCode.isNotBlank()) MaterialTheme.typography.titleLarge else MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = if (workspaceCode.isNotBlank()) FontFamily.Monospace else FontFamily.Default,
                                color = if (workspaceCode.isNotBlank()) MaterialTheme.colorScheme.primary else WarningOrange
                            )
                        }
                        if (workspaceCode.isNotBlank()) {
                            IconButton(
                                onClick = {
                                    clipboard.setText(AnnotatedString(workspaceCode))
                                    Toast.makeText(context, "تم نسخ رمز القاعدة الموحدة", Toast.LENGTH_SHORT).show()
                                }
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "نسخ رمز القاعدة",
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = { showCreateSubDeviceDialog = true },
                        modifier = Modifier.weight(1f).testTag("add_sub_device_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.AddModerator, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("إضافة جهاز فرعي", fontSize = 12.sp)
                    }

                    OutlinedButton(
                        onClick = { showConfirmCloudRestore = true },
                        enabled = !isSyncingCloud,
                        modifier = Modifier.weight(1f).testTag("restore_cloud_now_btn"),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Icon(Icons.Default.CloudDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("استرداد من السحابة", fontSize = 12.sp)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(10.dp))

                Text(
                    text = "الأجهزة الفرعية المرتبطة (${linkedDevices.size})",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (linkedDevices.isEmpty()) {
                    Text(
                        text = "لم تقم بإنشاء أجهزة فرعية بعد. اضغط «إضافة جهاز فرعي» لتوليد كلمة مرور منفردة وآمنة لكل جهاز.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        linkedDevices.forEach { dev ->
                            LinkedDeviceManagementCard(
                                device = dev,
                                onTogglePermission = { addItems, inward, disburse, editDel ->
                                    viewModel.updateSubDevicePermissions(
                                        device = dev,
                                        canAddItems = addItems,
                                        canInward = inward,
                                        canDisburse = disburse,
                                        canEditDelete = editDel
                                    )
                                },
                                onKickClick = { deviceToKick = dev },
                                onDeleteSlotClick = { deviceToDeleteSlot = dev }
                            )
                        }
                    }
                }
            } else {
                // SUB DEVICE MODE
                if (!showPairingForm && workspaceId.isNotBlank()) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = SuccessGreen.copy(alpha = 0.1f)
                        ),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.VerifiedUser, contentDescription = null, tint = SuccessGreen)
                                Spacer(modifier = Modifier.width(8.dp))
                                Column {
                                    Text(
                                        text = "متصل بالقاعدة الموحدة (${workspaceCode})",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessGreen
                                    )
                                    Text(
                                        text = "يمكنك الاطلاع على جميع الشاشات والتقارير بشكل فوري.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }

                            Text(
                                text = "صلاحياتك الحالية الممنوحة من الجهاز الرئيسي:",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold
                            )
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                PermissionStatusBadge("الاطلاع والقراءة", true)
                                PermissionStatusBadge("إضافة أصناف/مخازن", devicePermissions.canAddItems)
                                PermissionStatusBadge("تسجيل الوارد", devicePermissions.canInward)
                                PermissionStatusBadge("الصرف والتحويل", devicePermissions.canDisburse)
                                PermissionStatusBadge("التعديل والحذف", devicePermissions.canEditDelete)
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = { viewModel.performManualSync() },
                                    enabled = !isSyncingCloud,
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    if (isSyncingCloud) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                                    } else {
                                        Icon(Icons.Default.Sync, contentDescription = null, modifier = Modifier.size(16.dp))
                                    }
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("تحديث البيانات", fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = { showConfirmSwitchToMain = true },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.LinkOff, contentDescription = null, modifier = Modifier.size(16.dp), tint = DangerRed)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("فك الارتباط", fontSize = 12.sp, color = DangerRed)
                                }
                            }
                        }
                    }
                } else {
                    // Sub-Device Pairing Form
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (!isAuthenticated) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = WarningOrange.copy(alpha = 0.14f)),
                                shape = RoundedCornerShape(10.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "للربط السحابي كجهاز فرعي، يرجى التحقق من حساب Google أولاً:",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = WarningOrange,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Button(
                                        onClick = onRequestGoogleSignIn,
                                        shape = RoundedCornerShape(8.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("التحقق وتسجيل الدخول بحساب Google الآن", fontSize = 12.sp)
                                    }
                                }
                            }
                        }

                        Text(
                            text = "للربط كجهاز فرعي، أدخل رمز القاعدة الموحدة وكلمة المرور المنفردة الصادرة من الجهاز الرئيسي:",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        OutlinedTextField(
                            value = inputWsCode,
                            onValueChange = {
                                inputWsCode = it.uppercase()
                                viewModel.clearPairingError()
                            },
                            label = { Text("رمز القاعدة السحابية (مثال: INV-8421) *") },
                            leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                            modifier = Modifier.fillMaxWidth().testTag("pair_workspace_code_input"),
                            singleLine = true
                        )

                        OutlinedTextField(
                            value = inputDevicePwd,
                            onValueChange = {
                                inputDevicePwd = it
                                viewModel.clearPairingError()
                            },
                            label = { Text("كلمة المرور المنفردة لهذا الجهاز *") },
                            leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                            trailingIcon = {
                                IconButton(onClick = { showPasswordPlain = !showPasswordPlain }) {
                                    Icon(
                                        imageVector = if (showPasswordPlain) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = "إظهار/إخفاء كلمة المرور"
                                    )
                                }
                            },
                            visualTransformation = if (showPasswordPlain) VisualTransformation.None else PasswordVisualTransformation(),
                            modifier = Modifier.fillMaxWidth().testTag("pair_device_password_input"),
                            singleLine = true
                        )

                        if (remainingLockSeconds > 0L) {
                            Text(
                                text = "حماية أمنية: يرجى الانتظار $remainingLockSeconds ثانية قبل إعادة المحاولة.",
                                style = MaterialTheme.typography.labelSmall,
                                color = DangerRed,
                                fontWeight = FontWeight.Bold
                            )
                        } else if (!pairingError.isNullOrBlank()) {
                            Text(
                                text = pairingError!!,
                                style = MaterialTheme.typography.labelSmall,
                                color = DangerRed,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Button(
                                onClick = {
                                    viewModel.pairAsSubDevice(inputWsCode, inputDevicePwd) {
                                        inputDevicePwd = ""
                                        showPairingForm = false
                                    }
                                },
                                enabled = !isSyncingCloud && remainingLockSeconds == 0L,
                                modifier = Modifier.weight(1.3f).testTag("pair_sub_device_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                if (isSyncingCloud) {
                                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("جاري الربط...", fontSize = 12.sp)
                                } else {
                                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("ربط الجهاز", fontSize = 12.sp)
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    viewModel.clearPairingError()
                                    inputDevicePwd = ""
                                    showPairingForm = false
                                    viewModel.switchToMainDeviceMode()
                                },
                                modifier = Modifier.weight(1f).testTag("cancel_pairing_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Text("إلغاء / رئيسي", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    // Create Sub-Device Dialog
    if (showCreateSubDeviceDialog) {
        var devName by remember { mutableStateOf("") }
        var canAdd by remember { mutableStateOf(false) }
        var canInward by remember { mutableStateOf(false) }
        var canDisburse by remember { mutableStateOf(false) }
        var canEditDel by remember { mutableStateOf(false) }
        var nameError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { showCreateSubDeviceDialog = false },
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
                        value = devName,
                        onValueChange = {
                            devName = it
                            nameError = ""
                        },
                        label = { Text("اسم الجهاز / المستخدم (مثال: أمين مخزن الفرع)") },
                        modifier = Modifier.fillMaxWidth().testTag("new_sub_device_name_input"),
                        singleLine = true
                    )
                    if (nameError.isNotBlank()) {
                        Text(nameError, color = DangerRed, style = MaterialTheme.typography.labelSmall)
                    }

                    Text(
                        text = "الصلاحيات الممنوحة (يمكن تعديلها أو إلغاؤها لاحقاً في أي وقت):",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                    PermissionToggleRow("إضافة وإدارة المخازن والأصناف", canAdd) { canAdd = it }
                    PermissionToggleRow("تسجيل فواتير الوارد والتوريد", canInward) { canInward = it }
                    PermissionToggleRow("صرف وتحويل الأصناف", canDisburse) { canDisburse = it }
                    PermissionToggleRow("تعديل وحذف السجلات", canEditDel) { canEditDel = it }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (devName.isBlank()) {
                            nameError = "يرجى كتابة اسم تعريفي للجهاز"
                            return@Button
                        }
                        viewModel.createSubDeviceSlot(
                            deviceName = devName.trim(),
                            canAddItems = canAdd,
                            canInward = canInward,
                            canDisburse = canDisburse,
                            canEditDelete = canEditDel
                        )
                        showCreateSubDeviceDialog = false
                    },
                    modifier = Modifier.testTag("confirm_create_sub_device_btn")
                ) {
                    Text("توليد كلمة مرور منفردة")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCreateSubDeviceDialog = false }) { Text("إلغاء") }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // One-time Generated Credentials Handoff Dialog
    if (createdCredentials != null) {
        val creds = createdCredentials!!
        AlertDialog(
            onDismissRequest = { viewModel.clearCreatedDeviceCredentials() },
            icon = {
                Icon(Icons.Default.Key, contentDescription = null, tint = SuccessGreen, modifier = Modifier.size(36.dp))
            },
            title = {
                Text(
                    text = "بيانات الربط الآمن للجهاز: ${creds.deviceName}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("رمز القاعدة الموحدة:", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = creds.workspaceCode,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.primary
                            )
                            HorizontalDivider()
                            Text("كلمة المرور المنفردة لهذا الجهاز فقط:", style = MaterialTheme.typography.labelSmall)
                            Text(
                                text = creds.plainPassword,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                fontFamily = FontFamily.Monospace,
                                color = SuccessGreen
                            )
                        }
                    }

                    Text(
                        text = "تنبيه أمني: تُحفظ كلمة المرور مشفرة بتجزئة (SHA-256) وترتبط تلقائياً بأول جهاز يستخدمها ولا يمكن استخدامها في جهاز آخر.",
                        style = MaterialTheme.typography.labelSmall,
                        color = WarningOrange
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val copyText = "رمز القاعدة: ${creds.workspaceCode}\nكلمة مرور الجهاز (${creds.deviceName}): ${creds.plainPassword}"
                        clipboard.setText(AnnotatedString(copyText))
                        Toast.makeText(context, "تم نسخ رمز القاعدة وكلمة المرور المنفردة", Toast.LENGTH_SHORT).show()
                        viewModel.clearCreatedDeviceCredentials()
                    }
                ) {
                    Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("نسخ البيانات وإغلاق")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { viewModel.clearCreatedDeviceCredentials() }) { Text("إغلاق") }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    // Confirm Kick Sub-Device
    if (deviceToKick != null) {
        SimpleConfirmDialog(
            title = "طرد الجهاز وإيقاف وصوله",
            message = "هل أنت متأكد من طرد جهاز \"${deviceToKick!!.deviceName}\"؟ سيتم فصل الجلسة وإيقاف المزامنة ومسح نسخة البيانات لديه فوراً.",
            confirmButtonText = "نعم، اطرد الجهاز الآن",
            isDestructive = true,
            onConfirm = {
                viewModel.kickSubDevice(deviceToKick!!)
                deviceToKick = null
            },
            onDismiss = { deviceToKick = null }
        )
    }

    // Confirm Delete Device Slot
    if (deviceToDeleteSlot != null) {
        SimpleConfirmDialog(
            title = "حذف سجل الجهاز من القائمة",
            message = "سيتم حذف \"${deviceToDeleteSlot!!.deviceName}\" وإبطال كلمة مروره نهائياً. هل تريد المتابعة؟",
            confirmButtonText = "نعم، احذف",
            isDestructive = true,
            onConfirm = {
                viewModel.deleteSubDeviceSlot(deviceToDeleteSlot!!)
                deviceToDeleteSlot = null
            },
            onDismiss = { deviceToDeleteSlot = null }
        )
    }

    // Confirm Disconnect Sub-Device -> Switch to Main
    if (showConfirmSwitchToMain) {
        SimpleConfirmDialog(
            title = "فك الارتباط والتحويل لنمط رئيسي",
            message = "هل تريد فصل هذا الجهاز عن القاعدة المشتركة والعودة إلى نمط الجهاز الرئيسي المستقل؟",
            confirmButtonText = "نعم، فك الارتباط",
            isDestructive = true,
            onConfirm = {
                viewModel.switchToMainDeviceMode()
                showConfirmSwitchToMain = false
            },
            onDismiss = { showConfirmSwitchToMain = false }
        )
    }

    // Confirm Cloud Recovery Restore
    if (showConfirmCloudRestore) {
        SimpleConfirmDialog(
            title = "استرداد كافة البيانات من السحابة",
            message = "سيتم جلب آخر نسخة كاملة من قاعدة البيانات السحابية الموحدة واستبدال البيانات المحلية بها (مفيد بعد إعادة التثبيت).",
            confirmButtonText = "استرداد الآن",
            isDestructive = false,
            onConfirm = {
                viewModel.restoreFromCloudWorkspace()
                showConfirmCloudRestore = false
            },
            onDismiss = { showConfirmCloudRestore = false }
        )
    }
}

@Composable
private fun ModeOptionTile(
    title: String,
    subtitle: String,
    selected: Boolean,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .clickable { onClick() }
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(12.dp)
            ),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun LinkedDeviceManagementCard(
    device: LinkedSubDevice,
    onTogglePermission: (Boolean, Boolean, Boolean, Boolean) -> Unit,
    onKickClick: () -> Unit,
    onDeleteSlotClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (device.isKicked) DangerRed.copy(alpha = 0.08f)
            else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.deviceName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    val statusText = when {
                        device.isKicked -> "مطرود / تم إلغاء الوصول"
                        device.isClaimed -> "مقيد ومرتبط بجهاز فرعي نشط"
                        else -> "بانتظار تسجيل الدخول من الجهاز الفرعي"
                    }
                    val statusColor = when {
                        device.isKicked -> DangerRed
                        device.isClaimed -> SuccessGreen
                        else -> InfoBlue
                    }
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!device.isKicked) {
                        OutlinedButton(
                            onClick = onKickClick,
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.testTag("kick_device_btn_${device.id}")
                        ) {
                            Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("طرد المستخدم", fontSize = 11.sp)
                        }
                    }
                    IconButton(onClick = onDeleteSlotClick) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "حذف السجل",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (!device.isKicked) {
                HorizontalDivider()
                PermissionToggleRow("إضافة أصناف ومخازن", device.canAddItems) {
                    onTogglePermission(it, device.canInward, device.canDisburse, device.canEditDelete)
                }
                PermissionToggleRow("تسجيل الوارد والتوريد", device.canInward) {
                    onTogglePermission(device.canAddItems, it, device.canDisburse, device.canEditDelete)
                }
                PermissionToggleRow("صرف وتحويل الأصناف", device.canDisburse) {
                    onTogglePermission(device.canAddItems, device.canInward, it, device.canEditDelete)
                }
                PermissionToggleRow("تعديل وحذف السجلات", device.canEditDelete) {
                    onTogglePermission(device.canAddItems, device.canInward, device.canDisburse, it)
                }
            }
        }
    }
}

@Composable
private fun PermissionToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onCheckedChange(!checked) }
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

@Composable
private fun PermissionStatusBadge(label: String, granted: Boolean) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (granted) SuccessGreen.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.VerifiedUser else Icons.Default.Lock,
                contentDescription = null,
                tint = if (granted) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(12.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (granted) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
