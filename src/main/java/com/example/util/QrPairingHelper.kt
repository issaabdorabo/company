package com.example.util

import android.graphics.Bitmap
import android.graphics.Color
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.json.JSONObject

data class QrPairingPayload(
    val host: String,
    val code: String,
    val pwd: String
)

object QrPairingHelper {
    private const val PAYLOAD_TYPE = "SMART_INV_PAIR"

    fun encodePairingPayload(
        hostAddress: String,
        workspaceCode: String,
        plainPassword: String
    ): String {
        return JSONObject().apply {
            put("t", PAYLOAD_TYPE)
            put("transport", "FIREBASE")
            put("code", workspaceCode.trim().uppercase())
            put("pwd", plainPassword.trim())
        }.toString()
    }

    fun parsePairingQr(raw: String): QrPairingPayload? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            val obj = JSONObject(trimmed)
            if (obj.optString("t") != PAYLOAD_TYPE) return null
            val host = "FIREBASE"
            val code = obj.optString("code").trim().uppercase()
            val pwd = obj.optString("pwd").trim()
            if (code.isBlank() || pwd.isBlank()) return null
            QrPairingPayload(host = host, code = code, pwd = pwd)
        } catch (_: Exception) {
            null
        }
    }

    fun generateQrBitmap(content: String, sizePx: Int = 560): Bitmap {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2
        )
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            for (x in 0 until width) {
                pixels[rowOffset + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
        }
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply {
            setPixels(pixels, 0, width, 0, 0, width, height)
        }
    }

    fun decodeQrFromImageProxy(imageProxy: ImageProxy): String? {
        return try {
            val yPlane = imageProxy.planes.firstOrNull() ?: return null
            val width = imageProxy.width
            val height = imageProxy.height
            if (width <= 0 || height <= 0) return null

            val buffer = yPlane.buffer
            val rowStride = yPlane.rowStride
            val yBytes = ByteArray(width * height)
            if (rowStride == width) {
                val available = buffer.remaining().coerceAtMost(yBytes.size)
                buffer.get(yBytes, 0, available)
            } else {
                val rowBuffer = ByteArray(rowStride)
                for (row in 0 until height) {
                    val toRead = if (row == height - 1) width else rowStride
                    val readable = buffer.remaining().coerceAtMost(toRead)
                    if (readable <= 0) break
                    buffer.get(rowBuffer, 0, readable)
                    System.arraycopy(rowBuffer, 0, yBytes, row * width, width.coerceAtMost(readable))
                }
            }

            decodeQrFromLuminance(yBytes, width, height)
                ?: decodeQrFromLuminance(rotate90Clockwise(yBytes, width, height), height, width)
        } catch (_: Exception) {
            null
        } finally {
            imageProxy.close()
        }
    }

    internal fun decodeQrFromLuminance(yBytes: ByteArray, width: Int, height: Int): String? {
        val hints = mapOf(DecodeHintType.TRY_HARDER to true)
        val source = PlanarYUVLuminanceSource(
            yBytes,
            width,
            height,
            0,
            0,
            width,
            height,
            false
        )
        val reader = QRCodeReader()
        return try {
            val bitmap = BinaryBitmap(HybridBinarizer(source))
            reader.decode(bitmap, hints).text
        } catch (_: NotFoundException) {
            try {
                val invertedBitmap = BinaryBitmap(HybridBinarizer(source.invert()))
                reader.decode(invertedBitmap, hints).text
            } catch (_: Exception) {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun rotate90Clockwise(data: ByteArray, width: Int, height: Int): ByteArray {
        val out = ByteArray(data.size)
        var i = 0
        for (x in 0 until width) {
            for (y in height - 1 downTo 0) {
                out[i++] = data[y * width + x]
            }
        }
        return out
    }
}
