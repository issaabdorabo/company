package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudQueue
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.FileUpload
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.cloud.CloudAuthUser
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
fun CloudSyncAndAuthCard(
    cloudUser: CloudAuthUser?,
    cloudBackupsState: CloudBackupUiState,
    isBusy: Boolean,
    syncOperationLabel: String?,
    authError: String?,
    lastSyncedAtMillis: Long,
    lastSavedEmail: String,
    lastSavedBackupName: String,
    onOpenAuthSelectionDialog: () -> Unit,
    onSignOutGoogle: () -> Unit,
    onManualCloudBackup: () -> Unit,
    onOpenRestoreCloudBackup: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    val readyBackups = (cloudBackupsState as? CloudBackupUiState.Ready)?.backups.orEmpty()
    val latestServerBackup = readyBackups.firstOrNull()
    val serverLatestMillis = latestServerBackup?.let { (it.createdAt ?: it.updatedAt)?.toDate()?.time } ?: 0L
    val effectiveLastSyncedAt = maxOf(lastSyncedAtMillis, serverLatestMillis)
    val activeDisplayEmail = lastSavedEmail.ifBlank {
        cloudUser?.email?.ifBlank { cloudUser.displayName }.orEmpty()
    }.ifBlank {
        latestServerBackup?.userEmail.orEmpty()
    }
    val effectiveBackupName = lastSavedBackupName.ifBlank { latestServerBackup?.name.orEmpty() }
    val isVerified = cloudUser != null

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("backup_and_sync_section_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Card Header + Status Badge (matches Image 1 & 3)
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = if (isVerified) Icons.Default.CloudDone else Icons.Default.CloudQueue,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(26.dp)
                    )
                    Text(
                        text = "المزامنة السحابية والمصادقة",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                val badgeBg = when {
                    isBusy -> InfoBlue.copy(alpha = 0.14f)
                    isVerified -> SuccessGreen.copy(alpha = 0.16f)
                    else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.65f)
                }
                val badgeTextColor = when {
                    isBusy -> InfoBlue
                    isVerified -> SuccessGreen
                    else -> MaterialTheme.colorScheme.onPrimaryContainer
                }
                val badgeLabel = when {
                    isBusy -> "جاري المزامنة..."
                    isVerified -> "حساب موثق ومؤكد ✓"
                    else -> "غير مؤكد"
                }

                Surface(
                    shape = RoundedCornerShape(50),
                    color = badgeBg,
                    modifier = Modifier.testTag("backup_sync_status_badge")
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (isBusy) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(12.dp),
                                strokeWidth = 2.dp,
                                color = badgeTextColor
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Text(
                            text = badgeLabel,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = badgeTextColor
                        )
                    }
                }
            }

            // Inner Google Authentication Box (matches Image 1 unverified / Image 3 verified)
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (isVerified) {
                    SuccessGreen.copy(alpha = 0.04f)
                } else {
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
                },
                border = BorderStroke(
                    width = if (isVerified) 1.5.dp else 1.dp,
                    color = if (isVerified) SuccessGreen else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("google_auth_inner_card")
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Surface(
                                shape = CircleShape,
                                color = MaterialTheme.colorScheme.surface,
                                shadowElevation = 1.dp,
                                modifier = Modifier.size(36.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Text(
                                        text = "G",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Text(
                                text = "المصادقة بواسطة حساب Google",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (isVerified) {
                            Surface(
                                shape = RoundedCornerShape(50),
                                color = SuccessGreen.copy(alpha = 0.14f),
                                modifier = Modifier.testTag("cloud_auth_verified_badge")
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "موثق",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = SuccessGreen
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = SuccessGreen,
                                        modifier = Modifier.size(13.dp)
                                    )
                                }
                            }
                        }
                    }

                    Text(
                        text = "تسجيل الدخول والمصادقة المباشرة باستخدام حساب Google المعتمد في جهازك للمزامنة الفورية وتأمين البيانات.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (activeDisplayEmail.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = (if (isVerified) SuccessGreen else InfoBlue).copy(alpha = 0.08f),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = if (isVerified) SuccessGreen else InfoBlue,
                                    modifier = Modifier.size(16.dp)
                                )
                                Text(
                                    text = if (isVerified) {
                                        "الحساب المرتبط: $activeDisplayEmail"
                                    } else {
                                        "البريد المختار: $activeDisplayEmail"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isVerified) SuccessGreen else InfoBlue,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                    }

                    if (!isVerified) {
                        Button(
                            onClick = onOpenAuthSelectionDialog,
                            enabled = !isBusy,
                            shape = RoundedCornerShape(50),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .testTag("cloud_google_signin_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "المصادقة بواسطة حساب Google",
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Button(
                                onClick = onOpenAuthSelectionDialog,
                                enabled = !isBusy,
                                shape = RoundedCornerShape(50),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = SuccessGreen,
                                    contentColor = Color.White
                                ),
                                modifier = Modifier
                                    .weight(1.7f)
                                    .heightIn(min = 48.dp)
                                    .testTag("cloud_google_change_btn")
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CloudDone,
                                    contentDescription = null,
                                    modifier = Modifier.size(17.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "تغيير حساب Google",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            OutlinedButton(
                                onClick = onSignOutGoogle,
                                enabled = !isBusy,
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = DangerRed),
                                modifier = Modifier
                                    .weight(1f)
                                    .heightIn(min = 48.dp)
                                    .testTag("cloud_google_signout_btn")
                            ) {
                                Text(
                                    text = "خروج",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Last Synced Timestamp + Email Line (matches Image 1 & 3)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("last_synced_indicator_card"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = "وقت آخر مزامنة",
                    tint = if (effectiveLastSyncedAt > 0L) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                    val syncSummary = if (effectiveLastSyncedAt > 0L) {
                        val dateText = dateFormat.format(Date(effectiveLastSyncedAt))
                        if (activeDisplayEmail.isNotBlank()) "آخر مزامنة: $dateText ($activeDisplayEmail)"
                        else "آخر مزامنة: $dateText"
                    } else {
                        "آخر مزامنة: لم يتم الرفع بعد"
                    }
                    Text(
                        text = syncSummary,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (effectiveBackupName.isNotBlank() && effectiveLastSyncedAt > 0L) {
                        Text(
                            text = "آخر نسخة مرفوعة: $effectiveBackupName",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // Loading Spinner Banner during Upload / Restore / Rename
            AnimatedVisibility(visible = isBusy) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
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
                            text = syncOperationLabel ?: "جاري تنفيذ المزامنة السحابية...",
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
                    shape = RoundedCornerShape(12.dp),
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

            // Bottom Pill Action Buttons (matches Image 1 & 3 — manual cloud backup only)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = onManualCloudBackup,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(50),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    ),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("create_cloud_backup_btn")
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
                        Icon(
                            imageVector = Icons.Default.CloudUpload,
                            contentDescription = "رفع نسخة للسحابة الآن",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("رفع نسخة للسحابة الآن", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                OutlinedButton(
                    onClick = onOpenRestoreCloudBackup,
                    enabled = !isBusy,
                    shape = RoundedCornerShape(50),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp)
                        .testTag("restore_cloud_backup_dialog_btn")
                ) {
                    Icon(
                        imageVector = Icons.Default.CloudDownload,
                        contentDescription = "اختيار نسخة واستعادتها",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "اختيار نسخة واستعادتها",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }
    }
}

@Composable
fun LocalBackupAndFileCard(
    localBackups: List<BackupRecord>,
    isBusy: Boolean,
    onOpenCreateLocalBackup: () -> Unit,
    onOpenManageLocalBackups: () -> Unit,
    onExportJsonBackup: () -> Unit,
    onImportJsonBackup: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("local_backup_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
                    modifier = Modifier.size(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Backup,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Column {
                    Text(
                        text = "النسخ الاحتياطي المحلي وملفات الاسترداد",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "النسخ المحفوظة على الهاتف (${localBackups.size} نسخة) وتصدير ملفات JSON",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

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
                    shape = RoundedCornerShape(50)
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
                    shape = RoundedCornerShape(50)
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
                    shape = RoundedCornerShape(50)
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
                    shape = RoundedCornerShape(50)
                ) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("استرداد من ملف", fontSize = 12.sp)
                }
            }
        }
    }
}
