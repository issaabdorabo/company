package com.example.util

import java.security.MessageDigest
import java.security.SecureRandom

object SecurityUtils {
    private val secureRandom = SecureRandom()
    // Unambiguous characters (excluding 0/O, 1/I/l) for user readability
    private const val PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789@#$"
    private const val CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"

    fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
        return hash.joinToString("") { "%02x".format(it) }
    }

    fun generateSalt(): String {
        val bytes = ByteArray(16)
        secureRandom.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hashPassword(password: String, salt: String): String {
        return sha256("$salt:$password")
    }

    fun verifyPassword(password: String, salt: String, expectedHash: String): Boolean {
        val actual = hashPassword(password, salt)
        if (actual.length != expectedHash.length) return false
        var diff = 0
        for (i in actual.indices) {
            diff = diff or (actual[i].code xor expectedHash[i].code)
        }
        return diff == 0
    }

    fun computeInviteHash(workspaceCode: String, plainPassword: String): String {
        val normalizedCode = workspaceCode.trim().uppercase()
        val normalizedPwd = plainPassword.trim()
        return sha256("INVITE:$normalizedCode:$normalizedPwd")
    }

    fun generateUniqueDevicePassword(length: Int = 10): String {
        return buildString {
            repeat(length) {
                append(PASSWORD_CHARS[secureRandom.nextInt(PASSWORD_CHARS.length)])
            }
        }
    }

    fun generateWorkspaceCode(): String {
        val suffix = buildString {
            repeat(6) {
                append(CODE_CHARS[secureRandom.nextInt(CODE_CHARS.length)])
            }
        }
        return "INV-$suffix"
    }
}
