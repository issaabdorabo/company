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
import com.example.data.sync.LocalSyncJson
import org.json.JSONArray
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

/** Keeps the cloud heartbeat alive after the UI is moved to the background. */
class CloudPresenceService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeatJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (heartbeatJob?.isActive != true) {
            heartbeatJob = serviceScope.launch {
                val app = application as InventoryApp
                val preferences = app.preferencesManager
                val repository = CloudCollaborationRepository()
                while (isActive) {
                    try {
                        val workspaceId = preferences.cloudWorkspaceIdFlow.first()
                        val deviceId = preferences.cloudDeviceIdFlow.first()
                        if (workspaceId.isBlank() || deviceId.isBlank() || repository.currentUserIdOrNull().isNullOrBlank()) {
                            stopSelf()
                            break
                        }
                        repository.heartbeat(deviceId, appState = "BACKGROUND")
                        flushPendingCloudOperations(app, repository, workspaceId, deviceId)
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

    private suspend fun flushPendingCloudOperations(
        app: InventoryApp,
        cloud: CloudCollaborationRepository,
        workspaceId: String,
        deviceId: String
    ) {
        val pending = app.repository.getCloudPendingSyncOperations()
        if (pending.isEmpty()) return

        val workspace = cloud.getWorkspace(workspaceId)
        val isOwner = workspace.ownerUserId == cloud.currentUserIdOrNull()
        val warehouseMap = cloud.listWarehouses(workspaceId)
            .mapNotNull { row -> row.localId?.let { it to row.id } }
            .toMap()

        for (operation in pending) {
            try {
                val idsArray = JSONArray(operation.warehouseIdsJson)
                val localIds = buildSet {
                    for (index in 0 until idsArray.length()) {
                        val id = idsArray.optLong(index, 0L)
                        if (id > 0L) add(id)
                    }
                }
                val missing = localIds.filterNot { warehouseMap.containsKey(it) }
                if (missing.isNotEmpty()) {
                    app.repository.recordCloudPendingSyncFailure(
                        operation.operationId,
                        "المخازن التالية غير مربوطة سحابياً: ${missing.joinToString()}"
                    )
                    continue
                }

                val entities = LocalSyncJson.upsertsFromJsonArray(JSONArray(operation.entitiesJson))
                val cloudWarehouseIds = localIds.map { warehouseMap.getValue(it) }.toSet()
                val records = cloud.encodeCloudEntityRecords(
                    entities = entities,
                    cloudWarehouseIdByLocalId = warehouseMap,
                    targetWarehouseLocalIds = localIds,
                    includeGlobalRecords = isOwner ||
                        operation.actionType == "ADD_OR_MANAGE_CATALOG" ||
                        operation.actionType == "EDIT_CATALOG_ITEMS"
                )
                cloud.pushCloudSnapshot(
                    workspaceId = workspaceId,
                    deviceId = deviceId,
                    clientOperationId = operation.operationId,
                    operationType = operation.actionType,
                    warehouseIds = cloudWarehouseIds,
                    title = operation.operationTitle,
                    details = operation.operationDetails,
                    records = records
                )
                app.repository.removeCloudPendingSyncOperation(operation.operationId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                app.repository.recordCloudPendingSyncFailure(
                    operation.operationId,
                    error.localizedMessage ?: error.javaClass.simpleName
                )
                break
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "اتصال التعاون السحابي", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "يحافظ على ظهور الجهاز متصلاً أثناء عمل التطبيق في الخلفية"
                    setShowBadge(false)
                }
            )
        }
    }

    private fun buildNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("مخزني الذكي")
            .setContentText("المحافظة على اتصال التعاون السحابي")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

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
