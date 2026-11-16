package com.example.util

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import com.example.R
import com.google.firebase.FirebaseOptions
import java.security.MessageDigest

data class ApkSigningReport(
    val packageName: String,
    val sha1ColonHex: String,
    val sha1NoColonsLower: String,
    val sha256ColonHex: String,
    val registeredSha1NoColons: String,
    val matchesRegisteredFirebaseCert: Boolean
)

data class FirebaseConfigInspection(
    val projectId: String,
    val applicationId: String,
    val apiKey: String,
    val webClientId: String,
    val firestoreDatabaseId: String,
    val isDummyFallback: Boolean,
    val isConfigComplete: Boolean,
    val missingItems: List<String>
)

object FirebaseDiagnosticsHelper {
    // Registered Firebase project & SHA-1 certificates in app/google-services.json
    const val REGISTERED_FIREBASE_PROJECT_ID = "gen-lang-client-0861143851"
    const val REGISTERED_FIREBASE_SHA1_NO_COLONS = "1fcb1d541678f3cb71484231763c1491b780595c"
    const val REGISTERED_FIREBASE_SHA1_COLON = "1F:CB:1D:54:16:78:F3:CB:71:48:42:31:76:3C:14:91:B7:80:59:5C"

    val REGISTERED_FIREBASE_SHA1_SET_NO_COLONS = setOf(
        "1fcb1d541678f3cb71484231763c1491b780595c", // Fixed repository debug.keystore.base64
        "bb0d99561befb9a2b41353933ef7079496b5a93f", // Registered external/phone certificate
        "fef21259fb0f4f68c42551257269410937869450"  // Registered secondary certificate
    )

    fun normalizeSha1NoColons(sha1: String): String {
        return sha1.replace(":", "").trim().lowercase()
    }

    fun formatBytesAsColonHex(bytes: ByteArray): String {
        return bytes.joinToString(":") { b -> "%02X".format(b) }
    }

    fun computeDigestColonHex(rawCertBytes: ByteArray, algorithm: String): String {
        val md = MessageDigest.getInstance(algorithm)
        return formatBytesAsColonHex(md.digest(rawCertBytes))
    }

    fun isRegisteredSha1Match(sha1ColonOrRaw: String): Boolean {
        val normalized = normalizeSha1NoColons(sha1ColonOrRaw)
        return normalized.isNotBlank() && normalized in REGISTERED_FIREBASE_SHA1_SET_NO_COLONS
    }

    fun isPlaceholderFirebaseConfig(
        projectId: String,
        apiKey: String = "",
        webClientId: String = "",
        applicationId: String = ""
    ): Boolean {
        return projectId.equals("dummy-project", ignoreCase = true) ||
            projectId.equals("remixed-project-id", ignoreCase = true) ||
            projectId.startsWith("dummy", ignoreCase = true) ||
            apiKey.contains("dummy", ignoreCase = true) ||
            webClientId.contains("dummy", ignoreCase = true) ||
            applicationId.contains("dummy", ignoreCase = true)
    }

    @Suppress("DEPRECATION")
    fun getApkSigningInfo(context: Context): ApkSigningReport {
        val packageName = context.packageName
        val certBytes: ByteArray? = try {
            val pm = context.packageManager
            val signatures: Array<Signature>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info: PackageInfo = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNING_CERTIFICATES
                )
                val signingInfo = info.signingInfo
                if (signingInfo != null) {
                    if (signingInfo.hasMultipleSigners()) {
                        signingInfo.apkContentsSigners
                    } else {
                        signingInfo.signingCertificateHistory
                    }
                } else {
                    null
                }
            } else {
                val info: PackageInfo = pm.getPackageInfo(
                    packageName,
                    PackageManager.GET_SIGNATURES
                )
                info.signatures
            }
            signatures?.firstOrNull()?.toByteArray()
        } catch (_: Exception) {
            null
        }

        val sha1Colon = certBytes?.let { computeDigestColonHex(it, "SHA-1") } ?: "غير متاح"
        val sha256Colon = certBytes?.let { computeDigestColonHex(it, "SHA-256") } ?: "غير متاح"
        val sha1Raw = if (certBytes != null) normalizeSha1NoColons(sha1Colon) else ""

        return ApkSigningReport(
            packageName = packageName,
            sha1ColonHex = sha1Colon,
            sha1NoColonsLower = sha1Raw,
            sha256ColonHex = sha256Colon,
            registeredSha1NoColons = REGISTERED_FIREBASE_SHA1_NO_COLONS,
            matchesRegisteredFirebaseCert = isRegisteredSha1Match(sha1Raw)
        )
    }

    fun inspectFirebaseConfig(context: Context): FirebaseConfigInspection {
        val opts = runCatching { FirebaseOptions.fromResource(context) }.getOrNull()
        val projectId = opts?.projectId.orEmpty()
        val appId = opts?.applicationId.orEmpty()
        val apiKey = opts?.apiKey.orEmpty()
        val webClientId = runCatching { context.getString(R.string.default_web_client_id) }.getOrDefault("")
        val firestoreDbId = runCatching { context.getString(R.string.firestore_database_id) }.getOrDefault("")

        val isDummy = isPlaceholderFirebaseConfig(
            projectId = projectId,
            apiKey = apiKey,
            webClientId = webClientId,
            applicationId = appId
        )

        val missing = mutableListOf<String>()
        if (projectId.isBlank() || projectId == "remixed-project-id" || projectId.contains("dummy", ignoreCase = true)) {
            missing.add("project_id (${projectId.ifBlank { "missing" }})")
        }
        if (appId.isBlank() || appId.contains("dummy", ignoreCase = true)) missing.add("mobilesdk_app_id")
        if (apiKey.isBlank() || apiKey.contains("dummy", ignoreCase = true)) missing.add("google_api_key")
        if (webClientId.isBlank() || webClientId.contains("dummy", ignoreCase = true)) missing.add("default_web_client_id")
        if (firestoreDbId.isBlank()) missing.add("firestore_database_id")

        return FirebaseConfigInspection(
            projectId = projectId,
            applicationId = appId,
            apiKey = apiKey,
            webClientId = webClientId,
            firestoreDatabaseId = firestoreDbId,
            isDummyFallback = isDummy,
            isConfigComplete = missing.isEmpty(),
            missingItems = missing
        )
    }

    fun translateAuthErrorToArabic(
        rawMessage: String?,
        context: Context? = null,
        isCancellation: Boolean = false,
        isNoCredential: Boolean = false
    ): String {
        val msg = rawMessage.orEmpty()
        val signingReport = context?.let { runCatching { getApkSigningInfo(it) }.getOrNull() }
        val firebaseConfig = context?.let { runCatching { inspectFirebaseConfig(it) }.getOrNull() }

        val sha1Hint = signingReport?.let { report ->
            if (report.sha1NoColonsLower.isNotBlank() && !report.matchesRegisteredFirebaseCert) {
                "\n• تنبيه التوقيع: بصمة APK الحالية على هذا الهاتف (${report.sha1ColonHex}) لا تطابق بصمة Firebase المعتمدة ($REGISTERED_FIREBASE_SHA1_COLON). انسخ البصمة وأضفها في Firebase & Google Cloud أو ثبّت نسخة البناء من GitHub Actions."
            } else {
                ""
            }
        }.orEmpty()

        if (firebaseConfig != null && firebaseConfig.isDummyFallback) {
            return "تعذر التوثيق لأن هذا البناء افتقد ملف app/google-services.json فتم حقن مشروع وهمي (${firebaseConfig.projectId.ifBlank { "dummy-project" }}) ومفتاح API غير صالح.\n• الحل: تأكد من وجود app/google-services.json في مستودع GitHub (أو في GitHub Secret باسم GOOGLE_SERVICES_JSON) للمشروع $REGISTERED_FIREBASE_PROJECT_ID ثم أعد بناء APK.$sha1Hint"
        }

        return when {
            msg.contains("API key not valid", ignoreCase = true) ||
                msg.contains("API_KEY_INVALID", ignoreCase = true) ||
                msg.contains("INVALID_API_KEY", ignoreCase = true) -> {
                "رفض خادم Firebase مفتاح الـ API لهذا التثبيت (API key not valid). يحدث هذا عندما يبنى التطبيق بدون app/google-services.json أو عند تقييد المفتاح في Google Cloud لبصمة SHA-1 مختلفة.$sha1Hint"
            }

            msg.contains("DEVELOPER_ERROR", ignoreCase = true) ||
                msg.contains("status code: 10", ignoreCase = true) ||
                msg.contains("10:", ignoreCase = true) ||
                msg.contains("12500", ignoreCase = true) -> {
                "فشل التحقق من هوية التطبيق لدى Google (خطأ المطور 10): بصمة SHA-1 لتوقيع الحزمة الحالية لا تطابق البصمة المسجلة في مشروع Firebase ($REGISTERED_FIREBASE_PROJECT_ID).$sha1Hint"
            }

            msg.contains("Unable to resolve host", ignoreCase = true) ||
                msg.contains("network", ignoreCase = true) ||
                msg.contains("timeout", ignoreCase = true) -> {
                "تعذر الاتصال بخوادم Google لعدم توفر اتصال مستقر بالإنترنت. يمكنك الاستمرار بالعمل محلياً بشكل طبيعي تماماً بدون توثيق."
            }

            isNoCredential || msg.contains("NoCredential", ignoreCase = true) -> {
                "لم يتم العثور على حساب Google نشط متوافق في مدير الاعتمادات. تأكد من إضافة حساب Google في إعدادات الهاتف أو استخدم خيار المتصفح أدناه."
            }

            isCancellation ||
                msg.contains("canceled", ignoreCase = true) ||
                msg.contains("cancelled", ignoreCase = true) -> {
                if (sha1Hint.isNotBlank()) {
                    "تم إلغاء نافذة تسجيل الدخول بحساب Google تلقائياً بسبب عدم تطابق بصمة توقيع التطبيق.$sha1Hint"
                } else {
                    "تم إلغاء نافذة تسجيل الدخول بحساب Google. يمكنك إعادة المحاولة أو استخدام خيار اختيار الحساب عبر المتصفح أدناه."
                }
            }

            else -> {
                "تعذرت المصادقة بحساب Google: ${msg.ifBlank { "تأكد من الاتصال بالإنترنت وإعدادات Google Play" }}$sha1Hint"
            }
        }
    }
}
