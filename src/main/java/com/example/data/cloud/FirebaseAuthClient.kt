package com.example.data.cloud

import android.content.Context
import android.content.Intent
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
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
    // OAuth Web client ID configured for the Android app in Firebase Authentication.
    const val WEB_CLIENT_ID =
        "970638159667-gbt5i1nksbafkuf4lutsipv8icjvm5tr.apps.googleusercontent.com"

    /**
     * Opens the native Google account chooser, showing Google accounts already
     * saved on the phone and allowing the user to add another account.
     */
    fun googleAccountPickerIntent(context: Context): Intent {
        val options = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestIdToken(WEB_CLIENT_ID)
            .requestEmail()
            .build()
        return GoogleSignIn.getClient(context, options).signInIntent
    }

    fun googleIdTokenFromSignInResult(data: Intent?): String {
        val account = GoogleSignIn.getSignedInAccountFromIntent(data)
            .getResult(ApiException::class.java)
        return account.idToken?.takeIf { it.isNotBlank() }
            ?: error("لم تُرجع Google رمز الدخول. تحقق من إعداد Web Client ID في Firebase.")
    }

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
