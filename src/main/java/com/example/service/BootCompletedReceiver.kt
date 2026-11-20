package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/** Restores the user-enabled foreground connection after boot or an app update. */
class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED -> {
                try {
                    // startKeepAlive uses startForegroundService on Android O+.
                    // The service reads the persisted server and Telegram settings itself.
                    BrowserKeepAliveService.startKeepAlive(context.applicationContext)
                } catch (error: Exception) {
                    Log.e(TAG, "Unable to restore the background service", error)
                }
            }
        }
    }

    companion object {
        private const val TAG = "BootCompletedReceiver"
    }
}
