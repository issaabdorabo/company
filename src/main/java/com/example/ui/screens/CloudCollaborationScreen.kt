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
import java.time.Instant

import androidx.compose.foundation.background
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
    onOpenSettings: () -> Unit,
    onBeforeOpen: suspend () -> Unit = {}
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
    var selectedMemberId by remember { mutableStateOf("") }
    var messageText by remember(selectedConversation?.id) { mutableStateOf("") }

    LaunchedEffect(Unit) {
        runCatching { onBeforeOpen() }
        viewModel.refresh()
    }
    LaunchedEffect(members, currentUserId) {
        if (selectedMemberId.isBlank() || members.none { it.userId == selectedMemberId }) {
            selectedMemberId = members.firstOrNull { it.userId != currentUserId && it.role != "OWNER" }?.userId.orEmpty()
        }
    }

    val openConversation = selectedConversation
    BackHandler(enabled = openConversation != null) { viewModel.closeConversation() }
    if (openConversation != null) {
        ChatConversationPanel(
            conversation = openConversation,
            messages = messages,
            currentUserId = currentUserId,
            messageText = messageText,
            error = error,
            notice = notice,
            onMessageChange = { messageText = it },
            onSend = { body, replyId ->
                if (body.isNotBlank()) {
                    viewModel.sendMessage(body, replyId)
                    messageText = ""
                }
            },
            onSendAttachment = { name, mime, bytes ->
                viewModel.sendAttachment(name, mime, bytes)
            },
            onDeleteMessage = { messageId -> viewModel.hideMessageForMe(messageId) },
            onEditMessage = { messageId, body -> viewModel.editMessage(messageId, body) },
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
                        Text("لا توجد مخازن مرفوعة إلى السحابة بعد.")
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
                            onSend = { body, replyId ->
                                if (body.isNotBlank()) viewModel.sendMessage(body, replyId)
                                messageText = ""
                            },
                            onSendAttachment = { name, mime, bytes ->
                                viewModel.sendAttachment(name, mime, bytes)
                            },
                            onDeleteMessage = { messageId -> viewModel.hideMessageForMe(messageId) },
                            onEditMessage = { messageId, body -> viewModel.editMessage(messageId, body) },
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
                        warehouses.filter { warehouse ->
                            warehouseMemberships[warehouse.id].orEmpty()
                                .count { it.permission("canView") } > 1
                        }.forEach { warehouse ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(warehouse.name, modifier = Modifier.weight(1f))
                                TextButton(
                                    onClick = { viewModel.startWarehouseConversation(warehouse) },
                                    enabled = !busy
                                ) { Text("فتح المجموعة") }
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
                    if (conversation.conversationType == "WAREHOUSE_GROUP") "مجموعة مخزن"
                    else listOf(conversation.participantDisplayName, conversation.participantDeviceName)
                        .filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "محادثة مباشرة" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onOpen) { Text("فتح") }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChatConversationPanel(
    conversation: CloudConversation,
    messages: List<CloudMessage>,
    currentUserId: String,
    messageText: String,
    error: String?,
    notice: String?,
    onMessageChange: (String) -> Unit,
    onSend: (String, String?) -> Unit,
    onSendAttachment: (String, String, ByteArray) -> Unit,
    onDeleteMessage: (String) -> Unit,
    onEditMessage: (String, String) -> Unit,
    onResolveAttachment: suspend (String) -> String,
    onBack: () -> Unit,
    enabled: Boolean
) {
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val coroutineScope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    var selectedEmojiCategory by remember { mutableStateOf("الوجوه") }
    var appearanceIndex by remember { mutableStateOf(0) }
    var emojiDialogExpanded by remember { mutableStateOf(false) }
    var showChatAppearanceDialog by remember { mutableStateOf(false) }
    var chatBackgroundColor by remember { mutableStateOf(Color(0xFFEFF3F8)) }
    var myBubbleColor by remember { mutableStateOf(Color(0xFFD9FDD3)) }
    var otherBubbleColor by remember { mutableStateOf(Color.White) }
    val appearancePrefs = remember(context) { context.getSharedPreferences("chat_appearance", Context.MODE_PRIVATE) }
    val appearancePresets = remember {
        listOf(
            Triple("أخضر واتساب", Pair(Color(0xFFE9F5E9), Color(0xFFD9FDD3)), Color.White),
            Triple("أزرق هادئ", Pair(Color(0xFFEAF2FF), Color(0xFFD5E6FF)), Color.White),
            Triple("ليلي", Pair(Color(0xFF202B36), Color(0xFF315B4A)), Color(0xFF344454)),
            Triple("وردي", Pair(Color(0xFFFFF0F4), Color(0xFFFFDCE7)), Color.White)
        )
    }
    LaunchedEffect(conversation.id) {
        appearanceIndex = appearancePrefs.getInt(conversation.id, 0).coerceIn(0, appearancePresets.lastIndex)
        val selected = appearancePresets[appearanceIndex]
        chatBackgroundColor = selected.second.first
        myBubbleColor = selected.second.second
        otherBubbleColor = selected.third
    }
    var pendingDeleteMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageId by remember { mutableStateOf<String?>(null) }
    var selectedMessageIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var editingMessageId by remember { mutableStateOf<String?>(null) }
    var editDraft by remember { mutableStateOf("") }
    var replyToMessageId by remember { mutableStateOf<String?>(null) }
    var audioRecorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var audioFile by remember { mutableStateOf<File?>(null) }
    var recordedAudioFile by remember { mutableStateOf<File?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var isRecordingPaused by remember { mutableStateOf(false) }
    var showRecordingDialog by remember { mutableStateOf(false) }

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
            isRecordingPaused = false
            showRecordingDialog = true
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

    fun stopAudioRecording() {
        val recorder = audioRecorder ?: return
        val output = audioFile
        try {
            recorder.stop()
            recorder.release()
            audioRecorder = null
            audioFile = null
            isRecording = false
            isRecordingPaused = false
            if (output != null && output.exists() && output.length() > 0L) recordedAudioFile = output
            else {
                output?.delete()
                showRecordingDialog = false
                showLocalError("لم يتم تسجيل صوت صالح للإرسال.")
            }
        } catch (failure: Exception) {
            runCatching { recorder.release() }
            audioRecorder = null
            audioFile?.delete()
            audioFile = null
            recordedAudioFile = null
            isRecording = false
            showRecordingDialog = false
            showLocalError("تعذر إنهاء التسجيل الصوتي.")
        }
    }

    fun pauseOrResumeAudioRecording() {
        val recorder = audioRecorder ?: return
        runCatching {
            if (isRecordingPaused) recorder.resume() else recorder.pause()
            isRecordingPaused = !isRecordingPaused
        }.onFailure { showLocalError("تعذر تغيير حالة التسجيل مؤقتاً.") }
    }

    fun sendRecordedAudio() {
        val output = recordedAudioFile ?: return
        if (output.exists() && output.length() > 0L) onSendAttachment(output.name, "audio/mp4", output.readBytes())
        else showLocalError("ملف التسجيل غير صالح.")
        output.delete()
        recordedAudioFile = null
        showRecordingDialog = false
    }

    fun cancelAudioRecording() {
        audioRecorder?.let { recorder ->
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
        audioRecorder = null
        audioFile?.delete()
        audioFile = null
        recordedAudioFile?.delete()
        recordedAudioFile = null
        isRecording = false
        isRecordingPaused = false
        showRecordingDialog = false
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

    val imageLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) {
            runCatching {
                val resolver = context.contentResolver
                val mimeType = resolver.getType(uri) ?: "image/jpeg"
                require(mimeType.startsWith("image/")) { "اختر ملف صورة فقط." }
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() }
                    ?: error("تعذر قراءة الصورة المحددة.")
                require(bytes.size <= 20 * 1024 * 1024) { "حجم الصورة يتجاوز 20 ميجابايت." }
                val extension = when (mimeType.lowercase()) {
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    "image/gif" -> "gif"
                    else -> "jpg"
                }
                onSendAttachment("image-${System.currentTimeMillis()}.$extension", mimeType, bytes)
            }.onFailure { failure ->
                showLocalError(failure.localizedMessage ?: "تعذر قراءة الصورة.")
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
            recordedAudioFile?.delete()
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
                    if (conversation.conversationType == "DIRECT") {
                        listOf(conversation.participantDisplayName, conversation.participantDeviceName)
                            .filter { it.isNotBlank() }.joinToString(" • ").ifBlank { "محادثة خاصة" }
                    } else "مجموعة المخزن",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = { showChatAppearanceDialog = true }) {
                Icon(Icons.Default.Palette, contentDescription = "تخصيص مظهر المحادثة")
            }
        }

        if (selectedMessageIds.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text("تم تحديد ${selectedMessageIds.size} رسالة", modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    selectedMessageIds.toList().forEach(onDeleteMessage)
                    selectedMessageIds = emptySet()
                    selectedMessageId = null
                }) { Text("حذف من جهازي") }
                TextButton(onClick = { selectedMessageIds = emptySet(); selectedMessageId = null }) { Text("إلغاء") }
            }
        } else selectedMessageId?.let { selectedId ->
            val selected = messages.firstOrNull { it.id == selectedId }
            if (selected != null) {
                val editable = selected.senderUserId == currentUserId && runCatching {
                    Instant.now().toEpochMilli() - Instant.parse(selected.createdAt).toEpochMilli() <= 10 * 60 * 1000
                }.getOrDefault(false)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = { replyToMessageId = selected.id; selectedMessageId = null }) { Text("رد") }
                    if (editable) TextButton(onClick = { editingMessageId = selected.id; editDraft = selected.body }) { Text("تعديل") }
                    TextButton(onClick = { onDeleteMessage(selected.id); selectedMessageId = null }) { Text("حذف من جهازي") }
                    TextButton(onClick = { selectedMessageIds = setOf(selected.id); selectedMessageId = null }) { Text("تحديد") }
                    TextButton(onClick = { selectedMessageId = null }) { Text("إلغاء") }
                }
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
            colors = CardDefaults.cardColors(containerColor = chatBackgroundColor)
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
                                modifier = Modifier.fillMaxWidth(0.88f).combinedClickable(
                                    onClick = {
                                        if (selectedMessageIds.isNotEmpty()) {
                                            selectedMessageIds = if (message.id in selectedMessageIds) selectedMessageIds - message.id else selectedMessageIds + message.id
                                        } else selectedMessageId = if (selectedMessageId == message.id) null else message.id
                                    },
                                    onLongClick = {
                                        selectedMessageIds = if (message.id in selectedMessageIds) selectedMessageIds - message.id else selectedMessageIds + message.id
                                        selectedMessageId = null
                                    }
                                ),
                                color = if (mine) myBubbleColor else otherBubbleColor,
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

                                    }

                                    message.replyToMessageId?.let { replyId ->
                                        messages.firstOrNull { it.id == replyId }?.let { replied ->
                                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(8.dp)) {
                                                Column(Modifier.fillMaxWidth().padding(7.dp)) {
                                                    Text("رد على رسالة", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                                    Text(replied.body, maxLines = 2, style = MaterialTheme.typography.bodySmall)
                                                }
                                            }
                                        }
                                    }
                                    message.replyToMessageId?.let { replyId ->
                                        messages.firstOrNull { it.id == replyId }?.let { replied ->
                                            Surface(
                                                modifier = Modifier.fillMaxWidth(),
                                                color = MaterialTheme.colorScheme.surfaceVariant,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Column(Modifier.padding(7.dp)) {
                                                    Text("رد على رسالة", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                                                    Text(replied.body, maxLines = 2, style = MaterialTheme.typography.bodySmall)
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
                                        message.createdAt.replace("T", " ").take(16) + if (message.editedAt != null) " • تم التعديل ${message.editedAt.replace("T", " ").take(16)}" else "",
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

        if (replyToMessageId != null) {
            val replied = messages.firstOrNull { it.id == replyToMessageId }
            if (replied != null) {
                Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(10.dp)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("الرد على رسالة", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                        Text(replied.body, maxLines = 1, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { replyToMessageId = null }) { Icon(Icons.Default.Delete, contentDescription = "إلغاء الرد") }
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
                IconButton(
                    onClick = { imageLauncher.launch("image/*") },
                    enabled = enabled && !isRecording
                ) {
                    Icon(Icons.Default.Image, contentDescription = "اختيار صورة")
                }
                IconButton(
                    onClick = { documentLauncher.launch(arrayOf("application/pdf")) },
                    enabled = enabled && !isRecording
                ) {
                    Icon(Icons.Default.AttachFile, contentDescription = "اختيار ملف PDF")
                }
                IconButton(
                    onClick = { cameraLauncher.launch(null) },
                    enabled = enabled && !isRecording
                ) {
                    Icon(Icons.Default.CameraAlt, contentDescription = "التقاط صورة")
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
                            showRecordingDialog = true
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
                    onClick = { onSend(messageText, replyToMessageId); replyToMessageId = null },
                    enabled = enabled && messageText.isNotBlank() && !isRecording
                ) {
                    Icon(Icons.Default.Send, contentDescription = "إرسال")
                }
            }
        }
    }

    if (showChatAppearanceDialog) {
        AlertDialog(
            onDismissRequest = { showChatAppearanceDialog = false },
            title = { Text("مظهر المحادثة") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    appearancePresets.forEachIndexed { index, preset ->
                        TextButton(onClick = {
                            appearanceIndex = index
                            chatBackgroundColor = preset.second.first
                            myBubbleColor = preset.second.second
                            otherBubbleColor = preset.third
                            appearancePrefs.edit().putInt(conversation.id, index).apply()
                            showChatAppearanceDialog = false
                        }) {
                            Text(if (appearanceIndex == index) "✓ ${preset.first}" else preset.first)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChatAppearanceDialog = false }) { Text("إغلاق") }
            }
        )
    }

    editingMessageId?.let { messageId ->
        AlertDialog(
            onDismissRequest = { editingMessageId = null },
            title = { Text("تعديل الرسالة") },
            text = {
                OutlinedTextField(
                    value = editDraft,
                    onValueChange = { editDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("نص الرسالة") },
                    minLines = 2
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (editDraft.isNotBlank()) onEditMessage(messageId, editDraft.trim())
                    editingMessageId = null
                    selectedMessageId = null
                }) { Text("حفظ التعديل") }
            },
            dismissButton = {
                TextButton(onClick = { editingMessageId = null }) { Text("إلغاء") }
            }
        )
    }

    if (showRecordingDialog) {
        AlertDialog(
            onDismissRequest = { },
            title = { Text(if (isRecording) "تسجيل رسالة صوتية" else "إرسال التسجيل الصوتي") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (isRecording) {
                        Text(if (isRecordingPaused) "التسجيل متوقف مؤقتاً." else "جارٍ تسجيل الصوت. يمكنك إيقافه مؤقتاً أو إنهاءه.")
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (!isRecordingPaused) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                            Text(if (isRecordingPaused) "متوقف مؤقتاً" else "التسجيل جارٍ")
                        }
                        TextButton(onClick = { pauseOrResumeAudioRecording() }) {
                            Text(if (isRecordingPaused) "استئناف التسجيل" else "إيقاف مؤقت")
                        }
                    } else Text("تم إيقاف التسجيل. هل تريد إرسال الرسالة الصوتية؟")
                }
            },
            confirmButton = {
                TextButton(onClick = { if (isRecording) stopAudioRecording() else sendRecordedAudio() }) {
                    Text(if (isRecording) "إنهاء التسجيل" else "إرسال التسجيل")
                }
            },
            dismissButton = {
                TextButton(onClick = { cancelAudioRecording() }) { Text("إلغاء") }
            }
        )
    }

    if (emojiDialogExpanded) {
        val categories = linkedMapOf(
            "الوجوه" to listOf("😀","😃","😄","😁","😆","😅","🤣","😂","🙂","🙃","😉","😊","😇","🥰","😍","🤩","😘","😋","😎","🤔","🫡","🤫","🥱","😴","😢","😭","😤","😡","🤯","🥳","😱","🤗","🥺","😔","😌","🙄","😬","🤐"),
            "الأشخاص" to listOf("👍","👎","👌","✌️","🤞","🤟","🤘","👏","🙌","👐","🤲","🙏","💪","🫶","👋","🤝","👀","👶","👨","👩","🧑","👮","🧑‍💼"),
            "القلوب" to listOf("❤️","🧡","💛","💚","💙","💜","🖤","🤍","🤎","💔","💕","💞","💓","💗","💖","💘","💝","💟"),
            "الرموز" to listOf("💯","💢","💥","💫","💦","💤","🔥","✨","🎉","🎊","✅","❌","⚠️","⭐","🌟","☑️","❗","❓","💬"),
            "الأشياء" to listOf("🎁","🏆","📦","🚚","💰","📱","💻","🔧","🔨","🛠️","🚗","🏠","📅","📎","📌","📝")
        )
        val emojis = categories[selectedEmojiCategory] ?: categories.values.first()
        AlertDialog(
            onDismissRequest = { emojiDialogExpanded = false },
            title = { Text("اختر إيموجي") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        categories.keys.forEach { category ->
                            FilterChip(
                                selected = selectedEmojiCategory == category,
                                onClick = { selectedEmojiCategory = category },
                                label = { Text(category) }
                            )
                        }
                    }
                    Column(
                        modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        emojis.chunked(6).forEach { group ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                group.forEach { emoji ->
                                    TextButton(onClick = {
                                        onMessageChange(messageText + emoji)
                                        emojiDialogExpanded = false
                                    }) { Text(emoji, style = MaterialTheme.typography.titleLarge) }
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
