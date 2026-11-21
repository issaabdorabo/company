package com.example.data.cloud

enum class DeviceRole {
    MAIN,
    SUB
}

data class WarehousePermission(
    val warehouseId: Long,
    val canView: Boolean = true,
    val canInward: Boolean = false,
    val canDisburse: Boolean = false,
    val canTransfer: Boolean = false,
    val canAdjust: Boolean = false,
    val canReports: Boolean = false,
    val canEdit: Boolean = false
)

data class DevicePermissions(
    val isMainDevice: Boolean = true,
    // Restrictive defaults: a secondary device must receive explicit grants from its main device.
    val canAddItems: Boolean = false,
    val canInward: Boolean = false,
    val canDisburse: Boolean = false,
    val canEditDelete: Boolean = false, // Legacy compatibility for older paired devices
    val canEditInventory: Boolean = canEditDelete,
    val canDeleteItem: Boolean = canEditDelete,
    val canEditIssueVoucher: Boolean = canEditDelete,
    val canDeleteIssueVoucher: Boolean = canEditDelete,
    val canEditSupplyVoucher: Boolean = canEditDelete,
    val canDeleteSupplyVoucher: Boolean = canEditDelete,
    val isKicked: Boolean = false,
    val canBackup: Boolean = false,
    val canViewAllWarehouses: Boolean = false,
    val warehousePermissions: List<WarehousePermission> = emptyList()
) {
    val isReadOnly: Boolean
        get() = !isMainDevice && !isKicked && !canAddItems && !canInward && !canDisburse && !canEditDelete && !canEditInventory && !canDeleteItem && !canEditIssueVoucher && !canDeleteIssueVoucher && !canEditSupplyVoucher && !canDeleteSupplyVoucher &&
            warehousePermissions.none { it.canInward || it.canDisburse || it.canTransfer || it.canAdjust || it.canEdit }
}

data class LinkedSubDevice(
    val id: String,
    val deviceName: String,
    val passwordSalt: String,
    val passwordHash: String,
    val sessionToken: String,
    val boundInstallationId: String = "",
    val isClaimed: Boolean = false,
    val isKicked: Boolean = false,
    val canAddItems: Boolean = false,
    val canInward: Boolean = false,
    val canDisburse: Boolean = false,
    val canEditDelete: Boolean = false, // Legacy compatibility for older paired devices
    val canEditInventory: Boolean = canEditDelete,
    val canDeleteItem: Boolean = canEditDelete,
    val canEditIssueVoucher: Boolean = canEditDelete,
    val canDeleteIssueVoucher: Boolean = canEditDelete,
    val canEditSupplyVoucher: Boolean = canEditDelete,
    val canDeleteSupplyVoucher: Boolean = canEditDelete,
    val canBackup: Boolean = false,
    val canViewAllWarehouses: Boolean = false,
    val warehousePermissions: List<WarehousePermission> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastSeenAt: Long = 0L
) {
    fun toPermissions(): DevicePermissions {
        return if (isKicked) {
            DevicePermissions(
                isMainDevice = false,
                canAddItems = false,
                canInward = false,
                canDisburse = false,
                canEditDelete = false,
                canEditInventory = false, canDeleteItem = false,
                canEditIssueVoucher = false, canDeleteIssueVoucher = false,
                canEditSupplyVoucher = false, canDeleteSupplyVoucher = false,
                isKicked = true,
                canBackup = false,
                canViewAllWarehouses = false,
                warehousePermissions = emptyList()
            )
        } else {
            DevicePermissions(
                isMainDevice = false,
                canAddItems = canAddItems,
                canInward = canInward,
                canDisburse = canDisburse,
                canEditDelete = canEditDelete,
                canEditInventory = canEditInventory, canDeleteItem = canDeleteItem,
                canEditIssueVoucher = canEditIssueVoucher, canDeleteIssueVoucher = canDeleteIssueVoucher,
                canEditSupplyVoucher = canEditSupplyVoucher, canDeleteSupplyVoucher = canDeleteSupplyVoucher,
                isKicked = false,
                canBackup = canBackup,
                canViewAllWarehouses = canViewAllWarehouses,
                warehousePermissions = warehousePermissions
            )
        }
    }
}

data class CreatedDeviceCredentials(
    val deviceSlotId: String,
    val deviceName: String,
    val workspaceCode: String,
    val hostAddress: String,
    val plainPassword: String
)

data class DiscoveredMainDevice(
    val serviceName: String,
    val workspaceCode: String,
    val hostAddress: String,
    val orgName: String = ""
)

data class LocalServerStatus(
    val isRunning: Boolean = false,
    val port: Int = 8765,
    val hostAddresses: List<String> = emptyList(),
    val workspaceCode: String = "",
    val errorMessage: String? = null
) {
    val primaryAddress: String
        get() = hostAddresses.firstOrNull()?.let { "$it:$port" } ?: "127.0.0.1:$port"
}

data class SyncSnapshotResult(
    val workspaceCode: String,
    val orgName: String,
    val permissions: DevicePermissions,
    val isKicked: Boolean,
    val syncVersion: Long,
    val entities: List<CloudEntityRecord>
)

data class PairingResult(
    val workspaceCode: String,
    val orgName: String,
    val deviceSlotId: String,
    val deviceName: String,
    val sessionToken: String,
    val permissions: DevicePermissions,
    val syncVersion: Long,
    val entities: List<CloudEntityRecord>
)

data class CloudEntityRecord(
    val id: String = "",
    val workspaceId: String = "",
    val entityType: String = "",
    val localId: Long = 0L,
    val payload: Map<String, Any?> = emptyMap(),
    val updatedByUid: String = "",
    val isDeleted: Boolean = false
)

data class CloudEntityUpsert(
    val docId: String,
    val entityType: String,
    val localId: Long,
    val payload: Map<String, Any?>,
    val isDeleted: Boolean = false
)
