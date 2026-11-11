package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserPreferencesManager(private val context: Context) {
    companion object {
        val KEY_THEME = stringPreferencesKey("app_theme")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_ORG_NAME = stringPreferencesKey("organization_name")
        val KEY_GOOGLE_ACCOUNT = stringPreferencesKey("google_account")
        val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val KEY_LAST_SYNC = longPreferencesKey("last_sync_time")
        val KEY_DEVICE_ROLE = stringPreferencesKey("device_role") // "MAIN" or "SUB"
    }

    val themeFlow: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_THEME] ?: AppTheme.LIGHT.name
        try {
            AppTheme.valueOf(name)
        } catch (e: Exception) {
            AppTheme.LIGHT
        }
    }

    val userNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_USER_NAME] ?: "عيسى عبدربه"
    }

    val orgNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ORG_NAME] ?: "المؤسسة العامة للتوريد والمخازن"
    }

    val googleAccountFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_GOOGLE_ACCOUNT] ?: "issanew2026@gmail.com"
    }

    val syncEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SYNC_ENABLED] ?: true
    }

    val lastSyncFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNC] ?: System.currentTimeMillis()
    }

    val deviceRoleFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ROLE] ?: "MAIN"
    }

    val isMainDeviceFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        (prefs[KEY_DEVICE_ROLE] ?: "MAIN") == "MAIN"
    }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { prefs ->
            prefs[KEY_THEME] = theme.name
        }
    }

    suspend fun setUserName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_USER_NAME] = name
        }
    }

    suspend fun setOrgName(name: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_ORG_NAME] = name
        }
    }

    suspend fun setGoogleAccount(account: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_GOOGLE_ACCOUNT] = account
        }
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_SYNC_ENABLED] = enabled
        }
    }

    suspend fun setDeviceRole(role: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = role
        }
    }

    suspend fun updateLastSync() {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC] = System.currentTimeMillis()
        }
    }
}
