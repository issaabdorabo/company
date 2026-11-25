package com.example.data.sync

import com.example.data.cloud.CloudEntityRecord
import com.example.data.cloud.CloudEntityUpsert
import com.example.data.cloud.LinkedSubDevice
import com.example.data.cloud.LocalServerStatus
import com.example.data.model.OperationAuditLog
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
    private val onExportAuditLogs: suspend (deviceName: String) -> List<OperationAuditLog> = { emptyList() },
    private val onApplyRemoteEntities: suspend (List<CloudEntityRecord>) -> Unit,
    private val hasAppliedSyncOperation: suspend (String) -> Boolean = { false },
    private val onMarkSyncOperationApplied: suspend (String) -> Unit = {},
    private val onRemoteOperationAlert: suspend (
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
                    request.method == "POST" && path == "/api/unlink" -> handleUnlink(request.headers)
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


    private suspend fun handleUnlink(headers: Map<String, String>): HttpResponse {
        val token = extractBearerToken(headers)
            ?: return jsonResponse(403, errorJson("KICKED", "لا يوجد تصريح ارتباط صالح"))
        return writeMutex.withLock {
            val devices = getLinkedDevices()
            val slot = devices.firstOrNull { it.sessionToken == token }
                ?: return@withLock jsonResponse(403, errorJson("KICKED", "جلسة الجهاز غير صالحة"))
            if (slot.isKicked) return@withLock jsonResponse(403, errorJson("KICKED", "تم إيقاف هذا الجهاز من الرئيسي"))
            val updated = slot.copy(
                isClaimed = false,
                wasUnlinked = true,
                boundInstallationId = "",
                sessionToken = java.util.UUID.randomUUID().toString(),
                updatedAt = System.currentTimeMillis()
            )
            onSaveLinkedDevices(devices.map { if (it.id == slot.id) updated else it })
            runCatching {
                onRemoteOperationAlert(
                    slot.deviceName,
                    slot.deviceName,
                    "UNLINK",
                    "فك ارتباط جهاز فرعي",
                    "قام الجهاز الفرعي (${slot.deviceName}) بفك ارتباطه من الجهاز الرئيسي"
                )
            }
            jsonResponse(200, JSONObject().put("ok", true).put("message", "تم تسجيل فك الارتباط"))
        }
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
                wasUnlinked = false,
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
                    // Restore only this device's operation history after re-pairing.
                    put("auditLogs", LocalSyncJson.auditLogsToJsonArray(onExportAuditLogs(updatedSlot.deviceName)))
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

        // The sub-device polls frequently; update heartbeat on every successful pull so
        // the main device can distinguish a live connection from an offline historical slot.
        val now = System.currentTimeMillis()
        writeMutex.withLock {
            val fresh = getLinkedDevices().toMutableList()
            val idx = fresh.indexOfFirst { it.id == slot.id && !it.isKicked }
            if (idx >= 0 && now - fresh[idx].lastSeenAt >= 2_000L) {
                fresh[idx] = fresh[idx].copy(lastSeenAt = now)
                onSaveLinkedDevices(fresh)
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
                put("auditLogs", LocalSyncJson.auditLogsToJsonArray(onExportAuditLogs(slot.deviceName)))
            }
        )
    }

    private data class AuthorizedMergeResult(
        val records: List<CloudEntityRecord>,
        val errorMessage: String? = null
    )

    /**
     * The sub-device sends a complete snapshot for transport compatibility. Never apply it as-is:
     * merge only the entity classes and records allowed by the requested operation into the main
     * device snapshot. Deletion uses explicit IDs, not absence from a possibly stale snapshot.
     */
    private fun mergeAuthorizedSnapshot(
        actionType: String,
        currentSnapshot: List<CloudEntityUpsert>,
        incomingRecords: List<CloudEntityRecord>,
        workspaceCode: String,
        requestedWarehouseIds: Set<Long>,
        targetItemIds: Set<Long>,
        targetVoucherIds: Set<Long>
    ): AuthorizedMergeResult {
        val current = currentSnapshot.associate { upsert ->
            upsert.docId to CloudEntityRecord(
                id = upsert.docId,
                workspaceId = workspaceCode,
                entityType = upsert.entityType,
                localId = upsert.localId,
                payload = upsert.payload,
                isDeleted = upsert.isDeleted
            )
        }
        val incoming = incomingRecords.associateBy { it.id }
        val merged = current.toMutableMap()

        fun longValue(record: CloudEntityRecord, key: String): Long = when (val value = record.payload[key]) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }
        fun stringValue(record: CloudEntityRecord, key: String): String = record.payload[key]?.toString().orEmpty()
        fun typeIs(record: CloudEntityRecord, vararg types: String): Boolean = stringValue(record, "voucherType") in types
        fun incomingOf(type: String, predicate: (CloudEntityRecord) -> Boolean = { true }) =
            incoming.values.filter { it.entityType == type && !it.isDeleted && predicate(it) }
        fun currentOf(type: String, predicate: (CloudEntityRecord) -> Boolean = { true }) =
            current.values.filter { it.entityType == type && !it.isDeleted && predicate(it) }
        fun upsertExisting(type: String, predicate: (CloudEntityRecord) -> Boolean = { true }) {
            incomingOf(type, predicate).forEach { record -> if (record.id in current) merged[record.id] = record }
        }
        fun upsertNew(type: String, predicate: (CloudEntityRecord) -> Boolean = { true }): Set<Long> {
            val added = incomingOf(type, predicate).filter { it.id !in current }
            added.forEach { merged[it.id] = it }
            return added.map { longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId }.toSet()
        }
        fun replaceVoucherChildren(voucherIds: Set<Long>, allowedTypes: Set<String>) {
            if (voucherIds.isEmpty()) return
            val removeIds = merged.values.filter {
                it.entityType == "voucher_item" && longValue(it, "voucherId") in voucherIds
            }.map { it.id }
            removeIds.forEach { merged.remove(it) }
            incomingOf("voucher_item") { longValue(it, "voucherId") in voucherIds && stringValue(it, "voucherType") in allowedTypes }
                .forEach { merged[it.id] = it }
        }
        fun addNewVouchers(allowedTypes: Set<String>): Set<Long> {
            val addedVouchers = incomingOf("voucher") { typeIs(it, *allowedTypes.toTypedArray()) && it.id !in current }
            addedVouchers.forEach { merged[it.id] = it }
            val ids = addedVouchers.map { longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId }.toSet()
            replaceVoucherChildren(ids, allowedTypes)
            return ids
        }
        fun removeVoucherRecords(voucherIds: Set<Long>) {
            if (voucherIds.isEmpty()) return
            val removeIds = merged.values.filter { record ->
                (record.entityType == "voucher" && (longValue(record, "id").takeIf { it > 0L } ?: record.localId) in voucherIds) ||
                    (record.entityType == "voucher_item" && longValue(record, "voucherId") in voucherIds)
            }.map { it.id }
            removeIds.forEach { merged.remove(it) }
        }
        fun updateSerialsForVouchers(voucherIds: Set<Long>, allowNew: Boolean = false) {
            if (voucherIds.isEmpty()) return
            incomingOf("serial") { record ->
                val currentRecord = current[record.id]
                longValue(record, "disbursedVoucherId") in voucherIds ||
                    (currentRecord != null && longValue(currentRecord, "disbursedVoucherId") in voucherIds)
            }.forEach { record ->
                if (allowNew || record.id in current) merged[record.id] = record
            }
        }

        when (actionType) {
            "BACKUP_RESTORE" -> return AuthorizedMergeResult(incomingRecords)

            // Catalog permission is creation-only. Editing/deleting existing stock or vouchers
            // requires the corresponding dedicated permission.
            "ADD_OR_MANAGE_CATALOG" -> {
                listOf("warehouse", "item", "beneficiary", "supplier", "party", "category", "unit", "smaller_unit", "item_status")
                    .forEach { upsertNew(it) }
            }

            "EDIT_CATALOG_ITEMS" -> {
                val itemFilter: (CloudEntityRecord) -> Boolean = { record ->
                    targetItemIds.isEmpty() || (longValue(record, "id").takeIf { it > 0L } ?: record.localId) in targetItemIds
                }
                upsertExisting("item", itemFilter)
            }

            "INWARD" -> {
                val newIds = addNewVouchers(setOf("INWARD"))
                val validIds = if (requestedWarehouseIds.isEmpty()) newIds else incomingOf("voucher") {
                    (longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId) in newIds &&
                        longValue(it, "destinationWarehouseId") in requestedWarehouseIds
                }.map { longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId }.toSet()
                removeVoucherRecords(newIds - validIds)
                upsertNew("serial") { requestedWarehouseIds.isEmpty() || longValue(it, "warehouseId") in requestedWarehouseIds }
            }
            "DISBURSEMENT" -> {
                val newIds = addNewVouchers(setOf("DISBURSEMENT"))
                val validIds = if (requestedWarehouseIds.isEmpty()) newIds else incomingOf("voucher") {
                    (longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId) in newIds && longValue(it, "sourceWarehouseId") in requestedWarehouseIds
                }.map { longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId }.toSet()
                removeVoucherRecords(newIds - validIds)
                updateSerialsForVouchers(validIds)
            }
            "TRANSFER" -> {
                val newIds = addNewVouchers(setOf("TRANSFER"))
                val validIds = if (requestedWarehouseIds.isEmpty()) newIds else incomingOf("voucher") {
                    (longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId) in newIds &&
                        (longValue(it, "sourceWarehouseId") in requestedWarehouseIds || longValue(it, "destinationWarehouseId") in requestedWarehouseIds)
                }.map { longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId }.toSet()
                removeVoucherRecords(newIds - validIds)
                updateSerialsForVouchers(validIds)
            }

            "EDIT_INVENTORY", "ADJUST_STOCK" -> {
                val itemFilter: (CloudEntityRecord) -> Boolean = { record ->
                    targetItemIds.isNotEmpty() &&
                        (longValue(record, "id").takeIf { it > 0L } ?: record.localId) in targetItemIds
                }
                if (actionType == "EDIT_INVENTORY" && targetItemIds.isNotEmpty() && requestedWarehouseIds.isEmpty()) {
                    upsertExisting("item", itemFilter)
                }
                val associationRows = if (requestedWarehouseIds.isEmpty()) emptyList() else incomingOf("association") { record ->
                    (targetItemIds.isEmpty() || longValue(record, "itemId") in targetItemIds) &&
                        longValue(record, "warehouseId") in requestedWarehouseIds
                }
                associationRows.forEach { merged[it.id] = it }
                if (requestedWarehouseIds.isNotEmpty()) {
                    incomingOf("adjustment") { record ->
                        (targetItemIds.isEmpty() || longValue(record, "itemId") in targetItemIds) &&
                            longValue(record, "warehouseId") in requestedWarehouseIds
                    }.filter { it.id !in current }.forEach { merged[it.id] = it }
                }

                // Only accept stock-adjustment vouchers (ADJ-*) and their line items for the
                // requested item/warehouse. Normal inward/outward vouchers have separate actions.
                val adjustmentVouchers = if (requestedWarehouseIds.isEmpty()) emptyList() else incomingOf("voucher") { record ->
                    record.id !in current && stringValue(record, "voucherNumber").startsWith("ADJ-") &&
                        (longValue(record, "sourceWarehouseId") in requestedWarehouseIds ||
                            longValue(record, "destinationWarehouseId") in requestedWarehouseIds)
                }
                val adjustmentVoucherIds = adjustmentVouchers.map {
                    longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId
                }.toSet()
                val adjustmentLines = incomingOf("voucher_item") { record ->
                    longValue(record, "voucherId") in adjustmentVoucherIds &&
                        (targetItemIds.isEmpty() || longValue(record, "itemId") in targetItemIds)
                }
                val allowedAdjustmentVoucherIds = adjustmentLines.map { longValue(it, "voucherId") }.toSet()
                adjustmentVouchers.filter { (longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId) in allowedAdjustmentVoucherIds }
                    .forEach { merged[it.id] = it }
                adjustmentLines.forEach { merged[it.id] = it }

            }

            "EDIT_SERIALS" -> {
                incomingOf("serial") { record ->
                    targetItemIds.isNotEmpty() && longValue(record, "itemId") in targetItemIds &&
                        (requestedWarehouseIds.isEmpty() || longValue(record, "warehouseId") in requestedWarehouseIds)
                }.forEach { merged[it.id] = it }
            }

            "DELETE_ITEM" -> {
                if (targetItemIds.isEmpty()) {
                    return AuthorizedMergeResult(merged.values.toList(), "لم يصل معرّف الصنف المطلوب حذفه؛ أعد المحاولة من شاشة الأصناف")
                }
                val hasTransactions = currentOf("voucher_item") { longValue(it, "itemId") in targetItemIds }.isNotEmpty()
                if (hasTransactions) {
                    return AuthorizedMergeResult(merged.values.toList(), "لا يمكن حذف الصنف لأنه مرتبط بحركات مخزنية مسجلة على الجهاز الرئيسي")
                }
                val removeIds = current.values.filter { record ->
                    when (record.entityType) {
                        "item" -> longValue(record, "id").takeIf { it > 0L }?.let { it in targetItemIds } ?: (record.localId in targetItemIds)
                        "association", "adjustment", "serial" -> longValue(record, "itemId") in targetItemIds
                        else -> false
                    }
                }.map { it.id }
                removeIds.forEach { merged.remove(it) }
            }

            "EDIT_ISSUE_VOUCHER", "EDIT_SUPPLY_VOUCHER" -> {
                val allowedTypes = if (actionType == "EDIT_ISSUE_VOUCHER") setOf("DISBURSEMENT", "TRANSFER") else setOf("INWARD")
                if (targetVoucherIds.isEmpty()) {
                    return AuthorizedMergeResult(merged.values.toList(), "لم يصل معرّف السند المطلوب تعديله؛ أعد المحاولة من شاشة العمليات")
                }
                val allowedVoucherIds = currentOf("voucher") {
                    (longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId) in targetVoucherIds &&
                        typeIs(it, *allowedTypes.toTypedArray()) &&
                        (requestedWarehouseIds.isEmpty() || longValue(it, "sourceWarehouseId") in requestedWarehouseIds || longValue(it, "destinationWarehouseId") in requestedWarehouseIds)
                }.map { longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId }.toSet()
                if (allowedVoucherIds.size != targetVoucherIds.size) {
                    return AuthorizedMergeResult(merged.values.toList(), "السند المطلوب تعديله غير موجود أو لا يطابق نوع الصلاحية")
                }
                incomingOf("voucher") { (longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId) in allowedVoucherIds && typeIs(it, *allowedTypes.toTypedArray()) }
                    .forEach { merged[it.id] = it }
                replaceVoucherChildren(allowedVoucherIds, allowedTypes)
                if (actionType == "EDIT_ISSUE_VOUCHER") updateSerialsForVouchers(allowedVoucherIds)
            }

            "DELETE_ISSUE_VOUCHER", "DELETE_SUPPLY_VOUCHER" -> {
                if (targetVoucherIds.isEmpty()) {
                    return AuthorizedMergeResult(merged.values.toList(), "لم يصل معرّف السند المطلوب حذفه؛ أعد المحاولة من شاشة العمليات")
                }
                val allowedTypes = if (actionType == "DELETE_ISSUE_VOUCHER") setOf("DISBURSEMENT", "TRANSFER") else setOf("INWARD")
                val deleteIds = currentOf("voucher") {
                    (longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId) in targetVoucherIds && typeIs(it, *allowedTypes.toTypedArray())
                }.map { longValue(it, "id").takeIf { id -> id > 0L } ?: it.localId }.toSet()
                if (deleteIds.size != targetVoucherIds.size) {
                    return AuthorizedMergeResult(merged.values.toList(), "السند المطلوب حذفه غير موجود أو لا يطابق نوع الصلاحية")
                }
                val removeIds = current.values.filter { record ->
                    (record.entityType == "voucher" && (longValue(record, "id").takeIf { it > 0L } ?: record.localId) in deleteIds) ||
                        (record.entityType == "voucher_item" && longValue(record, "voucherId") in deleteIds)
                }.map { it.id }
                removeIds.forEach { merged.remove(it) }
                if (actionType == "DELETE_ISSUE_VOUCHER") updateSerialsForVouchers(deleteIds)
            }

            // Legacy/general permission is limited to master-data tables; it cannot modify items
            // or vouchers, which now have their own dedicated actions.
            "EDIT_OR_DELETE" -> {
                val generalTypes = setOf("warehouse", "beneficiary", "supplier", "party", "category", "unit", "smaller_unit", "item_status")
                generalTypes.forEach { type ->
                    val incomingRows = incomingOf(type)
                    val incomingIds = incomingRows.map { it.id }.toSet()
                    merged.values.filter { it.entityType == type && it.id !in incomingIds }.map { it.id }.forEach { merged.remove(it) }
                    incomingRows.forEach { merged[it.id] = it }
                }
            }

            else -> return AuthorizedMergeResult(merged.values.toList(), "نوع العملية غير مدعوم للمزامنة")
        }

        return AuthorizedMergeResult(merged.values.toList())
    }

    private suspend fun handleEntitiesPush(headers: Map<String, String>, rawBody: String): HttpResponse {
        val token = extractBearerToken(headers)
            ?: return jsonResponse(403, errorJson("KICKED", "لا يوجد تصريح ارتباط صالح"))

        val body = runCatching { JSONObject(rawBody) }.getOrNull()
            ?: return jsonResponse(400, errorJson("BAD_REQUEST", "بيانات المزامنة غير صالحة"))

        val clientOperationId = body.optString("clientOperationId", "").trim()
        val actionType = body.optString("actionType", "")
        val operationTitle = body.optString("operationTitle", "").trim()
        val operationDetails = body.optString("operationDetails", "").trim()
        val actorName = body.optString("actorName", "").trim()
        val requestedWarehouseIds = buildSet<Long> {
            val ids = body.optJSONArray("warehouseIds") ?: org.json.JSONArray()
            for (i in 0 until ids.length()) ids.optLong(i, 0L).takeIf { it > 0L }?.let { add(it) }
        }
        val records = LocalSyncJson.recordsFromJsonArray(body.optJSONArray("entities"), getWorkspaceCode())
        val targetItemIds = buildSet<Long> {
            val ids = body.optJSONArray("targetItemIds") ?: org.json.JSONArray()
            for (i in 0 until ids.length()) ids.optLong(i, 0L).takeIf { it > 0L }?.let { add(it) }
        }
        val targetVoucherIds = buildSet<Long> {
            val ids = body.optJSONArray("targetVoucherIds") ?: org.json.JSONArray()
            for (i in 0 until ids.length()) ids.optLong(i, 0L).takeIf { it > 0L }?.let { add(it) }
        }

        return writeMutex.withLock {
            val devices = getLinkedDevices()
            val slot = devices.firstOrNull { it.sessionToken == token }
                ?: return@withLock jsonResponse(403, errorJson("KICKED", "تم إلغاء هذا الجهاز من الجهاز الرئيسي"))

            if (slot.isKicked) {
                return@withLock jsonResponse(403, errorJson("KICKED", "تم طرد هذا الجهاز من الجهاز الرئيسي"))
            }

            if (clientOperationId.isNotBlank() && hasAppliedSyncOperation(clientOperationId)) {
                return@withLock jsonResponse(
                    200,
                    JSONObject().put("ok", true).put("duplicate", true).put("syncVersion", getSyncVersion())
                )
            }

            val legacyGlobalGrant = slot.canViewAllWarehouses && slot.warehousePermissions.isEmpty()
            val allowed = if (actionType == "BACKUP_RESTORE") {
                slot.canBackup
            } else if (actionType == "ADD_OR_MANAGE_CATALOG") {
                slot.canAddItems
            } else if (actionType == "EDIT_CATALOG_ITEMS") {
                slot.canEditCatalogItems
            } else if (requestedWarehouseIds.isNotEmpty()) {
                requestedWarehouseIds.all { warehouseId ->
                    val grant = slot.warehousePermissions.firstOrNull { it.warehouseId == warehouseId }
                    if (grant == null) {
                        legacyGlobalGrant && when (actionType) {
                            "INWARD" -> slot.canInward
                            "DISBURSEMENT", "DISBURSE_OR_TRANSFER" -> slot.canDisburse
                            "TRANSFER" -> slot.canDisburse
                            "EDIT_OR_DELETE" -> slot.canEditDelete
                            "ADJUST_STOCK" -> slot.canEditInventory
                            "EDIT_INVENTORY", "EDIT_SERIALS" -> slot.canEditInventory
                            "DELETE_ITEM" -> slot.canDeleteItem
                            "EDIT_ISSUE_VOUCHER" -> slot.canEditIssueVoucher
                            "DELETE_ISSUE_VOUCHER" -> slot.canDeleteIssueVoucher
                            "EDIT_SUPPLY_VOUCHER" -> slot.canEditSupplyVoucher
                            "DELETE_SUPPLY_VOUCHER" -> slot.canDeleteSupplyVoucher
                            "VIEW_REPORTS" -> true
                            else -> false
                        }
                    } else when (actionType) {
                        "INWARD" -> grant.canView && grant.canInward
                        "DISBURSEMENT", "DISBURSE_OR_TRANSFER" -> grant.canView && grant.canDisburse
                        "TRANSFER" -> grant.canView && grant.canTransfer
                        "ADJUST_STOCK" -> grant.canView && grant.canAdjust
                        "EDIT_OR_DELETE" -> grant.canView && grant.canEdit
                        "EDIT_INVENTORY", "EDIT_SERIALS" -> grant.canView && grant.canEditInventory
                        "DELETE_ITEM" -> grant.canView && grant.canDeleteItem && slot.canDeleteItem
                        "EDIT_ISSUE_VOUCHER" -> grant.canView && grant.canEdit && slot.canEditIssueVoucher
                        "DELETE_ISSUE_VOUCHER" -> grant.canView && slot.canDeleteIssueVoucher
                        "EDIT_SUPPLY_VOUCHER" -> grant.canView && grant.canEdit && slot.canEditSupplyVoucher
                        "DELETE_SUPPLY_VOUCHER" -> grant.canView && slot.canDeleteSupplyVoucher
                        "VIEW_REPORTS" -> grant.canView && grant.canReports
                        else -> false
                    }
                }
            } else when (actionType) {
                "INWARD" -> slot.canInward
                "DISBURSEMENT", "DISBURSE_OR_TRANSFER", "TRANSFER" -> slot.canDisburse
                "EDIT_OR_DELETE" -> slot.canEditDelete
                "EDIT_CATALOG_ITEMS" -> slot.canEditCatalogItems
                // Editing stock/serial records without a target warehouse is forbidden.
                "ADJUST_STOCK", "EDIT_INVENTORY", "EDIT_SERIALS" -> false
                "DELETE_ITEM" -> slot.canDeleteItem
                "EDIT_ISSUE_VOUCHER" -> slot.canEditIssueVoucher
                "DELETE_ISSUE_VOUCHER" -> slot.canDeleteIssueVoucher
                "EDIT_SUPPLY_VOUCHER" -> slot.canEditSupplyVoucher
                "DELETE_SUPPLY_VOUCHER" -> slot.canDeleteSupplyVoucher
                "VIEW_REPORTS" -> legacyGlobalGrant
                // Unknown actions must never become a permission bypass.
                else -> false
            }

            if (!allowed) {
                return@withLock jsonResponse(
                    403,
                    errorJson("PERMISSION_DENIED", "لا تملك صلاحية تنفيذ هذا الإجراء من الجهاز الرئيسي")
                )
            }

            if (records.isNotEmpty() || actionType == "DELETE_ITEM" || actionType == "DELETE_ISSUE_VOUCHER" || actionType == "DELETE_SUPPLY_VOUCHER") {
                val currentSnapshot = onExportEntities()
                val mergeResult = mergeAuthorizedSnapshot(
                    actionType = actionType,
                    currentSnapshot = currentSnapshot,
                    incomingRecords = records,
                    workspaceCode = getWorkspaceCode(),
                    requestedWarehouseIds = requestedWarehouseIds,
                    targetItemIds = targetItemIds,
                    targetVoucherIds = targetVoucherIds
                )
                if (mergeResult.errorMessage != null) {
                    return@withLock jsonResponse(
                        403,
                        errorJson("PERMISSION_DENIED", mergeResult.errorMessage)
                    )
                }
                onApplyRemoteEntities(mergeResult.records)
            }
            if (clientOperationId.isNotBlank()) {
                onMarkSyncOperationApplied(clientOperationId)
            }
            val now = System.currentTimeMillis()
            val updatedDevices = devices.map {
                if (it.id == slot.id) it.copy(lastSeenAt = now) else it
            }
            onSaveLinkedDevices(updatedDevices)

            val fallbackTitle = when (actionType) {
                "INWARD" -> "عملية توريد من جهاز فرعي"
                "DISBURSE_OR_TRANSFER", "DISBURSEMENT" -> "عملية صرف من جهاز فرعي"
                "TRANSFER" -> "عملية تحويل مخزني من جهاز فرعي"
                "ADJUST_STOCK" -> "تعديل رصيد من جهاز فرعي"
                "EDIT_OR_DELETE" -> "تعديل أو حذف من جهاز فرعي"
                "EDIT_INVENTORY" -> "تعديل مخزون من جهاز فرعي"
                "EDIT_SERIALS" -> "تعديل أرقام تسلسلية من جهاز فرعي"
                "DELETE_ITEM" -> "حذف صنف من جهاز فرعي"
                "EDIT_ISSUE_VOUCHER" -> "تعديل أمر صرف من جهاز فرعي"
                "DELETE_ISSUE_VOUCHER" -> "حذف أمر صرف من جهاز فرعي"
                "EDIT_SUPPLY_VOUCHER" -> "تعديل أمر توريد من جهاز فرعي"
                "DELETE_SUPPLY_VOUCHER" -> "حذف أمر توريد من جهاز فرعي"
                "ADD_OR_MANAGE_CATALOG" -> "إضافة في الدليل من جهاز فرعي"
                "BACKUP_RESTORE" -> "استعادة نسخة احتياطية من جهاز فرعي"
                else -> "تحديث بيانات من جهاز فرعي"
            }
            val titleToNotify = operationTitle.ifBlank { fallbackTitle }
            val detailsToNotify = operationDetails.ifBlank {
                "قام الجهاز الفرعي (${slot.deviceName}) بمزامنة عملية جديدة"
            }
            onRemoteOperationAlert(
                slot.deviceName,
                actorName,
                actionType,
                titleToNotify,
                detailsToNotify
            )

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
