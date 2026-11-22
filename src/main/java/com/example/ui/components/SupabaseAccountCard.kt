package com.example.ui.components

import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialException
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.data.cloud.SupabaseAccountInfo
import com.example.data.cloud.SupabaseAuthRepository
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

@Composable
fun SupabaseAccountCard() {
    val context = LocalContext.current
    val repository = remember(context) {
        SupabaseAuthRepository(context.applicationContext)
    }
    val scope = rememberCoroutineScope()

    var account by remember { mutableStateOf<SupabaseAccountInfo?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    LaunchedEffect(repository) {
        if (repository.isConfigured) {
            repository.restoreAccount()
                .onSuccess { restored -> account = restored }
                .onFailure { error ->
                    account = repository.cachedAccount()
                    message = error.localizedMessage ?: "تعذر تجديد جلسة الحساب حالياً."
                    messageIsError = true
                }
        }
    }

    fun signInWithGoogle() {
        scope.launch {
            isBusy = true
            message = null
            try {
                val webClientId = BuildConfig.GOOGLE_WEB_CLIENT_ID.trim()
                check(webClientId.endsWith(".apps.googleusercontent.com")) {
                    "لم تتم إضافة GOOGLE_WEB_CLIENT_ID إلى أسرار GitHub Actions بعد."
                }

                val rawNonce = UUID.randomUUID().toString()
                val hashedNonce = MessageDigest.getInstance("SHA-256")
                    .digest(rawNonce.toByteArray(Charsets.UTF_8))
                    .joinToString("") { byte -> "%02x".format(byte) }

                val googleIdOption = GetGoogleIdOption.Builder()
                    .setFilterByAuthorizedAccounts(false)
                    .setServerClientId(webClientId)
                    .setNonce(hashedNonce)
                    .build()

                val request = GetCredentialRequest.Builder()
                    .addCredentialOption(googleIdOption)
                    .build()

                val credentialManager = CredentialManager.create(context)
                val result = credentialManager.getCredential(
                    context = context,
                    request = request
                )
                val credential = result.credential
                check(
                    credential is CustomCredential &&
                        credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
                ) {
                    "لم يُرجع Google بيانات اعتماد صالحة."
                }

                val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
                repository.signInWithGoogleIdToken(
                    idToken = googleCredential.idToken,
                    rawNonce = rawNonce
                ).onSuccess {
                    account = it
                    message = "تم تسجيل الدخول باستخدام Google بنجاح."
                    messageIsError = false
                }.onFailure {
                    message = it.localizedMessage ?: "تعذر التحقق من حساب Google لدى Supabase."
                    messageIsError = true
                }
            } catch (error: GetCredentialException) {
                message = when (error) {
                    else -> error.localizedMessage ?: "تم إلغاء اختيار حساب Google أو تعذر الحصول على بياناته."
                }
                messageIsError = true
            } catch (error: Exception) {
                message = error.localizedMessage ?: "حدث خطأ أثناء تسجيل الدخول باستخدام Google."
                messageIsError = true
            } finally {
                isBusy = false
            }
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Text(
                text = "حساب المزامنة عبر الإنترنت",
                style = MaterialTheme.typography.titleMedium
            )
            Text(
                text = "سجّل الدخول مباشرة بحساب Google. سيتحقق Google من هويتك ثم ينشئ Supabase جلسة آمنة دون كلمة مرور أو رمز بريد.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (!repository.isConfigured) {
                Text(
                    text = "إعدادات Supabase غير موجودة في ملف APK الحالي. تحقق من أسرار SUPABASE_URL وSUPABASE_PUBLISHABLE_KEY في GitHub Actions ثم أعد البناء.",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodyMedium
                )
            } else if (account != null) {
                Text("الحساب المسجّل", style = MaterialTheme.typography.labelMedium)
                Text(
                    text = account?.email.orEmpty(),
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            isBusy = true
                            repository.signOut()
                            account = null
                            message = "تم تسجيل الخروج من هذا الجهاز."
                            messageIsError = false
                            isBusy = false
                        }
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تسجيل الخروج")
                }
            } else {
                Button(
                    onClick = { signInWithGoogle() },
                    enabled = !isBusy && BuildConfig.GOOGLE_WEB_CLIENT_ID.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("تسجيل الدخول باستخدام Google")
                }
                if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
                    Text(
                        text = "يلزم إعداد Google OAuth وإضافة GOOGLE_WEB_CLIENT_ID إلى GitHub Actions قبل استخدام الزر.",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            if (isBusy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("جارٍ التحقق من حساب Google...")
                }
            }

            if (!message.isNullOrBlank()) {
                Text(
                    text = message.orEmpty(),
                    color = if (messageIsError) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
