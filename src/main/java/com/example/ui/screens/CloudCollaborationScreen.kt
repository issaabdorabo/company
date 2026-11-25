package com.example.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaRecorder
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive

@Composable
fun CloudCollaborationScreen(
    viewModel: CloudCollaborationViewModel,
    onOpenSettings: () -> Unit
) {
    val isAuthenticated by viewModel.isAuthenticated.collectAsState()
    val userEmail by viewModel.userEmail.collectAsState()
    val currentUserId by viewModel.currentUserId.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    val canCreateFirstWorkspace by viewModel.canCreateFirstWorkspace.collectAsState()
    val members by viewModel.members.collectAsState()
    val registeredUsers by viewModel.registeredUsers.collectAsState()
    val warehouses by viewModel.warehouses.collectAsState()
    val warehouseMemberships by viewModel.warehouseMemberships.collectAsState()
    val conversations by viewModel.conversations.collectAsState()
    val selectedConversation by viewModel.selectedConversation.collectAsState()
    val messages by viewModel.messages.collectAsState()
    val busy by viewModel.isBusy.collectAsState()
    val error by viewModel.error.collectAsState()
    val notice by viewModel.notice.collectAsState()

    var selectedTab by remember { mutableStateOf(0) }
    var workspaceName by remember { mutableStateOf("") }
    var memberEmail by remember { mutableStateOf("") }
    var memberDisplayName by remember { mutableStateOf("") }
    var memberRole by remember { mutableStateOf("SUBUSER") }
    var selectedMemberId by remember { mutableStateOf("") }
    var messageText by remember(selectedConversation?.id) { mutableStateOf("") }

    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(members, currentUserId) {
        if (selectedMemberId.isBlank() || members.none { it.userId == selectedMemberId }) {
            selectedMemberId = members.firstOrNull { it.userId != currentUserId && it.role != "OWNER" }?.userId.orEmpty()
        }
    }

    val openConversation = selectedConversation
    if (openConversation != null) {
        ChatConversationPanel(
            conversation = openConversation,
            messages = messages,
            currentUserId = currentUserId,
            messageText = messageText,
            error = error,
            notice = notice,
            onMessageChange = { messageText = it },
            onSend = {
                if (messageText.isNotBlank()) {
                    viewModel.sendMessage(messageText)
                    messageText = ""
                }
            },
            onSendAttachment = { name, mime, bytes ->
                viewModel.sendAttachment(name, mime, bytes)
            },
            onDeleteMessage = { messageId -> viewModel.deleteMessage(messageId) },
            onResolveAttachment = { attachmentPath -> viewModel.resolveAttachmentUrl(attachmentPath) },
            onBack = { viewModel.closeConversation() },
            enabled = !busy
        )
        return
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
                    userEmail.ifBlank { "لم يتم تسجيل الدخول إلى Supabase Auth" },
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
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator()
            }
        }

        if (isAuthenticated) {
            TabRow(selectedTabIndex = selectedTab) {
                listOf("مساحة العمل", "الصلاحيات", "المحادثات").forEachIndexed { index, title ->
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
                                        "إضافة مستخدم إلى المساحة المشتركة",
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    OutlinedTextField(
                                        value = memberEmail,
                                        onValueChange = { memberEmail = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("بريد حساب Google للمستخدم") },
                                        singleLine = true
                                    )
                                    OutlinedTextField(
                                        value = memberDisplayName,
                                        onValueChange = { memberDisplayName = it },
                                        modifier = Modifier.fillMaxWidth(),
                                        label = { Text("اسم المستخدم (اختياري)") },
                                        singleLine = true
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        FilterChip(
                                            selected = memberRole == "SUBUSER",
                                            onClick = { memberRole = "SUBUSER" },
                                            label = { Text("مستخدم فرعي") }
                                        )
                                        FilterChip(
                                            selected = memberRole == "MULTI_WAREHOUSE",
                                            onClick = { memberRole = "MULTI_WAREHOUSE" },
                                            label = { Text("متعدد المخازن") }
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            viewModel.addMember(memberEmail, memberDisplayName, memberRole)
                                            memberEmail = ""
                                            memberDisplayName = ""
                                        },
                                        enabled = !busy && memberEmail.contains("@"),
                                        modifier = Modifier.fillMaxWidth()
                                    ) { Text("إضافة إلى مساحة العمل") }
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
                                                modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text(account.displayName.ifBlank { account.email }, fontWeight = FontWeight.SemiBold)
                                                    Text(account.email, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                TextButton(
                                                    onClick = { viewModel.addMember(account.email, account.displayName, "SUBUSER") },
                                                    enabled = !busy
                                                ) { Text("إضافة") }
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
                        if (activeWorkspace?.ownerUserId == currentUserId) {
                            Button(
                                onClick = { viewModel.uploadLocalInventoryToCloud() },
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("رفع بيانات المخازن الحالية إلى السحابة") }
                            Text(
                                "ارفع بيانات الجهاز الرئيسي مرة واحدة لبدء مشاركة المخازن مع بقية المستخدمين.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.downloadCloudWorkspaceData() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("مزامنة بيانات المخازن الآن") }
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
                        Text("لا توجد مخازن مرفوعة إلى السحابة بعد.")
                    } else {
                        warehouses.forEach { warehouse ->
                            val access = warehouseMemberships[warehouse.id].orEmpty()
                                .firstOrNull { it.userId == member.userId }
                            val granted = buildList {
                                if (access.permission("canView")) add("عرض المخزن")
                                if (access.permission("canInward")) add("التوريد")
                                if (access.permission("canDisburse")) add("الصرف")
                                if (access.permission("canTransfer")) add("التحويل")
                                if (access.permission("canAdjust")) add("التسوية")
                                if (access.permission("canReports")) add("التقارير")
                                if (access.permission("canEdit")) add("التعديل العام")
                                if (access.permission("canEditInventory")) add("تعديل المخزون")
                                if (access.permission("canDeleteItem")) add("حذف الأصناف")
                                if (access.permission("canEditIssueVoucher")) add("تعديل سند الصرف")
                                if (access.permission("canDeleteIssueVoucher")) add("حذف سند الصرف")
                                if (access.permission("canEditSupplyVoucher")) add("تعديل سند التوريد")
                                if (access.permission("canDeleteSupplyVoucher")) add("حذف سند التوريد")
                                if (access.permission("canAddItems")) add("إضافة الأصناف")
                                if (access.permission("canEditCatalogItems")) add("تعديل دليل الأصناف")
                                if (access.permission("canBackup")) add("النسخ الاحتياطي")
                            }
                            Card(modifier = Modifier.fillMaxWidth()) {
                                Column(
                                    Modifier.padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Text(warehouse.name, fontWeight = FontWeight.Bold)
                                    Text(
                                        when {
                                            access == null -> "لم تتم مزامنة صلاحيات هذا الجهاز بعد."
                                            granted.isEmpty() -> "لا توجد صلاحيات ممنوحة لهذا المخزن."
                                            else -> granted.joinToString(" • ")
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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
                else -> {
                    selectedConversation?.let { conversation ->
                        ChatConversationPanel(
                            conversation = conversation,
                            messages = messages,
                            currentUserId = currentUserId,
                            messageText = messageText,
                            error = error,
                            notice = notice,
                            onMessageChange = { messageText = it },
                            onSend = {
                                viewModel.sendMessage(messageText)
                                messageText = ""
                            },
                            onSendAttachment = { name, mime, bytes ->
                                viewModel.sendAttachment(name, mime, bytes)
                            },
                            onDeleteMessage = { messageId -> viewModel.deleteMessage(messageId) },
                            onResolveAttachment = { attachmentPath -> viewModel.resolveAttachmentUrl(attachmentPath) },
                            onBack = { viewModel.closeConversation() },
                            enabled = !busy
                        )
                    } ?: run {
                        Text("محادثات مساحة العمل", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        conversations.forEach { conversation ->
                            ConversationRow(
                                conversation = conversation,
                                onOpen = { viewModel.openConversation(conversation) }
                            )
                        }
                        if (conversations.isEmpty()) Text("لا توجد محادثات بعد. ابدأ محادثة مباشرة أو أنشئ محادثة لمخزن.")
                        HorizontalDivider()
                        Text("بدء محادثة مباشرة", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        members.filter { it.userId != currentUserId && it.isActive }.forEach { member ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(member.displayName.ifBlank { member.email }, fontWeight = FontWeight.SemiBold)
                                    Text(member.role, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                TextButton(onClick = { viewModel.startDirectConversation(member) }, enabled = !busy) {
                                    Text("محادثة")
                                }
                            }
                        }
                        HorizontalDivider()
                        Text("محادثات المخازن", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        warehouses.forEach { warehouse ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(warehouse.name, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = { viewModel.startWarehouseConversation(warehouse) },
                                    enabled = !busy
                                ) { Text("فتح مجموعة") }
                            }
                        }
                    }
                }
            }
        } else {
            Text("بعد ربط حساب Google، ستظهر هنا مساحات العمل والأعضاء والصلاحيات والمحادثات.")
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

@Composable
private fun ConversationRow(conversation: CloudConversation, onOpen: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    conversation.title.ifBlank {
                        if (conversation.conversationType == "DIRECT") "محادثة مباشرة" else "محادثة مخزن"
                    },
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    if (conversation.conversationType == "WAREHOUSE_GROUP") "مجموعة مخزن" else "محادثة مباشرة",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpen) { Text("فتح") }
        }
    }
}

@Composable
private fun ChatConversationPanel(
    conversation: CloudConversation,
    messages: List<CloudMessage>,
    currentUserId: String,
    messageText: String,
    error: String?,
    notice: String?,
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendAttachment: (String, String, ByteArray) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onResolveAttachment: suspend (String) -> String,
    onBack: () -> Unit,
    enabled: Boolean
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var attachmentMenuExpanded by remember { mutableStateOf(false) }
    var emojiDialogExpanded by remember { mutableStateOf(false) }
    var pendingDeleteMessageId by remember { mutableStateOf<String?>(null) }
    var audioRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }

    fun showLocalError(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }

    fun startAudioRecording() {
        if (audioRecorder != null) return
        val output = File(context.cacheDir, "voice-${System.currentTimeMillis()}.m4a")
        try {
            val recorder = MediaRecorder()
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            recorder.setOutputFile(output.absolutePath)
            recorder.prepare()
            recorder.start()
            audioRecorder = recorder
            audioFile = output
            isRecording = true
        } catch (failure: Exception) {
            runCatching { audioRecorder?.release() }
            audioRecorder = null
            output.delete()
            showLocalError(
                "تعذر بدء تسجيل الصوت: " +
                    (failure.localizedMessage ?: "تحقق من إذن الميكروفون")
            )
        }
    }

    fun stopAudioRecordingAndSend() {
        val recorder = audioRecorder ?: return
        val output = audioFile
        try {
            recorder.stop()
            recorder.release()
            audioRecorder = null
            audioFile = null
            isRecording = false
            if (output != null && output.exists() && output.length() > 0L) {
                onSendAttachment(output.name, "audio/mp4", output.readBytes())
            } else {
                showLocalError("لم يتم تسجيل صوت صالح للإرسال.")
            }
        } catch (failure: Exception) {
            runCatching { recorder.release() }
            audioRecorder = null
            audioFile?.delete()
            audioFile = null
            isRecording = false
            showLocalError("تعذر إنهاء التسجيل الصوتي.")
        } finally {
            output?.delete()
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startAudioRecording()
        else showLocalError("يجب السماح باستخدام الميكروفون لتسجيل رسالة صوتية.")
    }

    val documentLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val resolver = context.contentResolver
                val displayName = resolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME),
                    null,
                    null,
                    null
                )?.use { cursor ->
                    val columnIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && columnIndex >= 0) cursor.getString(columnIndex)
                    else null
                } ?: "مرفق"
                val mimeType = resolver.getType(uri)
                    ?: when (displayName.substringAfterLast('.', "").lowercase()) {
                        "pdf" -> "application/pdf"
                        "jpg", "jpeg" -> "image/jpeg"
                        "png" -> "image/png"
                        "gif" -> "image/gif"
                        "webp" -> "image/webp"
                        else -> ""
                    }
                require(
                    mimeType in setOf(
                        "image/jpeg", "image/png", "image/webp",
                        "image/gif", "application/pdf"
                    )
                ) { "اختر صورة أو ملف PDF فقط." }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("تعذر قراءة الملف المحدد.")
                require(bytes.size <= 20 * 1024 * 1024) {
                    "حجم المرفق يتجاوز 20 ميجابايت."
                }
                onSendAttachment(displayName, mimeType, bytes)
            }.onFailure { failure ->
                showLocalError(failure.localizedMessage ?: "تعذر قراءة المرفق.")
            }
        }
    }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bitmap ->
        if (bitmap != null) {
            val output = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, output)
            val bytes = output.toByteArray()
            if (bytes.isNotEmpty()) {
                onSendAttachment(
                    "camera-${System.currentTimeMillis()}.jpg",
                    "image/jpeg",
                    bytes
                )
            } else {
                showLocalError("تعذر التقاط الصورة.")
            }
        }
    }

    fun openAttachment(path: String) {
        coroutineScope.launch {
            runCatching { onResolveAttachment(path) }
                .onSuccess { signedUrl -> uriHandler.openUri(signedUrl) }
                .onFailure { failure ->
                    showLocalError(failure.localizedMessage ?: "تعذر فتح المرفق.")
                }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    DisposableEffect(Unit) {
        onDispose {
            audioRecorder?.let { recorder ->
                runCatching { recorder.stop() }
                runCatching { recorder.release() }
            }
            audioFile?.delete()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .imePadding()
            .navigationBarsPadding()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.Default.ArrowBack, contentDescription = "رجوع")
            }
            Column(Modifier.weight(1f)) {
                Text(
                    conversation.title.ifBlank {
                        if (conversation.conversationType == "DIRECT") "محادثة مباشرة"
                        else "محادثة المخزن"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    if (conversation.conversationType == "DIRECT") "محادثة خاصة"
                    else "مجموعة المخزن",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        notice?.let {
            Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
        }

        Card(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
            )
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (messages.isEmpty()) {
                    item {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 28.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("لا توجد رسائل بعد. ابدأ المحادثة.")
                        }
                    }
                } else {
                    items(messages, key = { it.id }) { message ->
                        val mine = message.senderUserId == currentUserId
                        var messageMenuExpanded by remember(message.id) { mutableStateOf(false) }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxWidth(0.88f),
                                color = if (mine) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(
                                    topStart = 18.dp,
                                    topEnd = 18.dp,
                                    bottomEnd = if (mine) 5.dp else 18.dp,
                                    bottomStart = if (mine) 18.dp else 5.dp
                                ),
                                shadowElevation = 1.dp
                            ) {
                                Column(
                                    Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            if (mine) "أنت" else "مستخدم",
                                            modifier = Modifier.weight(1f),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                        if (mine) {
                                            Box {
                                                IconButton(
                                                    onClick = { messageMenuExpanded = true },
                                                    modifier = Modifier.size(28.dp)
                                                ) {
                                                    Icon(
                                                        Icons.Default.MoreVert,
                                                        contentDescription = "خيارات الرسالة",
                                                        modifier = Modifier.size(17.dp)
                                                    )
                                                }
                                                DropdownMenu(
                                                    expanded = messageMenuExpanded,
                                                    onDismissRequest = { messageMenuExpanded = false }
                                                ) {
                                                    DropdownMenuItem(
                                                        text = { Text("حذف الرسالة والمرفق") },
                                                        leadingIcon = {
                                                            Icon(Icons.Default.Delete, contentDescription = null)
                                                        },
                                                        onClick = {
                                                            messageMenuExpanded = false
                                                            pendingDeleteMessageId = message.id
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Text(message.body, style = MaterialTheme.typography.bodyMedium)

                                    message.attachmentPath?.let { attachmentPath ->
                                        Surface(
                                            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.75f),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { openAttachment(attachmentPath) }
                                                    .padding(10.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                            ) {
                                                val fileName = attachmentPath.substringAfterLast('/')
                                                Icon(
                                                    when {
                                                        fileName.endsWith(".pdf", ignoreCase = true) ->
                                                            Icons.Default.PictureAsPdf
                                                        fileName.endsWith(".m4a", ignoreCase = true) ||
                                                            fileName.endsWith(".aac", ignoreCase = true) ->
                                                            Icons.Default.Mic
                                                        else -> Icons.Default.Image
                                                    },
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                                Column(Modifier.weight(1f)) {
                                                    Text(
                                                        message.body.ifBlank { fileName },
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.SemiBold
                                                    )
                                                    Text(
                                                        "اضغط لفتح المرفق",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = MaterialTheme.colorScheme.primary
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        message.createdAt.replace("T", " ").take(16),
                                        modifier = Modifier.align(Alignment.End),
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

        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(28.dp),
            border = androidx.compose.foundation.BorderStroke(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant
            ),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Box {
                    IconButton(
                        onClick = { attachmentMenuExpanded = true },
                        enabled = enabled && !isRecording
                    ) {
                        Icon(Icons.Default.AttachFile, contentDescription = "إرفاق صورة أو PDF")
                    }
                    DropdownMenu(
                        expanded = attachmentMenuExpanded,
                        onDismissRequest = { attachmentMenuExpanded = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("اختيار صورة أو PDF") },
                            leadingIcon = { Icon(Icons.Default.PictureAsPdf, contentDescription = null) },
                            onClick = {
                                attachmentMenuExpanded = false
                                documentLauncher.launch(arrayOf("image/*", "application/pdf"))
                            }
                        )
                        DropdownMenuItem(
                            text = { Text("التقاط صورة بالكاميرا") },
                            leadingIcon = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
                            onClick = {
                                attachmentMenuExpanded = false
                                cameraLauncher.launch(null)
                            }
                        )
                    }
                }

                TextField(
                    value = messageText,
                    onValueChange = onMessageChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("اكتب رسالة...") },
                    enabled = enabled && !isRecording,
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        disabledContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                        disabledIndicatorColor = Color.Transparent
                    )
                )

                IconButton(
                    onClick = { emojiDialogExpanded = true },
                    enabled = enabled && !isRecording
                ) {
                    Icon(Icons.Default.EmojiEmotions, contentDescription = "إيموجي")
                }

                IconButton(
                    onClick = {
                        if (isRecording) {
                            stopAudioRecordingAndSend()
                        } else if (
                            ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            startAudioRecording()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    enabled = enabled
                ) {
                    Icon(
                        if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                        contentDescription = if (isRecording) "إيقاف وإرسال التسجيل" else "تسجيل صوت",
                        tint = if (isRecording) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary
                    )
                }

                FilledIconButton(
                    onClick = onSend,
                    enabled = enabled && messageText.isNotBlank() && !isRecording
                ) {
                    Icon(Icons.Default.Send, contentDescription = "إرسال")
                }
            }
        }
    }

    if (emojiDialogExpanded) {
        val emojis = listOf(
            "😀", "😂", "😍", "🥰", "😊", "😉",
            "👍", "👏", "🙏", "❤️", "🔥", "🎉",
            "😢", "😎", "🤔", "✅", "❌", "📦"
        )
        AlertDialog(
            onDismissRequest = { emojiDialogExpanded = false },
            title = { Text("اختر إيموجي") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    emojis.chunked(6).forEach { group ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            group.forEach { emoji ->
                                TextButton(
                                    onClick = {
                                        onMessageChange(messageText + emoji)
                                        emojiDialogExpanded = false
                                    }
                                ) {
                                    Text(emoji, style = MaterialTheme.typography.titleLarge)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { emojiDialogExpanded = false }) { Text("إغلاق") }
            }
        )
    }

    pendingDeleteMessageId?.let { messageId ->
        AlertDialog(
            onDismissRequest = { pendingDeleteMessageId = null },
            title = { Text("حذف الرسالة") },
            text = { Text("هل تريد حذف هذه الرسالة ومرفقها من المحادثة؟") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteMessage(messageId)
                        pendingDeleteMessageId = null
                    }
                ) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteMessageId = null }) { Text("إلغاء") }
            }
        )
    }
}
