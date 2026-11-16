package com.example.data.cloud

enum class DeviceRole {
    MAIN,
    SUB
}

data class DevicePermissions(
    val isMainDevice: Boolean = true,
    val canAddItems: Boolean = true,
    val canInward: Boolean = true,
    val canDisburse: Boolean = true,
    val canEditDelete: Boolean = true,
    val isKicked: Boolean = false
) {
    val isReadOnly: Boolean
        get() = !isMainDevice && !isKicked && !canAddItems && !canInward && !canDisburse && !canEditDelete
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
    val canEditDelete: Boolean = false,
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
                isKicked = true
            )
        } else {
            DevicePermissions(
                isMainDevice = false,
                canAddItems = canAddItems,
                canInward = canInward,
                canDisburse = canDisburse,
                canEditDelete = canEditDelete,
                isKicked = false
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
