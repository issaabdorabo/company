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
import com.example.data.cloud.DeviceRole
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.WarehousePermission
import com.example.ui.theme.AppTheme
import com.example.util.SecurityUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_settings")

class UserPreferencesManager(private val context: Context) {
    companion object {
        val KEY_THEME = stringPreferencesKey("app_theme")
        val KEY_USER_NAME = stringPreferencesKey("user_name")
        val KEY_ORG_NAME = stringPreferencesKey("organization_name")

        // Direct Multi-Device Sync & Pairing Keys (No Google Auth / No Fingerprint)
        val KEY_INSTALLATION_ID = stringPreferencesKey("installation_id")
        val KEY_DEVICE_ROLE = stringPreferencesKey("device_role")
        val KEY_WORKSPACE_CODE = stringPreferencesKey("workspace_code")
        val KEY_MAIN_HOST_URL = stringPreferencesKey("main_host_url")
        val KEY_SUB_SESSION_TOKEN = stringPreferencesKey("sub_session_token")
        val KEY_DEVICE_SLOT_ID = stringPreferencesKey("device_slot_id")
        val KEY_DEVICE_NAME = stringPreferencesKey("device_name")
        val KEY_LINKED_DEVICES_JSON = stringPreferencesKey("linked_devices_json")
        val KEY_SYNC_REVISION = longPreferencesKey("sync_revision")
        val KEY_LAST_SYNC_AT = longPreferencesKey("last_sync_at")
        val KEY_LAST_APPLIED_REVISION = longPreferencesKey("last_applied_revision")

        val KEY_PERM_ADD_ITEMS = booleanPreferencesKey("perm_add_items")
        val KEY_PERM_INWARD = booleanPreferencesKey("perm_inward")
        val KEY_PERM_DISBURSE = booleanPreferencesKey("perm_disburse")
        val KEY_PERM_EDIT_DELETE = booleanPreferencesKey("perm_edit_delete")
        val KEY_PERM_EDIT_INVENTORY = booleanPreferencesKey("perm_edit_inventory")
        val KEY_PERM_DELETE_ITEM = booleanPreferencesKey("perm_delete_item")
        val KEY_PERM_EDIT_ISSUE = booleanPreferencesKey("perm_edit_issue_voucher")
        val KEY_PERM_DELETE_ISSUE = booleanPreferencesKey("perm_delete_issue_voucher")
        val KEY_PERM_EDIT_SUPPLY = booleanPreferencesKey("perm_edit_supply_voucher")
        val KEY_PERM_DELETE_SUPPLY = booleanPreferencesKey("perm_delete_supply_voucher")
        val KEY_PERM_EDIT_CATALOG_ITEMS = booleanPreferencesKey("perm_edit_catalog_items")
        val KEY_PERM_BACKUP = booleanPreferencesKey("perm_backup")
        val KEY_CAN_VIEW_ALL_WAREHOUSES = booleanPreferencesKey("can_view_all_warehouses")
        val KEY_WAREHOUSE_PERMISSIONS_JSON = stringPreferencesKey("warehouse_permissions_json")
        val KEY_IS_KICKED = booleanPreferencesKey("is_kicked")

        val KEY_PAIRING_FAILED_ATTEMPTS = intPreferencesKey("pairing_failed_attempts")
        val KEY_PAIRING_LOCKED_UNTIL = longPreferencesKey("pairing_locked_until")

        // Cloud Backup Status & Verified Google Email Keys
        val KEY_VERIFIED_GOOGLE_EMAIL = stringPreferencesKey("verified_google_email")
        val KEY_LAST_CLOUD_BACKUP_AT = longPreferencesKey("last_cloud_backup_at")
        val KEY_LAST_CLOUD_BACKUP_EMAIL = stringPreferencesKey("last_cloud_backup_email")
        val KEY_LAST_CLOUD_BACKUP_NAME = stringPreferencesKey("last_cloud_backup_name")
        val KEY_SAVED_CLOUD_EMAILS_JSON = stringPreferencesKey("saved_cloud_emails_json")

        // Supabase collaboration identity (separate from the local Wi-Fi pairing workspace).
        val KEY_CLOUD_WORKSPACE_ID = stringPreferencesKey("cloud_workspace_id")
        val KEY_CLOUD_WORKSPACE_NAME = stringPreferencesKey("cloud_workspace_name")
        val KEY_CLOUD_DEVICE_ID = stringPreferencesKey("cloud_device_id")
        val KEY_CLOUD_SNAPSHOT_SIGNATURE = stringPreferencesKey("cloud_snapshot_signature")
        val KEY_SEEN_CLOUD_NOTIFICATIONS_JSON = stringPreferencesKey("seen_cloud_notifications_json")
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

    val deviceRoleFlow: Flow<DeviceRole> = context.dataStore.data.map { prefs ->
        val raw = prefs[KEY_DEVICE_ROLE] ?: DeviceRole.MAIN.name
        try {
            DeviceRole.valueOf(raw)
        } catch (_: Exception) {
            DeviceRole.MAIN
        }
    }

    val isMainDeviceFlow: Flow<Boolean> = deviceRoleFlow.map { it == DeviceRole.MAIN }

    val workspaceCodeFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WORKSPACE_CODE] ?: ""
    }

    val mainHostUrlFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_MAIN_HOST_URL] ?: ""
    }

    val subSessionTokenFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_SUB_SESSION_TOKEN] ?: ""
    }

    val deviceSlotIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_SLOT_ID] ?: ""
    }

    val deviceNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_DEVICE_NAME] ?: ""
    }

    val syncRevisionFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_SYNC_REVISION] ?: 1L
    }

    val lastSyncAtFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_SYNC_AT] ?: 0L
    }

    val lastAppliedRevisionFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_APPLIED_REVISION] ?: 0L
    }

    val pairingLockedUntilFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_PAIRING_LOCKED_UNTIL] ?: 0L
    }

    val linkedSubDevicesFlow: Flow<List<LinkedSubDevice>> = context.dataStore.data.map { prefs ->
        parseLinkedSubDevices(prefs[KEY_LINKED_DEVICES_JSON] ?: "[]")
    }

    val verifiedGoogleEmailFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_VERIFIED_GOOGLE_EMAIL] ?: ""
    }

    val lastCloudBackupAtFlow: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_CLOUD_BACKUP_AT] ?: 0L
    }

    val lastCloudBackupEmailFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_CLOUD_BACKUP_EMAIL] ?: ""
    }

    val lastCloudBackupNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_LAST_CLOUD_BACKUP_NAME] ?: ""
    }

    val savedCloudEmailsFlow: Flow<List<String>> = context.dataStore.data.map { prefs ->
        parseSavedEmails(prefs[KEY_SAVED_CLOUD_EMAILS_JSON] ?: "[]")
    }

    val cloudWorkspaceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_WORKSPACE_ID] ?: ""
    }

    val cloudWorkspaceNameFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_WORKSPACE_NAME] ?: ""
    }

    val cloudDeviceIdFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_DEVICE_ID] ?: ""
    }

    val cloudSnapshotSignatureFlow: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CLOUD_SNAPSHOT_SIGNATURE] ?: ""
    }

    val devicePermissionsFlow: Flow<DevicePermissions> = context.dataStore.data.map { prefs ->
        val role = try {
            DeviceRole.valueOf(prefs[KEY_DEVICE_ROLE] ?: DeviceRole.MAIN.name)
        } catch (_: Exception) {
            DeviceRole.MAIN
        }
        if (role == DeviceRole.MAIN) {
            DevicePermissions(
                isMainDevice = true,
                canAddItems = true,
                canInward = true,
                canDisburse = true,
                canEditDelete = true,
                isKicked = false,
                canBackup = true,
                canViewAllWarehouses = true
            )
        } else {
            val kicked = prefs[KEY_IS_KICKED] ?: false
            if (kicked) {
                DevicePermissions(
                    isMainDevice = false,
                    canAddItems = false,
                    canInward = false,
                    canDisburse = false,
                    canEditDelete = false,
                    isKicked = true,
                    canBackup = false,
                    canViewAllWarehouses = false,
                    warehousePermissions = emptyList()
                )
            } else {
                DevicePermissions(
                    isMainDevice = false,
                    canAddItems = prefs[KEY_PERM_ADD_ITEMS] ?: false,
                    canInward = prefs[KEY_PERM_INWARD] ?: false,
                    canDisburse = prefs[KEY_PERM_DISBURSE] ?: false,
                    canEditDelete = prefs[KEY_PERM_EDIT_DELETE] ?: false,
                    canEditInventory = prefs[KEY_PERM_EDIT_INVENTORY] ?: (prefs[KEY_PERM_EDIT_DELETE] ?: false),
                    canDeleteItem = prefs[KEY_PERM_DELETE_ITEM] ?: (prefs[KEY_PERM_EDIT_DELETE] ?: false),
                    canEditIssueVoucher = prefs[KEY_PERM_EDIT_ISSUE] ?: (prefs[KEY_PERM_EDIT_DELETE] ?: false),
                    canDeleteIssueVoucher = prefs[KEY_PERM_DELETE_ISSUE] ?: (prefs[KEY_PERM_EDIT_DELETE] ?: false),
                    canEditSupplyVoucher = prefs[KEY_PERM_EDIT_SUPPLY] ?: (prefs[KEY_PERM_EDIT_DELETE] ?: false),
                    canDeleteSupplyVoucher = prefs[KEY_PERM_DELETE_SUPPLY] ?: (prefs[KEY_PERM_EDIT_DELETE] ?: false),
                    canEditCatalogItems = prefs[KEY_PERM_EDIT_CATALOG_ITEMS] ?: false,
                    isKicked = false,
                    canBackup = prefs[KEY_PERM_BACKUP] ?: false,
                    canViewAllWarehouses = prefs[KEY_CAN_VIEW_ALL_WAREHOUSES] ?: false,
                    warehousePermissions = parseWarehousePermissions(prefs[KEY_WAREHOUSE_PERMISSIONS_JSON] ?: "[]")
                )
            }
        }
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

    suspend fun setVerifiedGoogleEmail(email: String) {
        val clean = email.trim()
        if (clean.isBlank()) return
        context.dataStore.edit { prefs ->
            prefs[KEY_VERIFIED_GOOGLE_EMAIL] = clean
            prefs[KEY_LAST_CLOUD_BACKUP_EMAIL] = clean
            val existing = parseSavedEmails(prefs[KEY_SAVED_CLOUD_EMAILS_JSON] ?: "[]")
            val updated = listOf(clean) + existing.filterNot { it.equals(clean, ignoreCase = true) }
            prefs[KEY_SAVED_CLOUD_EMAILS_JSON] = encodeSavedEmails(updated.take(10))
        }
    }

    suspend fun clearVerifiedGoogleEmail() {
        context.dataStore.edit { prefs ->
            prefs[KEY_VERIFIED_GOOGLE_EMAIL] = ""
        }
    }

    suspend fun saveLastCloudEmail(email: String) {
        val clean = email.trim()
        if (clean.isBlank()) return
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_CLOUD_BACKUP_EMAIL] = clean
            val existing = parseSavedEmails(prefs[KEY_SAVED_CLOUD_EMAILS_JSON] ?: "[]")
            val updated = listOf(clean) + existing.filterNot { it.equals(clean, ignoreCase = true) }
            prefs[KEY_SAVED_CLOUD_EMAILS_JSON] = encodeSavedEmails(updated.take(10))
        }
    }

    suspend fun rememberSavedCloudEmail(email: String) {
        val clean = email.trim()
        if (clean.isBlank()) return
        context.dataStore.edit { prefs ->
            val existing = parseSavedEmails(prefs[KEY_SAVED_CLOUD_EMAILS_JSON] ?: "[]")
            val updated = listOf(clean) + existing.filterNot { it.equals(clean, ignoreCase = true) }
            prefs[KEY_SAVED_CLOUD_EMAILS_JSON] = encodeSavedEmails(updated.take(10))
        }
    }

    suspend fun recordCloudBackupSuccess(
        email: String,
        backupName: String,
        timestampMillis: Long = System.currentTimeMillis()
    ) {
        context.dataStore.edit { prefs ->
            val cleanEmail = email.trim()
            if (cleanEmail.isNotBlank()) {
                prefs[KEY_LAST_CLOUD_BACKUP_EMAIL] = cleanEmail
                val existing = parseSavedEmails(prefs[KEY_SAVED_CLOUD_EMAILS_JSON] ?: "[]")
                val updated = listOf(cleanEmail) + existing.filterNot { it.equals(cleanEmail, ignoreCase = true) }
                prefs[KEY_SAVED_CLOUD_EMAILS_JSON] = encodeSavedEmails(updated.take(10))
            }
            if (backupName.isNotBlank()) {
                prefs[KEY_LAST_CLOUD_BACKUP_NAME] = backupName.trim()
            }
            if (timestampMillis > 0L) {
                prefs[KEY_LAST_CLOUD_BACKUP_AT] = timestampMillis
            }
        }
    }

    suspend fun saveCloudWorkspace(workspaceId: String, workspaceName: String) {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLOUD_WORKSPACE_ID] = workspaceId.trim()
            prefs[KEY_CLOUD_WORKSPACE_NAME] = workspaceName.trim()
            // A device record is scoped to its workspace. Register it again after switching.
            prefs[KEY_CLOUD_DEVICE_ID] = ""
        }
    }

    suspend fun saveCloudDeviceId(deviceId: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOUD_DEVICE_ID] = deviceId.trim() }
    }

    suspend fun clearCloudWorkspace() {
        context.dataStore.edit { prefs ->
            prefs[KEY_CLOUD_WORKSPACE_ID] = ""
            prefs[KEY_CLOUD_WORKSPACE_NAME] = ""
            prefs[KEY_CLOUD_DEVICE_ID] = ""
            prefs[KEY_CLOUD_SNAPSHOT_SIGNATURE] = ""
        }
    }

    suspend fun saveCloudSnapshotSignature(signature: String) {
        context.dataStore.edit { prefs -> prefs[KEY_CLOUD_SNAPSHOT_SIGNATURE] = signature }
    }

    suspend fun hasSeenCloudNotification(notificationId: String): Boolean {
        if (notificationId.isBlank()) return true
        val raw = context.dataStore.data.first()[KEY_SEEN_CLOUD_NOTIFICATIONS_JSON] ?: "[]"
        val ids = runCatching { JSONArray(raw) }.getOrNull() ?: return false
        return (0 until ids.length()).any { ids.optString(it) == notificationId }
    }

    suspend fun markCloudNotificationSeen(notificationId: String) {
        if (notificationId.isBlank()) return
        context.dataStore.edit { prefs ->
            val raw = prefs[KEY_SEEN_CLOUD_NOTIFICATIONS_JSON] ?: "[]"
            val existing = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
            val ids = LinkedHashSet<String>()
            for (index in 0 until existing.length()) {
                existing.optString(index).takeIf { it.isNotBlank() }?.let(ids::add)
            }
            ids.add(notificationId)
            val trimmed = ids.toList().takeLast(200)
            prefs[KEY_SEEN_CLOUD_NOTIFICATIONS_JSON] = JSONArray(trimmed).toString()
        }
    }

    suspend fun getOrCreateInstallationId(): String {
        val current = context.dataStore.data.first()[KEY_INSTALLATION_ID]
        if (!current.isNullOrBlank()) return current
        val created = UUID.randomUUID().toString()
        context.dataStore.edit { prefs -> prefs[KEY_INSTALLATION_ID] = created }
        return created
    }

    suspend fun ensureWorkspaceCode(): String {
        val current = context.dataStore.data.first()[KEY_WORKSPACE_CODE]
        if (!current.isNullOrBlank()) return current
        val generated = SecurityUtils.generateWorkspaceCode()
        context.dataStore.edit { prefs -> prefs[KEY_WORKSPACE_CODE] = generated }
        return generated
    }

    suspend fun regenerateWorkspaceCode(): String {
        val generated = SecurityUtils.generateWorkspaceCode()
        context.dataStore.edit { prefs -> prefs[KEY_WORKSPACE_CODE] = generated }
        return generated
    }

    suspend fun setDeviceRole(role: DeviceRole) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = role.name
            if (role == DeviceRole.MAIN) {
                prefs[KEY_IS_KICKED] = false
                if (prefs[KEY_WORKSPACE_CODE].isNullOrBlank()) {
                    prefs[KEY_WORKSPACE_CODE] = SecurityUtils.generateWorkspaceCode()
                }
            }
        }
    }

    suspend fun savePairedSubDevice(
        hostUrl: String,
        workspaceCode: String,
        deviceSlotId: String,
        deviceName: String,
        sessionToken: String,
        permissions: DevicePermissions,
        appliedRevision: Long
    ) {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = DeviceRole.SUB.name
            prefs[KEY_MAIN_HOST_URL] = hostUrl
            prefs[KEY_WORKSPACE_CODE] = workspaceCode
            prefs[KEY_DEVICE_SLOT_ID] = deviceSlotId
            prefs[KEY_DEVICE_NAME] = deviceName
            prefs[KEY_SUB_SESSION_TOKEN] = sessionToken
            prefs[KEY_PERM_ADD_ITEMS] = permissions.canAddItems
            prefs[KEY_PERM_INWARD] = permissions.canInward
            prefs[KEY_PERM_DISBURSE] = permissions.canDisburse
            prefs[KEY_PERM_EDIT_DELETE] = permissions.canEditDelete
            prefs[KEY_PERM_EDIT_INVENTORY] = permissions.canEditInventory
            prefs[KEY_PERM_DELETE_ITEM] = permissions.canDeleteItem
            prefs[KEY_PERM_EDIT_ISSUE] = permissions.canEditIssueVoucher
            prefs[KEY_PERM_DELETE_ISSUE] = permissions.canDeleteIssueVoucher
            prefs[KEY_PERM_EDIT_SUPPLY] = permissions.canEditSupplyVoucher
            prefs[KEY_PERM_DELETE_SUPPLY] = permissions.canDeleteSupplyVoucher
            prefs[KEY_PERM_EDIT_CATALOG_ITEMS] = permissions.canEditCatalogItems
            prefs[KEY_PERM_BACKUP] = permissions.canBackup
            prefs[KEY_CAN_VIEW_ALL_WAREHOUSES] = permissions.canViewAllWarehouses
            prefs[KEY_WAREHOUSE_PERMISSIONS_JSON] = encodeWarehousePermissions(permissions.warehousePermissions)
            prefs[KEY_IS_KICKED] = false
            prefs[KEY_LAST_SYNC_AT] = System.currentTimeMillis()
            prefs[KEY_LAST_APPLIED_REVISION] = appliedRevision
            prefs[KEY_PAIRING_FAILED_ATTEMPTS] = 0
            prefs[KEY_PAIRING_LOCKED_UNTIL] = 0L
        }
    }

    suspend fun updateSubDevicePermissions(permissions: DevicePermissions) {
        context.dataStore.edit { prefs ->
            prefs[KEY_PERM_ADD_ITEMS] = permissions.canAddItems
            prefs[KEY_PERM_INWARD] = permissions.canInward
            prefs[KEY_PERM_DISBURSE] = permissions.canDisburse
            prefs[KEY_PERM_EDIT_DELETE] = permissions.canEditDelete
            prefs[KEY_PERM_EDIT_INVENTORY] = permissions.canEditInventory
            prefs[KEY_PERM_DELETE_ITEM] = permissions.canDeleteItem
            prefs[KEY_PERM_EDIT_ISSUE] = permissions.canEditIssueVoucher
            prefs[KEY_PERM_DELETE_ISSUE] = permissions.canDeleteIssueVoucher
            prefs[KEY_PERM_EDIT_SUPPLY] = permissions.canEditSupplyVoucher
            prefs[KEY_PERM_DELETE_SUPPLY] = permissions.canDeleteSupplyVoucher
            prefs[KEY_PERM_EDIT_CATALOG_ITEMS] = permissions.canEditCatalogItems
            prefs[KEY_PERM_BACKUP] = permissions.canBackup
            prefs[KEY_CAN_VIEW_ALL_WAREHOUSES] = permissions.canViewAllWarehouses
            prefs[KEY_WAREHOUSE_PERMISSIONS_JSON] = encodeWarehousePermissions(permissions.warehousePermissions)
            prefs[KEY_IS_KICKED] = permissions.isKicked
        }
    }

    suspend fun markSubDeviceKicked() {
        context.dataStore.edit { prefs ->
            prefs[KEY_IS_KICKED] = true
            prefs[KEY_SUB_SESSION_TOKEN] = ""
            prefs[KEY_PERM_ADD_ITEMS] = false
            prefs[KEY_PERM_INWARD] = false
            prefs[KEY_PERM_DISBURSE] = false
            prefs[KEY_PERM_EDIT_DELETE] = false
            prefs[KEY_PERM_EDIT_CATALOG_ITEMS] = false
            prefs[KEY_PERM_BACKUP] = false
            prefs[KEY_CAN_VIEW_ALL_WAREHOUSES] = false
            prefs[KEY_WAREHOUSE_PERMISSIONS_JSON] = "[]"
        }
    }

    suspend fun clearSubPairingAndSwitchToMain() {
        context.dataStore.edit { prefs ->
            prefs[KEY_DEVICE_ROLE] = DeviceRole.MAIN.name
            prefs[KEY_MAIN_HOST_URL] = ""
            prefs[KEY_SUB_SESSION_TOKEN] = ""
            prefs[KEY_DEVICE_SLOT_ID] = ""
            prefs[KEY_DEVICE_NAME] = ""
            prefs[KEY_IS_KICKED] = false
            prefs[KEY_LAST_APPLIED_REVISION] = 0L
            if (prefs[KEY_WORKSPACE_CODE].isNullOrBlank()) {
                prefs[KEY_WORKSPACE_CODE] = SecurityUtils.generateWorkspaceCode()
            }
        }
    }

    suspend fun recordPairingFailure(): Pair<Int, Long> {
        var attemptsResult = 0
        var lockedUntilResult = 0L
        context.dataStore.edit { prefs ->
            val next = (prefs[KEY_PAIRING_FAILED_ATTEMPTS] ?: 0) + 1
            attemptsResult = next
            prefs[KEY_PAIRING_FAILED_ATTEMPTS] = next
            if (next >= 5) {
                lockedUntilResult = System.currentTimeMillis() + 60_000L
                prefs[KEY_PAIRING_LOCKED_UNTIL] = lockedUntilResult
                prefs[KEY_PAIRING_FAILED_ATTEMPTS] = 0
            }
        }
        return attemptsResult to lockedUntilResult
    }

    suspend fun resetPairingFailures() {
        context.dataStore.edit { prefs ->
            prefs[KEY_PAIRING_FAILED_ATTEMPTS] = 0
            prefs[KEY_PAIRING_LOCKED_UNTIL] = 0L
        }
    }

    suspend fun incrementSyncRevision(): Long {
        var nextRev = 1L
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_SYNC_REVISION] ?: 1L
            nextRev = current + 1L
            prefs[KEY_SYNC_REVISION] = nextRev
            prefs[KEY_LAST_SYNC_AT] = System.currentTimeMillis()
        }
        return nextRev
    }

    suspend fun updateLastSync(revision: Long) {
        context.dataStore.edit { prefs ->
            prefs[KEY_LAST_SYNC_AT] = System.currentTimeMillis()
            prefs[KEY_LAST_APPLIED_REVISION] = revision
        }
    }

    /**
     * Export linked-device identities and permissions without preserving active session tokens.
     * Password hashes remain salted; every restored device must pair again.
     */
    suspend fun exportLinkedDevicesBackupJson(): String {
        val raw = context.dataStore.data.first()[KEY_LINKED_DEVICES_JSON] ?: "[]"
        val devices = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index) ?: continue
            device.put("sessionToken", UUID.randomUUID().toString())
            device.put("boundInstallationId", "")
            device.put("isClaimed", false)
        }
        return devices.toString()
    }

    suspend fun restoreLinkedDevicesBackupJson(rawJson: String) {
        val devices = JSONArray(rawJson)
        for (index in 0 until devices.length()) {
            val device = devices.optJSONObject(index)
                ?: error("بيانات جهاز مرتبطة غير صالحة في النسخة الاحتياطية")
            require(device.optString("id").isNotBlank()) { "معرّف جهاز غير صالح في النسخة الاحتياطية" }
            require(device.optString("deviceName").isNotBlank()) { "اسم جهاز غير صالح في النسخة الاحتياطية" }
            device.put("sessionToken", UUID.randomUUID().toString())
            device.put("boundInstallationId", "")
            device.put("isClaimed", false)
        }
        context.dataStore.edit { prefs ->
            prefs[KEY_LINKED_DEVICES_JSON] = devices.toString()
            prefs[KEY_SYNC_REVISION] = (prefs[KEY_SYNC_REVISION] ?: 1L) + 1L
        }
    }

    suspend fun saveLinkedSubDevices(devices: List<LinkedSubDevice>) {
        val arr = JSONArray()
        devices.forEach { d ->
            arr.put(
                JSONObject().apply {
                    put("id", d.id)
                    put("deviceName", d.deviceName)
                    put("passwordSalt", d.passwordSalt)
                    put("passwordHash", d.passwordHash)
                    put("sessionToken", d.sessionToken)
                    put("boundInstallationId", d.boundInstallationId)
                    put("isClaimed", d.isClaimed)
                    put("isKicked", d.isKicked)
                    put("wasUnlinked", d.wasUnlinked)
                    put("canAddItems", d.canAddItems)
                    put("canInward", d.canInward)
                    put("canDisburse", d.canDisburse)
                    put("canEditDelete", d.canEditDelete)
                    put("canEditInventory", d.canEditInventory)
                    put("canDeleteItem", d.canDeleteItem)
                    put("canEditIssueVoucher", d.canEditIssueVoucher)
                    put("canDeleteIssueVoucher", d.canDeleteIssueVoucher)
                    put("canEditSupplyVoucher", d.canEditSupplyVoucher)
                    put("canDeleteSupplyVoucher", d.canDeleteSupplyVoucher)
                    put("canEditCatalogItems", d.canEditCatalogItems)
                    put("canBackup", d.canBackup)
                    put("canViewAllWarehouses", d.canViewAllWarehouses)
                    put("warehousePermissions", JSONArray().apply {
                        d.warehousePermissions.forEach { grant ->
                            put(JSONObject().apply {
                                put("warehouseId", grant.warehouseId)
                                put("canView", grant.canView)
                                put("canInward", grant.canInward)
                                put("canDisburse", grant.canDisburse)
                                put("canTransfer", grant.canTransfer)
                                put("canAdjust", grant.canAdjust)
                                put("canReports", grant.canReports)
                                put("canEdit", grant.canEdit)
                                put("canEditInventory", grant.canEditInventory)
                                put("canDeleteItem", grant.canDeleteItem)
                            })
                        }
                    })
                    put("createdAt", d.createdAt)
                    put("updatedAt", d.updatedAt)
                    put("lastSeenAt", d.lastSeenAt)
                }
            )
        }
        context.dataStore.edit { prefs ->
            prefs[KEY_LINKED_DEVICES_JSON] = arr.toString()
        }
    }

    suspend fun getLinkedSubDevicesSnapshot(): List<LinkedSubDevice> {
        val json = context.dataStore.data.first()[KEY_LINKED_DEVICES_JSON] ?: "[]"
        return parseLinkedSubDevices(json)
    }

    private fun parseLinkedSubDevices(json: String): List<LinkedSubDevice> {
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    add(
                        LinkedSubDevice(
                            id = obj.optString("id"),
                            deviceName = obj.optString("deviceName"),
                            passwordSalt = obj.optString("passwordSalt"),
                            passwordHash = obj.optString("passwordHash"),
                            sessionToken = obj.optString("sessionToken"),
                            boundInstallationId = obj.optString("boundInstallationId"),
                            isClaimed = obj.optBoolean("isClaimed", false),
                            isKicked = obj.optBoolean("isKicked", false),
                            wasUnlinked = obj.optBoolean("wasUnlinked", false),
                            canAddItems = obj.optBoolean("canAddItems", false),
                            canInward = obj.optBoolean("canInward", false),
                            canDisburse = obj.optBoolean("canDisburse", false),
                            canEditDelete = obj.optBoolean("canEditDelete", false),
                            canEditInventory = obj.optBoolean("canEditInventory", obj.optBoolean("canEditDelete", false)),
                            canDeleteItem = obj.optBoolean("canDeleteItem", obj.optBoolean("canEditDelete", false)),
                            canEditIssueVoucher = obj.optBoolean("canEditIssueVoucher", obj.optBoolean("canEditDelete", false)),
                            canDeleteIssueVoucher = obj.optBoolean("canDeleteIssueVoucher", obj.optBoolean("canEditDelete", false)),
                            canEditSupplyVoucher = obj.optBoolean("canEditSupplyVoucher", obj.optBoolean("canEditDelete", false)),
                            canDeleteSupplyVoucher = obj.optBoolean("canDeleteSupplyVoucher", obj.optBoolean("canEditDelete", false)),
                            canEditCatalogItems = obj.optBoolean("canEditCatalogItems", false),
                            canBackup = obj.optBoolean("canBackup", false),
                            canViewAllWarehouses = obj.optBoolean("canViewAllWarehouses", true),
                            warehousePermissions = parseWarehousePermissions(obj.optJSONArray("warehousePermissions")?.toString() ?: "[]"),
                            createdAt = obj.optLong("createdAt", 0L),
                            updatedAt = obj.optLong("updatedAt", 0L),
                            lastSeenAt = obj.optLong("lastSeenAt", 0L)
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeWarehousePermissions(grants: List<WarehousePermission>): String {
        val arr = JSONArray()
        grants.forEach { grant ->
            arr.put(JSONObject().apply {
                put("warehouseId", grant.warehouseId)
                put("canView", grant.canView)
                put("canInward", grant.canInward)
                put("canDisburse", grant.canDisburse)
                put("canTransfer", grant.canTransfer)
                put("canAdjust", grant.canAdjust)
                put("canReports", grant.canReports)
                put("canEdit", grant.canEdit)
                put("canEditInventory", grant.canEditInventory)
                put("canDeleteItem", grant.canDeleteItem)
            })
        }
        return arr.toString()
    }

    private fun parseWarehousePermissions(json: String): List<WarehousePermission> = try {
        val arr = JSONArray(json)
        buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val id = obj.optLong("warehouseId", 0L)
                if (id > 0L) add(WarehousePermission(
                    warehouseId = id,
                    canView = obj.optBoolean("canView", true),
                    canInward = obj.optBoolean("canInward", false),
                    canDisburse = obj.optBoolean("canDisburse", false),
                    canTransfer = obj.optBoolean("canTransfer", false),
                    canAdjust = obj.optBoolean("canAdjust", false),
                    canReports = obj.optBoolean("canReports", false),
                    canEdit = obj.optBoolean("canEdit", false),
                    canEditInventory = obj.optBoolean("canEditInventory", obj.optBoolean("canEdit", false)),
                    canDeleteItem = obj.optBoolean("canDeleteItem", obj.optBoolean("canEdit", false))
                ))
            }
        }
    } catch (_: Exception) { emptyList() }

    private fun parseSavedEmails(json: String): List<String> {
        return try {
            val arr = JSONArray(json)
            buildList {
                for (i in 0 until arr.length()) {
                    val value = arr.optString(i).trim()
                    if (value.isNotBlank() && none { it.equals(value, ignoreCase = true) }) {
                        add(value)
                    }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun encodeSavedEmails(emails: List<String>): String {
        val arr = JSONArray()
        emails.forEach { arr.put(it) }
        return arr.toString()
    }
}
