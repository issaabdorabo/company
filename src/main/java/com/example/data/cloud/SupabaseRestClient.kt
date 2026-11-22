package com.example.data.cloud

import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** Authenticated Supabase REST transport. Never use a service_role/secret key in the app. */
class SupabaseRestClient(private val client: OkHttpClient = OkHttpClient()) {
    val isConfigured: Boolean
        get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_PUBLISHABLE_KEY.isNotBlank()

    suspend fun get(path: String, accessToken: String, query: String = ""): Result<String> =
        execute("GET", path, accessToken, query, null)

    suspend fun post(path: String, accessToken: String, jsonBody: String): Result<String> =
        execute("POST", path, accessToken, "", jsonBody)

    private suspend fun execute(method: String, path: String, token: String, query: String, body: String?): Result<String> =
        withContext(Dispatchers.IO) {
            runCatching {
                check(isConfigured) { "Supabase is not configured. Check GitHub Actions secrets." }
                require(token.isNotBlank()) { "A Supabase Auth access token is required." }
                val url = BuildConfig.SUPABASE_URL.trimEnd('/') + "/rest/v1/" + path.trimStart('/') +
                    if (query.isBlank()) "" else "?$query"
                val builder = Request.Builder().url(url)
                    .header("apikey", BuildConfig.SUPABASE_PUBLISHABLE_KEY)
                    .header("Authorization", "Bearer $token")
                    .header("Accept", "application/json")
                val request = if (method == "GET") builder.get().build() else builder
                    .header("Content-Type", "application/json")
                    .post((body ?: "{}").toRequestBody("application/json; charset=utf-8".toMediaType())).build()
                client.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    check(response.isSuccessful) { "Supabase HTTP ${response.code}: ${text.take(1000)}" }
                    text
                }
            }
        }
}