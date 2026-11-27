package com.example.data.cloud

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.GoogleAuthProvider
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
        val firebaseCredential = GoogleAuthProvider.getCredential(googleCredential.idToken, null)

        withContext(Dispatchers.IO) {
            Tasks.await(auth.signInWithCredential(firebaseCredential))
        }
    }

    fun signOut() {
        auth.signOut()
    }
}
