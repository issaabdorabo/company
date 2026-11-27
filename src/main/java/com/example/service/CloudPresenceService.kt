package com.example.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.example.InventoryApp
import com.example.data.cloud.CloudCollaborationRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Keeps the process and application-scoped encrypted Realtime coordinator alive after
 * the activity is swiped away. Identity and presence use Firebase; stock rows remain local and Google Drive is the backup source.
 */
class CloudPresenceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val app = application as InventoryApp
        app.ensureCloudCoordinator()
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = serviceScope.launch {
                val app = application as InventoryApp
                val preferences = app.preferencesManager
                val repository = CloudCollaborationRepository()
                while (isActive) {
                    app.ensureCloudCoordinator()
                    try {
                        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
                        val deviceId = preferences.cloudDeviceIdFlow.first()
                        if (workspaceId.isBlank() || deviceId.isBlank() || repository.currentUserIdOrNull().isNullOrBlank()) {
                            stopSelf()
                            break
                        }
                        repository.heartbeat(deviceId, appState = "BACKGROUND", workspaceId = workspaceId)
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        // Retain the service and retry at the next interval.
                    }
                    delay(20_000L)
                }
            }
        }
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "اتصال Firebase", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "يحافظ على ظهور الجهاز متصلاً بخدمة Firebase أثناء عمل التطبيق في الخلفية"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("مخزني الذكي")
            .setContentText("المحافظة على اتصال Firebase")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    override fun onTimeout(startId: Int, fgsType: Int) {
        // Android 15+ enforces a rolling six-hour daily quota for dataSync foreground services.
        // Stop cleanly rather than allowing the OS to crash the process on timeout.
        heartbeatJob?.cancel()
        serviceScope.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    override fun onDestroy() {
        heartbeatJob?.cancel()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "cloud_presence"
        const val NOTIFICATION_ID = 7412
    }
}
