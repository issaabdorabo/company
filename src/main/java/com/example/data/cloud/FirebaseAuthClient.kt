package com.example.data.cloud

import android.content.Context
import androidx.credentials.CredentialManager
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
 * Firebase identity for cloud collaboration.
 *
 * This identity is intentionally separate from GoogleOAuthHelper, which continues
 * to authorize Google Drive backup/restore and must not be signed out as a side effect.
 */
object FirebaseAuthClient {
    // OAuth Web client ID configured for the Android app in Firebase Authentication.
    private const val WEB_CLIENT_ID =
        "970638159667-gbt5i1nksbafkuf4lutsipv8icjvm5tr.apps.googleusercontent.com"

    const val databaseUrl = "https://mkhzny-c1402-default-rtdb.europe-west1.firebasedatabase.app"

    val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    fun currentUserIdOrNull(): String? = auth.currentUser?.uid

    fun currentUserEmailOrNull(): String? = auth.currentUser?.email

    suspend fun signInWithGoogle(context: Context) {
        val googleOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(WEB_CLIENT_ID)
            .build()

        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleOption)
            .build()

        val credentialManager = CredentialManager.create(context)
        val result = credentialManager.getCredential(
            request = request,
            context = context
        )
        val googleCredential = GoogleIdTokenCredential.createFrom(result.credential.data)
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
            Tasks.await(
                FirebaseDatabase.getInstance(databaseUrl).reference
                    .child("users").child(user.uid).updateChildren(profile)
            )
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
