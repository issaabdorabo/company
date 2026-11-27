package com.example.data.cloud

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Firebase identity for account-based device linking and secure realtime sync.
 *
 * This identity is intentionally separate from GoogleOAuthHelper, which continues
 * to authorize Google Drive backup/restore and must not be signed out as a side effect.
 */
object FirebaseAuthClient {
    /**
     * Uses the OAuth Web client generated from this Firebase project's
     * google-services.json. Never reuse a client ID from another Firebase project.
     */
    private fun webClientId(context: Context): String {
        val resourceId = context.resources.getIdentifier(
            "default_web_client_id",
            "string",
            context.packageName
        )
        if (resourceId == 0) {
            error(
                "إعداد تسجيل Google غير مكتمل في Firebase: ملف google-services.json لا يحتوي على " +
                    "OAuth Web Client ID. فعّل Google من Firebase Authentication ثم نزّل الملف المحدّث."
            )
        }
        return context.getString(resourceId).takeIf { it.isNotBlank() }
            ?: error("OAuth Web Client ID في إعداد Firebase فارغ.")
    }

    const val databaseUrl = "https://mkhzny-c1402-default-rtdb.europe-west1.firebasedatabase.app"

    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    fun currentUserIdOrNull(): String? = auth.currentUser?.uid

    fun currentUserEmailOrNull(): String? = auth.currentUser?.email

    suspend fun signInWithGoogle(context: Context) {
        val googleOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(webClientId(context))
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        val credentialManager = CredentialManager.create(context)
        val result = credentialManager.getCredential(
            request = request,
            context = context
        )
        val credential = result.credential
        if (credential !is CustomCredential ||
            credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
        ) {
            error("لم تُرجع Google بيانات اعتماد صالحة. اختر حساب Google ثم أعد المحاولة.")
        }
        val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
        signInWithGoogleIdToken(googleCredential.idToken)
    }

    /**
     * Reuses the Google ID token already obtained by the shared sign-in flow.
     * This avoids opening a second Google account picker and creates the Firebase
     * identity/profile used by Firebase collaboration services.
     */
    suspend fun signInWithGoogleIdToken(idToken: String) {
        require(idToken.isNotBlank()) { "Google ID token is empty" }
        val firebaseCredential = GoogleAuthProvider.getCredential(idToken, null)
        withContext(Dispatchers.IO) {
            val result = Tasks.await(auth.signInWithCredential(firebaseCredential))
            val user = result.user ?: error("Firebase did not return an authenticated user")
            val email = user.email?.trim()?.lowercase()
                ?: error("Google account has no email address")
            val profile = hashMapOf<String, Any>(
                "email" to email,
                "displayName" to (user.displayName ?: ""),
                "lastSeenAt" to ServerValue.TIMESTAMP
            )
            user.photoUrl?.toString()?.takeIf { it.isNotBlank() }?.let {
                profile["photoUrl"] = it
            }
            // Authentication is already successful at this point. A profile write
            // must not turn a valid Google/Firebase sign-in into a reported login failure;
            // the device directory registration below reports its own actionable errors.
            runCatching {
                Tasks.await(
                    FirebaseDatabase.getInstance(databaseUrl).reference
                        .child("users").child(user.uid).updateChildren(profile)
                )
            }
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
