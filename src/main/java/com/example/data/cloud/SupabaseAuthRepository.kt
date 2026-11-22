package com.example.data.cloud

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class SupabaseAccountInfo(
    val userId: String,
    val email: String
)

data class SupabaseSignUpOutcome(
    val account: SupabaseAccountInfo?,
    val emailConfirmationRequired: Boolean
)

/**
 * Supabase Auth client for Android.
 *
 * Uses the existing OkHttp dependency so the app can keep minSdk 24.
 * Only the public/publishable project key is used here; never put service_role
 * or a database password in the Android application.
 */
class SupabaseAuthRepository(
    context: Context,
    private val supabaseUrl: String = BuildConfig.SUPABASE_URL,
    private val publishableKey: String = BuildConfig.SUPABASE_PUBLISHABLE_KEY,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {
    private val sessionStore = SecureAuthSessionStore(context.applicationContext)

    val isConfigured: Boolean
        get() = supabaseUrl.startsWith("https://", ignoreCase = true) &&
            publishableKey.isNotBlank()

    fun cachedAccount(): SupabaseAccountInfo? = readStoredSession()?.toAccountInfo()

    suspend fun signUp(email: String, password: String): Result<SupabaseSignUpOutcome> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireConfigured()
                val cleanEmail = email.trim().lowercase()
                require(cleanEmail.isNotBlank()) { "يرجى إدخال البريد الإلكتروني." }
                require(password.isNotBlank()) { "يرجى إدخال كلمة المرور." }

                val response = postAuth(
                    endpoint = "signup",
                    payload = JSONObject()
                        .put("email", cleanEmail)
                        .put("password", password)
                )
                val session = persistSessionIfPresent(response, fallback = null)
                SupabaseSignUpOutcome(
                    account = session?.toAccountInfo(),
                    emailConfirmationRequired = session == null
                )
            }
        }

    suspend fun signIn(email: String, password: String): Result<SupabaseAccountInfo> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireConfigured()
                val cleanEmail = email.trim().lowercase()
                require(cleanEmail.isNotBlank()) { "يرجى إدخال البريد الإلكتروني." }
                require(password.isNotBlank()) { "يرجى إدخال كلمة المرور." }

                val response = postAuth(
                    endpoint = "token?grant_type=password",
                    payload = JSONObject()
                        .put("email", cleanEmail)
                        .put("password", password)
                )
                val session = persistSessionIfPresent(response, fallback = null)
                    ?: throw IOException("لم تُرجع خدمة الدخول جلسة صالحة.")
                session.toAccountInfo()
            }
        }

    suspend fun resendSignupConfirmation(email: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireConfigured()
                val cleanEmail = email.trim().lowercase()
                require(cleanEmail.isNotBlank()) { "يرجى إدخال البريد الإلكتروني." }
                postAuth(
                    endpoint = "resend",
                    payload = JSONObject()
                        .put("type", "signup")
                        .put("email", cleanEmail)
                )
                Unit
            }
        }

    /**
     * Restores the cached account and refreshes an expiring session.
     * A transient network error does not erase the locally encrypted session.
     */
    suspend fun restoreAccount(): Result<SupabaseAccountInfo?> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireConfigured()
                val current = readStoredSession() ?: return@runCatching null
                if (current.expiresAtMillis > System.currentTimeMillis() + 60_000L) {
                    return@runCatching current.toAccountInfo()
                }

                val refreshed = postAuth(
                    endpoint = "token?grant_type=refresh_token",
                    payload = JSONObject().put("refresh_token", current.refreshToken)
                )
                val updated = persistSessionIfPresent(refreshed, fallback = current)
                    ?: throw IOException("تعذر تجديد جلسة الحساب.")
                updated.toAccountInfo()
            }
        }

    suspend fun getValidAccessToken(): Result<String?> =
        withContext(Dispatchers.IO) {
            runCatching {
                requireConfigured()
                val current = readStoredSession() ?: return@runCatching null
                if (current.expiresAtMillis > System.currentTimeMillis() + 60_000L) {
                    return@runCatching current.accessToken
                }
                val refreshed = postAuth(
                    endpoint = "token?grant_type=refresh_token",
                    payload = JSONObject().put("refresh_token", current.refreshToken)
                )
                persistSessionIfPresent(refreshed, fallback = current)?.accessToken
                    ?: throw IOException("تعذر تجديد جلسة الحساب.")
            }
        }

    suspend fun signOut(): Result<Unit> = withContext(Dispatchers.IO) {
        val current = readStoredSession()
        try {
            if (current != null && isConfigured) {
                postAuth(
                    endpoint = "logout",
                    payload = JSONObject(),
                    bearerToken = current.accessToken
                )
            }
            Result.success(Unit)
        } catch (error: Exception) {
            Result.failure(error)
        } finally {
            sessionStore.clear()
        }
    }

    private fun requireConfigured() {
        check(isConfigured) {
            "إعدادات Supabase غير موجودة في هذا البناء. تحقق من SUPABASE_URL وSUPABASE_PUBLISHABLE_KEY في GitHub Actions."
        }
    }

    private fun postAuth(
        endpoint: String,
        payload: JSONObject,
        bearerToken: String? = null
    ): JSONObject {
        val url = "${supabaseUrl.trimEnd('/')}/auth/v1/$endpoint"
        val requestBuilder = Request.Builder()
            .url(url)
            .addHeader("apikey", publishableKey)
            .addHeader("Accept", "application/json")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))

        if (!bearerToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $bearerToken")
        }

        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IOException(parseAuthError(response.code, responseBody))
            }
            return if (responseBody.isBlank()) JSONObject() else JSONObject(responseBody)
        }
    }

    private fun persistSessionIfPresent(
        response: JSONObject,
        fallback: StoredSupabaseSession?
    ): StoredSupabaseSession? {
        val accessToken = response.optString("access_token").takeIf { it.isNotBlank() }
            ?: return null
        val refreshToken = response.optString("refresh_token").takeIf { it.isNotBlank() }
            ?: return null

        val user = response.optJSONObject("user")
        val userId = user?.optString("id")?.takeIf { it.isNotBlank() }
            ?: fallback?.userId.orEmpty()
        val email = user?.optString("email")?.takeIf { it.isNotBlank() }
            ?: fallback?.email.orEmpty()
        val expiresAtSeconds = response.optLong("expires_at", 0L)
        val expiresAtMillis = if (expiresAtSeconds > 0L) {
            expiresAtSeconds * 1000L
        } else {
            System.currentTimeMillis() + response.optLong("expires_in", 3600L).coerceAtLeast(60L) * 1000L
        }

        val session = StoredSupabaseSession(
            accessToken = accessToken,
            refreshToken = refreshToken,
            userId = userId,
            email = email,
            expiresAtMillis = expiresAtMillis
        )
        sessionStore.save(
            JSONObject()
                .put("accessToken", session.accessToken)
                .put("refreshToken", session.refreshToken)
                .put("userId", session.userId)
                .put("email", session.email)
                .put("expiresAtMillis", session.expiresAtMillis)
                .toString()
        )
        return session
    }

    private fun readStoredSession(): StoredSupabaseSession? {
        val serialized = sessionStore.read() ?: return null
        return try {
            val json = JSONObject(serialized)
            val accessToken = json.optString("accessToken")
            val refreshToken = json.optString("refreshToken")
            if (accessToken.isBlank() || refreshToken.isBlank()) {
                sessionStore.clear()
                null
            } else {
                StoredSupabaseSession(
                    accessToken = accessToken,
                    refreshToken = refreshToken,
                    userId = json.optString("userId"),
                    email = json.optString("email"),
                    expiresAtMillis = json.optLong("expiresAtMillis", 0L)
                )
            }
        } catch (_: Exception) {
            sessionStore.clear()
            null
        }
    }

    private fun parseAuthError(statusCode: Int, responseBody: String): String {
        val message = runCatching {
            val json = JSONObject(responseBody)
            listOf("msg", "message", "error_description", "error")
                .firstNotNullOfOrNull { key ->
                    json.optString(key).takeIf { it.isNotBlank() && it != "null" }
                }
        }.getOrNull()
        return message ?: "فشل الاتصال بخدمة الحسابات (HTTP $statusCode)."
    }

    private data class StoredSupabaseSession(
        val accessToken: String,
        val refreshToken: String,
        val userId: String,
        val email: String,
        val expiresAtMillis: Long
    ) {
        fun toAccountInfo() = SupabaseAccountInfo(userId = userId, email = email)
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}

/**
 * Stores only authenticated ciphertext in app-private preferences.
 * The AES key is generated and held by Android Keystore, not in the APK or preferences.
 */
private class SecureAuthSessionStore(context: Context) {
    private val preferences = context.getSharedPreferences(
        "supabase_auth_session_secure",
        Context.MODE_PRIVATE
    )

    @Synchronized
    fun save(plainText: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        cipher.updateAAD(AAD)
        val encrypted = cipher.doFinal(plainText.toByteArray(Charsets.UTF_8))
        val encoded = listOf(
            VERSION,
            Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
        ).joinToString(".")
        check(preferences.edit().putString(STORAGE_KEY, encoded).commit()) {
            "تعذر حفظ جلسة الحساب المشفرة."
        }
    }

    @Synchronized
    fun read(): String? {
        val encoded = preferences.getString(STORAGE_KEY, null) ?: return null
        return try {
            val parts = encoded.split(".")
            require(parts.size == 3 && parts[0] == VERSION) { "صيغة جلسة غير معروفة." }
            val iv = Base64.decode(parts[1], Base64.NO_WRAP)
            val ciphertext = Base64.decode(parts[2], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            cipher.updateAAD(AAD)
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            clear()
            null
        }
    }

    @Synchronized
    fun clear() {
        preferences.edit().remove(STORAGE_KEY).commit()
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEY_STORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "com.example.supabase.auth.session.v1"
        private const val STORAGE_KEY = "encrypted_session"
        private const val VERSION = "v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val AAD = "supabase-auth-session-v1".toByteArray(Charsets.UTF_8)
    }
}
