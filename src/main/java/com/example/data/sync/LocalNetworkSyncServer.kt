package com.example.data.sync

import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.LocalServerStatus
import com.example.util.SecurityUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException

/**
 * Embedded LAN HTTP/JSON Sync Server running on the Main Device.
 * Requires zero Google Sign-In, zero Firebase API keys, and zero external servers.
 */
class LocalNetworkSyncServer(
    private val scope: CoroutineScope,
    private val getWorkspaceCode: suspend () -> String,
    private val getOrgName: suspend () -> String,
    private val getSyncVersion: suspend () -> Long,
    private val getLinkedDevices: suspend () -> List<LinkedSubDevice>,
    private val onSaveLinkedDevices: suspend (List<LinkedSubDevice>) -> Unit,
    private val onExportEntities: suspend () -> List<CloudEntityUpsert>,
    private val onApplyRemoteEntities: suspend (List<CloudEntityRecord>) -> Unit,
    private val onRemoteOperationAlert: (
        subDeviceName: String,
        actorName: String,
        actionType: String,
        operationTitle: String,
        operationDetails: String
    ) -> Unit = { _, _, _, _, _ -> }
) {
    companion object {
        const val DEFAULT_PORT = 8765
        const val MAX_BODY_BYTES = 16 * 1024 * 1024 // 16 MB safety cap

        fun getLocalLanIpAddresses(): List<String> {
            val prioritized = mutableListOf<String>()
            val others = mutableListOf<String>()
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces() ?: return emptyList()
                for (iface in interfaces) {
                    if (!iface.isUp || iface.isLoopback) continue
                    val name = iface.name.lowercase()
                    val isWifiOrHotspot = name.startsWith("wlan") ||
                        name.startsWith("ap") ||
                        name.startsWith("swlan") ||
                        name.contains("wifi")
                    val addrs = iface.inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (addr is Inet4Address && !addr.isLoopbackAddress) {
                            val ip = addr.hostAddress ?: continue
                            if (isWifiOrHotspot) prioritized.add(ip) else others.add(ip)
                        }
                    }
                }
            } catch (_: Exception) {
            }
            return (prioritized + others).distinct()
        }
    }

    private val writeMutex = Mutex()
    private var serverSocket: ServerSocket? = null
    private var acceptJob: Job? = null

    private val _status = MutableStateFlow(LocalServerStatus())
    val status: StateFlow<LocalServerStatus> = _status.asStateFlow()

    val actualPort: Int
        get() = serverSocket?.localPort ?: 0

    @Synchronized
    fun start(preferredPort: Int = DEFAULT_PORT, bindLoopbackOnly: Boolean = false): Int {
        if (serverSocket != null && serverSocket?.isClosed == false && acceptJob?.isActive == true) {
            refreshIpAddresses()
            return serverSocket!!.localPort
        }
        stop()

        val socket = openServerSocket(preferredPort, bindLoopbackOnly)
        if (socket == null) {
            scope.launch {
                _status.value = LocalServerStatus(
                    isRunning = false,
                    port = preferredPort,
                    hostAddresses = emptyList(),
                    workspaceCode = runCatching { getWorkspaceCode() }.getOrDefault(""),
                    errorMessage = "تعذر تشغيل خادم الربط على المنفذ المحلي"
                )
            }
            return 0
        }

        serverSocket = socket
        val boundPort = socket.localPort
        refreshStatusAsync(isRunning = true, port = boundPort)

        acceptJob = scope.launch(Dispatchers.IO) {
            while (isActive && !socket.isClosed) {
                try {
                    val client = socket.accept()
                    launch(Dispatchers.IO) {
                        handleClient(client)
                    }
                } catch (_: SocketException) {
                    break
                } catch (_: Exception) {
                    if (socket.isClosed) break
                }
            }
        }
        return boundPort
    }

    fun refreshIpAddresses() {
        val current = serverSocket
        if (current != null && !current.isClosed) {
            refreshStatusAsync(isRunning = true, port = current.localPort)
        }
    }

    private fun refreshStatusAsync(isRunning: Boolean, port: Int) {
        scope.launch {
            val code = runCatching { getWorkspaceCode() }.getOrDefault("")
            val ips = getLocalLanIpAddresses()
            _status.value = LocalServerStatus(
                isRunning = isRunning,
                port = port,
                hostAddresses = ips,
                workspaceCode = code,
                errorMessage = null
            )
        }
    }

    @Synchronized
    fun stop() {
        acceptJob?.cancel()
        acceptJob = null
        try {
            serverSocket?.close()
        } catch (_: Exception) {
        }
        serverSocket = null
        _status.value = _status.value.copy(isRunning = false)
    }

    private fun openServerSocket(preferredPort: Int, bindLoopbackOnly: Boolean): ServerSocket? {
        val bindAddr: InetAddress? = if (bindLoopbackOnly) InetAddress.getByName("127.0.0.1") else null
        if (preferredPort == 0) {
            return runCatching { ServerSocket(0, 50, bindAddr) }.getOrNull()
        }
        for (port in preferredPort..(preferredPort + 10)) {
            val created = runCatching { ServerSocket(port, 50, bindAddr) }.getOrNull()
            if (created != null) return created
        }
        return runCatching { ServerSocket(0, 50, bindAddr) }.getOrNull()
    }

    private suspend fun handleClient(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 10_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()

            val request = readHttpRequest(input) ?: return
            val path = request.path.substringBefore("?")
            val response: HttpResponse = try {
                when {
                    request.method == "GET" && path == "/api/ping" -> handlePing()
                    request.method == "POST" && path == "/api/pair" -> handlePair(request.body)
                    request.method == "GET" && path == "/api/sync" -> handleSyncPull(request.headers)
                    request.method == "POST" && path == "/api/entities" -> handleEntitiesPush(request.headers, request.body)
                    else -> jsonResponse(404, JSONObject().put("error", "NOT_FOUND").put("message", "المسار غير موجود"))
                }
            } catch (e: Exception) {
                jsonResponse(
                    500,
                    JSONObject()
                        .put("error", "SERVER_ERROR")
                        .put("message", e.localizedMessage ?: "خطأ داخلي في الخادم")
                )
            }

            val bodyBytes = response.body.toByteArray(Charsets.UTF_8)
            val headerBuilder = StringBuilder()
                .append("HTTP/1.1 ").append(response.statusCode).append(' ').append(response.reasonPhrase).append("\r\n")
                .append("Content-Type: application/json; charset=utf-8\r\n")
                .append("Content-Length: ").append(bodyBytes.size).append("\r\n")
                .append("Connection: close\r\n")
                .append("\r\n")
            output.write(headerBuilder.toString().toByteArray(Charsets.US_ASCII))
            output.write(bodyBytes)
            output.flush()
        }
    }

    private suspend fun handlePing(): HttpResponse {
        val code = getWorkspaceCode()
        val org = getOrgName()
        val rev = getSyncVersion()
        val count = getLinkedDevices().count { !it.isKicked }
        return jsonResponse(
            200,
            JSONObject().apply {
                put("ok", true)
                put("workspaceCode", code)
                put("orgName", org)
                put("syncVersion", rev)
                put("deviceCount", count)
            }
        )
    }

    private suspend fun handlePair(rawBody: String): HttpResponse {
        val body = runCatching { JSONObject(rawBody) }.getOrNull()
            ?: return jsonResponse(400, errorJson("BAD_REQUEST", "صيغة الطلب غير صالحة"))

        val requestedCode = body.optString("workspaceCode").trim().uppercase()
        val plainPassword = body.optString("password").trim()
        val installationId = body.optString("installationId").trim()

        if (requestedCode.isBlank() || plainPassword.isBlank() || installationId.isBlank()) {
            return jsonResponse(400, errorJson("MISSING_FIELDS", "يرجى إدخال رمز القاعدة وكلمة المرور"))
        }

        val currentCode = getWorkspaceCode().trim().uppercase()
        if (currentCode.isBlank() || requestedCode != currentCode) {
            return jsonResponse(401, errorJson("INVALID_WORKSPACE", "رمز قاعدة البيانات غير صحيح"))
        }

        return writeMutex.withLock {
            val devices = getLinkedDevices()
            val matchedIndex = devices.indexOfFirst { slot ->
                SecurityUtils.verifyPassword(plainPassword, slot.passwordSalt, slot.passwordHash)
            }

            if (matchedIndex < 0) {
                return@withLock jsonResponse(
                    401,
                    errorJson("INVALID_PASSWORD", "كلمة المرور المنفردة غير صحيحة")
                )
            }

            val slot = devices[matchedIndex]
            if (slot.isKicked) {
                return@withLock jsonResponse(
                    403,
                    errorJson("KICKED", "تم طرد هذا الحساب وإيقافه من الجهاز الرئيسي")
                )
            }

            if (slot.isClaimed && slot.boundInstallationId.isNotBlank() && slot.boundInstallationId != installationId) {
                return@withLock jsonResponse(
                    403,
                    errorJson(
                        "DEVICE_ALREADY_BOUND",
                        "تم استخدام كلمة المرور هذه مسبقاً على جهاز آخر ولا يمكن استخدامها لأكثر من جهاز"
                    )
                )
            }

            val now = System.currentTimeMillis()
            val updatedSlot = slot.copy(
                isClaimed = true,
                boundInstallationId = installationId,
                updatedAt = now,
                lastSeenAt = now
            )
            val updatedList = devices.toMutableList().apply { set(matchedIndex, updatedSlot) }
            onSaveLinkedDevices(updatedList)

            val entities = onExportEntities()
            val syncVersion = getSyncVersion()
            val orgName = getOrgName()

            runCatching {
                onRemoteOperationAlert(
                    updatedSlot.deviceName,
                    "",
                    "PAIR",
                    "ربط جهاز فرعي جديد",
                    "انضم الجهاز الفرعي (${updatedSlot.deviceName}) بنجاح إلى الشبكة المحلية"
                )
            }

            jsonResponse(
                200,
                JSONObject().apply {
                    put("workspaceCode", currentCode)
                    put("orgName", orgName)
                    put("deviceSlotId", updatedSlot.id)
                    put("deviceName", updatedSlot.deviceName)
                    put("sessionToken", updatedSlot.sessionToken)
                    put("permissions", LocalSyncJson.permissionsToJson(updatedSlot.toPermissions()))
                    put("syncVersion", syncVersion)
                    put("entities", LocalSyncJson.upsertsToJsonArray(entities))
                }
            )
        }
    }

    private suspend fun handleSyncPull(headers: Map<String, String>): HttpResponse {
        val token = extractBearerToken(headers)
            ?: return jsonResponse(403, errorJson("KICKED", "لا يوجد تصريح ارتباط صالح"))

        val devices = getLinkedDevices()
        val slotIndex = devices.indexOfFirst { it.sessionToken == token }
        if (slotIndex < 0) {
            return jsonResponse(403, errorJson("KICKED", "تم إلغاء هذا الجهاز من الجهاز الرئيسي"))
        }

        val slot = devices[slotIndex]
        if (slot.isKicked) {
            return jsonResponse(403, errorJson("KICKED", "تم طرد هذا الجهاز من الجهاز الرئيسي"))
        }

        val now = System.currentTimeMillis()
        if (now - slot.lastSeenAt > 30_000L) {
            writeMutex.withLock {
                val fresh = getLinkedDevices().toMutableList()
                val idx = fresh.indexOfFirst { it.id == slot.id }
                if (idx >= 0) {
                    fresh[idx] = fresh[idx].copy(lastSeenAt = now)
                    onSaveLinkedDevices(fresh)
                }
            }
        }

        val entities = onExportEntities()
        val syncVersion = getSyncVersion()
        val workspaceCode = getWorkspaceCode()
        val orgName = getOrgName()

        return jsonResponse(
            200,
            JSONObject().apply {
                put("workspaceCode", workspaceCode)
                put("orgName", orgName)
                put("isKicked", false)
                put("permissions", LocalSyncJson.permissionsToJson(slot.toPermissions()))
                put("syncVersion", syncVersion)
                put("entities", LocalSyncJson.upsertsToJsonArray(entities))
            }
        )
    }

    private suspend fun handleEntitiesPush(headers: Map<String, String>, rawBody: String): HttpResponse {
        val token = extractBearerToken(headers)
            ?: return jsonResponse(403, errorJson("KICKED", "لا يوجد تصريح ارتباط صالح"))

        val body = runCatching { JSONObject(rawBody) }.getOrNull()
            ?: return jsonResponse(400, errorJson("BAD_REQUEST", "بيانات المزامنة غير صالحة"))

        val actionType = body.optString("actionType", "")
        val operationTitle = body.optString("operationTitle", "").trim()
        val operationDetails = body.optString("operationDetails", "").trim()
        val actorName = body.optString("actorName", "").trim()
        val records = LocalSyncJson.recordsFromJsonArray(body.optJSONArray("entities"), getWorkspaceCode())

        return writeMutex.withLock {
            val devices = getLinkedDevices()
            val slot = devices.firstOrNull { it.sessionToken == token }
                ?: return@withLock jsonResponse(403, errorJson("KICKED", "تم إلغاء هذا الجهاز من الجهاز الرئيسي"))

            if (slot.isKicked) {
                return@withLock jsonResponse(403, errorJson("KICKED", "تم طرد هذا الجهاز من الجهاز الرئيسي"))
            }

            val allowed = when (actionType) {
                "ADD_OR_MANAGE_CATALOG" -> slot.canAddItems
                "INWARD" -> slot.canInward
                "DISBURSE_OR_TRANSFER" -> slot.canDisburse
                "EDIT_OR_DELETE" -> slot.canEditDelete
                else -> slot.canAddItems || slot.canInward || slot.canDisburse || slot.canEditDelete
            }

            if (!allowed) {
                return@withLock jsonResponse(
                    403,
                    errorJson("PERMISSION_DENIED", "لا تملك صلاحية تنفيذ هذا الإجراء من الجهاز الرئيسي")
                )
            }

            if (records.isNotEmpty()) {
                onApplyRemoteEntities(records)
            }
            val now = System.currentTimeMillis()
            val updatedDevices = devices.map {
                if (it.id == slot.id) it.copy(lastSeenAt = now) else it
            }
            onSaveLinkedDevices(updatedDevices)

            val fallbackTitle = when (actionType) {
                "INWARD" -> "عملية توريد من جهاز فرعي"
                "DISBURSE_OR_TRANSFER" -> "عملية صرف/تحويل من جهاز فرعي"
                "ADD_OR_MANAGE_CATALOG" -> "إضافة في الدليل من جهاز فرعي"
                "EDIT_OR_DELETE" -> "تعديل أو حذف من جهاز فرعي"
                else -> "تحديث بيانات من جهاز فرعي"
            }
            val titleToNotify = operationTitle.ifBlank { fallbackTitle }
            val detailsToNotify = operationDetails.ifBlank {
                "قام الجهاز الفرعي (${slot.deviceName}) بمزامنة عملية جديدة"
            }
            runCatching {
                onRemoteOperationAlert(
                    slot.deviceName,
                    actorName,
                    actionType,
                    titleToNotify,
                    detailsToNotify
                )
            }

            val newVersion = getSyncVersion()
            jsonResponse(
                200,
                JSONObject().apply {
                    put("ok", true)
                    put("syncVersion", newVersion)
                }
            )
        }
    }

    private fun extractBearerToken(headers: Map<String, String>): String? {
        val auth = headers["authorization"] ?: return null
        if (!auth.startsWith("Bearer ", ignoreCase = true)) return null
        val token = auth.substring(7).trim()
        return token.takeIf { it.isNotBlank() }
    }

    private fun readHttpRequest(input: InputStream): HttpRequest? {
        val headerBytes = ByteArrayOutputStream()
        var state = 0
        while (true) {
            val b = input.read()
            if (b == -1) break
            headerBytes.write(b)
            if (headerBytes.size() > 64 * 1024) return null
            state = when (state) {
                0 -> if (b == '\r'.code) 1 else 0
                1 -> if (b == '\n'.code) 2 else if (b == '\r'.code) 1 else 0
                2 -> if (b == '\r'.code) 3 else 0
                3 -> if (b == '\n'.code) 4 else if (b == '\r'.code) 1 else 0
                else -> 0
            }
            if (state == 4) break
        }

        val headerText = headerBytes.toString(Charsets.UTF_8.name())
        val lines = headerText.split("\r\n").filter { it.isNotEmpty() }
        if (lines.isEmpty()) return null

        val requestParts = lines[0].split(" ")
        if (requestParts.size < 2) return null
        val method = requestParts[0].trim().uppercase()
        val path = requestParts[1].trim()

        val headers = HashMap<String, String>()
        for (i in 1 until lines.size) {
            val idx = lines[i].indexOf(':')
            if (idx > 0) {
                val key = lines[i].substring(0, idx).trim().lowercase()
                val value = lines[i].substring(idx + 1).trim()
                headers[key] = value
            }
        }

        val contentLength = headers["content-length"]?.toIntOrNull()?.coerceAtLeast(0) ?: 0
        if (contentLength > MAX_BODY_BYTES) return null

        val bodyBytes = ByteArray(contentLength)
        var totalRead = 0
        while (totalRead < contentLength) {
            val read = input.read(bodyBytes, totalRead, contentLength - totalRead)
            if (read == -1) break
            totalRead += read
        }

        return HttpRequest(
            method = method,
            path = path,
            headers = headers,
            body = String(bodyBytes, 0, totalRead, Charsets.UTF_8)
        )
    }

    private fun errorJson(code: String, message: String): JSONObject =
        JSONObject().put("error", code).put("message", message)

    private fun jsonResponse(statusCode: Int, json: JSONObject): HttpResponse {
        val reason = when (statusCode) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            403 -> "Forbidden"
            404 -> "Not Found"
            else -> "Internal Server Error"
        }
        return HttpResponse(statusCode, reason, json.toString())
    }

    private data class HttpRequest(
        val method: String,
        val path: String,
        val headers: Map<String, String>,
        val body: String
    )

    private data class HttpResponse(
        val statusCode: Int,
        val reasonPhrase: String,
        val body: String
    )
}
