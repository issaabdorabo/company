package com.example.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class QrPairingHelperTest {

    @Test
    fun encodeAndParsePairingPayload_roundTripMatches() {
        val raw = QrPairingHelper.encodePairingPayload(
            hostAddress = " 192.168.1.44:8765 ",
            workspaceCode = "inv-ab12cd",
            plainPassword = "Pass#9988"
        )
        val parsed = QrPairingHelper.parsePairingQr(raw)
        assertNotNull(parsed)
        assertEquals("192.168.1.44:8765", parsed!!.host)
        assertEquals("INV-AB12CD", parsed.code)
        assertEquals("Pass#9988", parsed.pwd)
    }

    @Test
    fun parsePairingQr_rejectsForeignOrIncompletePayloads() {
        assertNull(QrPairingHelper.parsePairingQr(""))
        assertNull(QrPairingHelper.parsePairingQr("https://example.com"))
        assertNull(QrPairingHelper.parsePairingQr("""{"t":"OTHER","host":"1.2.3.4","code":"INV-1","pwd":"123"}"""))
        assertNull(QrPairingHelper.parsePairingQr("""{"t":"SMART_INV_PAIR","host":"1.2.3.4","code":"","pwd":"123"}"""))
    }

    @Test
    fun generateQrBitmap_decodesBackToOriginalPairingPayload() {
        val payload = QrPairingHelper.encodePairingPayload(
            hostAddress = "192.168.43.1:8765",
            workspaceCode = "INV-778899",
            plainPassword = "XyZ#4567"
        )
        val bitmap = QrPairingHelper.generateQrBitmap(payload, sizePx = 320)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val yBytes = ByteArray(width * height) { idx ->
            val rgb = pixels[idx]
            val r = (rgb shr 16) and 0xFF
            val g = (rgb shr 8) and 0xFF
            val b = rgb and 0xFF
            ((r + g + b) / 3).toByte()
        }

        val decoded = QrPairingHelper.decodeQrFromLuminance(yBytes, width, height)
        assertEquals(payload, decoded)
        val parsed = QrPairingHelper.parsePairingQr(decoded!!)
        assertNotNull(parsed)
        assertEquals("192.168.43.1:8765", parsed!!.host)
        assertEquals("INV-778899", parsed.code)
        assertEquals("XyZ#4567", parsed.pwd)
    }
    @Test
    fun cloudInviteCode_isHumanReadableAndRoundTripsThroughQrPayload() {
        val code = QrPairingHelper.generateCloudInviteCode()
        assertEquals(20, code.length)
        assertEquals(code, QrPairingHelper.normalizeCloudInviteCode(code))
        assertEquals(5, QrPairingHelper.formatCloudInviteCode(code).count { it == '-' } + 1)

        val payload = QrPairingHelper.encodeCloudPairingPayload(code)
        val parsed = QrPairingHelper.parsePairingQr(payload)
        assertNotNull(parsed)
        assertEquals(code, parsed!!.inviteCode)
        assertEquals(true, parsed.isCloudInviteCode)
    }

    @Test
    fun cloudInviteCode_rejectsAmbiguousOrMalformedPayloads() {
        assertNull(QrPairingHelper.parsePairingQr(
            """{"t":"SMART_INV_CLOUD_INVITE","code":"OOOOIIII1111"}"""
        ))
        assertNull(QrPairingHelper.parsePairingQr(
            """{"t":"SMART_INV_CLOUD_INVITE","code":"ABC123"}"""
        ))
        assertEquals("ABCDEFGH2345", QrPairingHelper.normalizeCloudInviteCode("ab-cd efgh-2345"))
    }


    @Test
    fun generatedCloudInviteQr_decodesToTheSameManualCode() {
        val code = QrPairingHelper.generateCloudInviteCode()
        val payload = QrPairingHelper.encodeCloudPairingPayload(code)
        val bitmap = QrPairingHelper.generateQrBitmap(payload, sizePx = 320)
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val luminance = ByteArray(width * height) { index ->
            val color = pixels[index]
            val red = (color shr 16) and 0xFF
            val green = (color shr 8) and 0xFF
            val blue = color and 0xFF
            ((red + green + blue) / 3).toByte()
        }

        val decoded = QrPairingHelper.decodeQrFromLuminance(luminance, width, height)
        val parsed = QrPairingHelper.parsePairingQr(decoded.orEmpty())
        assertNotNull(parsed)
        assertEquals(code, parsed!!.inviteCode)
    }

}
