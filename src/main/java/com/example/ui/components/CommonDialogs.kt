package com.example.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.DangerRed
import com.example.ui.theme.WarningOrange

@Composable
fun SimpleConfirmDialog(
    title: String,
    message: String,
    confirmButtonText: String = "تأكيد",
    dismissButtonText: String = "إلغاء",
    isDestructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isDestructive) {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = null,
                        tint = DangerRed,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        },
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyLarge,
                lineHeight = 22.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = if (isDestructive) {
                    ButtonDefaults.buttonColors(containerColor = DangerRed)
                } else {
                    ButtonDefaults.buttonColors()
                },
                modifier = Modifier.testTag("dialog_confirm_button")
            ) {
                Text(confirmButtonText)
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("dialog_dismiss_button")
            ) {
                Text(dismissButtonText)
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * Double confirmation dialog for high-stakes deletions
 * (e.g. deleting a warehouse that has stock, or an item that has transactions)
 */
@Composable
fun DoubleWarningConfirmDialog(
    step: Int, // 1 for first step, 2 for second final warning
    itemName: String,
    reasonText: String,
    onProceedToStepTwo: () -> Unit,
    onFinalConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    if (step == 1) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = WarningOrange,
                    modifier = Modifier.size(36.dp)
                )
            },
            title = {
                Text(
                    text = "تأكيد الحذف (الخطوة 1 من 2)",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "هل أنت متأكد من رغبتك في حذف: \"$itemName\"؟",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = WarningOrange.copy(alpha = 0.12f)
                        ),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = reasonText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = WarningOrange,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onProceedToStepTwo,
                    colors = ButtonDefaults.buttonColors(containerColor = WarningOrange),
                    modifier = Modifier.testTag("double_confirm_step1_btn")
                ) {
                    Text("متابعة للحذف")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("double_confirm_cancel_btn")
                ) {
                    Text("إلغاء وتراجع")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    } else if (step == 2) {
        AlertDialog(
            onDismissRequest = onDismiss,
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                    tint = DangerRed,
                    modifier = Modifier.size(42.dp)
                )
            },
            title = {
                Text(
                    text = "⚠️ تحذير نهائي مؤكد (الخطوة 2 من 2)",
                    style = MaterialTheme.typography.titleLarge,
                    color = DangerRed,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "انتبه! هذا الإجراء خطير ولا يمكن التراجع عنه.\nسيتم حذف \"$itemName\" نهائياً من قاعدة البيانات.",
                        style = MaterialTheme.typography.bodyLarge,
                        textAlign = TextAlign.Center,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = DangerRed.copy(alpha = 0.15f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "للتأكيد: هل توافق نهائياً على إتمام الحذف الآن؟",
                            style = MaterialTheme.typography.bodyMedium,
                            color = DangerRed,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(12.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = onFinalConfirm,
                    colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                    modifier = Modifier.testTag("double_confirm_final_btn")
                ) {
                    Text("نعم، احذف نهائياً")
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("double_confirm_final_cancel_btn")
                ) {
                    Text("تراجع وإلغاء")
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }
}

/**
 * Dialog shown when a Sub-Device (Read-Only) attempts any write/edit/delete/add action.
 */
@Composable
fun SubDeviceRestrictedDialog(
    actionName: String = "التعديل أو الإضافة أو الحذف",
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = WarningOrange,
                modifier = Modifier.size(36.dp)
            )
        },
        title = {
            Text(
                text = "تنبيه: نمط الجهاز الفرعي (للاطلاع فقط)",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Text(
                text = "هذا الجهاز مسجل حالياً كـ (جهاز فرعي للاطلاع والقراءة فقط).\n\nلا تملك صلاحية $actionName على البيانات في المخازن والأصناف.\n\nالصلاحيات الكاملة مخصصة للجهاز الرئيسي فقط لضمان سلامة المخزون ودقة القيود.\n\nيمكنك استعراض البيانات والتقارير والمعاينات بحرية.",
                style = MaterialTheme.typography.bodyMedium,
                lineHeight = 22.sp,
                textAlign = TextAlign.Center
            )
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("حسناً، فهمت")
            }
        },
        shape = RoundedCornerShape(16.dp)
    )
}

/**
 * Status Chip displaying whether current device is MAIN or SUB.
 */
@Composable
fun DeviceRoleStatusChip(
    isMainDevice: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = if (isMainDevice) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer,
        modifier = if (onClick != null) modifier.clickable { onClick() } else modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isMainDevice) "👑 جهاز رئيسي (صلاحيات كاملة)" else "👁️ جهاز فرعي (للاطلاع فقط)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isMainDevice) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary
            )
        }
    }
}
