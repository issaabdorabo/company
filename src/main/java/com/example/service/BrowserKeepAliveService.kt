package com.example.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.SmartBrowserApp
import com.example.data.DownloadItem
import com.example.data.DownloadStatus
import com.example.data.UrlSlot
import com.example.util.MediaGallerySaver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

class BrowserKeepAliveService : Service() {

    companion object {
        const val ACTION_START_KEEP_ALIVE = "com.example.action.START_KEEP_ALIVE"
        const val ACTION_UPDATE_SERVER_URL = "com.example.action.UPDATE_SERVER_URL"
        const val ACTION_ENQUEUE_DOWNLOAD = "com.example.action.ENQUEUE_DOWNLOAD"
        const val ACTION_CANCEL_DOWNLOAD = "com.example.action.CANCEL_DOWNLOAD"
        const val ACTION_TRIGGER_TELEGRAM_UPLOAD = "com.example.action.TRIGGER_TELEGRAM_UPLOAD"
        const val ACTION_STOP_TELEGRAM_UPLOAD = "com.example.action.STOP_TELEGRAM_UPLOAD"
        const val ACTION_STOP_SERVICE = "com.example.action.STOP_SERVICE"

        const val EXTRA_URL = "extra_url"
        const val EXTRA_FILE_NAME = "extra_file_name"
        const val EXTRA_MIME_TYPE = "extra_mime_type"
        const val EXTRA_REFERER = "extra_referer"
        const val EXTRA_USER_AGENT = "extra_user_agent"
        const val EXTRA_SOURCE_SLOT = "extra_source_slot"
        const val EXTRA_DOWNLOAD_ID = "extra_download_id"

        private const val CHANNEL_KEEPALIVE = "keepalive_channel"
        private const val CHANNEL_DOWNLOADS = "downloads_channel"
        private const val NOTIFICATION_KEEPALIVE_ID = 1001

        fun startKeepAlive(context: Context, serverUrl: String? = null) {
            val intent = Intent(context, BrowserKeepAliveService::class.java).apply {
                action = ACTION_START_KEEP_ALIVE
                if (!serverUrl.isNullOrBlank()) {
                    putExtra(EXTRA_URL, serverUrl)
                }
            }
            startForegroundSafely(context, intent)
        }

        fun updateServerUrl(context: Context, serverUrl: String) {
            val intent = Intent(context, BrowserKeepAliveService::class.java).apply {
                action = ACTION_UPDATE_SERVER_URL
                putExtra(EXTRA_URL, serverUrl)
            }
            startForegroundSafely(context, intent)
        }

        fun enqueueDownload(
            context: Context,
            url: String,
            sourceSlot: UrlSlot,
            fileName: String? = null,
            mimeType: String? = null,
            referer: String? = null,
            userAgent: String? = null
        ) {
            val intent = Intent(context, BrowserKeepAliveService::class.java).apply {
                action = ACTION_ENQUEUE_DOWNLOAD
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_SOURCE_SLOT, sourceSlot.key)
                putExtra(EXTRA_FILE_NAME, fileName)
                putExtra(EXTRA_MIME_TYPE, mimeType)
                putExtra(EXTRA_REFERER, referer)
                putExtra(EXTRA_USER_AGENT, userAgent)
            }
            startForegroundSafely(context, intent)
        }

        fun cancelDownload(context: Context, downloadId: String) {
            val intent = Intent(context, BrowserKeepAliveService::class.java).apply {
                action = ACTION_CANCEL_DOWNLOAD
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            startForegroundSafely(context, intent)
        }

        fun triggerTelegramUpload(context: Context) {
            val intent = Intent(context, BrowserKeepAliveService::class.java).apply {
                action = ACTION_TRIGGER_TELEGRAM_UPLOAD
            }
            startForegroundSafely(context, intent)
        }

        fun stopTelegramUpload(context: Context) {
            val intent = Intent(context, BrowserKeepAliveService::class.java).apply {
                action = ACTION_STOP_TELEGRAM_UPLOAD
            }
            startForegroundSafely(context, intent)
        }

        private fun startForegroundSafely(context: Context, intent: Intent) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (_: Exception) {
                runCatching { context.startService(intent) }
            }
        }
    }

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val activeDownloads = ConcurrentHashMap<String, Job>()

    private var heartbeatJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    @Volatile
    private var currentServerUrl: String = ""

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(5, 5, TimeUnit.MINUTES))
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .retryOnConnectionFailure(true)
            .build()
    }

    private val app: SmartBrowserApp
        get() = application as SmartBrowserApp

    private val telegramPhotoUploader: TelegramPhotoUploader by lazy {
        TelegramPhotoUploader(
            context = applicationContext,
            scope = serviceScope,
            okHttpClient = okHttpClient,
            configRepository = app.configRepository,
            downloadRepository = app.downloadRepository,
            onStatusNotificationUpdate = { text -> updateKeepAliveNotification(text) }
        )
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        acquirePartialWakeLock()
        promoteToForeground("متصل بالخادم في الخلفية")
        app.downloadRepository.updateServerState {
            it.copy(isServiceRunning = true)
        }
        startHeartbeatLoop()
        serviceScope.launch {
            if (app.configRepository.isTelegramAutoUploadEnabled()) {
                telegramPhotoUploader.start()
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        promoteToForeground("متصل بالخادم في الخلفية")

        when (intent?.action) {
            ACTION_STOP_SERVICE -> {
                serviceScope.launch {
                    app.configRepository.setKeepAliveEnabled(false)
                    telegramPhotoUploader.stop()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            ACTION_UPDATE_SERVER_URL -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    currentServerUrl = url
                    triggerSingleHeartbeat()
                }
            }
            ACTION_ENQUEUE_DOWNLOAD -> {
                val url = intent.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    val slot = UrlSlot.fromKey(intent.getStringExtra(EXTRA_SOURCE_SLOT))
                    val fileName = intent.getStringExtra(EXTRA_FILE_NAME)
                    val mimeType = intent.getStringExtra(EXTRA_MIME_TYPE)
                    val referer = intent.getStringExtra(EXTRA_REFERER)
                    val userAgent = intent.getStringExtra(EXTRA_USER_AGENT)
                    launchDownload(url, slot, fileName, mimeType, referer, userAgent)
                }
            }
            ACTION_CANCEL_DOWNLOAD -> {
                val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
                if (!id.isNullOrBlank()) {
                    activeDownloads[id]?.cancel()
                }
            }
            ACTION_TRIGGER_TELEGRAM_UPLOAD -> {
                telegramPhotoUploader.start()
            }
            ACTION_STOP_TELEGRAM_UPLOAD -> {
                telegramPhotoUploader.stop()
            }
            else -> {
                val url = intent?.getStringExtra(EXTRA_URL)
                if (!url.isNullOrBlank()) {
                    currentServerUrl = url
                }
                if (heartbeatJob?.isActive != true) {
                    startHeartbeatLoop()
                }
                serviceScope.launch {
                    if (app.configRepository.isTelegramAutoUploadEnabled()) {
                        telegramPhotoUploader.start()
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        serviceScope.launch {
            if (app.configRepository.isKeepAliveEnabled()) {
                val restartIntent = Intent(applicationContext, BrowserKeepAliveService::class.java).apply {
                    action = ACTION_START_KEEP_ALIVE
                    putExtra(EXTRA_URL, currentServerUrl)
                }
                val flags = PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
                val pendingIntent = PendingIntent.getService(applicationContext, 2002, restartIntent, flags)
                val alarmManager = getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                alarmManager?.set(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + 2000L,
                    pendingIntent
                )
            }
        }
    }

    override fun onDestroy() {
        telegramPhotoUploader.stop()
        app.downloadRepository.updateServerState {
            it.copy(isServiceRunning = false, isConnected = false)
        }
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startHeartbeatLoop() {
        heartbeatJob?.cancel()
        heartbeatJob = serviceScope.launch {
            while (isActive) {
                val config = app.configRepository.loadAssetsConfig()
                val targetUrl = currentServerUrl.ifBlank {
                    app.configRepository.getActiveServerUrl()
                }
                currentServerUrl = targetUrl
                performHeartbeat(targetUrl)
                delay(config.keepAliveIntervalSeconds * 1000L)
            }
        }
    }

    private fun triggerSingleHeartbeat() {
        serviceScope.launch {
            val targetUrl = currentServerUrl.ifBlank {
                app.configRepository.getActiveServerUrl()
            }
            performHeartbeat(targetUrl)
        }
    }

    private suspend fun performHeartbeat(url: String) {
        if (url.isBlank()) return
        val startMs = SystemClock.elapsedRealtime()
        try {
            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
            val headRequest = Request.Builder()
                .url(url)
                .head()
                .apply {
                    if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                    header("Connection", "keep-alive")
                }
                .build()

            val response = okHttpClient.newCall(headRequest).execute()
            val code = response.code
            response.close()

            val latency = (SystemClock.elapsedRealtime() - startMs).coerceAtLeast(1L)
            val reachable = code in 200..499
            app.downloadRepository.updateServerState { prev ->
                prev.copy(
                    isServiceRunning = true,
                    isConnected = reachable,
                    serverUrl = url,
                    latencyMs = latency,
                    heartbeatCount = prev.heartbeatCount + 1,
                    lastPingMillis = System.currentTimeMillis(),
                    lastError = if (reachable) null else "HTTP $code"
                )
            }
            val activeCount = activeDownloads.size
            val statusText = if (activeCount > 0) {
                "متصل بالخادم (${latency}ms) • جاري تنزيل $activeCount ملف"
            } else {
                "متصل بالخادم في الخلفية (${latency}ms)"
            }
            updateKeepAliveNotification(statusText)
        } catch (e: Exception) {
            app.downloadRepository.updateServerState { prev ->
                prev.copy(
                    isServiceRunning = true,
                    isConnected = false,
                    serverUrl = url,
                    lastPingMillis = System.currentTimeMillis(),
                    lastError = e.localizedMessage ?: "تعذر الاتصال بالخادم"
                )
            }
            updateKeepAliveNotification("إعادة محاولة الاتصال بالخادم...")
        }
    }

    private fun launchDownload(
        url: String,
        sourceSlot: UrlSlot,
        suggestedName: String?,
        suggestedMime: String?,
        referer: String?,
        userAgent: String?
    ) {
        val downloadId = UUID.randomUUID().toString()
        val isPrivateVault = sourceSlot == UrlSlot.SECONDARY
        val initialMime = suggestedMime?.takeIf { it.isNotBlank() } ?: guessMimeType(url)
        val initialFileName = resolveFileName(url, suggestedName, initialMime)

        val job = serviceScope.launch {
            val tempFile = File(cacheDir, "dl_$downloadId.tmp")
            var currentItem = DownloadItem(
                id = downloadId,
                url = url,
                fileName = initialFileName,
                mimeType = initialMime,
                status = DownloadStatus.DOWNLOADING,
                isPrivateVault = isPrivateVault,
                sourceSlot = sourceSlot
            )
            app.downloadRepository.upsertDownload(currentItem)

            try {
                val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                val request = Request.Builder()
                    .url(url)
                    .apply {
                        if (!cookie.isNullOrBlank()) header("Cookie", cookie)
                        if (!referer.isNullOrBlank()) header("Referer", referer)
                        if (!userAgent.isNullOrBlank()) header("User-Agent", userAgent)
                    }
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IllegalStateException("HTTP ${response.code}")
                    }
                    val body = response.body ?: throw IllegalStateException("Empty response body")
                    val contentDisposition = response.header("Content-Disposition")
                    val headerMime = response.header("Content-Type")?.substringBefore(';')?.trim()
                    val resolvedMime = when {
                        !headerMime.isNullOrBlank() && headerMime != "application/octet-stream" -> headerMime
                        else -> initialMime
                    }
                    val resolvedName = extractFileNameFromDisposition(contentDisposition)
                        ?: resolveFileName(url, suggestedName, resolvedMime)
                    val totalBytes = body.contentLength()

                    currentItem = currentItem.copy(
                        fileName = resolvedName,
                        mimeType = resolvedMime,
                        totalBytes = totalBytes
                    )
                    app.downloadRepository.upsertDownload(currentItem)

                    var downloaded = 0L
                    var lastReportTime = 0L
                    body.byteStream().use { input ->
                        FileOutputStream(tempFile).use { output ->
                            val buffer = ByteArray(32 * 1024)
                            while (isActive) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read

                                val now = SystemClock.elapsedRealtime()
                                if (now - lastReportTime >= 250L) {
                                    lastReportTime = now
                                    currentItem = currentItem.copy(bytesDownloaded = downloaded)
                                    app.downloadRepository.upsertDownload(currentItem)
                                    val percent = if (totalBytes > 0) ((downloaded * 100) / totalBytes).toInt() else -1
                                    val msg = when {
                                        isPrivateVault && percent >= 0 -> "جاري التنزيل الخاص ($percent%)"
                                        isPrivateVault -> "جاري التنزيل الخاص..."
                                        percent >= 0 -> "جاري التنزيل: $resolvedName ($percent%)"
                                        else -> "جاري التنزيل: $resolvedName"
                                    }
                                    updateKeepAliveNotification(msg)
                                }
                            }
                        }
                    }

                    currentItem = currentItem.copy(
                        bytesDownloaded = downloaded,
                        totalBytes = if (totalBytes > 0) totalBytes else downloaded,
                        status = DownloadStatus.SAVING_TO_GALLERY
                    )
                    app.downloadRepository.upsertDownload(currentItem)

                    val autoSaveToGallery = app.configRepository.isAutoSaveToGallery()
                    val savedResult = MediaGallerySaver.saveFile(
                        context = applicationContext,
                        sourceTempFile = tempFile,
                        fileName = resolvedName,
                        mimeType = resolvedMime,
                        autoSaveToGallery = autoSaveToGallery,
                        isPrivateVault = isPrivateVault
                    )

                    currentItem = currentItem.copy(
                        status = DownloadStatus.COMPLETED,
                        savedToGallery = savedResult.savedToGallery,
                        isPrivateVault = savedResult.isPrivateVault,
                        galleryUri = savedResult.contentUri.toString(),
                        localPath = savedResult.displayPath
                    )
                    app.downloadRepository.upsertDownload(currentItem)
                    if (!isPrivateVault) {
                        postDownloadCompletionNotification(
                            fileName = resolvedName,
                            savedToGallery = savedResult.savedToGallery
                        )
                    }
                }
            } catch (_: CancellationException) {
                currentItem = currentItem.copy(status = DownloadStatus.CANCELLED)
                app.downloadRepository.upsertDownload(currentItem)
            } catch (e: Exception) {
                currentItem = currentItem.copy(
                    status = DownloadStatus.FAILED,
                    errorMessage = e.localizedMessage ?: "فشل التنزيل"
                )
                app.downloadRepository.upsertDownload(currentItem)
            } finally {
                tempFile.delete()
                activeDownloads.remove(downloadId)
                updateKeepAliveNotification("متصل بالخادم في الخلفية")
            }
        }
        activeDownloads[downloadId] = job
    }

    private fun guessMimeType(url: String): String {
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        val ext = MimeTypeMap.getFileExtensionFromUrl(cleanUrl).lowercase()
        if (ext.isNotBlank()) {
            MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)?.let { return it }
        }
        return when {
            cleanUrl.endsWith(".webm", true) -> "video/webm"
            cleanUrl.endsWith(".mov", true) -> "video/quicktime"
            cleanUrl.endsWith(".mkv", true) -> "video/x-matroska"
            else -> "video/mp4"
        }
    }

    private fun resolveFileName(url: String, suggestedName: String?, mimeType: String): String {
        val baseCandidate = suggestedName?.takeIf { it.isNotBlank() }
            ?: url.substringBefore('?').substringBefore('#').substringAfterLast('/', "")
        val sanitized = baseCandidate.replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF._-]"), "_").take(64)
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimeType) ?: "mp4"
        return when {
            sanitized.isBlank() || sanitized == "_" -> "video_${System.currentTimeMillis()}.$ext"
            sanitized.contains('.') -> sanitized
            else -> "$sanitized.$ext"
        }
    }

    private fun extractFileNameFromDisposition(disposition: String?): String? {
        if (disposition.isNullOrBlank()) return null
        val match = Regex("filename=\"?([^\";]+)\"?").find(disposition) ?: return null
        return match.groupValues.getOrNull(1)?.trim()?.replace(Regex("[^a-zA-Z0-9._-]"), "_")
    }

    private fun promoteToForeground(contentText: String) {
        val notification = buildKeepAliveNotification(contentText)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_KEEPALIVE_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            startForeground(NOTIFICATION_KEEPALIVE_ID, notification)
        }
    }

    private fun updateKeepAliveNotification(contentText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_KEEPALIVE_ID, buildKeepAliveNotification(contentText))
    }

    private fun buildKeepAliveNotification(contentText: String): Notification {
        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun postDownloadCompletionNotification(fileName: String, savedToGallery: Boolean) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val launchIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this,
            10,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val message = if (savedToGallery) {
            "تم تنزيل وحفظ ($fileName) تلقائياً في المعرض"
        } else {
            "تم تنزيل ($fileName) بنجاح"
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_DOWNLOADS)
            .setSmallIcon(R.mipmap.ic_launcher_round)
            .setContentTitle("اكتمل التنزيل")
            .setContentText(message)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .build()
        manager.notify(fileName.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val keepAliveChannel = NotificationChannel(
                CHANNEL_KEEPALIVE,
                getString(R.string.notification_channel_keepalive),
                NotificationManager.IMPORTANCE_LOW
            )
            val downloadsChannel = NotificationChannel(
                CHANNEL_DOWNLOADS,
                getString(R.string.notification_channel_downloads),
                NotificationManager.IMPORTANCE_DEFAULT
            )
            manager.createNotificationChannel(keepAliveChannel)
            manager.createNotificationChannel(downloadsChannel)
        }
    }

    private fun acquirePartialWakeLock() {
        runCatching {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SmartBrowser::KeepAliveLock").apply {
                setReferenceCounted(false)
                acquire(60 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        }
    }
}
