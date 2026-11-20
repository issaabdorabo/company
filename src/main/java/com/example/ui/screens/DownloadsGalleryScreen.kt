package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cancel
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.example.data.DownloadItem
import com.example.data.DownloadStatus
import com.example.data.TelegramUploadState
import com.example.data.UrlSlot
import com.example.ui.theme.StatusError
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.util.FormatUtils
import java.io.File

@Composable
fun DownloadsGalleryScreen(
    downloads: List<DownloadItem>,
    activeSlot: UrlSlot,
    autoSaveToGallery: Boolean,
    telegramState: TelegramUploadState,
    telegramAutoUploadEnabled: Boolean,
    onToggleAutoSave: (Boolean) -> Unit,
    onToggleTelegramUpload: (Boolean) -> Unit,
    onTriggerTelegramUpload: () -> Unit,
    onStartDirectDownload: (String) -> Unit,
    onOpenSecondaryBrowser: () -> Unit,
    onCancelDownload: (String) -> Unit,
    onRemoveRecord: (String) -> Unit,
    onShowMessage: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var directUrlText by remember { mutableStateOf("") }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (activeSlot == UrlSlot.SECONDARY) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "الخزنة المخفية للمتصفح الآخر",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "أي فيديو يتم تنزيله هنا يختفي تلقائياً من المعرض والتطبيق عند العودة للمتصفح الافتراضي.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        } else {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Surface(
                            modifier = Modifier.size(46.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.secondaryContainer
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Rounded.PhotoLibrary,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.secondary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "الحفظ التلقائي في المعرض",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = if (autoSaveToGallery) {
                                    "يتم حفظ الفيديوهات والصور المنزلة تلقائياً في معرض الهاتف"
                                } else {
                                    "يتم الحفظ في مجلد التنزيلات فقط"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Switch(
                            checked = autoSaveToGallery,
                            onCheckedChange = onToggleAutoSave,
                            modifier = Modifier.testTag("auto_save_gallery_switch")
                        )
                    }
                }
            }
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "تنزيل من يوتيوب أو رابط فيديو مباشر",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            OutlinedTextField(
                                value = directUrlText,
                                onValueChange = { directUrlText = it },
                                placeholder = { Text("https://youtube.com/watch?v=...") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("direct_download_url_input"),
                                shape = RoundedCornerShape(14.dp)
                            )
                        }
                        Button(
                            onClick = {
                                val trimmed = directUrlText.trim()
                                if (trimmed.equals("ruba", ignoreCase = true)) {
                                    directUrlText = ""
                                    onOpenSecondaryBrowser()
                                } else if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                                    onStartDirectDownload(trimmed)
                                    directUrlText = ""
                                } else {
                                    onShowMessage("أدخل رابطاً يبدأ بـ http أو https")
                                }
                            },
                            modifier = Modifier
                                .height(54.dp)
                                .testTag("start_direct_download_button"),
                            shape = RoundedCornerShape(14.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Download,
                                contentDescription = "تنزيل"
                            )
                        }
                    }
                }
            }
        }

        if (downloads.isEmpty()) {
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Movie,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = "لا توجد تنزيلات في هذا الوضع",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "افتح أي فيديو في المتصفح أو يوتيوب واضغط على زر تنزيل الفيديو.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(downloads, key = { it.id }) { item ->
                DownloadItemCard(
                    item = item,
                    onCancel = { onCancelDownload(item.id) },
                    onOpen = { openDownloadedItem(context, item, onShowMessage) },
                    onShare = { shareDownloadedItem(context, item, onShowMessage) },
                    onDeleteRecord = {
                        // Also delete hidden vault file from disk when removed
                        if (item.isPrivateVault && !item.localPath.isNullOrBlank()) {
                            runCatching { File(item.localPath).delete() }
                        }
                        onRemoveRecord(item.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun DownloadItemCard(
    item: DownloadItem,
    onCancel: () -> Unit,
    onOpen: () -> Unit,
    onShare: () -> Unit,
    onDeleteRecord: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(44.dp),
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (item.isPrivateVault) Icons.Rounded.Lock else if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.InsertDriveFile,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = item.fileName,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    when (item.status) {
                        DownloadStatus.DOWNLOADING -> {
                            val totalText = if (item.totalBytes > 0) {
                                " / ${FormatUtils.formatBytes(item.totalBytes)}"
                            } else {
                                ""
                            }
                            Text(
                                text = "جاري التنزيل: ${FormatUtils.formatBytes(item.bytesDownloaded)}$totalText",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        DownloadStatus.SAVING_TO_GALLERY -> {
                            Text(
                                text = if (item.isPrivateVault) "جاري الحفظ في الخزنة المخفية..." else "جاري الحفظ في المعرض...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.secondary
                            )
                        }
                        DownloadStatus.COMPLETED -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.CheckCircle,
                                    contentDescription = null,
                                    tint = StatusSuccess,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = when {
                                        item.isPrivateVault -> "مخفي بالخزنة • ${FormatUtils.formatBytes(item.bytesDownloaded)}"
                                        item.savedToGallery -> "محفوظ بالمعرض • ${FormatUtils.formatBytes(item.bytesDownloaded)}"
                                        else -> "مكتمل • ${FormatUtils.formatBytes(item.bytesDownloaded)}"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusSuccess
                                )
                            }
                        }
                        DownloadStatus.FAILED -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Rounded.ErrorOutline,
                                    contentDescription = null,
                                    tint = StatusError,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = item.errorMessage ?: "فشل التنزيل",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = StatusError,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        DownloadStatus.CANCELLED -> {
                            Text(
                                text = "تم إلغاء التنزيل",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.SAVING_TO_GALLERY) {
                    IconButton(onClick = onCancel) {
                        Icon(
                            imageVector = Icons.Rounded.Cancel,
                            contentDescription = "إلغاء التنزيل",
                            tint = StatusError
                        )
                    }
                } else {
                    if (item.status == DownloadStatus.COMPLETED) {
                        FilledTonalIconButton(onClick = onOpen) {
                            Icon(
                                imageVector = if (item.isVideo) Icons.Rounded.PlayArrow else Icons.Rounded.OpenInNew,
                                contentDescription = "فتح الملف"
                            )
                        }
                        IconButton(onClick = onShare) {
                            Icon(
                                imageVector = Icons.Rounded.Share,
                                contentDescription = "مشاركة"
                            )
                        }
                    }
                    IconButton(onClick = onDeleteRecord) {
                        Icon(
                            imageVector = Icons.Rounded.DeleteOutline,
                            contentDescription = "حذف"
                        )
                    }
                }
            }

            if (item.status == DownloadStatus.DOWNLOADING || item.status == DownloadStatus.SAVING_TO_GALLERY) {
                Spacer(modifier = Modifier.height(10.dp))
                if (item.totalBytes > 0L && item.status == DownloadStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { item.progressFraction },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                } else {
                    LinearProgressIndicator(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
        }
    }
}

private fun resolveUriForItem(context: Context, item: DownloadItem): Uri? {
    item.galleryUri?.let { return Uri.parse(it) }
    val path = item.localPath ?: return null
    val file = File(path)
    if (!file.exists()) return null
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

private fun openDownloadedItem(
    context: Context,
    item: DownloadItem,
    onError: (String) -> Unit
) {
    val uri = resolveUriForItem(context, item)
    if (uri == null) {
        onError("الملف غير متوفر")
        return
    }
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, item.mimeType)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    runCatching { context.startActivity(intent) }.onFailure {
        onError("لا يوجد تطبيق مناسب لفتح هذا الملف")
    }
}

private fun shareDownloadedItem(
    context: Context,
    item: DownloadItem,
    onError: (String) -> Unit
) {
    val uri = resolveUriForItem(context, item)
    if (uri == null) {
        onError("الملف غير متوفر")
        return
    }
    val shareIntent = Intent(Intent.ACTION_SEND).apply {
        type = item.mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching {
        context.startActivity(Intent.createChooser(shareIntent, "مشاركة الملف").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }.onFailure {
        onError("تعذر مشاركة الملف")
    }
}
