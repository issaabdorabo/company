package com.example.ui.screens

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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
    val workspaces by viewModel.workspaces.collectAsState()
    val activeWorkspace by viewModel.activeWorkspace.collectAsState()
    val members by viewModel.members.collectAsState()
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
                    Text("إنشاء مساحة عمل جديدة", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = workspaceName,
                        onValueChange = { workspaceName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("اسم المؤسسة أو مساحة العمل") },
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            viewModel.createWorkspace(workspaceName)
                            workspaceName = ""
                        },
                        enabled = !busy && workspaceName.trim().length >= 2,
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("إنشاء مساحة عمل سحابية") }

                    if (workspaces.isNotEmpty()) {
                        Text("مساحات العمل المرتبطة بحسابك", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                        workspaces.forEach { workspace ->
                            FilterChip(
                                selected = activeWorkspace?.id == workspace.id,
                                onClick = { viewModel.selectWorkspace(workspace) },
                                label = { Text(workspace.name) },
                                enabled = !busy
                            )
                        }
                    }

                    activeWorkspace?.let { workspace ->
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(workspace.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text("معرّف مساحة العمل: ${workspace.id}", style = MaterialTheme.typography.labelSmall)
                                Text("الأعضاء: ${members.size} • المخازن المتاحة: ${warehouses.size}", style = MaterialTheme.typography.bodySmall)
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
                                ) { Text("إضافة المستخدم إلى مساحة العمل") }
                                Text(
                                    "يجب أن يكون المستخدم قد ربط حساب Google في التطبيق مرة واحدة على الأقل حتى يستطيع المسؤول إضافته.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("تسجيل هذا الجهاز", fontWeight = FontWeight.SemiBold)
                                    TextButton(onClick = { viewModel.registerCurrentDevice() }, enabled = !busy) {
                                        Text("تسجيل / تحديث")
                                    }
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
                                "هذه الخطوة تنشئ النسخة السحابية الأولى من قاعدة الجهاز الرئيسي. لا تبدأها من جهاز مستخدم فرعي.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        OutlinedButton(
                            onClick = { viewModel.downloadCloudWorkspaceData() },
                            enabled = !busy,
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("تنزيل / تحديث بيانات المخازن من السحابة على هذا الجهاز") }
                    }

                    if (members.isNotEmpty()) {
                        Text("أعضاء مساحة العمل", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
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
                    Text("تعيين صلاحيات المخازن", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(
                        "حدد مستخدماً ثم امنحه صلاحيات كل مخزن. حفظ الصلاحيات يتم على Supabase، وقفل المخزن يتحقق منه الخادم.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (members.isNotEmpty()) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            members.forEach { member ->
                                FilterChip(
                                    selected = selectedMemberId == member.userId,
                                    onClick = { selectedMemberId = member.userId },
                                    label = { Text(member.displayName.ifBlank { member.email.ifBlank { member.role } }) }
                                )
                            }
                        }
                    }
                    val member = members.firstOrNull { it.userId == selectedMemberId }
                    if (member == null) {
                        Text("لا يوجد مستخدم محدد لتعديل صلاحياته.")
                    } else if (member.role == "OWNER") {
                        Text("مالك مساحة العمل يملك صلاحيات الإدارة الكاملة.")
                    } else if (warehouses.isEmpty()) {
                        Text("لا توجد مخازن متاحة في مساحة العمل.")
                    } else {
                        warehouses.forEach { warehouse ->
                            val access = warehouseMemberships[warehouse.id].orEmpty()
                                .firstOrNull { it.userId == member.userId }
                            WarehousePermissionEditor(
                                warehouse = warehouse,
                                member = member,
                                access = access,
                                enabled = !busy,
                                onSave = { permissions, dedicated ->
                                    viewModel.saveWarehousePermissions(
                                        warehouseId = warehouse.id,
                                        userId = member.userId,
                                        canView = permissions.canView,
                                        canInward = permissions.canInward,
                                        canDisburse = permissions.canDisburse,
                                        canTransfer = permissions.canTransfer,
                                        canAdjust = permissions.canAdjust,
                                        canReports = permissions.canReports,
                                        canEdit = permissions.canEdit,
                                        canEditInventory = permissions.canEditInventory,
                                        canDeleteItem = permissions.canDeleteItem,
                                        makeDedicated = dedicated
                                    )
                                }
                            )
                        }
                    }
                }

                else -> {
                    selectedConversation?.let { conversation ->
                        ChatConversationPanel(
                            conversation = conversation,
                            messages = messages,
                            currentUserId = currentUserId,
                            messageText = messageText,
                            onMessageChange = { messageText = it },
                            onSend = {
                                viewModel.sendMessage(messageText)
                                messageText = ""
                            },
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

private data class EditableWarehousePermissions(
    val canView: Boolean,
    val canInward: Boolean,
    val canDisburse: Boolean,
    val canTransfer: Boolean,
    val canAdjust: Boolean,
    val canReports: Boolean,
    val canEdit: Boolean,
    val canEditInventory: Boolean,
    val canDeleteItem: Boolean
)

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
private fun WarehousePermissionEditor(
    warehouse: CloudWarehouse,
    member: CloudWorkspaceMember,
    access: CloudWarehouseMembership?,
    enabled: Boolean,
    onSave: (EditableWarehousePermissions, Boolean) -> Unit
) {
    var canView by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canView")) }
    var canInward by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canInward")) }
    var canDisburse by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canDisburse")) }
    var canTransfer by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canTransfer")) }
    var canAdjust by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canAdjust")) }
    var canReports by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canReports")) }
    var canEdit by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canEdit")) }
    var canEditInventory by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canEditInventory")) }
    var canDeleteItem by remember(warehouse.id, member.userId, access) { mutableStateOf(access.permission("canDeleteItem")) }
    var dedicated by remember(warehouse.id, member.userId, access) { mutableStateOf(false) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(warehouse.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Text(
                if (access != null) "لدى المستخدم عضوية محفوظة لهذا المخزن" else "لم تُمنح صلاحية لهذا المخزن بعد",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            PermissionToggle("عرض المخزن", canView, enabled) { canView = it }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PermissionToggle("توريد", canInward, enabled) { canInward = it; if (it) canView = true }
                PermissionToggle("صرف", canDisburse, enabled) { canDisburse = it; if (it) canView = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PermissionToggle("تحويل", canTransfer, enabled) { canTransfer = it; if (it) canView = true }
                PermissionToggle("تسوية", canAdjust, enabled) { canAdjust = it; if (it) canView = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PermissionToggle("تقارير", canReports, enabled) { canReports = it; if (it) canView = true }
                PermissionToggle("تعديل عام", canEdit, enabled) { canEdit = it; if (it) canView = true }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                PermissionToggle("تعديل المخزون", canEditInventory, enabled) { canEditInventory = it; if (it) canView = true }
                PermissionToggle("حذف صنف", canDeleteItem, enabled) { canDeleteItem = it; if (it) canView = true }
            }
            PermissionToggle("تخصيص هذا المخزن لهذا المستخدم", dedicated, enabled) { dedicated = it }
            Button(
                onClick = {
                    onSave(
                        EditableWarehousePermissions(
                            canView, canInward, canDisburse, canTransfer, canAdjust,
                            canReports, canEdit, canEditInventory, canDeleteItem
                        ),
                        dedicated
                    )
                },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) { Text("حفظ الصلاحيات على السحابة") }
        }
    }
}

@Composable
private fun PermissionToggle(label: String, checked: Boolean, enabled: Boolean, onChecked: (Boolean) -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.padding(end = 4.dp)
    ) {
        Checkbox(checked = checked, onCheckedChange = onChecked, enabled = enabled)
        Text(label, style = MaterialTheme.typography.bodySmall)
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
    onMessageChange: (String) -> Unit,
    onSend: () -> Unit,
    onBack: () -> Unit,
    enabled: Boolean
) {
    TextButton(onClick = onBack) { Text("رجوع إلى المحادثات") }
    Text(
        conversation.title.ifBlank {
            if (conversation.conversationType == "DIRECT") "محادثة مباشرة" else "محادثة المخزن"
        },
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold
    )
    Card(
        modifier = Modifier.fillMaxWidth().height(340.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f))
    ) {
        if (messages.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("لا توجد رسائل بعد. ابدأ المحادثة.")
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                items(messages, key = { it.id }) { message ->
                    val mine = message.senderUserId == currentUserId
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = if (mine) Arrangement.Start else Arrangement.End
                    ) {
                        Surface(
                            color = if (mine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Column(Modifier.width(260.dp).padding(10.dp)) {
                                Text(
                                    if (mine) "أنت" else "مستخدم",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(message.body, style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    message.createdAt.replace("T", " ").take(16),
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
    OutlinedTextField(
        value = messageText,
        onValueChange = onMessageChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("اكتب رسالة") },
        enabled = enabled,
        minLines = 2,
        maxLines = 4
    )
    Button(
        onClick = onSend,
        enabled = enabled && messageText.isNotBlank(),
        modifier = Modifier.fillMaxWidth()
    ) { Text("إرسال الرسالة") }
}
