package com.example.ui.components

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
    var selectedBackupId by remember { mutableStateOf<String?>(null) }

    val backups = (cloudBackupsState as? CloudBackupUiState.Ready)?.backups.orEmpty()

    LaunchedEffect(backups) {
        if (selectedBackupId != null && backups.none { it.id == selectedBackupId }) {
            selectedBackupId = null
        }
    }

    val selectedBackup = backups.firstOrNull { it.id == selectedBackupId }

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
                        text = "استعادة نسخة احتياطية من السحابة",
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
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("dialog_refresh_cloud_backups_btn"),
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تحديث قائمة النسخ من Google Drive", fontSize = 12.sp)
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
                            Text("جاري جلب النسخ الاحتياطية...", style = MaterialTheme.typography.bodySmall)
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
                            text = "يرجى إضافة حساب Google وتأكيده أولاً لعرض النسخ المحفوظة.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is CloudBackupUiState.Ready -> {
                        Text(
                            text = "كل النسخ الاحتياطية المحفوظة لحسابك (${backups.size}):",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )

                        if (backups.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 20.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "لا توجد نسخ احتياطية مرفوعة على Google Drive لهذا الحساب حتى الآن.",
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
                                items(backups.size) { idx ->
                                    val bkp = backups[idx]
                                    val isSelected = selectedBackupId == bkp.id
                                    val tsMillis = maxOf(bkp.createdAtMillis, bkp.updatedAtMillis)
                                    val dateStr = if (tsMillis > 0L) dateFormat.format(Date(tsMillis)) else "الآن"

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
                    text = "استعادة النسخة المختارة",
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
    onConfirmUpload: (name: String) -> Unit,
    onDismiss: () -> Unit
) {
    var cloudBackupName by remember {
        val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date())
        mutableStateOf("نسخة $dateStr")
    }
    var nameError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        shape = RoundedCornerShape(24.dp),
        title = {
            Text(
                text = "عمل نسخة احتياطية سحابية",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = InfoBlue.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Default.Email, contentDescription = null, tint = InfoBlue, modifier = Modifier.size(16.dp))
                        Text(
                            text = "الحساب المربوط: $linkedEmail",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = InfoBlue,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

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
                    text = "سيتم حفظ هذه النسخة في Google Drive الخاص بالحساب أعلاه ولن تتأثر بحذف التطبيق.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmedName = cloudBackupName.trim()
                    if (trimmedName.isEmpty()) {
                        nameError = "يرجى إدخال اسم النسخة السحابية"
                    } else {
                        onConfirmUpload(trimmedName)
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
                                                text = "مخازن: ${bkp.warehouseCount} • أصناف: ${bkp.itemCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }

                                        Row {
                                            IconButton(
                                                onClick = { onSelectRename(bkp) },
                                                modifier = Modifier.testTag("rename_local_backup_${bkp.id}")
                                            ) {
                                                Icon(
                                                    Icons.Default.Edit,
                                                    contentDescription = "تعديل الاسم",
                                                    tint = MaterialTheme.colorScheme.primary
                                                )
                                            }
                                            IconButton(
                                                onClick = { onSelectShare(bkp) },
                                                modifier = Modifier.testTag("share_local_backup_${bkp.id}")
                                            ) {
                                                Icon(
                                                    Icons.Default.Share,
                                                    contentDescription = "مشاركة الملف",
                                                    tint = InfoBlue
                                                )
                                            }
                                            IconButton(
                                                onClick = { onSelectRestore(bkp) },
                                                modifier = Modifier.testTag("restore_local_backup_${bkp.id}")
                                            ) {
                                                Icon(
                                                    Icons.Default.Restore,
                                                    contentDescription = "استعادة",
                                                    tint = SuccessGreen
                                                )
                                            }
                                            IconButton(
                                                onClick = { onSelectDelete(bkp) },
                                                modifier = Modifier.testTag("delete_local_backup_${bkp.id}")
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    contentDescription = "حذف",
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
                onClick = onDismiss,
                shape = RoundedCornerShape(50),
                modifier = Modifier.heightIn(min = 48.dp)
            ) {
                Text("إغلاق")
            }
        }
    )
}
