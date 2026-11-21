package com.example.data.sync

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import com.example.data.cloud.DiscoveredMainDevice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import java.net.Inet4Address

/**
 * Zero-permission LAN mDNS (NsdManager) helper for auto-discovering the Main Device
 * on the same Wi-Fi or Mobile Hotspot network.
 */
class NsdDiscoveryHelper(private val context: Context) {
    companion object {
        const val SERVICE_TYPE = "_smartinv._tcp."
    }

    private val nsdManager: NsdManager? by lazy {
        runCatching { context.getSystemService(Context.NSD_SERVICE) as? NsdManager }.getOrNull()
    }

    private var registrationListener: NsdManager.RegistrationListener? = null

    fun registerMainService(port: Int, workspaceCode: String) {
        unregisterMainService()
        val manager = nsdManager ?: return
        if (port <= 0 || workspaceCode.isBlank()) return

        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "SmartInv-$workspaceCode"
            serviceType = SERVICE_TYPE
            setPort(port)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }

        registrationListener = listener
        runCatching {
            manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun unregisterMainService() {
        val listener = registrationListener ?: return
        registrationListener = null
        runCatching {
            nsdManager?.unregisterService(listener)
        }
    }

    @Suppress("DEPRECATION")
    fun observeDiscoveredMainDevices(): Flow<List<DiscoveredMainDevice>> = callbackFlow {
        val manager = nsdManager
        if (manager == null) {
            trySend(emptyList())
            awaitClose {}
            return@callbackFlow
        }

        val discoveredMap = LinkedHashMap<String, DiscoveredMainDevice>()

        fun emitSnapshot() {
            trySend(discoveredMap.values.toList())
        }

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {}

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (!serviceInfo.serviceType.contains("_smartinv", ignoreCase = true)) return
                val rawName = serviceInfo.serviceName ?: return
                if (!rawName.startsWith("SmartInv-")) return

                runCatching {
                    manager.resolveService(
                        serviceInfo,
                        object : NsdManager.ResolveListener {
                            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {}

                            override fun onServiceResolved(resolved: NsdServiceInfo) {
                                val hostAddr = resolved.host
                                val ip = (hostAddr as? Inet4Address)?.hostAddress
                                    ?: hostAddr?.hostAddress
                                    ?: return
                                val port = resolved.port
                                if (port <= 0) return
                                val code = resolved.serviceName.removePrefix("SmartInv-")
                                val item = DiscoveredMainDevice(
                                    serviceName = resolved.serviceName,
                                    workspaceCode = code,
                                    hostAddress = "$ip:$port"
                                )
                                synchronized(discoveredMap) {
                                    discoveredMap[resolved.serviceName] = item
                                    emitSnapshot()
                                }
                            }
                        }
                    )
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                synchronized(discoveredMap) {
                    discoveredMap.remove(serviceInfo.serviceName)
                    emitSnapshot()
                }
            }

            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                emitSnapshot()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        }

        runCatching {
            manager.discoverServices(SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        }.onFailure {
            trySend(emptyList())
        }

        awaitClose {
            runCatching { manager.stopServiceDiscovery(discoveryListener) }
        }
    }
}
