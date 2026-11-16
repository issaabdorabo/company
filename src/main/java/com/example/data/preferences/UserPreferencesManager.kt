package com.example.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.example.data.cloud.DevicePermissions
import com.example.data.cloud.MemberStatus
import com.example.data.cloud.PairingResult
import com.example.ui.theme.AppTheme
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserPreferencesManager(private val context: Context) {
    companion object {
        val KEY_THEME = stringPreferencesKey("app_theme")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_ORG_NAME = stringPreferencesKey("organization_name")
        val KEY_GOOGLE_ACCOUNT = stringPreferencesKey("google_account")
        val KEY_SYNC_ENABLED = booleanPreferencesKey("sync_enabled")
        val KEY_LAST_SYNC = longPreferencesKey("last_sync_time")

        // Multi-device & Cloud Workspace Pairing
        val KEY_DEVICE_ROLE = stringPreferencesKey("device_role") // "MAIN" or "SUB"
        val KEY_INSTALLATION_ID = stringPreferencesKey("device_installation_id")
        val KEY_WORKSPACE_ID = stringPreferencesKey("cloud_workspace_id")
        val KEY_WORKSPACE_CODE = stringPreferencesKey("cloud_workspace_code")
        val KEY_DEVICE_SLOT_ID = stringPreferencesKey("cloud_device_slot_id")
        val KEY_DEVICE_NAME = stringPreferencesKey("cloud_device_name")

        // Granular Sub-Device Permissions (default read-only when SUB)
        val KEY_PERM_ADD_ITEMS = booleanPreferencesKey("perm_can_add_items")
        val KEY_PERM_INWARD = booleanPreferencesKey("perm_can_inward")
        val KEY_PERM_DISBURSE = booleanPreferencesKey("perm_can_disburse")
        val KEY_PERM_EDIT_DELETE = booleanPreferencesKey("perm_can_edit_delete")
        val KEY_IS_KICKED = booleanPreferencesKey("perm_is_kicked")

        // Brute-force lockout for pairing password attempts
        val KEY_PAIRING_FAILED_ATTEMPTS = intPreferencesKey("pairing_failed_attempts")
        val KEY_PAIRING_LOCKED_UNTIL = longPreferencesKey("pairing_locked_until")
    }

    val themeFlow: Flow<AppTheme> = context.dataStore.data.map { prefs ->
        val name = prefs[KEY_THEME] ?: AppTheme.LIGHT.name
        try {
            AppTheme.valueOf(name)
        } catch (_: Exception) {
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
        prefs[KEY_GOOGLE_ACCOUNT] ?: ""
    }

    val syncEnabledFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SYNC_ENABLED] ?: true
    }

    val lastSyncFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNC] ?: 0L
    }

    val deviceRoleFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_ROLE] ?: "MAIN"
    }

    val isMainDeviceFlow: Flow<Boolean> = context.dataStore.data.map { prefs ->
        (prefs[KEY_DEVICE_ROLE] ?: "MAIN") == "MAIN"
    }

    val workspaceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WORKSPACE_ID] ?: ""
    }

    val workspaceCodeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WORKSPACE_CODE] ?: ""
    }

    val deviceSlotIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_SLOT_ID] ?: ""
    }

    val deviceNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: "الجهاز الحالي"
    }

    val devicePermissionsFlow: Flow<DevicePermissions> = context.dataStore.data.map { prefs ->
        val isMain = (prefs[KEY_DEVICE_ROLE] ?: "MAIN") == "MAIN"
        if (isMain) {
            DevicePermissions(
                isMainDevice = true,
                canAddItems = true,
                canInward = true,
                canDisburse = true,
                canEditDelete = true,
                isKicked = false
            )
        } else {
            val isKicked = prefs[KEY_IS_KICKED] ?: false
            if (isKicked) {
                DevicePermissions(
                    isMainDevice = false,
                    canAddItems = false,
                    canInward = false,
                    canDisburse = false,
                    canEditDelete = false,
                    isKicked = true
                )
            } else {
                DevicePermissions(
                    isMainDevice = false,
                    canAddItems = prefs[KEY_PERM_ADD_ITEMS] ?: false,
                    canInward = prefs[KEY_PERM_INWARD] ?: false,
                    canDisburse = prefs[KEY_PERM_DISBURSE] ?: false,
                    canEditDelete = prefs[KEY_PERM_EDIT_DELETE] ?: false,
                    isKicked = false
                )
            }
        }
    }

    val pairingLockedUntilFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_PAIRING_LOCKED_UNTIL] ?: 0L
    }

    suspend fun getOrCreateInstallationId(): String {
        val current = context.dataStore.data.first()[KEY_INSTALLATION_ID]
        if (!current.isNullOrBlank()) return current
        val generated = "inst_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        context.dataStore.edit { prefs ->
            prefs[KEY_INSTALLATION_ID] = generated
        }
        return generated
    }

    suspend fun setTheme(theme: AppTheme) {
        context.dataStore.edit { prefs -> prefs[KEY_THEME] = theme.name }
    }

    suspend fun setUserName(name: String) {
        context.dataStore.edit { prefs -> prefs[KEY_USER_NAME] = name }
    }

    suspend fun setOrgName(name: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ORG_NAME] = name }
    }

    suspend fun setGoogleAccount(account: String) {
        context.dataStore.edit { prefs -> prefs[KEY_GOOGLE_ACCOUNT] = account }
    }

    suspend fun setSyncEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SYNC_ENABLED] = enabled }
    }

    suspend fun setDeviceRole(role: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = role
            if (role == "MAIN") {
                prefs[KEY_IS_KICKED] = false
            }
        }
    }

    suspend fun saveMainWorkspace(workspaceId: String, workspaceCode: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = "MAIN"
            prefs[KEY_WORKSPACE_ID] = workspaceId
            prefs[KEY_WORKSPACE_CODE] = workspaceCode
            prefs[KEY_DEVICE_SLOT_ID] = ""
            prefs[KEY_IS_KICKED] = false
        }
    }

    suspend fun savePairedSubDevice(result: PairingResult) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = "SUB"
            prefs[KEY_WORKSPACE_ID] = result.workspaceId
            prefs[KEY_WORKSPACE_CODE] = result.workspaceCode
            prefs[KEY_DEVICE_SLOT_ID] = result.deviceSlotId
            prefs[KEY_DEVICE_NAME] = result.deviceName
            prefs[KEY_PERM_ADD_ITEMS] = result.permissions.canAddItems
            prefs[KEY_PERM_INWARD] = result.permissions.canInward
            prefs[KEY_PERM_DISBURSE] = result.permissions.canDisburse
            prefs[KEY_PERM_EDIT_DELETE] = result.permissions.canEditDelete
            prefs[KEY_IS_KICKED] = false
            prefs[KEY_PAIRING_FAILED_ATTEMPTS] = 0
            prefs[KEY_PAIRING_LOCKED_UNTIL] = 0L
        }
    }

    suspend fun updateLiveMemberPermissions(status: MemberStatus) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = "SUB"
            prefs[KEY_DEVICE_NAME] = status.deviceName
            prefs[KEY_PERM_ADD_ITEMS] = status.canAddItems && status.isActive && !status.isKicked
            prefs[KEY_PERM_INWARD] = status.canInward && status.isActive && !status.isKicked
            prefs[KEY_PERM_DISBURSE] = status.canDisburse && status.isActive && !status.isKicked
            prefs[KEY_PERM_EDIT_DELETE] = status.canEditDelete && status.isActive && !status.isKicked
            prefs[KEY_IS_KICKED] = status.isKicked || !status.isActive
        }
    }

    suspend fun markKickedLocally() {
        context.dataStore.edit { prefs ->
            prefs[KEY_PERM_ADD_ITEMS] = false
            prefs[KEY_PERM_INWARD] = false
            prefs[KEY_PERM_DISBURSE] = false
            prefs[KEY_PERM_EDIT_DELETE] = false
            prefs[KEY_IS_KICKED] = true
        }
    }

    suspend fun clearWorkspaceLink() {
        context.dataStore.edit { prefs ->
            prefs[KEY_WORKSPACE_ID] = ""
            prefs[KEY_WORKSPACE_CODE] = ""
            prefs[KEY_DEVICE_SLOT_ID] = ""
            prefs[KEY_PERM_ADD_ITEMS] = false
            prefs[KEY_PERM_INWARD] = false
            prefs[KEY_PERM_DISBURSE] = false
            prefs[KEY_PERM_EDIT_DELETE] = false
            prefs[KEY_IS_KICKED] = false
        }
    }

    suspend fun recordPairingFailure(): Long {
        var lockedUntil = 0L
        context.dataStore.edit { prefs ->
            val attempts = (prefs[KEY_PAIRING_FAILED_ATTEMPTS] ?: 0) + 1
            prefs[KEY_PAIRING_FAILED_ATTEMPTS] = attempts
            if (attempts >= 5) {
                lockedUntil = System.currentTimeMillis() + 60_000L
                prefs[KEY_PAIRING_LOCKED_UNTIL] = lockedUntil
                prefs[KEY_PAIRING_FAILED_ATTEMPTS] = 0
            }
        }
        return lockedUntil
    }

    suspend fun resetPairingFailures() {
        context.dataStore.edit { prefs ->
            prefs[KEY_PAIRING_FAILED_ATTEMPTS] = 0
            prefs[KEY_PAIRING_LOCKED_UNTIL] = 0L
        }
    }

    suspend fun updateLastSync() {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC] = System.currentTimeMillis()
        }
    }
}
