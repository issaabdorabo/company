package com.example.ui.components

import android.accounts.AccountManager
import android.app.Activity
import com.google.android.gms.common.AccountPicker
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
    var otp by remember { mutableStateOf("") }
    var otpSent by remember { mutableStateOf(false) }
    var account by remember { mutableStateOf<SupabaseAccountInfo?>(null) }
    var isBusy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var messageIsError by remember { mutableStateOf(false) }

    val accountPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val chosenEmail = result.data
                ?.getStringExtra(AccountManager.KEY_ACCOUNT_NAME)
                ?.trim()
                .orEmpty()
            if (chosenEmail.isNotBlank()) {
                email = chosenEmail
                otp = ""
                otpSent = false
                message = "تم اختيار $chosenEmail. أرسل رمز التحقق للمتابعة."
                messageIsError = false
            }
        }
    }

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
                text = "اختر حساب Google الموجود على هاتفك، ثم أدخل رمز التحقق المكوّن من 6 أرقام الذي سنرسله إلى البريد. لا تحتاج إلى كلمة مرور.",
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
                            email = ""
                            otp = ""
                            otpSent = false
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
                OutlinedButton(
                    onClick = {
                        val intent = AccountPicker.newChooseAccountIntent(
                            null,
                            null,
                            arrayOf("com.google"),
                            false,
                            null,
                            null,
                            null,
                            null
                        )
                        accountPicker.launch(intent)
                    },
                    enabled = !isBusy,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("اختيار حساب Google من الهاتف")
                }

                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        otp = ""
                        otpSent = false
                        message = null
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("البريد الإلكتروني المختار") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email)
                )

                if (!otpSent) {
                    Button(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                message = null
                                repository.sendEmailOtp(email).onSuccess {
                                    otpSent = true
                                    otp = ""
                                    message = "أرسلنا رمزاً من 6 أرقام إلى $email. تحقق من الوارد والرسائل غير المرغوب فيها."
                                    messageIsError = false
                                }.onFailure {
                                    message = it.localizedMessage ?: "تعذر إرسال رمز التحقق."
                                    messageIsError = true
                                }
                                isBusy = false
                            }
                        },
                        enabled = !isBusy && email.isNotBlank(),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("إرسال رمز التحقق إلى البريد")
                    }
                } else {
                    OutlinedTextField(
                        value = otp,
                        onValueChange = { value ->
                            otp = value.filter(Char::isDigit).take(6)
                            message = null
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("رمز التحقق المكوّن من 6 أرقام") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Button(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                message = null
                                repository.verifyEmailOtp(email, otp).onSuccess {
                                    account = it
                                    otp = ""
                                    otpSent = false
                                    message = "تم التحقق من البريد وتسجيل الدخول بنجاح."
                                    messageIsError = false
                                }.onFailure {
                                    message = it.localizedMessage ?: "رمز التحقق غير صحيح أو انتهت صلاحيته."
                                    messageIsError = true
                                }
                                isBusy = false
                            }
                        },
                        enabled = !isBusy && otp.length == 6,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("تأكيد الرمز وتسجيل الدخول")
                    }
                    OutlinedButton(
                        onClick = {
                            scope.launch {
                                isBusy = true
                                repository.sendEmailOtp(email).onSuccess {
                                    otp = ""
                                    message = "تم طلب إرسال رمز جديد. تحقق من بريدك."
                                    messageIsError = false
                                }.onFailure {
                                    message = it.localizedMessage ?: "تعذر إعادة إرسال الرمز."
                                    messageIsError = true
                                }
                                isBusy = false
                            }
                        },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("إعادة إرسال الرمز")
                    }
                    OutlinedButton(
                        onClick = {
                            otpSent = false
                            otp = ""
                            message = null
                        },
                        enabled = !isBusy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("اختيار بريد آخر")
                    }
                }
            }

            if (isBusy) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
