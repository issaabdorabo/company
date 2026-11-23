package com.example.data.cloud

import android.content.Intent
import com.example.BuildConfig
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.gotrue.GoTrue
import io.github.jan.supabase.gotrue.handleDeeplinks
import io.github.jan.supabase.gotrue.providers.builtin.Google

/**
 * Supabase Auth for online collaboration. This is deliberately separate from
 * GoogleOAuthHelper, which is only used for Google Drive appData backups.
 */
object SupabaseAuthClient {
    private const val REDIRECT_SCHEME = "com.aistudio.inventory.essa"
    private const val REDIRECT_HOST = "auth-callback"

    val client by lazy {
        createSupabaseClient(
            supabaseUrl = BuildConfig.SUPABASE_URL,
            supabaseKey = BuildConfig.SUPABASE_PUBLISHABLE_KEY
        ) {
            install(GoTrue) {
                flowType = io.github.jan.supabase.gotrue.FlowType.PKCE
                scheme = REDIRECT_SCHEME
                host = REDIRECT_HOST
                defaultExternalAuthAction =
                    io.github.jan.supabase.gotrue.ExternalAuthAction.CUSTOM_TABS
            }
        }
    }

    suspend fun signInWithGoogle() {
        client.gotrue.loginWith(Google)
    }

    fun handleDeeplink(intent: Intent) {
        if (intent.data?.scheme == REDIRECT_SCHEME && intent.data?.host == REDIRECT_HOST) {
            client.handleDeeplinks(intent)
        }
    }
}
