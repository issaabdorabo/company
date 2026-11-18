package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Share
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.cloud.CloudAuthUser
import com.example.data.cloud.CloudBackupItem
import com.example.data.model.BackupRecord
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.CloudBackupUiState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupAndSyncSection(
    cloudUser: CloudAuthUser?,
    cloudBackupsState: CloudBackupUiState,
    localBackups: List<BackupRecord>,
    isBusy: Boolean,
    syncOperationLabel: String?,
    authError: String?,
    lastSyncedAtMillis: Long,
    lastSavedEmail: String,
    lastSavedBackupName: String,
    onSignInGoogle: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onOpenManualEmailDialog: () -> Unit,
    onManualCloudBackup: () -> Unit,
    onOpenRestoreCloudBackup: () -> Unit,
    onRefreshCloudBackups: () -> Unit,
    onRequestRestoreCloudBackup: (CloudBackupItem) -> Unit,
    onRequestRenameCloudBackup: (CloudBackupItem) -> Unit,
    onRequestDeleteCloudBackup: (CloudBackupItem) -> Unit,
    onOpenCreateLocalBackup: () -> Unit,
    onOpenManageLocalBackups: () -> Unit,
    onExportJsonBackup: () -> Unit,
    onImportJsonBackup: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }

    val readyBackups = (cloudBackupsState as? CloudBackupUiState.Ready)?.backups.orEmpty()
    val latestServerBackup = readyBackups.firstOrNull()
    val serverLatestMillis = latestServerBackup?.let { (it.createdAt ?: it.updatedAt)?.toDate()?.time } ?: 0L
    val effectiveLastSyncedAt = maxOf(lastSyncedAtMillis, serverLatestMillis)
    val effectiveEmail = cloudUser?.email?.ifBlank { cloudUser.displayName }
        ?.takeIf { it.isNotBlank() }
        ?: latestServerBackup?.userEmail?.takeIf { it.isNotBlank() }
        ?: lastSavedEmail
    val effectiveBackupName = lastSavedBackupName.ifBlank { latestServerBackup?.name.orEmpty() }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backup_and_sync_section_card"),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header + Status Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        shape = CircleShape,
                        color = (if (cloudUser != null) InfoBlue else MaterialTheme.colorScheme.primary).copy(alpha = 0.14f),
                        modifier = Modifier.size(40.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = if (cloudUser != null) Icons.Default.CloudDone else Icons.Default.Backup,
                                contentDescription = null,
                                tint = if (cloudUser != null) InfoBlue else MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "النسخ الاحتياطي والمزامنة (Backup & Sync)",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "إدارة النسخ السحابية والمحلية وحفظها عبر بريدك الإلكتروني",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))

                val statusColor = when {
                    isBusy -> InfoBlue
                    cloudUser != null -> SuccessGreen
                    else -> WarningOrange
                }
                val statusLabel = when {
                    isBusy -> "جاري المزامنة"
                    cloudUser != null -> "متصل بالسحابة"
                    else -> "محلي فقط"
                }
                Surface(
                    shape = RoundedCornerShape(20.dp),
                    color = statusColor.copy(alpha = 0.12f),
                    border = BorderStroke(1.dp, statusColor.copy(alpha = 0.35f)),
                    modifier = Modifier.testTag("backup_sync_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = statusColor
                            )
                        } else {
                            Icon(
                                imageVector = if (cloudUser != null) Icons.Default.CheckCircle else Icons.Default.CloudOff,
                                contentDescription = null,
                                tint = statusColor,
                                modifier = Modifier.size(13.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = statusColor
                        )
                    }
                }
            }

            // Last Synced Timestamp & Saved Email Indicator Strip
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = (if (effectiveLastSyncedAt > 0L) SuccessGreen else MaterialTheme.colorScheme.surfaceVariant)
                    .copy(alpha = if (effectiveLastSyncedAt > 0L) 0.09f else 0.5f),
                border = BorderStroke(
                    1.dp,
                    (if (effectiveLastSyncedAt > 0L) SuccessGreen else MaterialTheme.colorScheme.outlineVariant)
                        .copy(alpha = 0.45f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("last_synced_indicator_card")
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = "وقت آخر مزامنة سحابية",
                            tint = if (effectiveLastSyncedAt > 0L) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (effectiveLastSyncedAt > 0L) {
                                "آخر مزامنة سحابية: ${dateFormat.format(Date(effectiveLastSyncedAt))}"
                            } else {
                                "آخر مزامنة سحابية: لم يتم الرفع بعد"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (effectiveLastSyncedAt > 0L) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (effectiveBackupName.isNotBlank() && effectiveLastSyncedAt > 0L) {
                        Text(
                            text = "آخر نسخة مرفوعة: $effectiveBackupName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (effectiveEmail.isNotBlank()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = "البريد الإلكتروني المحفوظ",
                                tint = InfoBlue,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "البريد المحفوظ للنسخ: $effectiveEmail",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = InfoBlue
                            )
                        }
                    }
                }
            }

            // Loading Spinner Banner during Backup / Restore / Rename
            AnimatedVisibility(visible = isBusy) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = InfoBlue.copy(alpha = 0.1f),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("backup_sync_loading_banner")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.5.dp,
                            color = InfoBlue
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = syncOperationLabel ?: "جاري تنفيذ عملية المزامنة السحابية...",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            color = InfoBlue
                        )
                    }
                }
            }

            // Auth / Sync Error Display
            if (!authError.isNullOrBlank()) {
                Surface(
                    color = DangerRed.copy(alpha = 0.1f),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = authError,
                        color = DangerRed,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }

            // Google Account & Manual Email Connection Row (Single Selection + Saved Email Display)
            if (cloudUser == null) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (lastSavedEmail.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = InfoBlue.copy(alpha = 0.08f),
                            border = BorderStroke(1.dp, InfoBlue.copy(alpha = 0.3f)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    modifier = Modifier.weight(1f),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Email,
                                        contentDescription = null,
                                        tint = InfoBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "البريد المدخل: $lastSavedEmail",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.SemiBold,
                                        color = InfoBlue
                                    )
                                }
                                OutlinedButton(
                                    onClick = onOpenManualEmailDialog,
                                    enabled = !isBusy,
                                    modifier = Modifier
                                        .heightIn(min = 48.dp)
                                        .testTag("edit_manual_email_btn"),
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Icon(Icons.Default.Edit, contentDescription = "تعديل البريد", modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("تعديل", fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = onSignInGoogle,
                            enabled = !isBusy,
                            colors = ButtonDefaults.buttonColors(containerColor = InfoBlue),
                            modifier = Modifier
                                .weight(1.25f)
                                .heightIn(min = 48.dp)
                                .testTag("cloud_google_signin_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Email, contentDescription = null, modifier = Modifier.size(17.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("اختيار حساب Google", fontSize = 12.sp)
                        }

                        OutlinedButton(
                            onClick = onOpenManualEmailDialog,
                            enabled = !isBusy,
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .testTag("open_manual_email_btn"),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("إدخال بريد يدوياً", fontSize = 11.sp)
                        }
                    }
                }
            } else {
                val activeDisplayEmail = lastSavedEmail.ifBlank {
                    cloudUser.email.ifBlank { cloudUser.displayName }
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = SuccessGreen.copy(alpha = 0.08f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "البريد الإلكتروني المرتبط",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = SuccessGreen
                            )
                            Text(
                                text = activeDisplayEmail,
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            if (cloudUser.email.isNotBlank() && lastSavedEmail.isNotBlank() && !lastSavedEmail.equals(cloudUser.email, ignoreCase = true)) {
                                Text(
                                    text = "حساب Google: ${cloudUser.email}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutlinedButton(
                                onClick = onOpenManualEmailDialog,
                                enabled = !isBusy,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("edit_manual_email_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.Default.Edit, contentDescription = "إدخال بريد يدوياً", modifier = Modifier.size(15.dp))
                            }
                            OutlinedButton(
                                onClick = onSignOutGoogle,
                                enabled = !isBusy,
                                modifier = Modifier
                                    .heightIn(min = 48.dp)
                                    .testTag("cloud_google_signout_btn"),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "فصل الحساب", modifier = Modifier.size(15.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("فصل", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // Distinct Cloud Sync Action Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onManualCloudBackup,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("create_cloud_backup_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    if (isBusy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("جاري التنفيذ...", fontSize = 11.sp)
                    } else {
                        Icon(Icons.Default.CloudUpload, contentDescription = "نسخ احتياطي سحابي يدوي", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("نسخ سحابي يدوي", fontSize = 12.sp)
                    }
                }

                Button(
                    onClick = onOpenRestoreCloudBackup,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("restore_cloud_backup_dialog_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, contentDescription = "استعادة من السحابة", modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("استعادة من السحابة", fontSize = 12.sp)
                }
            }

            if (cloudUser == null) {
                Text(
                    text = "عند الضغط على «نسخ سحابي يدوي» أو «استعادة من السحابة» سيُطلب ربط بريدك الإلكتروني تلقائياً ثم إكمال العملية.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                OutlinedButton(
                    onClick = onRefreshCloudBackups,
                    enabled = !isBusy,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .testTag("refresh_cloud_backups_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = "جلب وتحديث النسخ من السحابة", modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("جلب وتحديث النسخ من السحابة", fontSize = 12.sp)
                }

                when (cloudBackupsState) {
                    is CloudBackupUiState.Loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
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
                    is CloudBackupUiState.SignedOut -> Unit
                    is CloudBackupUiState.Ready -> {
                        val list = cloudBackupsState.backups
                        Text(
                            text = "النسخ السحابية المحفوظة (${list.size})",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (list.isEmpty()) {
                            Text(
                                text = "لا توجد نسخ سحابية مرفوعة على هذا البريد حتى الآن.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                list.forEach { bkp ->
                                    val ts = (bkp.createdAt ?: bkp.updatedAt)?.toDate()
                                    val dateStr = if (ts != null) dateFormat.format(ts) else "الآن"
                                    val sizeKb = (bkp.sizeBytes / 1024L).coerceAtLeast(1L)
                                    val bkpEmail = bkp.userEmail.ifBlank { cloudUser.email }

                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .testTag("cloud_backup_card_${bkp.id}")
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                                Text(
                                                    text = bkp.name,
                                                    style = MaterialTheme.typography.titleSmall,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                if (bkpEmail.isNotBlank()) {
                                                    Text(
                                                        text = "البريد: $bkpEmail",
                                                        style = MaterialTheme.typography.labelSmall,
                                                        color = InfoBlue
                                                    )
                                                }
                                                Text(
                                                    text = "$dateStr • $sizeKb كيلوبايت",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                                Text(
                                                    text = "مخازن: ${bkp.warehouseCount} | أصناف: ${bkp.itemCount} | سندات: ${bkp.voucherCount}",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.primary,
                                                    fontWeight = FontWeight.SemiBold
                                                )
                                            }
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Button(
                                                    onClick = { onRequestRestoreCloudBackup(bkp) },
                                                    enabled = !isBusy,
                                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .heightIn(min = 48.dp)
                                                        .testTag("restore_cloud_backup_${bkp.id}"),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("استعادة", fontSize = 11.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = { onRequestRenameCloudBackup(bkp) },
                                                    enabled = !isBusy,
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("rename_cloud_backup_${bkp.id}"),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Edit, contentDescription = "تعديل اسم النسخة", modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("تعديل", fontSize = 11.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = { onRequestDeleteCloudBackup(bkp) },
                                                    enabled = !isBusy,
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("delete_cloud_backup_${bkp.id}"),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "حذف النسخة السحابية", modifier = Modifier.size(15.dp))
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            HorizontalDivider()

            // Unified Local Backups & External JSON Export/Import Section
            Text(
                text = "النسخ الاحتياطي المحلي وملفات الاسترداد (${localBackups.size} نسخة)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onOpenCreateLocalBackup,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("create_backup_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("حفظ نسخة محلية", fontSize = 12.sp)
                }

                Button(
                    onClick = onOpenManageLocalBackups,
                    enabled = !isBusy,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("restore_backup_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("النسخ المحلية", fontSize = 12.sp)
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedButton(
                    onClick = onExportJsonBackup,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("export_backup_file_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FileUpload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("تصدير لملف JSON", fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = onImportJsonBackup,
                    enabled = !isBusy,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("import_backup_file_btn"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("استرداد من ملف", fontSize = 12.sp)
                }
            }
        }
    }
}

@Composable
fun CreateCloudBackupDialog(
    linkedEmail: String,
    onConfirmUpload: (name: String, targetEmail: String) -> Unit,
    onDismiss: () -> Unit
) {
    var cloudBackupName by remember {
        val dateStr = SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.getDefault()).format(Date())
        mutableStateOf("سحابة_$dateStr")
    }
    var emailInput by remember(linkedEmail) { mutableStateOf(linkedEmail) }
    var nameError by remember { mutableStateOf<String?>(null) }
    var emailError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "النسخ الاحتياطي السحابي اليدوي",
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
                    label = { Text("البريد الإلكتروني لحفظ النسخة (قابل للتعديل) *") },
                    isError = emailError != null,
                    supportingText = emailError?.let { msg -> { Text(msg, color = DangerRed) } },
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = null, tint = InfoBlue)
                    },
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
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("cloud_backup_name_input"),
                    singleLine = true
                )
                Text(
                    text = "ستبقى هذه النسخة محفوظة سحابياً على البريد الإلكتروني ولن تُحذف عند إزالة التطبيق وإعادة تثبيته.",
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
                modifier = Modifier.testTag("confirm_upload_cloud_backup_btn")
            ) {
                Text("رفع وحفظ في السحابة")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
fun ManualEmailDialog(
    initialEmail: String,
    onConfirmEmail: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var emailInput by remember(initialEmail) { mutableStateOf(initialEmail) }
    var emailError by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "إدخال البريد الإلكتروني للنسخ السحابي",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = emailInput,
                    onValueChange = {
                        emailInput = it
                        emailError = null
                    },
                    label = { Text("البريد الإلكتروني *") },
                    placeholder = { Text("example@gmail.com") },
                    isError = emailError != null,
                    supportingText = emailError?.let { msg -> { Text(msg, color = DangerRed) } },
                    leadingIcon = {
                        Icon(Icons.Default.Email, contentDescription = null, tint = InfoBlue)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("manual_email_input"),
                    singleLine = true
                )
                Text(
                    text = "سيتم حفظ هذا البريد وعرض النسخ السحابية الخاصة به لتتمكن من حفظها أو استعادتها لاحقاً.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val trimmed = emailInput.trim()
                    if (trimmed.isEmpty() || !trimmed.contains("@") || !trimmed.contains(".")) {
                        emailError = "يرجى إدخال بريد إلكتروني صحيح"
                    } else {
                        onConfirmEmail(trimmed)
                    }
                },
                modifier = Modifier.testTag("confirm_manual_email_btn")
            ) {
                Text("حفظ البريد")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
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
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nameInput,
                    onValueChange = {
                        nameInput = it
                        nameError = null
                    },
                    label = { Text("الاسم الجديد للنسخة *") },
                    isError = nameError != null,
                    supportingText = nameError?.let { msg -> { Text(msg, color = DangerRed) } },
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("rename_backup_name_input"),
                    singleLine = true
                )
            }
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
                modifier = Modifier.testTag("confirm_rename_backup_btn")
            ) {
                Text("حفظ الاسم الجديد")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
        }
    )
}

@Composable
fun CloudRestoreSelectorDialog(
    linkedEmail: String,
    cloudBackupsState: CloudBackupUiState,
    isBusy: Boolean,
    onRefresh: () -> Unit,
    onSelectRestore: (CloudBackupItem) -> Unit,
    onSelectRename: (CloudBackupItem) -> Unit,
    onSelectDelete: (CloudBackupItem) -> Unit,
    onDismiss: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault()) }
    var filterEmail by remember(linkedEmail) { mutableStateOf(linkedEmail) }
    var showAllEmails by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = "استعادة من السحابة",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (linkedEmail.isNotBlank()) {
                    Text(
                        text = "البريد المرتبط: $linkedEmail",
                        style = MaterialTheme.typography.labelMedium,
                        color = InfoBlue
                    )
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
                    label = { Text("عرض النسخ الخاصة بالبريد") },
                    leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = InfoBlue) },
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
                        shape = RoundedCornerShape(10.dp)
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
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(if (showAllEmails) "البريد فقط" else "عرض الكل", fontSize = 11.sp)
                    }
                }

                when (cloudBackupsState) {
                    is CloudBackupUiState.Loading -> {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
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
                            text = "يرجى ربط البريد الإلكتروني أولاً لعرض النسخ السحابية.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is CloudBackupUiState.Ready -> {
                        val allBackups = cloudBackupsState.backups
                        val cleanFilter = filterEmail.trim()
                        val list = if (showAllEmails || cleanFilter.isBlank()) {
                            allBackups
                        } else {
                            allBackups.filter { bkp ->
                                val itemEmail = bkp.userEmail.trim()
                                itemEmail.equals(cleanFilter, ignoreCase = true) ||
                                    (itemEmail.isBlank() && linkedEmail.equals(cleanFilter, ignoreCase = true))
                            }
                        }
                        if (list.isEmpty()) {
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
                                        "لا توجد نسخ سحابية مرفوعة على هذا البريد حتى الآن."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 320.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(list.size) { idx ->
                                    val bkp = list[idx]
                                    val ts = (bkp.createdAt ?: bkp.updatedAt)?.toDate()
                                    val dateStr = if (ts != null) dateFormat.format(ts) else "الآن"
                                    val sizeKb = (bkp.sizeBytes / 1024L).coerceAtLeast(1L)
                                    val savedEmail = bkp.userEmail.ifBlank { linkedEmail }

                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                        )
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(10.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = bkp.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            if (savedEmail.isNotBlank()) {
                                                Text(
                                                    text = "البريد: $savedEmail",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = InfoBlue
                                                )
                                            }
                                            Text(
                                                text = "$dateStr • $sizeKb كيلوبايت",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "مخازن: ${bkp.warehouseCount} | أصناف: ${bkp.itemCount} | سندات: ${bkp.voucherCount}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Button(
                                                    onClick = { onSelectRestore(bkp) },
                                                    enabled = !isBusy,
                                                    colors = ButtonDefaults.buttonColors(containerColor = SuccessGreen),
                                                    modifier = Modifier
                                                        .weight(1f)
                                                        .heightIn(min = 48.dp)
                                                        .testTag("dialog_restore_cloud_backup_${bkp.id}"),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Restore, contentDescription = null, modifier = Modifier.size(15.dp))
                                                    Spacer(modifier = Modifier.width(4.dp))
                                                    Text("استعادة", fontSize = 12.sp)
                                                }
                                                OutlinedButton(
                                                    onClick = { onSelectRename(bkp) },
                                                    enabled = !isBusy,
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("dialog_rename_cloud_backup_${bkp.id}"),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Edit, contentDescription = "تعديل الاسم", modifier = Modifier.size(15.dp))
                                                }
                                                OutlinedButton(
                                                    onClick = { onSelectDelete(bkp) },
                                                    enabled = !isBusy,
                                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                                    modifier = Modifier
                                                        .heightIn(min = 48.dp)
                                                        .testTag("dialog_delete_cloud_backup_${bkp.id}"),
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Icon(Icons.Default.Delete, contentDescription = "حذف النسخة السحابية", modifier = Modifier.size(15.dp))
                                                }
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
            Button(onClick = onDismiss) { Text("إغلاق") }
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
                modifier = Modifier.testTag("confirm_save_local_backup_btn")
            ) {
                Text("حفظ النسخة")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("إلغاء") }
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
                                RoundedCornerShape(8.dp)
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
                                    shape = RoundedCornerShape(10.dp)
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
            Button(onClick = onDismiss) { Text("إغلاق") }
        }
    )
}
