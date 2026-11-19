package com.example.service

import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import com.example.SmartBrowserApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.net.Socket
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class BrowserVpnService : VpnService() {

    companion object {
        const val ACTION_START_VPN = "com.example.action.START_VPN"
        const val ACTION_STOP_VPN = "com.example.action.STOP_VPN"

        fun start(context: Context) {
            val intent = Intent(context, BrowserVpnService::class.java).apply {
                action = ACTION_START_VPN
            }
            runCatching { context.startService(intent) }
        }

        fun stop(context: Context) {
            val intent = Intent(context, BrowserVpnService::class.java).apply {
                action = ACTION_STOP_VPN
            }
            runCatching { context.startService(intent) }
        }
    }

    private val vpnScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var vpnInterface: ParcelFileDescriptor? = null
    private var tunnelJob: Job? = null

    private val app: SmartBrowserApp
        get() = application as SmartBrowserApp

    private val dohClient: OkHttpClient by lazy {
        val protectedFactory = object : SocketFactory() {
            private val delegate = getDefault()
            override fun createSocket(): Socket = delegate.createSocket().also { protect(it) }
            override fun createSocket(host: String?, port: Int): Socket =
                delegate.createSocket(host, port).also { protect(it) }
            override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(host, port, localHost, localPort).also { protect(it) }
            override fun createSocket(host: InetAddress?, port: Int): Socket =
                delegate.createSocket(host, port).also { protect(it) }
            override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
                delegate.createSocket(address, port, localAddress, localPort).also { protect(it) }
        }
        OkHttpClient.Builder()
            .socketFactory(protectedFactory)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_VPN -> {
                stopVpnTunnel()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                if (vpnInterface == null) {
                    startVpnTunnel()
                }
            }
        }
        return START_STICKY
    }

    override fun onRevoke() {
        stopVpnTunnel()
        stopSelf()
        super.onRevoke()
    }

    override fun onDestroy() {
        stopVpnTunnel()
        vpnScope.cancel()
        super.onDestroy()
    }

    private fun startVpnTunnel() {
        app.downloadRepository.updateVpnState {
            it.copy(isConnecting = true, statusMessage = "جاري تشغيل نفق VPN...")
        }
        try {
            val builder = Builder()
                .setSession("SmartBrowser VPN")
                .addAddress("10.10.0.1", 30)
                .addDnsServer("10.10.0.2")
                .addRoute("10.10.0.2", 32)
                .setMtu(1500)

            val established = builder.establish()
            if (established == null) {
                app.downloadRepository.updateVpnState {
                    it.copy(
                        isVpnActive = false,
                        isConnecting = false,
                        statusMessage = "بانتظار إذن VPN من النظام"
                    )
                }
                return
            }

            vpnInterface = established
            app.downloadRepository.updateVpnState {
                it.copy(
                    isVpnActive = true,
                    isConnecting = false,
                    statusMessage = "VPN متصل تلقائياً (DNS مشفر)"
                )
            }

            tunnelJob?.cancel()
            tunnelJob = vpnScope.launch {
                runDnsTunnelLoop(established)
            }
        } catch (e: Exception) {
            app.downloadRepository.updateVpnState {
                it.copy(
                    isVpnActive = false,
                    isConnecting = false,
                    statusMessage = "تعذر تشغيل VPN: ${e.localizedMessage ?: "خطأ"}"
                )
            }
        }
    }

    private fun stopVpnTunnel() {
        tunnelJob?.cancel()
        tunnelJob = null
        runCatching { vpnInterface?.close() }
        vpnInterface = null
        app.downloadRepository.updateVpnState {
            it.copy(
                isVpnActive = false,
                isConnecting = false,
                statusMessage = "غير متصل"
            )
        }
    }

    private fun runDnsTunnelLoop(tunFd: ParcelFileDescriptor) {
        val input = FileInputStream(tunFd.fileDescriptor)
        val output = FileOutputStream(tunFd.fileDescriptor)
        val buffer = ByteArray(32767)

        while (vpnScope.isActive && vpnInterface != null) {
            val length = try {
                input.read(buffer)
            } catch (_: Exception) {
                break
            }
            if (length <= 0) {
                Thread.sleep(10)
                continue
            }

            val packet = buffer.copyOf(length)
            if (!isIpv4UdpPacket(packet)) continue

            val ihl = (packet[0].toInt() and 0x0F) * 4
            if (packet.size < ihl + 8) continue
            val dstPort = ((packet[ihl + 2].toInt() and 0xFF) shl 8) or (packet[ihl + 3].toInt() and 0xFF)
            if (dstPort != 53) continue

            val udpLength = ((packet[ihl + 4].toInt() and 0xFF) shl 8) or (packet[ihl + 5].toInt() and 0xFF)
            if (packet.size < ihl + udpLength || udpLength <= 8) continue

            val dnsQuery = packet.copyOfRange(ihl + 8, ihl + udpLength)
            vpnScope.launch {
                val dnsReply = forwardDnsOverHttps(dnsQuery)
                if (dnsReply != null) {
                    val responsePacket = buildUdpIpv4Response(packet, ihl, dnsReply)
                    synchronized(output) {
                        runCatching {
                            output.write(responsePacket)
                            output.flush()
                        }
                    }
                    app.downloadRepository.updateVpnState {
                        it.copy(dnsQueriesEncrypted = it.dnsQueriesEncrypted + 1)
                    }
                }
            }
        }
    }

    private fun isIpv4UdpPacket(packet: ByteArray): Boolean {
        if (packet.size < 28) return false
        val version = (packet[0].toInt() shr 4) and 0x0F
        val protocol = packet[9].toInt() and 0xFF
        return version == 4 && protocol == 17
    }

    private fun forwardDnsOverHttps(query: ByteArray): ByteArray? {
        return try {
            val body = query.toRequestBody("application/dns-message".toMediaType())
            val request = Request.Builder()
                .url("https://1.1.1.1/dns-query")
                .header("Accept", "application/dns-message")
                .header("Content-Type", "application/dns-message")
                .post(body)
                .build()

            dohClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) response.body?.bytes() else null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun buildUdpIpv4Response(requestPacket: ByteArray, reqIhl: Int, dnsPayload: ByteArray): ByteArray {
        val ipHeaderLen = 20
        val udpHeaderLen = 8
        val totalLen = ipHeaderLen + udpHeaderLen + dnsPayload.size
        val out = ByteArray(totalLen)

        // IPv4 Header
        out[0] = 0x45.toByte()
        out[1] = 0x00
        out[2] = ((totalLen shr 8) and 0xFF).toByte()
        out[3] = (totalLen and 0xFF).toByte()
        out[4] = requestPacket[4]
        out[5] = requestPacket[5]
        out[6] = 0x40
        out[7] = 0x00
        out[8] = 64.toByte()
        out[9] = 17.toByte() // UDP
        out[10] = 0
        out[11] = 0
        // Swap src <-> dst IP
        System.arraycopy(requestPacket, 16, out, 12, 4)
        System.arraycopy(requestPacket, 12, out, 16, 4)

        val ipChecksum = computeChecksum(out, 0, ipHeaderLen)
        out[10] = ((ipChecksum shr 8) and 0xFF).toByte()
        out[11] = (ipChecksum and 0xFF).toByte()

        // UDP Header (swap src <-> dst port)
        out[ipHeaderLen] = requestPacket[reqIhl + 2]
        out[ipHeaderLen + 1] = requestPacket[reqIhl + 3]
        out[ipHeaderLen + 2] = requestPacket[reqIhl]
        out[ipHeaderLen + 3] = requestPacket[reqIhl + 1]
        val udpLen = udpHeaderLen + dnsPayload.size
        out[ipHeaderLen + 4] = ((udpLen shr 8) and 0xFF).toByte()
        out[ipHeaderLen + 5] = (udpLen and 0xFF).toByte()
        out[ipHeaderLen + 6] = 0
        out[ipHeaderLen + 7] = 0

        System.arraycopy(dnsPayload, 0, out, ipHeaderLen + udpHeaderLen, dnsPayload.size)

        val udpChecksum = computeUdpChecksum(out, ipHeaderLen, udpLen)
        out[ipHeaderLen + 6] = ((udpChecksum shr 8) and 0xFF).toByte()
        out[ipHeaderLen + 7] = (udpChecksum and 0xFF).toByte()
        return out
    }

    private fun computeChecksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var i = offset
        while (i < offset + length - 1) {
            val word = ((data[i].toInt() and 0xFF) shl 8) or (data[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (length % 2 == 1) {
            sum += (data[offset + length - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    private fun computeUdpChecksum(packet: ByteArray, udpOffset: Int, udpLen: Int): Int {
        var sum = 0L
        // Pseudo header: src IP (12..15), dst IP (16..19), zero + proto(17), udpLen
        for (i in 12 until 20 step 2) {
            sum += ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
        }
        sum += 17L
        sum += udpLen.toLong()

        var i = udpOffset
        while (i < udpOffset + udpLen - 1) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (udpLen % 2 == 1) {
            sum += (packet[udpOffset + udpLen - 1].toInt() and 0xFF) shl 8
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = (sum.inv() and 0xFFFF).toInt()
        return if (checksum == 0) 0xFFFF else checksum
    }
}
