@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.cloud.CloudConversation
import com.example.data.cloud.CloudMessage
import com.example.data.cloud.CloudRegisteredUser
import com.example.data.cloud.CloudWarehouse
import com.example.data.cloud.CloudWarehouseMembership
import com.example.data.cloud.CloudWorkspaceMember
import com.example.ui.viewmodel.CloudCollaborationViewModel
import com.example.ui.theme.SuccessGreen
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun CloudCollaborationScreen(
    viewModel: CloudCollaborationViewModel,
    onOpenSettings: () -> Unit,
    onBeforeOpen: suspend () -> Unit = {}
) {
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    val canCreateFirstWorkspace by viewModel.canCreateFirstWorkspace.collectAsState()
    val members by viewModel.members.collectAsState()
    val devices by viewModel.devices.collectAsState()
    val registeredUsers by viewModel.registeredUsers.collectAsState()
    val warehouses by viewModel.warehouses.collectAsState()
    val warehouseMemberships by viewModel.warehouseMemberships.collectAsState()
    val busy by viewModel.isBusy.collectAsState()
    val error by viewModel.error.collectAsState()
    val notice by viewModel.notice.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var workspaceName by remember { mutableStateOf("") }
    var selectedMemberId by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { onBeforeOpen() }
        viewModel.refresh()
    }
    LaunchedEffect(members, currentUserId) {
        if (selectedMemberId.isBlank() || members.none { it.userId == selectedMemberId }) {
            selectedMemberId = members.firstOrNull { it.userId != currentUserId && it.role != "OWNER" }?.userId.orEmpty()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("التعاون السحابي", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    "ربط المستخدمين والأجهزة والمخازن عبر الإنترنت",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = { viewModel.refresh() }, enabled = !busy) { Text("تحديث") }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isAuthenticated) MaterialTheme.colorScheme.secondaryContainer
                else MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    if (isAuthenticated) "حساب التعاون مرتبط" else "يلزم ربط حساب Google",
                    fontWeight = FontWeight.Bold
                )
                Text(
                    userEmail.ifBlank { "لم يتم تسجيل الدخول إلى Firebase Authentication" },
                    style = MaterialTheme.typography.bodySmall
                )
                if (!isAuthenticated) {
                    Text(
                        "افتح الإعدادات ثم اربط حساب التعاون عبر Google، وبعدها ارجع إلى هذه الشاشة.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Button(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
                        Text("فتح إعدادات ربط Google")
                    }
                }
            }
        }

        if (error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(error.orEmpty(), modifier = Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (notice != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(notice.orEmpty(), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { viewModel.dismissNotice() }) { Text("إغلاق") }
                }
            }
        }

        if (busy) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.5.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("جارٍ تنفيذ العملية والاتصال بالخادم…", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        if (isAuthenticated) {
            TabRow(selectedTabIndex = selectedTab) {
                listOf("مساحة العمل", "الصلاحيات").forEachIndexed { index, title ->
                    Tab(
                        selected = selectedTab == index,
                        onClick = { selectedTab = index },
                        text = { Text(title, maxLines = 1) }
                    )
                }
            }

            when (selectedTab) {
                0 -> {
                    if (activeWorkspace == null) {
                        if (canCreateFirstWorkspace) {
                            Text(
                                "إعداد مساحة العمل الرئيسية",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            OutlinedTextField(
                                value = workspaceName,
                                onValueChange = { workspaceName = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("اسم المؤسسة") },
                                singleLine = true
                            )
                            Button(
                                onClick = {
                                    viewModel.createWorkspace(workspaceName)
                                    workspaceName = ""
                                },
                                enabled = !busy && workspaceName.trim().length >= 2,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("إنشاء مساحة العمل الرئيسية") }
                            Text(
                                "يتم إنشاء مساحة واحدة فقط للتطبيق. بعد ذلك يضيف المسؤول بقية المستخدمين إليها.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    Modifier.padding(14.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        "لم تتم إضافتك إلى مساحة العمل الرئيسية بعد",
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "اطلب من المسؤول إضافة بريد Google الذي تستخدمه في التطبيق. لا تنشئ مساحة جديدة.",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    OutlinedButton(
                                        onClick = { viewModel.refresh() },
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("التحقق من الدعوة") }
                                }
                            }
                        }
                    }

                    activeWorkspace?.let { workspace ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                Modifier.padding(14.dp),
                                verticalArrangement = Arrangement.spacedBy(9.dp)
                            ) {
                                Text(
                                    workspace.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    "مساحة العمل الموحدة لجميع المستخدمين والأجهزة",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                Text(
                                    "الأعضاء: ${members.size} • المخازن المتاحة: ${warehouses.size}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                if (workspace.ownerUserId == currentUserId) {
                                    HorizontalDivider()
                                    Text(
                                        "الحسابات التي سجلت الدخول ويمكن إضافتها",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        "يجب أن يسجل المستخدم الدخول إلى التطبيق بحساب Google مرة واحدة أولاً. بعد إضافته ستظهر له مساحة العمل نفسها تلقائياً.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "يمكن إضافة المستخدمين عن بُعد؛ لا يلزم اتصالهم بشبكة Wi-Fi نفسها.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                    if (registeredUsers.isNotEmpty()) {
                                        HorizontalDivider()
                                        Text("حسابات سجلت الدخول ويمكن إضافتها", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        registeredUsers.forEach { account ->
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .padding(vertical = 5.dp)
                                                    .clickable(enabled = !busy) {
                                                        viewModel.addMember(account.email, account.displayName, "SUBUSER")
                                                    }
                                                    .padding(horizontal = 10.dp, vertical = 9.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(account.displayName.ifBlank { account.email }, fontWeight = FontWeight.SemiBold)
                                                    Text(account.email, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                Text(
                                                    "اضغط لإضافة الحساب",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                    } else {
                                        Text(
                                            "ستظهر هنا تلقائياً الحسابات التي تسجل الدخول إلى التطبيق بحساب Google ولم تُضف للمساحة بعد.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    HorizontalDivider()
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("تسجيل هذا الجهاز", fontWeight = FontWeight.SemiBold)
                                        TextButton(
                                            onClick = { viewModel.registerCurrentDevice() },
                                            enabled = !busy
                                        ) { Text("تسجيل / تحديث") }
                                    }
                                    HorizontalDivider()
                                    Text("حالة اتصال الأجهزة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                    devices.forEach { device ->
                                        val lastSeenMillis = device.lastSeenAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() } ?: 0L
                                        val isOnline = lastSeenMillis > 0L && System.currentTimeMillis() - lastSeenMillis <= 45_000L
                                        Row(
                                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.SpaceBetween
                                        ) {
                                            Column(Modifier.weight(1f)) {
                                                Text(device.deviceName, fontWeight = FontWeight.SemiBold)
                                                Text(if (isOnline) "آخر نبضة: الآن" else "آخر اتصال: " + (device.lastSeenAt ?: "غير معروف"), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Text(
                                                if (isOnline) "متصل" else "غير متصل",
                                                color = if (isOnline) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                } else {
                                    Text(
                                        "أنت عضو في مساحة العمل المشتركة. يتولى المسؤول إضافة المستخدمين وإدارة الصلاحيات.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    if (activeWorkspace != null) {
                        Text(
                            "قاعدة المخزون محلية على كل جهاز ولا تُرفع إلى Firebase. يُستخدم Firebase لحسابات المستخدمين والصلاحيات وحالة اتصال الأجهزة، وتُحفظ النسخة الاحتياطية الكاملة عبر Google Drive.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (members.isNotEmpty()) {
                        Text(
                            "أعضاء مساحة العمل",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                        members.forEach { member ->
                            MemberRow(
                                member = member,
                                isSelected = selectedMemberId == member.userId,
                                onClick = { selectedMemberId = member.userId }
                            )
                        }
                    }
                }

                1 -> {
                    Text(
                        "الصلاحيات المعتمدة من شاشة الأجهزة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "تعرض هذه الشاشة الصلاحيات كما حُددت في إدارة الأجهزة. لا يمكن تعديلها أو مزامنتها من هنا.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    val selectedMemberForGlobal = members.firstOrNull { it.userId == selectedMemberId }
                    if (selectedMemberForGlobal != null) {
                        val memberAccess = warehouses.mapNotNull { warehouse ->
                            warehouseMemberships[warehouse.id].orEmpty().firstOrNull { it.userId == selectedMemberForGlobal.userId }
                        }
                        val generalPermissions = listOf(
                            "canAddItems" to "إضافة أصناف إلى الدليل",
                            "canEditCatalogItems" to "تعديل بيانات الأصناف في الدليل",
                            "canEdit" to "تعديل البيانات العامة",
                            "canDeleteItem" to "حذف صنف من الدليل",
                            "canEditIssueVoucher" to "تعديل أوامر الصرف",
                            "canDeleteIssueVoucher" to "حذف أوامر الصرف",
                            "canEditSupplyVoucher" to "تعديل أوامر التوريد",
                            "canDeleteSupplyVoucher" to "حذف أوامر التوريد",
                            "canBackup" to "النسخ الاحتياطي والتصدير"
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("الصلاحيات العامة التسع", fontWeight = FontWeight.Bold)
                                generalPermissions.forEach { (key, label) ->
                                    val granted = memberAccess.any { it.permission(key) }
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                        Text(label, style = MaterialTheme.typography.bodySmall)
                                        Text(if (granted) "ممنوحة" else "غير ممنوحة",
                                            color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                            style = MaterialTheme.typography.labelSmall)
                                    }
                                }
                            }
                        }
                    }

                    if (members.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            members.filter { it.role != "OWNER" }.forEach { member ->
                                FilterChip(
                                    selected = selectedMemberId == member.userId,
                                    onClick = { selectedMemberId = member.userId },
                                    label = {
                                        Text(member.displayName.ifBlank { member.email.ifBlank { member.role } })
                                    }
                                )
                            }
                        }
                    }

                    val member = members.firstOrNull { it.userId == selectedMemberId }
                    if (member == null) {
                        Text("لا يوجد مستخدم فرعي محدد.")
                    } else if (warehouses.isEmpty()) {
                        Text("لا توجد مخازن مرتبطة بمساحة العمل بعد.")
                    } else {
                        warehouses.forEach { warehouse ->
                            val access = warehouseMemberships[warehouse.id].orEmpty()
                                .firstOrNull { it.userId == member.userId }
                            val warehousePermissions = listOf(
                                "canView" to "عرض المخزن",
                                "canInward" to "التوريد",
                                "canDisburse" to "الصرف",
                                "canTransfer" to "التحويل المخزني",
                                "canAdjust" to "تعديل الأرصدة والتسويات",
                                "canReports" to "تقارير المخزن",
                                "canEdit" to "التعديلات العامة داخل المخزن",
                                "canEditInventory" to "تعديل بيانات المخزون والأصناف",
                                "canDeleteItem" to "حذف الأصناف المرتبطة بالمخزن"
                            )
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(warehouse.name, fontWeight = FontWeight.Bold)
                                    if (access == null) {
                                        Text(
                                            "لم تتم مزامنة صلاحيات هذا الجهاز بعد.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                    warehousePermissions.forEach { (key, label) ->
                                        val granted = access.permission(key)
                                        Row(
                                            Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(label, style = MaterialTheme.typography.bodySmall)
                                            Text(
                                                if (granted) "ممنوحة" else "غير ممنوحة",
                                                color = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                                style = MaterialTheme.typography.labelSmall
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        "لتعديل الصلاحيات استخدم شاشة الأجهزة المرتبطة؛ فهي المصدر الوحيد للصلاحيات.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        } else {
            Text("بعد ربط حساب Google، ستظهر هنا مساحة العمل والأعضاء والصلاحيات.")
        }
    }
}

private fun CloudWarehouseMembership?.permission(key: String): Boolean =
    runCatching { this?.permissions?.get(key)?.jsonPrimitive?.booleanOrNull ?: false }.getOrDefault(false)

@Composable
private fun MemberRow(member: CloudWorkspaceMember, isSelected: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
            else MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(member.displayName.ifBlank { member.email.ifBlank { "عضو" } }, fontWeight = FontWeight.SemiBold)
                Text("${member.email} • ${member.role}", style = MaterialTheme.typography.labelSmall)
            }
            TextButton(onClick = onClick) { Text(if (isSelected) "محدد" else "تحديد") }
        }
    }
}
