package com.example.util

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import java.util.concurrent.atomic.AtomicInteger

class AppNotificationManager(private val context: Context) {

    companion object {
        const val CHANNEL_OPERATIONS = "smart_inv_operations"
        const val CHANNEL_SUB_DEVICES = "smart_inv_sub_devices"

        private val notificationIdGenerator = AtomicInteger(1001)

        fun hasNotificationPermission(context: Context): Boolean {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.POST_NOTIFICATIONS
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                NotificationManagerCompat.from(context).areNotificationsEnabled()
            }
        }
    }

    init {
        ensureChannelsCreated()
    }

    fun ensureChannelsCreated() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                ?: return

            val operationsChannel = NotificationChannel(
                CHANNEL_OPERATIONS,
                "تنبيهات العمليات المخزنية",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات فورية عند تنفيذ عمليات التوريد، الصرف، التحويل، وإدارة المخازن والأصناف"
                enableVibration(true)
            }

            val subDeviceChannel = NotificationChannel(
                CHANNEL_SUB_DEVICES,
                "تنبيهات الأجهزة الفرعية المرتبطة",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "إشعارات فورية تصل للجهاز الرئيسي عند قيام أي جهاز فرعي مرتبط بتنفيذ عمليات"
                enableVibration(true)
            }

            manager.createNotificationChannel(operationsChannel)
            manager.createNotificationChannel(subDeviceChannel)
        }
    }

    fun notifyOperation(
        title: String,
        details: String,
        isRemoteSubDeviceAlert: Boolean = false
    ) {
        if (!hasNotificationPermission(context)) return
        ensureChannelsCreated()

        val channelId = if (isRemoteSubDeviceAlert) CHANNEL_SUB_DEVICES else CHANNEL_OPERATIONS
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            if (isRemoteSubDeviceAlert) 201 else 200,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val cleanTitle = title.trim().ifBlank { "عملية في مخزني الذكي" }
        val cleanBody = details.trim().ifBlank { cleanTitle }

        val notification = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(R.drawable.ic_notification_warehouse)
            .setContentTitle(cleanTitle)
            .setContentText(cleanBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(cleanBody))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(
                notificationIdGenerator.getAndIncrement(),
                notification
            )
        } catch (_: SecurityException) {
            // Permission revoked or denied by user; fail gracefully without crashing
        } catch (_: Exception) {
        }
    }
}
