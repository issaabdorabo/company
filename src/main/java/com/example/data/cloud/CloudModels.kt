package com.example.data.cloud

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.PropertyName
import com.google.firebase.firestore.ServerTimestamp

data class DevicePermissions(
    val isMainDevice: Boolean = true,
    val canAddItems: Boolean = true,
    val canInward: Boolean = true,
    val canDisburse: Boolean = true,
    val canEditDelete: Boolean = true,
    val isKicked: Boolean = false
) {
    val isReadOnly: Boolean
        get() = !isMainDevice && !canAddItems && !canInward && !canDisburse && !canEditDelete
}

@IgnoreExtraProperties
data class CloudWorkspace(
    @DocumentId val id: String = "",
    val ownerUid: String = "",
    val workspaceCode: String = "",
    val orgName: String = "",
    val syncVersion: Long = 0L,
    val updatedByUid: String = "",
    val updatedByDeviceId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class LinkedSubDevice(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val ownerUid: String = "",
    val deviceName: String = "",
    val passwordSalt: String = "",
    val passwordHash: String = "",
    val inviteHash: String = "",
    val boundUid: String = "",
    val boundInstallationId: String = "",
    @get:PropertyName("isClaimed") @field:PropertyName("isClaimed") val isClaimed: Boolean = false,
    @get:PropertyName("isKicked") @field:PropertyName("isKicked") val isKicked: Boolean = false,
    val canAddItems: Boolean = false,
    val canInward: Boolean = false,
    val canDisburse: Boolean = false,
    val canEditDelete: Boolean = false,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class MemberStatus(
    @DocumentId val id: String = "",
    val uid: String = "",
    val workspaceId: String = "",
    val ownerUid: String = "",
    val deviceSlotId: String = "",
    val deviceName: String = "",
    val inviteHash: String = "",
    val boundInstallationId: String = "",
    @get:PropertyName("isActive") @field:PropertyName("isActive") val isActive: Boolean = true,
    @get:PropertyName("isKicked") @field:PropertyName("isKicked") val isKicked: Boolean = false,
    val canAddItems: Boolean = false,
    val canInward: Boolean = false,
    val canDisburse: Boolean = false,
    val canEditDelete: Boolean = false,
    @ServerTimestamp val joinedAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class PairingInvite(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val workspaceCode: String = "",
    val ownerUid: String = "",
    val deviceSlotId: String = "",
    val deviceName: String = "",
    val canAddItems: Boolean = false,
    val canInward: Boolean = false,
    val canDisburse: Boolean = false,
    val canEditDelete: Boolean = false,
    @get:PropertyName("isUsed") @field:PropertyName("isUsed") val isUsed: Boolean = false,
    @get:PropertyName("isRevoked") @field:PropertyName("isRevoked") val isRevoked: Boolean = false,
    val boundUid: String = "",
    val boundInstallationId: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@IgnoreExtraProperties
data class CloudEntityRecord(
    @DocumentId val id: String = "",
    val workspaceId: String = "",
    val entityType: String = "",
    val localId: Long = 0L,
    val payload: Map<String, Any?> = emptyMap(),
    val updatedByUid: String = "",
    @get:PropertyName("isDeleted") @field:PropertyName("isDeleted") val isDeleted: Boolean = false,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class CreatedDeviceCredentials(
    val deviceSlotId: String,
    val deviceName: String,
    val workspaceCode: String,
    val plainPassword: String
)

data class PairingResult(
    val workspaceId: String,
    val workspaceCode: String,
    val orgName: String,
    val deviceSlotId: String,
    val deviceName: String,
    val permissions: DevicePermissions
)
