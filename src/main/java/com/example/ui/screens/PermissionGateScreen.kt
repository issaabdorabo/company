package com.example.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.PermMedia
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.util.MandatoryPermissionStatus

@Composable
fun PermissionGateScreen(
    status: MandatoryPermissionStatus,
    onRequestRuntimePermissions: () -> Unit,
    onOpenFileManagerPermission: () -> Unit,
    onOpenNotificationAccessSettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                )
            ),
        contentAlignment = Alignment.TopCenter
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 600.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp)
            ) {
                Box(modifier = Modifier.fillMaxWidth()) {
                    Image(
                        painter = painterResource(id = R.drawable.img_permission_shield),
                        contentDescription = "شعار تفعيل الصلاحيات المطلوبة",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(168.dp),
                        contentScale = ContentScale.Crop
                    )
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(12.dp),
                        shape = RoundedCornerShape(50),
                        color = Color(0xCC0F172A)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Lock,
                                contentDescription = null,
                                tint = Color(0xFF22D3EE),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "بوابة الأمان الإلزامية",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color.White
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "تفعيل الصلاحيات لتشغيل المتصفح",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "لا يعمل التطبيق إلا بعد تفعيل صلاحية الكاميرا، الصور والفيديوهات، التحكم في مدير الملفات، والوصول إلى إشعارات التطبيقات.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "تقدم التفعيل",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "${status.grantedMandatoryCount} من 4 صلاحيات مفعّلة",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { status.grantedMandatoryCount / 4f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(8.dp)),
                        color = MaterialTheme.colorScheme.primary,
                        trackColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            PermissionRequirementCard(
                icon = Icons.Rounded.CameraAlt,
                title = "صلاحية الكاميرا",
                subtitle = "مطلوبة لتشغيل الكاميرا ومكالمات الفيديو ورفع الصور داخل مواقع المتصفح.",
                isGranted = status.cameraGranted,
                buttonTag = "grant_camera_button",
                onGrantClick = onRequestRuntimePermissions
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionRequirementCard(
                icon = Icons.Rounded.PermMedia,
                title = "صلاحية الصور والفيديوهات",
                subtitle = "مطلوبة لتنزيل الفيديوهات وحفظها تلقائياً في معرض الجهاز.",
                isGranted = status.photosAndVideosGranted,
                buttonTag = "grant_media_button",
                onGrantClick = onRequestRuntimePermissions
            )

            Spacer(modifier = Modifier.height(12.dp))

            PermissionRequirementCard(
                icon = Icons.Rounded.FolderOpen,
                title = "التحكم في مدير الملفات",
                subtitle = "مطلوبة للوصول الكامل إلى وحدة التخزين وإدارة المجلدات والملفات المنزلة.",
                isGranted = status.fileManagerGranted,
                buttonTag = "grant_files_button",
                onGrantClick = onOpenFileManagerPermission
            )

            Spacer(modifier = Modifier.height(12.dp))
            PermissionRequirementCard(
                icon = Icons.Rounded.NotificationsActive,
                title = "الوصول إلى إشعارات التطبيقات",
                subtitle = "صلاحية نظام مطلوبة لتمكين خدمة قراءة إشعارات التطبيقات. لا يتم إرسال محتوى الإشعارات إلى البوت في هذه المرحلة.",
                isGranted = status.notificationAccessGranted,
                buttonTag = "grant_notification_access_button",
                onGrantClick = onOpenNotificationAccessSettings
            )

            Spacer(modifier = Modifier.height(20.dp))

            val needsRuntime = !status.cameraGranted || !status.photosAndVideosGranted
            Button(
                onClick = {
                    if (needsRuntime) {
                        onRequestRuntimePermissions()
                    } else if (!status.fileManagerGranted) {
                        onOpenFileManagerPermission()
                    } else if (!status.notificationAccessGranted) {
                        onOpenNotificationAccessSettings()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("grant_all_permissions_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(
                    imageVector = Icons.Rounded.Shield,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (needsRuntime) {
                        "تفعيل صلاحيات الكاميرا والوسائط الآن"
                    } else if (!status.fileManagerGranted) {
                        "منح صلاحية مدير الملفات للمتابعة"
                    } else {
                        "تفعيل الوصول إلى إشعارات التطبيقات"
                    },
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            OutlinedButton(
                onClick = onOpenAppSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("open_app_settings_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(
                    imageVector = Icons.Rounded.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "فتح إعدادات التطبيق يدوياً",
                    style = MaterialTheme.typography.labelLarge
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun PermissionRequirementCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isGranted: Boolean,
    buttonTag: String,
    onGrantClick: () -> Unit
) {
    val badgeBg by animateColorAsState(
        targetValue = if (isGranted) StatusSuccess.copy(alpha = 0.14f) else StatusWarning.copy(alpha = 0.14f),
        label = "badgeBg"
    )
    val badgeTextColor = if (isGranted) StatusSuccess else StatusWarning

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                color = if (isGranted) {
                    StatusSuccess.copy(alpha = 0.15f)
                } else {
                    MaterialTheme.colorScheme.primaryContainer
                }
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = if (isGranted) Icons.Rounded.CheckCircle else icon,
                        contentDescription = title,
                        tint = if (isGranted) StatusSuccess else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = badgeBg
                    ) {
                        Text(
                            text = if (isGranted) "مفعّلة" else "مطلوبة",
                            style = MaterialTheme.typography.labelSmall,
                            color = badgeTextColor,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!isGranted) {
                Spacer(modifier = Modifier.width(8.dp))
                FilledTonalButton(
                    onClick = onGrantClick,
                    modifier = Modifier.testTag(buttonTag),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "تفعيل",
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }
}
