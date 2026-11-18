package com.example.data.cloud

import android.content.Context
import android.util.Log
import androidx.annotation.Keep
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.NoCredentialException
import com.example.BuildConfig
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ServerTimestamp
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.UUID

@Keep
@IgnoreExtraProperties
data class CloudBackupItem(
    @DocumentId val id: String = "",
    val userId: String = "",
    val userEmail: String = "",
    val name: String = "",
    val orgName: String = "",
    val appVersion: String = BuildConfig.VERSION_NAME,
    val warehouseCount: Long = 0L,
    val itemCount: Long = 0L,
    val voucherCount: Long = 0L,
    val sizeBytes: Long = 0L,
    val backupJson: String = "",
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

data class CloudAuthUser(
    val uid: String,
    val email: String,
    val displayName: String
)

class CloudBackupRepository(
    private val db: FirebaseFirestore,
    private val auth: FirebaseAuth = FirebaseAuth.getInstance()
) {
    constructor(context: Context) : this(
        db = FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        auth = FirebaseAuth.getInstance()
    )

    fun authStateFlow(): Flow<CloudAuthUser?> = callbackFlow {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            trySend(firebaseAuth.currentUser?.toCloudAuthUser())
        }
        trySend(auth.currentUser?.toCloudAuthUser())
        auth.addAuthStateListener(listener)
        awaitClose { auth.removeAuthStateListener(listener) }
    }

    fun currentUser(): CloudAuthUser? = auth.currentUser?.toCloudAuthUser()

    private fun FirebaseUser.toCloudAuthUser(): CloudAuthUser {
        return CloudAuthUser(
            uid = uid,
            email = email.orEmpty(),
            displayName = displayName.orEmpty().ifBlank { email.orEmpty() }
        )
    }

    fun observeCloudBackups(userId: String): Flow<List<CloudBackupItem>> {
        val collectionRef = db.collection("users")
            .document(userId)
            .collection("cloud_backups")
            .orderBy("createdAt", Query.Direction.DESCENDING)

        return collectionRef
            .snapshots()
            .map { snapshot ->
                snapshot.toObjects(
                    CloudBackupItem::class.java,
                    DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
                )
            }
    }

    suspend fun fetchCloudBackupsFromServer(userId: String): Result<List<CloudBackupItem>> {
        val colPath = "users/$userId/cloud_backups"
        return try {
            val snapshot = db.collection("users")
                .document(userId)
                .collection("cloud_backups")
                .get(Source.SERVER)
                .await()
            val list = snapshot.toObjects(
                CloudBackupItem::class.java,
                DocumentSnapshot.ServerTimestampBehavior.ESTIMATE
            ).sortedByDescending { (it.createdAt ?: it.updatedAt)?.seconds ?: 0L }
            Result.success(list)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.LIST, colPath)
            Result.failure(e)
        }
    }

    suspend fun createCloudBackup(
        userId: String,
        userEmail: String,
        name: String,
        orgName: String,
        warehouseCount: Int,
        itemCount: Int,
        voucherCount: Int,
        backupJson: String
    ): Result<String> {
        val backupId = "bkp_${UUID.randomUUID().toString().replace("-", "").take(20)}"
        val docPath = "users/$userId/cloud_backups/$backupId"
        return try {
            val uid = auth.currentUser?.uid
                ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول بالبريد الإلكتروني أولاً لرفع النسخة السحابية."))
            check(uid == userId) { "تغير حساب Google النشط؛ يرجى المحاولة مرة أخرى." }

            val cleanName = name.trim().ifBlank { "نسخة_سحابية" }.take(120)
            val cleanEmail = userEmail.ifBlank { auth.currentUser?.email.orEmpty() }.trim().take(160)
            val cleanOrg = orgName.trim().take(160)
            val jsonBytes = backupJson.toByteArray(Charsets.UTF_8).size
            if (jsonBytes > 900_000 || backupJson.length > 900_000) {
                return Result.failure(
                    IllegalArgumentException("حجم قاعدة البيانات تجاوز الحد المسموح للنسخة السحابية (900 كيلوبايت).")
                )
            }

            val payload = mapOf(
                "userId" to userId,
                "userEmail" to cleanEmail,
                "name" to cleanName,
                "orgName" to cleanOrg,
                "appVersion" to BuildConfig.VERSION_NAME.take(32),
                "warehouseCount" to warehouseCount.toLong().coerceAtLeast(0L),
                "itemCount" to itemCount.toLong().coerceAtLeast(0L),
                "voucherCount" to voucherCount.toLong().coerceAtLeast(0L),
                "sizeBytes" to jsonBytes.toLong().coerceAtLeast(1L),
                "backupJson" to backupJson,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )

            db.collection("users")
                .document(userId)
                .collection("cloud_backups")
                .document(backupId)
                .set(payload)
                .await()

            Result.success(backupId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.CREATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun renameCloudBackup(
        userId: String,
        backupId: String,
        newName: String
    ): Result<Unit> {
        val docPath = "users/$userId/cloud_backups/$backupId"
        return try {
            val uid = auth.currentUser?.uid
                ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول بالبريد الإلكتروني أولاً."))
            check(uid == userId) { "تغير حساب Google النشط؛ يرجى إعادة المحاولة." }
            require(backupId.isNotBlank()) { "معرف النسخة الاحتياطية غير صالح." }
            val cleanName = newName.trim().take(120)
            require(cleanName.isNotEmpty()) { "يرجى إدخال اسم صالح للنسخة الاحتياطية." }

            db.collection("users")
                .document(userId)
                .collection("cloud_backups")
                .document(backupId)
                .update(
                    mapOf(
                        "name" to cleanName,
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                )
                .await()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.UPDATE, docPath)
            Result.failure(e)
        }
    }

    suspend fun deleteCloudBackup(userId: String, backupId: String): Result<Unit> {
        val docPath = "users/$userId/cloud_backups/$backupId"
        return try {
            val uid = auth.currentUser?.uid
                ?: return Result.failure(IllegalStateException("يجب تسجيل الدخول بالبريد الإلكتروني أولاً."))
            check(uid == userId) { "تغير حساب Google النشط؛ يرجى إعادة المحاولة." }
            require(backupId.isNotBlank()) { "معرف النسخة الاحتياطية غير صالح." }

            db.collection("users")
                .document(userId)
                .collection("cloud_backups")
                .document(backupId)
                .delete()
                .await()

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.DELETE, docPath)
            Result.failure(e)
        }
    }

    suspend fun signInWithGoogleInteractive(
        activityContext: Context,
        clearExistingCredentialState: Boolean = false
    ): Result<CloudAuthUser> {
        return try {
            val webClientId = activityContext.getString(R.string.default_web_client_id)
            val credentialManager = CredentialManager.create(activityContext)
            if (clearExistingCredentialState) {
                runCatching {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                }
            }
            val signInOption = GetSignInWithGoogleOption.Builder(webClientId)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(signInOption)
                .build()
            val result = credentialManager.getCredential(activityContext, request)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleIdTokenCredential = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleIdTokenCredential.idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                val user = authResult.user?.toCloudAuthUser()
                    ?: return Result.failure(IllegalStateException("تعذر إتمام التوثيق مع Firebase."))
                Result.success(user)
            } else {
                Result.failure(IllegalStateException("نوع بيانات الاعتماد المستلم غير مدعوم."))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: GetCredentialCancellationException) {
            Log.w("Auth", "Google Sign-In flow cancelled or dismissed: ${e.message}", e)
            Result.failure(GoogleSignInCancelledException())
        } catch (e: NoCredentialException) {
            Log.w("Auth", "No Google account credential available on device: ${e.message}", e)
            Result.failure(
                IllegalStateException(
                    "لا توجد حسابات Google متاحة على هذا الجهاز حالياً. يمكنك إضافة حساب Google في إعدادات الهاتف أو إدخال البريد الإلكتروني يدوياً."
                )
            )
        } catch (e: GetCredentialException) {
            Log.w("Auth", "CredentialManager Google sign-in unavailable: ${e.type} - ${e.message}", e)
            Result.failure(
                IllegalStateException(
                    "تعذر فتح نافذة اختيار حساب Google (${e.message ?: "خطأ في خدمات Google Play"}). تأكد من توفر خدمات Google Play وتسجيل حساب على الجهاز."
                )
            )
        } catch (e: Exception) {
            Log.w("Auth", "Unexpected Google sign-in error: ${e.message}", e)
            Result.failure(
                IllegalStateException(
                    e.localizedMessage ?: "تعذر تسجيل الدخول بحساب Google"
                )
            )
        }
    }

    suspend fun attemptSilentGoogleSignIn(context: Context): CloudAuthUser? {
        if (auth.currentUser != null) return auth.currentUser?.toCloudAuthUser()
        return try {
            val webClientId = context.getString(R.string.default_web_client_id)
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(true)
                .setServerClientId(webClientId)
                .setAutoSelectEnabled(true)
                .build()
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()
            val credentialManager = CredentialManager.create(context)
            val result = credentialManager.getCredential(context, request)
            val credential = result.credential
            if (credential is CustomCredential &&
                credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                val googleCred = GoogleIdTokenCredential.createFrom(credential.data)
                val firebaseCredential = GoogleAuthProvider.getCredential(googleCred.idToken, null)
                val authResult = auth.signInWithCredential(firebaseCredential).await()
                authResult.user?.toCloudAuthUser()
            } else {
                null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.d(TAG, "Silent Google sign-in skipped: ${e.message}")
            null
        }
    }

    suspend fun signOut(context: Context) {
        withContext(NonCancellable) {
            runCatching {
                CredentialManager.create(context.applicationContext)
                    .clearCredentialState(ClearCredentialStateRequest())
            }
            auth.signOut()
        }
    }

    private companion object {
        const val TAG = "CloudBackupRepo"
    }
}

class GoogleSignInCancelledException : Exception("تم إلغاء نافذة تسجيل الدخول.")
