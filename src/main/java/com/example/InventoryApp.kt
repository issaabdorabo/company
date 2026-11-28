package com.example

import android.app.Application
import com.example.data.cloud.CloudBackupRepository
import com.example.data.database.AppDatabase
import com.example.data.preferences.UserPreferencesManager
import com.example.data.repository.InventoryRepository
import com.example.data.sync.FirebaseCloudSyncEngine
import com.example.data.sync.CloudSyncWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InventoryApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { InventoryRepository(database, this) }
    val preferencesManager by lazy { UserPreferencesManager(this) }
    val cloudBackupRepository by lazy { CloudBackupRepository(this) }
    val cloudSyncEngine by lazy { FirebaseCloudSyncEngine(this, repository, preferencesManager) }

    override fun onCreate() {
        super.onCreate()
        CloudSyncWorker.schedule(this)
        // Seed initial warehouses and items if database is freshly created
        applicationScope.launch {
            repository.seedInitialDataIfEmpty()
            repository.ensureDefaultLookupRecords()
        }
    }
}
