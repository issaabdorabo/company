package com.example

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.data.cloud.CloudBackupRepository
import com.example.data.cloud.CloudCollaborationRepository
import com.example.service.CloudPresenceService
import com.example.ui.viewmodel.InventoryViewModel
import kotlinx.coroutines.Job
import com.example.data.database.AppDatabase
import com.example.data.preferences.UserPreferencesManager
import com.example.data.repository.InventoryRepository
import com.example.util.CrashDiagnostics
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

class InventoryApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { InventoryRepository(database, this) }
    val preferencesManager by lazy { UserPreferencesManager(this) }
    val cloudBackupRepository by lazy { CloudBackupRepository(this) }

    @Volatile private var cloudCoordinatorJob: Job? = null
    @Volatile private var backgroundCoordinatorOwner: InventoryViewModel? = null

    @Synchronized
    fun replaceCloudCoordinator(owner: InventoryViewModel, job: Job) {
        cloudCoordinatorJob?.takeIf { it !== job }?.cancel()
        cloudCoordinatorJob = job
        if (backgroundCoordinatorOwner !== owner) backgroundCoordinatorOwner = null
    }

    @Synchronized
    fun ensureCloudCoordinator() {
        if (cloudCoordinatorJob?.isActive != true) {
            backgroundCoordinatorOwner = InventoryViewModel(this)
        }
    }

    override fun onCreate() {
        super.onCreate()

        // Persist uncaught Java/Kotlin exceptions before Android terminates the process.
        // This lets the user copy the exact stack trace from Settings on the next launch.
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            CrashDiagnostics.record(this, thread, throwable)
            previousHandler?.uncaughtException(thread, throwable)
        }

        // Seed initial warehouses and items if database is freshly created
        applicationScope.launch {
            repository.seedInitialDataIfEmpty()
            repository.ensureDefaultLookupRecords()
        }

        // Restart the authenticated cloud-presence foreground service after an ordinary app launch.
        applicationScope.launch {
            delay(1_500L)
            val workspaceId = preferencesManager.cloudWorkspaceIdFlow.first()
            val deviceId = preferencesManager.cloudDeviceIdFlow.first()
            if (workspaceId.isNotBlank() && deviceId.isNotBlank() &&
                !CloudCollaborationRepository().currentUserIdOrNull().isNullOrBlank()
            ) {
                runCatching {
                    ContextCompat.startForegroundService(
                        this@InventoryApp,
                        Intent(this@InventoryApp, CloudPresenceService::class.java)
                    )
                }
            }
        }
    }
}
