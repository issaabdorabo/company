package com.example.ui.components

import android.accounts.AccountManager
import android.app.Activity
import android.content.ActivityNotFoundException
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBox
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.cloud.CloudBackupItem
import com.example.data.model.BackupRecord
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.viewmodel.CloudBackupUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun GoogleAuthSelectionDialog(
    initialEmail: String,
    activeCloudEmail: String,
    savedEmails: List<String>,
    onPickedFromSystem: (String) -> Unit,
    onConfirmAuthenticate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val candidateEmails = remember(savedEmails, activeCloudEmail, initialEmail) {
        buildList<String> {
            fun addUnique(email: String) {
                val clean = email.trim()
                if (clean.isNotBlank() && none { it.equals(clean, ignoreCase = true) }) {
                    add(clean)
                }
            }
            addUnique(activeCloudEmail)
            addUnique(initialEmail)
            savedEmails.forEach { addUnique(it) }
        }
    }

    var emailInput by remember(initialEmail, activeCloudEmail) {
        mutableStateOf(initialEmail.ifBlank { activeCloudEmail })
    }
    var emailError by remember { mutableStateOf<String?>(null) }

    val systemAccountPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val pickedEmail = result.data?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)?.trim().orEmpty()
            if (pickedEmail.isNotBlank()) {
                emailInput = pickedEmail
                emailError = null
                onPickedFromSystem(pickedEmail)
            }
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "G",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(
                    text = "المصادقة بواسطة حساب Google",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "اختر أحد حسابات Google الموجودة في هاتفك للمصادقة الفورية وربط السحابة بأمان تام:",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedButton(
                    onClick = {
                        try {
                            val intent = AccountManager.newChooseAccountIntent(
                                null,
                                null,
                                arrayOf("com.google"),
                                "اختر حساب Google للنسخ الاحتياطي",
                                null,
                                null,
                                null
                            )
                            systemAccountPickerLauncher.launch(intent)
                        } catch (_: ActivityNotFoundException) {
                            Toast.makeText(
                                context,
                                "لا تتوفر نافذة حسابات النظام على هذا الجهاز، يمكنك كتابة البريد يدوياً.",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.6f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("open_system_account_picker_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.AccountBox,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "فتح نافذة حسابات Google بالنظام",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                if (candidateEmails.isNotEmpty()) {
                    Text(
                        text = "الحسابات الموجودة في هاتفك (انقر للاختيار مباشرة):",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        candidateEmails.take(4).forEachIndexed { idx, email ->
                            val isSelected = emailInput.trim().equals(email, ignoreCase = true)
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = if (isSelected) {
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.09f)
                                } else {
                                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                },
                                border = BorderStroke(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.outlineVariant
                                    }
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        emailInput = email
                                        emailError = null
                                    }
                                    .testTag("device_email_chip_$idx")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Surface(
                                            shape = CircleShape,
                                            color = MaterialTheme.colorScheme.surface,
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Box(contentAlignment = Alignment.Center) {
                                                Text(
                                                    text = "G",
                                                    fontWeight = FontWeight.ExtraBold,
                                                    fontSize = 13.sp,
                                                    color = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                        }
                                        Spacer(modifier = Modifier.width(10.dp))
                                        Text(
                                            text = email,
                                            style = MaterialTheme.typography.bodySmall,
                                            fontWeight = FontWeight.SemiBold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (isSelected) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = "مختار",
                                            tint = MaterialTheme.colorScheme.primary,
                                            modifier = Modifier.size(18.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "أو اكتب بريد Google يدوياً:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = emailInput,
                        onValueChange = {
                            emailInput = it
                            emailError = null
                        },
                        label = { Text("بريد حساب Google") },
                        placeholder = { Text("example@gmail.com") },
                        isError = emailError != null,
                        supportingText = emailError?.let { msg -> { Text(msg, color = DangerRed) } },
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("google_auth_email_input"),
                        singleLine = true
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val clean = emailInput.trim()
                    if (clean.isNotBlank() && (!clean.contains("@") || !clean.contains("."))) {
                        emailError = "يرجى إدخال بريد إلكتروني صحيح أو تركه لاختيار الحساب من Google"
                    } else {
                        onConfirmAuthenticate(clean)
                    }
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_google_auth_btn")
            ) {
                Text(
                    text = "المصادقة وتأكيد الحساب",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun CloudBackupRadioPickerDialog(
    linkedEmail: String,
    cloudBackupsState: CloudBackupUiState,
    isBusy: Boolean,
    onRefresh: () -> Unit,
    onSelectRestore: (CloudBackupItem) -> Unit,
    onSelectRename: (CloudBackupItem) -> Unit,
    onSelectDelete: (CloudBackupItem) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }
    var filterEmail by remember(linkedEmail) { mutableStateOf(linkedEmail) }
    var showAllEmails by remember { mutableStateOf(false) }
    var selectedBackupId by remember { mutableStateOf<String?>(null) }

    val allBackups = (cloudBackupsState as? CloudBackupUiState.Ready)?.backups.orEmpty()
    val cleanFilter = filterEmail.trim()
    val filteredList = remember(allBackups, cleanFilter, showAllEmails, linkedEmail) {
        if (showAllEmails || cleanFilter.isBlank()) {
            allBackups
        } else {
            allBackups.filter { bkp ->
                val itemEmail = bkp.userEmail.trim()
                itemEmail.equals(cleanFilter, ignoreCase = true) ||
                    (itemEmail.isBlank() && linkedEmail.equals(cleanFilter, ignoreCase = true))
            }
        }
    }

    LaunchedEffect(filteredList) {
        if (selectedBackupId != null && filteredList.none { it.id == selectedBackupId }) {
            selectedBackupId = null
        }
    }

    val selectedBackup = filteredList.firstOrNull { it.id == selectedBackupId }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.CloudDownload,
                    contentDescription = null,
                    tint = InfoBlue,
                    modifier = Modifier.size(26.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "اختر نسخة احتياطية من السحابة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    if (linkedEmail.isNotBlank()) {
                        Text(
                            text = linkedEmail,
                            style = MaterialTheme.typography.labelSmall,
                            color = InfoBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = filterEmail,
                    onValueChange = {
                        filterEmail = it
                        if (it.isNotBlank()) showAllEmails = false
                    },
                    label = { Text("تصفية حسب البريد") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = InfoBlue) },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("restore_dialog_email_filter_input"),
                    singleLine = true
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onRefresh,
                        enabled = !isBusy,
                        modifier = Modifier
                            .weight(1f)
                            .heightIn(min = 48.dp)
                            .testTag("dialog_refresh_cloud_backups_btn"),
                        shape = RoundedCornerShape(50)
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("تحديث السحابة", fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { showAllEmails = !showAllEmails },
                        enabled = !isBusy,
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .testTag("toggle_all_emails_backups_btn"),
                        shape = RoundedCornerShape(50)
                    ) {
                        Text(if (showAllEmails) "البريد فقط" else "عرض الكل", fontSize = 12.sp)
                    }
                }

                when (cloudBackupsState) {
                    is CloudBackupUiState.Loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 24.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري جلب النسخ السحابية...", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    is CloudBackupUiState.Error -> {
                        Text(
                            text = cloudBackupsState.message,
                            style = MaterialTheme.typography.bodySmall,
                            color = DangerRed
                        )
                    }
                    is CloudBackupUiState.SignedOut -> {
                        Text(
                            text = "يرجى المصادقة بحساب Google أولاً لعرض النسخ المحفوظة في السحابة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is CloudBackupUiState.Ready -> {
                        Text(
                            text = "النسخ الاحتياطية المحفوظة في السحابة لحسابك (${filteredList.size}):",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (filteredList.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = if (allBackups.isNotEmpty() && !showAllEmails) {
                                        "لا توجد نسخ مطابقة للبريد ($cleanFilter). اضغط «عرض الكل» لمشاهدة ${allBackups.size} نسخة."
                                    } else {
                                        "لا توجد نسخ سحابية مرفوعة حتى الآن."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 300.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(filteredList.size) { idx ->
                                    val bkp = filteredList[idx]
                                    val isSelected = selectedBackupId == bkp.id
                                    val ts = (bkp.createdAt ?: bkp.updatedAt)?.toDate()
                                    val dateStr = if (ts != null) dateFormat.format(ts) else "الآن"
                                    val savedEmail = bkp.userEmail.ifBlank { linkedEmail }

                                    Surface(
                                        shape = RoundedCornerShape(16.dp),
                                        color = if (isSelected) {
                                            MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                                        } else {
                                            MaterialTheme.colorScheme.surface
                                        },
                                        border = BorderStroke(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = if (isSelected) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.outlineVariant
                                            }
                                        ),
                                        tonalElevation = 1.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable(enabled = !isBusy) { selectedBackupId = bkp.id }
                                            .testTag("cloud_backup_card_${bkp.id}")
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 8.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            RadioButton(
                                                selected = isSelected,
                                                onClick = { if (!isBusy) selectedBackupId = bkp.id },
                                                modifier = Modifier.testTag("radio_cloud_backup_${bkp.id}")
                                            )

                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .padding(horizontal = 4.dp),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = bkp.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    maxLines = 1,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                                Text(
                                                    text = dateStr,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "${bkp.itemCount} صنف • ${bkp.warehouseCount} مخزن • ${bkp.voucherCount} سند",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                if (savedEmail.isNotBlank()) {
                                                    Text(
                                                        text = savedEmail,
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = InfoBlue,
                                                        maxLines = 1,
                                                        overflow = TextOverflow.Ellipsis
                                                    )
                                                }
                                            }

                                            IconButton(
                                                onClick = { onSelectRename(bkp) },
                                                enabled = !isBusy,
                                                modifier = Modifier.testTag("dialog_rename_cloud_backup_${bkp.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Edit,
                                                    contentDescription = "تعديل اسم النسخة",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }

                                            IconButton(
                                                onClick = { onSelectDelete(bkp) },
                                                enabled = !isBusy,
                                                modifier = Modifier.testTag("dialog_delete_cloud_backup_${bkp.id}")
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Delete,
                                                    contentDescription = "حذف النسخة السحابية",
                                                    tint = DangerRed
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
        },
        confirmButton = {
            Button(
                onClick = { selectedBackup?.let(onSelectRestore) },
                enabled = selectedBackup != null && !isBusy,
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_selected_cloud_restore_btn")
            ) {
                Text(
                    text = "استعادة هذه النسخة المختارة",
                    fontWeight = FontWeight.Bold
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun CreateCloudBackupDialog(
    linkedEmail: String,
    onConfirmUpload: (name: String, targetEmail: String) -> Unit,
    onDismiss: () -> Unit
) {
    var cloudBackupName by remember {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        mutableStateOf("نسخة $dateStr")
    }
    var emailInput by remember(linkedEmail) { mutableStateOf(linkedEmail) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "رفع نسخة للسحابة الآن",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        emailError = null
                    },
                    label = { Text("البريد الإلكتروني لحفظ النسخة *") },
                    isError = emailError != null,
                    supportingText = emailError?.let { msg -> { Text(msg, color = DangerRed) } },
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = null, tint = InfoBlue)
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_backup_email_input"),
                    singleLine = true
                )
                OutlinedTextField(
                    value = cloudBackupName,
                    onValueChange = {
                        cloudBackupName = it
                        nameError = null
                    },
                    label = { Text("اسم النسخة السحابية (قابل للتعديل) *") },
                    isError = nameError != null,
                    supportingText = nameError?.let { msg -> { Text(msg, color = DangerRed) } },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_backup_name_input"),
                    singleLine = true
                )
                Text(
                    text = "ستبقى هذه النسخة محفوظة سحابياً على بريدك ولن تُحذف عند إزالة التطبيق وإعادة تثبيته.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = cloudBackupName.trim()
                    val trimmedEmail = emailInput.trim()
                    when {
                        trimmedEmail.isEmpty() || !trimmedEmail.contains("@") || !trimmedEmail.contains(".") -> {
                            emailError = "يرجى إدخال بريد إلكتروني صحيح"
                        }
                        trimmedName.isEmpty() -> {
                            nameError = "يرجى إدخال اسم النسخة السحابية"
                        }
                        else -> onConfirmUpload(trimmedName, trimmedEmail)
                    }
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_upload_cloud_backup_btn")
            ) {
                Text("رفع وحفظ الآن")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun RenameBackupDialog(
    title: String,
    initialName: String,
    onConfirmRename: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var nameInput by remember(initialName) { mutableStateOf(initialName) }
    var nameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            OutlinedTextField(
                value = nameInput,
                onValueChange = {
                    nameInput = it
                    nameError = null
                },
                label = { Text("الاسم الجديد للنسخة *") },
                isError = nameError != null,
                supportingText = nameError?.let { msg -> { Text(msg, color = DangerRed) } },
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("rename_backup_name_input"),
                singleLine = true
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = nameInput.trim()
                    if (trimmed.isEmpty()) {
                        nameError = "الاسم لا يمكن أن يكون فارغاً"
                    } else {
                        onConfirmRename(trimmed)
                    }
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_rename_backup_btn")
            ) {
                Text("حفظ الاسم الجديد")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun CreateLocalBackupDialog(
    onConfirmSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var backupName by remember {
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        mutableStateOf("نسخة_$dateStr")
    }
    var nameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "حفظ نسخة احتياطية محلية",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = backupName,
                    onValueChange = {
                        backupName = it
                        nameError = null
                    },
                    label = { Text("اسم النسخة الاحتياطية (قابل للتعديل) *") },
                    isError = nameError != null,
                    supportingText = nameError?.let { msg -> { Text(msg, color = DangerRed) } },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("local_backup_name_input"),
                    singleLine = true
                )
                Text(
                    text = "سيتم حفظ النسخة كاملة على الهاتف مع إمكانية تعديل اسمها أو استعادتها أو مشاركتها كملف في أي وقت.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = backupName.trim()
                    if (trimmed.isEmpty()) {
                        nameError = "يرجى إدخال اسم النسخة الاحتياطية"
                    } else {
                        onConfirmSave(trimmed)
                    }
                },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("confirm_save_local_backup_btn")
            ) {
                Text("حفظ النسخة")
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("إلغاء")
            }
        }
    )
}

@Composable
fun LocalBackupSelectorDialog(
    backups: List<BackupRecord>,
    onSelectRestore: (BackupRecord) -> Unit,
    onSelectRename: (BackupRecord) -> Unit,
    onSelectShare: (BackupRecord) -> Unit,
    onSelectDelete: (BackupRecord) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "استعادة وإدارة النسخ المحلية",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                if (backups.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد نسخ احتياطية محلية محفوظة حتى الآن",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(320.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(6.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(backups.size) { i ->
                                val bkp = backups[i]
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Icon(
                                                    imageVector = Icons.Default.PhoneAndroid,
                                                    contentDescription = null,
                                                    tint = MaterialTheme.colorScheme.primary,
                                                    modifier = Modifier.size(16.dp)
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = bkp.name,
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                            Text(
                                                text = dateFormat.format(Date(bkp.timestamp)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "مخازن: ${bkp.warehouseCount} | أصناف: ${bkp.itemCount} | حركات: ${bkp.voucherCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                        }

                                        Row {
                                            IconButton(
                                                onClick = { onSelectRestore(bkp) },
                                                modifier = Modifier.testTag("restore_local_backup_${bkp.id}")
                                            ) {
                                                Icon(Icons.Default.Restore, contentDescription = "استعادة", tint = SuccessGreen)
                                            }
                                            IconButton(
                                                onClick = { onSelectRename(bkp) },
                                                modifier = Modifier.testTag("rename_local_backup_${bkp.id}")
                                            ) {
                                                Icon(Icons.Default.Edit, contentDescription = "تعديل الاسم", tint = InfoBlue)
                                            }
                                            IconButton(
                                                onClick = { onSelectShare(bkp) },
                                                modifier = Modifier.testTag("share_local_backup_${bkp.id}")
                                            ) {
                                                Icon(Icons.Default.Share, contentDescription = "مشاركة", tint = MaterialTheme.colorScheme.primary)
                                            }
                                            IconButton(
                                                onClick = { onSelectDelete(bkp) },
                                                modifier = Modifier.testTag("delete_local_backup_${bkp.id}")
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
            Button(
                onClick = onDismiss,
                shape = RoundedCornerShape(50),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("إغلاق")
            }
        }
    )
}
