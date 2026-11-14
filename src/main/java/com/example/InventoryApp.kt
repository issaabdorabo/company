package com.example

import android.app.Application
import com.example.data.cloud.CloudSyncRepository
import com.example.data.database.AppDatabase
import com.example.data.preferences.UserPreferencesManager
import com.example.data.repository.InventoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class InventoryApp : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { InventoryRepository(database, this) }
    val preferencesManager by lazy { UserPreferencesManager(this) }
    val cloudSyncRepository by lazy { CloudSyncRepository(this) }

    override fun onCreate() {
        super.onCreate()
        // Seed initial warehouses and items if database is freshly created
        applicationScope.launch {
            repository.seedInitialDataIfEmpty()
        }
    }
}
