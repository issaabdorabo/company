package com.example

import android.app.Application
import com.example.data.BrowserConfigRepository
import com.example.data.DownloadRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class SmartBrowserApp : Application() {

    lateinit var configRepository: BrowserConfigRepository
        private set

    lateinit var downloadRepository: DownloadRepository
        private set

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        configRepository = BrowserConfigRepository(applicationContext)
        downloadRepository = DownloadRepository(configRepository)
        appScope.launch {
            downloadRepository.restoreHistory()
        }
    }
}
