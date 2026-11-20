package com.example.data.cloud

import android.accounts.Account
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.GoogleAuthException
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.UserRecoverableAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

sealed interface OAuthTokenResult {
    data class Success(val accessToken: String) : OAuthTokenResult
    data class NeedsConsent(val consentIntent: Intent) : OAuthTokenResult
    data class Error(val message: String, val cause: Throwable? = null) : OAuthTokenResult
}

object GoogleOAuthHelper {
    // Only access the application's private Drive data area. No Gmail permissions are requested.
    private const val OAUTH_SCOPE_STRING =
        "oauth2:https://www.googleapis.com/auth/drive.appdata"

    suspend fun fetchAccessToken(context: Context, email: String): OAuthTokenResult {
        val cleanEmail = email.trim()
        if (cleanEmail.isBlank()) {
            return OAuthTokenResult.Error("يرجى اختيار حساب Google صالح أولاً.")
        }
        return withContext(Dispatchers.IO) {
            try {
                val account = Account(cleanEmail, "com.google")
                val token = GoogleAuthUtil.getToken(
                    context.applicationContext,
                    account,
                    OAUTH_SCOPE_STRING
                )
                if (token.isBlank()) {
                    OAuthTokenResult.Error("تعذر الحصول على تصريح الوصول للحساب ($cleanEmail).")
                } else {
                    OAuthTokenResult.Success(token)
                }
            } catch (e: UserRecoverableAuthException) {
                val intent = e.intent
                if (intent != null) {
                    OAuthTokenResult.NeedsConsent(intent)
                } else {
                    OAuthTokenResult.Error("يلزم منح التطبيق صلاحية الوصول إلى مساحة النسخ الاحتياطي في Google Drive لهذا الحساب.", e)
                }
            } catch (e: IOException) {
                OAuthTokenResult.Error("تعذر الاتصال بخدمات Google؛ تحقق من اتصال الإنترنت وحاول مجدداً.", e)
            } catch (e: GoogleAuthException) {
                OAuthTokenResult.Error(
                    "تعذر تفويض حساب Google ($cleanEmail): ${e.localizedMessage ?: "تأكد من تسجيل الحساب على الهاتف"}",
                    e
                )
            } catch (e: Exception) {
                OAuthTokenResult.Error(
                    e.localizedMessage ?: "حدث خطأ غير متوقع أثناء تفويض الحساب",
                    e
                )
            }
        }
    }

    suspend fun clearToken(context: Context, token: String) {
        if (token.isBlank()) return
        withContext(Dispatchers.IO) {
            runCatching {
                GoogleAuthUtil.clearToken(context.applicationContext, token)
            }
        }
    }
}
