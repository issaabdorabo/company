package com.example.data.cloud

import com.google.android.gms.tasks.Tasks
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Registers authenticated app accounts and delivers one-time device-link approvals.
 * Only identity and device-link metadata are stored here; inventory rows and vouchers
 * remain in Room and Google Drive backups.
 */
class FirebaseDeviceAccountsRepository {
    private val root get() = FirebaseDatabase.getInstance(FirebaseAuthClient.databaseUrl).reference

    suspend fun registerCurrentAccount() = withContext(Dispatchers.IO) {
        val user = FirebaseAuthClient.auth.currentUser ?: error("يجب تسجيل الدخول إلى Firebase أولاً")
        val email = user.email?.trim()?.lowercase()
            ?.takeIf { it.contains("@") } ?: error("حساب Firebase لا يحتوي على بريد إلكتروني")
        val values = hashMapOf<String, Any>(
            "userId" to user.uid,
            "email" to email,
            "displayName" to (user.displayName ?: ""),
            "lastSeenAt" to ServerValue.TIMESTAMP
        )
        user.photoUrl?.toString()?.takeIf { it.isNotBlank() }?.let { values["photoUrl"] = it }
        Tasks.await(root.child("registeredAccounts").child(user.uid).updateChildren(values))
    }

    suspend fun listSignedInAccounts(): List<RegisteredFirebaseAccount> = withContext(Dispatchers.IO) {
        val owner = FirebaseAuthClient.auth.currentUser ?: error("سجّل الدخول بحساب Firebase على الجهاز الرئيسي")
        val snapshot = Tasks.await(root.child("registeredAccounts").get())
        snapshot.children.mapNotNull { account ->
            val uid = account.key ?: return@mapNotNull null
            if (uid == owner.uid) return@mapNotNull null
            val email = account.child("email").getValue(String::class.java)?.trim().orEmpty()
            if (!email.contains("@")) return@mapNotNull null
            RegisteredFirebaseAccount(
                userId = uid,
                email = email,
                displayName = account.child("displayName").getValue(String::class.java).orEmpty(),
                lastSeenAt = account.child("lastSeenAt").getValue(Long::class.java) ?: 0L
            )
        }.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName.ifBlank { it.email } })
    }

    suspend fun approveAccount(
        workspaceId: String,
        mainDeviceId: String,
        candidate: RegisteredFirebaseAccount,
        slotId: String,
        sessionToken: String,
        deviceName: String
    ) = withContext(Dispatchers.IO) {
        val owner = FirebaseAuthClient.auth.currentUser ?: error("سجّل الدخول بحساب Firebase على الجهاز الرئيسي")
        require(workspaceId.isNotBlank() && mainDeviceId.isNotBlank() && slotId.isNotBlank() && sessionToken.isNotBlank()) {
            "بيانات اعتماد حساب الجهاز الفرعي غير مكتملة"
        }
        require(candidate.userId.isNotBlank() && candidate.userId != owner.uid) {
            "اختر حساباً فرعياً مختلفاً عن حساب الجهاز الرئيسي"
        }
        val workspaceRef = root.child("workspaces").child(workspaceId)
        val workspace = Tasks.await(workspaceRef.get())
        require(workspace.child("ownerUserId").getValue(String::class.java) == owner.uid) {
            "اعتماد الحساب متاح للمستخدم الرئيسي فقط"
        }
        val account = Tasks.await(root.child("registeredAccounts").child(candidate.userId).get())
        val email = account.child("email").getValue(String::class.java)?.trim()?.lowercase().orEmpty()
        require(email.isNotBlank() && email == candidate.email.trim().lowercase()) {
            "لم يعد حساب Firebase المختار مسجلاً. حدّث قائمة الحسابات وحاول مجدداً."
        }

        val now = ServerValue.TIMESTAMP
        val member = mapOf(
            "userId" to candidate.userId,
            "email" to email,
            "displayName" to account.child("displayName").getValue(String::class.java).orEmpty(),
            "role" to "SUBUSER",
            "active" to true,
            "approvedBy" to owner.uid,
            "createdAt" to now,
            "updatedAt" to now
        )
        val invite = mapOf(
            "workspaceId" to workspaceId,
            "mainCloudDeviceId" to mainDeviceId,
            "deviceSlotId" to slotId,
            "sessionToken" to sessionToken,
            "deviceName" to deviceName,
            "approved" to true,
            "approvedBy" to owner.uid,
            "createdAt" to now
        )
        val updates = hashMapOf<String, Any>(
            "/workspaces/$workspaceId/members/\${candidate.userId}" to member,
            "/deviceLinkInvites/\${candidate.userId}/$workspaceId" to invite
        )
        Tasks.await(root.updateChildren(updates))
    }

    suspend fun readPendingInvite(): FirebaseDeviceLinkInvite? = withContext(Dispatchers.IO) {
        val user = FirebaseAuthClient.auth.currentUser ?: error("سجّل الدخول بحساب Firebase على الجهاز الفرعي")
        val invites = Tasks.await(root.child("deviceLinkInvites").child(user.uid).get())
        for (entry in invites.children) {
            if (entry.child("approved").getValue(Boolean::class.java) != true) continue
            val workspaceId = entry.child("workspaceId").getValue(String::class.java).orEmpty()
            if (workspaceId.isBlank()) continue
            val member = Tasks.await(root.child("workspaces").child(workspaceId).child("members").child(user.uid).get())
            if (member.child("active").getValue(Boolean::class.java) != true) continue
            val workspace = Tasks.await(root.child("workspaces").child(workspaceId).get())
            val mainUid = workspace.child("ownerUserId").getValue(String::class.java).orEmpty()
            val mainDeviceId = entry.child("mainCloudDeviceId").getValue(String::class.java).orEmpty()
            val slotId = entry.child("deviceSlotId").getValue(String::class.java).orEmpty()
            val token = entry.child("sessionToken").getValue(String::class.java).orEmpty()
            if (mainUid.isBlank() || mainUid == user.uid || mainDeviceId.isBlank() || slotId.isBlank() || token.isBlank()) continue
            Tasks.await(root.child("userWorkspaces").child(user.uid).child(workspaceId).setValue(true))
            return@withContext FirebaseDeviceLinkInvite(
                cloudWorkspaceId = workspaceId,
                mainCloudDeviceId = mainDeviceId,
                deviceSlotId = slotId,
                sessionToken = token,
                deviceName = entry.child("deviceName").getValue(String::class.java).orEmpty()
            )
        }
        null
    }

    suspend fun completeInvite(workspaceId: String) = withContext(Dispatchers.IO) {
        val uid = FirebaseAuthClient.currentUserIdOrNull() ?: return@withContext
        Tasks.await(root.child("deviceLinkInvites").child(uid).child(workspaceId).removeValue())
    }
}
