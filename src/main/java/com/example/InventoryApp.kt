package com.example

import android.app.Application
import com.example.data.cloud.CloudBackupRepository
import com.example.data.database.AppDatabase
import com.example.data.preferences.UserPreferencesManager
import com.example.data.repository.InventoryRepository
import com.example.util.CrashDiagnostics
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
    }
}
