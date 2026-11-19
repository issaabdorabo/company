package com.example.service

import android.service.notification.NotificationListenerService

/**
 * Registers the app as a notification listener so Android can grant the special access.
 * This initial permission-only implementation intentionally does not read, store, or forward
 * notification contents. Telegram forwarding can be added later with explicit app controls.
 */
class AppNotificationListenerService : NotificationListenerService()
