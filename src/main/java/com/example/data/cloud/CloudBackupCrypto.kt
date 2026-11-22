package com.example.data.cloud

import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * Password-based encryption for portable Google Drive backups.
 * The passphrase is never persisted; the envelope contains only KDF parameters and ciphertext.
 */
object CloudBackupCrypto {
    private const val FORMAT = "smart_inventory_encrypted_backup"
    private const val FORMAT_VERSION = 1
    private const val ITERATIONS = 310_000
    private const val KEY_BITS = 256
    private const val SALT_BYTES = 16
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128

    fun encrypt(plainJson: String, passphrase: CharArray): String {
        require(plainJson.isNotBlank()) { "محتوى النسخة الاحتياطية فارغ." }
        require(passphrase.size >= 10) { "كلمة مرور النسخة يجب ألا تقل عن 10 أحرف." }

        val random = SecureRandom()
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val iv = ByteArray(IV_BYTES).also(random::nextBytes)
        val key = deriveKey(passphrase, salt)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD("$FORMAT:$FORMAT_VERSION".toByteArray(Charsets.UTF_8))
        val encrypted = cipher.doFinal(plainJson.toByteArray(Charsets.UTF_8))

        return JSONObject().apply {
            put("backupEncryption", FORMAT)
            put("formatVersion", FORMAT_VERSION)
            put("kdf", "PBKDF2WithHmacSHA256")
            put("iterations", ITERATIONS)
            put("cipher", "AES-256-GCM")
            put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            put("ciphertext", Base64.encodeToString(encrypted, Base64.NO_WRAP))
        }.toString()
    }

    /**
     * Returns legacy plaintext JSON unchanged for backward compatibility.
     * Encrypted envelopes are authenticated before any restore code can see their plaintext.
     */
    fun decryptOrLegacy(payload: String, passphrase: CharArray): String {
        require(payload.isNotBlank()) { "ملف النسخة الاحتياطية فارغ." }
        val envelope = runCatching { JSONObject(payload) }.getOrNull()
            ?: return payload
        if (!envelope.has("backupEncryption")) return payload

        require(envelope.optString("backupEncryption") == FORMAT) {
            "صيغة تشفير النسخة غير مدعومة."
        }
        require(envelope.optInt("formatVersion") == FORMAT_VERSION) {
            "إصدار تشفير النسخة غير مدعوم."
        }
        require(passphrase.size >= 10) { "كلمة المرور غير صحيحة أو قصيرة جداً." }

        val salt = Base64.decode(envelope.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(envelope.getString("iv"), Base64.NO_WRAP)
        val ciphertext = Base64.decode(envelope.getString("ciphertext"), Base64.NO_WRAP)
        require(salt.size == SALT_BYTES && iv.size == IV_BYTES) {
            "بيانات تشفير النسخة تالفة."
        }
        val iterations = envelope.optInt("iterations", -1)
        require(iterations in 100_000..1_000_000) { "إعدادات اشتقاق المفتاح غير صالحة." }

        val key = deriveKey(passphrase, salt, iterations)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
        cipher.updateAAD("$FORMAT:$FORMAT_VERSION".toByteArray(Charsets.UTF_8))
        return try {
            String(cipher.doFinal(ciphertext), Charsets.UTF_8)
        } catch (_: Exception) {
            throw IllegalArgumentException("كلمة المرور غير صحيحة أو أن ملف النسخة تعرض للتلف.")
        }
    }

    private fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int = ITERATIONS): SecretKeySpec {
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            val bytes = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(spec).encoded
            SecretKeySpec(bytes, "AES")
        } finally {
            spec.clearPassword()
        }
    }
}
