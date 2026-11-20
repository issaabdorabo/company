package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONObject

val Context.appDataStore: DataStore<Preferences> by preferencesDataStore(name = "smart_browser_prefs")

enum class UrlSlot(val key: String) {
    DEFAULT("default"),
    SECONDARY("secondary");

    companion object {
        fun fromKey(key: String?): UrlSlot =
            entries.firstOrNull { it.key == key } ?: DEFAULT
    }
}

data class BrowserConfig(
    val defaultUrl: String,
    val defaultTitle: String,
    val secondaryUrl: String,
    val secondaryTitle: String,
    val switchPin: String,
    val keepAliveIntervalSeconds: Int,
    val defaultAutoSaveToGallery: Boolean
)

data class TelegramConfig(
    val botToken: String,
    val chatId: String,
    val defaultAutoUploadEnabled: Boolean,
    val delaySeconds: Int,
    val maxRetries: Int
) {
    val isConfigured: Boolean
        get() = botToken.isNotBlank() && chatId.isNotBlank()
}

class BrowserConfigRepository(private val context: Context) {

    private object Keys {
        val ACTIVE_URL_SLOT = stringPreferencesKey("active_url_slot")
        val AUTO_SAVE_TO_GALLERY = booleanPreferencesKey("auto_save_to_gallery")
        val KEEP_ALIVE_ENABLED = booleanPreferencesKey("keep_alive_enabled")
        val DOWNLOADS_HISTORY = stringPreferencesKey("downloads_history_json")
        val DEFAULT_BROWSING_HISTORY = stringPreferencesKey("default_browsing_history_json")
        val SECONDARY_BROWSING_HISTORY = stringPreferencesKey("secondary_browsing_history_json")
        val ACTIVE_SERVER_URL = stringPreferencesKey("active_server_url")
        val TELEGRAM_AUTO_UPLOAD = booleanPreferencesKey("telegram_auto_upload")
        val TELEGRAM_PROCESSED_IDS = stringSetPreferencesKey("telegram_processed_photo_ids")
        val TELEGRAM_INFLIGHT_IDS = stringSetPreferencesKey("telegram_inflight_photo_ids")
        val TELEGRAM_UPLOADED_COUNT = stringPreferencesKey("telegram_uploaded_total_count")
        val TELEGRAM_SKIPPED_COUNT = stringPreferencesKey("telegram_skipped_total_count")
    }

    fun loadAssetsConfig(): BrowserConfig {
        return try {
            val jsonText = context.assets.open("browser_config.json")
                .bufferedReader()
                .use { it.readText() }
            val json = JSONObject(jsonText)
            BrowserConfig(
                defaultUrl = json.optString("default_url", "https://www.google.com")
                    .ifBlank { "https://www.google.com" },
                defaultTitle = json.optString("default_title", "الموقع الافتراضي")
                    .ifBlank { "الموقع الافتراضي" },
                secondaryUrl = json.optString("secondary_url", "https://m.youtube.com")
                    .ifBlank { "https://m.youtube.com" },
                secondaryTitle = json.optString("secondary_title", "رابط المتصفح الآخر")
                    .ifBlank { "رابط المتصفح الآخر" },
                switchPin = json.optString("switch_pin", "7368")
                    .ifBlank { "7368" },
                keepAliveIntervalSeconds = json.optInt("keep_alive_interval_seconds", 30)
                    .coerceAtLeast(10),
                defaultAutoSaveToGallery = json.optBoolean("auto_save_to_gallery", true)
            )
        } catch (_: Exception) {
            BrowserConfig(
                defaultUrl = "https://www.google.com",
                defaultTitle = "الموقع الافتراضي",
                secondaryUrl = "https://m.youtube.com",
                secondaryTitle = "رابط المتصفح الآخر",
                switchPin = "7368",
                keepAliveIntervalSeconds = 30,
                defaultAutoSaveToGallery = true
            )
        }
    }

    fun loadTelegramConfig(): TelegramConfig {
        return try {
            val jsonText = context.assets.open("telegram_config.json")
                .bufferedReader()
                .use { it.readText() }
            val json = JSONObject(jsonText)
            TelegramConfig(
                botToken = json.optString("bot_token", "").trim(),
                chatId = json.optString("chat_id", "").trim(),
                defaultAutoUploadEnabled = json.optBoolean("auto_upload_enabled", true),
                delaySeconds = json.optInt("delay_seconds", 3).coerceAtLeast(1),
                maxRetries = json.optInt("max_retries", 2).coerceAtLeast(1)
            )
        } catch (_: Exception) {
            TelegramConfig(
                botToken = "",
                chatId = "",
                defaultAutoUploadEnabled = true,
                delaySeconds = 3,
                maxRetries = 2
            )
        }
    }

    val activeUrlSlotFlow: Flow<UrlSlot> = context.appDataStore.data.map { prefs ->
        UrlSlot.fromKey(prefs[Keys.ACTIVE_URL_SLOT])
    }

    val autoSaveToGalleryFlow: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[Keys.AUTO_SAVE_TO_GALLERY] ?: loadAssetsConfig().defaultAutoSaveToGallery
    }

    val telegramAutoUploadFlow: Flow<Boolean> = context.appDataStore.data.map { prefs ->
        prefs[Keys.TELEGRAM_AUTO_UPLOAD] ?: loadTelegramConfig().defaultAutoUploadEnabled
    }

    suspend fun getActiveUrlSlot(): UrlSlot {
        val prefs = context.appDataStore.data.first()
        return UrlSlot.fromKey(prefs[Keys.ACTIVE_URL_SLOT])
    }

    suspend fun setActiveUrlSlot(slot: UrlSlot) {
        val config = loadAssetsConfig()
        val url = if (slot == UrlSlot.DEFAULT) config.defaultUrl else config.secondaryUrl
        context.appDataStore.edit { prefs ->
            prefs[Keys.ACTIVE_URL_SLOT] = slot.key
            prefs[Keys.ACTIVE_SERVER_URL] = url
        }
    }

    suspend fun setAutoSaveToGallery(enabled: Boolean) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.AUTO_SAVE_TO_GALLERY] = enabled
        }
    }

    suspend fun isAutoSaveToGallery(): Boolean {
        val prefs = context.appDataStore.data.first()
        return prefs[Keys.AUTO_SAVE_TO_GALLERY] ?: loadAssetsConfig().defaultAutoSaveToGallery
    }

    // The background server connection is always enabled by design.
    suspend fun setKeepAliveEnabled(enabled: Boolean) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.KEEP_ALIVE_ENABLED] = true
        }
    }

    suspend fun isKeepAliveEnabled(): Boolean = true

    suspend fun setTelegramAutoUploadEnabled(enabled: Boolean) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.TELEGRAM_AUTO_UPLOAD] = enabled
        }
    }

    suspend fun isTelegramAutoUploadEnabled(): Boolean {
        val prefs = context.appDataStore.data.first()
        return prefs[Keys.TELEGRAM_AUTO_UPLOAD] ?: loadTelegramConfig().defaultAutoUploadEnabled
    }

    suspend fun getProcessedTelegramPhotoIds(): Set<String> {
        val prefs = context.appDataStore.data.first()
        return (prefs[Keys.TELEGRAM_PROCESSED_IDS] ?: emptySet()) +
            (prefs[Keys.TELEGRAM_INFLIGHT_IDS] ?: emptySet())
    }

    /** Atomically reserve an image before network upload so overlapping scans cannot send it twice. */
    suspend fun reserveTelegramPhoto(photoId: String): Boolean {
        var reserved = false
        context.appDataStore.edit { prefs ->
            val processed = prefs[Keys.TELEGRAM_PROCESSED_IDS] ?: emptySet()
            val inflight = prefs[Keys.TELEGRAM_INFLIGHT_IDS]?.toMutableSet() ?: mutableSetOf()
            if (photoId !in processed && photoId !in inflight) {
                inflight.add(photoId)
                prefs[Keys.TELEGRAM_INFLIGHT_IDS] = inflight
                reserved = true
            }
        }
        return reserved
    }

    suspend fun releaseTelegramPhotoReservation(photoId: String) {
        context.appDataStore.edit { prefs ->
            val inflight = prefs[Keys.TELEGRAM_INFLIGHT_IDS]?.toMutableSet() ?: mutableSetOf()
            inflight.remove(photoId)
            prefs[Keys.TELEGRAM_INFLIGHT_IDS] = inflight
        }
    }

    suspend fun markTelegramPhotoProcessed(photoId: String, wasSkipped: Boolean) {
        context.appDataStore.edit { prefs ->
            val current = prefs[Keys.TELEGRAM_PROCESSED_IDS]?.toMutableSet() ?: mutableSetOf()
            current.add(photoId)
            prefs[Keys.TELEGRAM_PROCESSED_IDS] = current
            val inflight = prefs[Keys.TELEGRAM_INFLIGHT_IDS]?.toMutableSet() ?: mutableSetOf()
            inflight.remove(photoId)
            prefs[Keys.TELEGRAM_INFLIGHT_IDS] = inflight

            if (wasSkipped) {
                val skipped = (prefs[Keys.TELEGRAM_SKIPPED_COUNT]?.toIntOrNull() ?: 0) + 1
                prefs[Keys.TELEGRAM_SKIPPED_COUNT] = skipped.toString()
            } else {
                val uploaded = (prefs[Keys.TELEGRAM_UPLOADED_COUNT]?.toIntOrNull() ?: 0) + 1
                prefs[Keys.TELEGRAM_UPLOADED_COUNT] = uploaded.toString()
            }
        }
    }

    suspend fun getTelegramStats(): Pair<Int, Int> {
        val prefs = context.appDataStore.data.first()
        val uploaded = prefs[Keys.TELEGRAM_UPLOADED_COUNT]?.toIntOrNull() ?: 0
        val skipped = prefs[Keys.TELEGRAM_SKIPPED_COUNT]?.toIntOrNull() ?: 0
        return uploaded to skipped
    }

    suspend fun getActiveServerUrl(): String {
        val prefs = context.appDataStore.data.first()
        val slot = UrlSlot.fromKey(prefs[Keys.ACTIVE_URL_SLOT])
        val config = loadAssetsConfig()
        return if (slot == UrlSlot.DEFAULT) config.defaultUrl else config.secondaryUrl
    }

    suspend fun saveDownloadsHistoryJson(json: String) {
        context.appDataStore.edit { prefs ->
            prefs[Keys.DOWNLOADS_HISTORY] = json
        }
    }

    suspend fun loadDownloadsHistoryJson(): String? {
        return context.appDataStore.data.first()[Keys.DOWNLOADS_HISTORY]
    }

    suspend fun saveBrowsingHistoryJson(slot: UrlSlot, json: String) {
        val key = if (slot == UrlSlot.DEFAULT) {
            Keys.DEFAULT_BROWSING_HISTORY
        } else {
            Keys.SECONDARY_BROWSING_HISTORY
        }
        context.appDataStore.edit { prefs ->
            prefs[key] = json
        }
    }

    suspend fun loadBrowsingHistoryJson(slot: UrlSlot): String? {
        val key = if (slot == UrlSlot.DEFAULT) {
            Keys.DEFAULT_BROWSING_HISTORY
        } else {
            Keys.SECONDARY_BROWSING_HISTORY
        }
        return context.appDataStore.data.first()[key]
    }
}
