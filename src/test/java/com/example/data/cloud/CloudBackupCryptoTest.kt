package com.example.data.cloud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE)
class CloudBackupCryptoTest {

    @Test
    fun encryptedBackupRoundTripsWithCorrectPassphrase() {
        val original = """{"appName":"مخزني الذكي","warehouses":[{"id":1,"name":"المخزن الرئيسي"}]}"""
        val encrypted = CloudBackupCrypto.encrypt(original, "StrongPassphrase123".toCharArray())

        assertEquals(true, encrypted.contains("smart_inventory_encrypted_backup"))
        assertEquals(original, CloudBackupCrypto.decryptOrLegacy(encrypted, "StrongPassphrase123".toCharArray()))
    }

    @Test
    fun wrongPassphraseDoesNotReturnPlaintext() {
        val encrypted = CloudBackupCrypto.encrypt(
            """{"warehouses":[]}""",
            "StrongPassphrase123".toCharArray()
        )

        assertThrows(IllegalArgumentException::class.java) {
            CloudBackupCrypto.decryptOrLegacy(encrypted, "WrongPassphrase123".toCharArray())
        }
    }

    @Test
    fun legacyPlainJsonRemainsRestorableWithoutPassphrase() {
        val legacy = """{"appName":"مخزني الذكي","warehouses":[]}"""
        assertEquals(legacy, CloudBackupCrypto.decryptOrLegacy(legacy, charArrayOf()))
    }
}
