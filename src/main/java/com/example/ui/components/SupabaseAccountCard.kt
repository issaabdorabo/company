package com.example.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.example.data.cloud.SupabaseAccountInfo
import com.example.data.cloud.SupabaseAuthRepository
import kotlinx.coroutines.launch

@Composable
fun SupabaseAccountCard() {
    val context = LocalContext.current
    val repository = remember(context) {
        SupabaseAuthRepository(context.applicationContext)
    }
    val scope = rememberCoroutineScope()

    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var account by remember { mutableStateOf<SupabaseAccountInfo?>(null) }
    var pendingConfirmationEmail by remember { mutableStateOf<String?>(null) }
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
                text = "أنشئ حساباً أو سجّل الدخول بالبريد وكلمة المرور. هذه الخطوة تجهّز هوية المستخدم فقط؛ مشاركة المخزون والعمليات بين المواقع ستُفعّل بعد ربط العضويات والصلاحيات بالخادم.",
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
                            pendingConfirmationEmail = null
                            password = ""
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
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("البريد الإلكتروني") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("كلمة المرور") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password)
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                message = null
                                val result = repository.signIn(email, password)
                                result.onSuccess {
                                    account = it
                                    password = ""
                                    pendingConfirmationEmail = null
                                    message = "تم تسجيل الدخول بنجاح."
                                    messageIsError = false
                                }.onFailure {
                                    message = it.localizedMessage ?: "تعذر تسجيل الدخول."
                                    messageIsError = true
                                }
                                isBusy = false
                            }
                        },
                        enabled = !isBusy && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("تسجيل الدخول")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                message = null
                                val result = repository.signUp(email, password)
                                result.onSuccess { outcome ->
                                    if (outcome.account != null) {
                                        account = outcome.account
                                        password = ""
                                        message = "تم إنشاء الحساب وتسجيل الدخول."
                                        messageIsError = false
                                    } else {
                                        pendingConfirmationEmail = email.trim()
                                        password = ""
                                        message = "تم إنشاء الحساب. افتح رسالة التأكيد التي أُرسلت إلى بريدك ثم ارجع لتسجيل الدخول."
                                        messageIsError = false
                                    }
                                }.onFailure {
                                    message = it.localizedMessage ?: "تعذر إنشاء الحساب."
                                    messageIsError = true
                                }
                                isBusy = false
                            }
                        },
                        enabled = !isBusy && email.isNotBlank() && password.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("إنشاء حساب")
                    }
                }

                if (pendingConfirmationEmail != null) {
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                val result = repository.resendSignupConfirmation(pendingConfirmationEmail.orEmpty())
                                result.onSuccess {
                                    message = "تم طلب إعادة إرسال رسالة التأكيد."
                                    messageIsError = false
                                }.onFailure {
                                    message = it.localizedMessage ?: "تعذر إعادة إرسال رسالة التأكيد."
                                    messageIsError = true
                                }
                                isBusy = false
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("إعادة إرسال رسالة التأكيد")
                    }
                }
            }

            if (isBusy) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("جارٍ الاتصال...")
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
