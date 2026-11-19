package com.example.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

enum class DownloadStatus {
    DOWNLOADING,
    SAVING_TO_GALLERY,
    COMPLETED,
    FAILED,
    CANCELLED
}

data class DownloadItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val fileName: String,
    val mimeType: String,
    val status: DownloadStatus = DownloadStatus.DOWNLOADING,
    val bytesDownloaded: Long = 0L,
    val totalBytes: Long = -1L,
    val savedToGallery: Boolean = false,
    val isPrivateVault: Boolean = false,
    val sourceSlot: UrlSlot = UrlSlot.DEFAULT,
    val galleryUri: String? = null,
    val localPath: String? = null,
    val errorMessage: String? = null,
    val createdAtMillis: Long = System.currentTimeMillis()
) {
    val progressFraction: Float
        get() = if (totalBytes > 0L) (bytesDownloaded.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f

    val isVideo: Boolean
        get() = mimeType.startsWith("video/", ignoreCase = true) ||
            fileName.endsWith(".mp4", true) ||
            fileName.endsWith(".webm", true) ||
            fileName.endsWith(".mov", true) ||
            fileName.endsWith(".mkv", true) ||
            fileName.endsWith(".m4v", true) ||
            fileName.endsWith(".3gp", true)
}

data class DetectedVideo(
    val url: String,
    val title: String,
    val mimeType: String = "video/mp4",
    val sourceTag: String = "video",
    val qualityLabel: String? = null
)

data class BrowsingHistoryItem(
    val id: String = UUID.randomUUID().toString(),
    val url: String,
    val title: String,
    val visitedAtMillis: Long = System.currentTimeMillis(),
    val slot: UrlSlot = UrlSlot.DEFAULT
)

data class ServerConnectionState(
    val isServiceRunning: Boolean = false,
    val isConnected: Boolean = false,
    val serverUrl: String = "",
    val latencyMs: Long = 0L,
    val heartbeatCount: Long = 0L,
    val lastPingMillis: Long = 0L,
    val lastError: String? = null
)

data class VpnConnectionState(
    val isVpnActive: Boolean = false,
    val isConnecting: Boolean = false,
    val dnsQueriesEncrypted: Long = 0L,
    val statusMessage: String = "غير متصل"
)

data class TelegramUploadState(
    val isConfigured: Boolean = false,
    val isEnabled: Boolean = true,
    val isUploading: Boolean = false,
    val isWaitingForNetwork: Boolean = false,
    val currentUploadedIndex: Int = 0,
    val remainingCount: Int = 0,
    val totalUploadedAllTime: Int = 0,
    val totalSkippedAllTime: Int = 0,
    val currentFileName: String = "",
    val statusMessage: String = "في وضع الاستعداد",
    val lastError: String? = null
)

class DownloadRepository(private val configRepository: BrowserConfigRepository) {

    private val _downloads = MutableStateFlow<List<DownloadItem>>(emptyList())
    val downloads: StateFlow<List<DownloadItem>> = _downloads.asStateFlow()

    private val _detectedVideos = MutableStateFlow<List<DetectedVideo>>(emptyList())
    val detectedVideos: StateFlow<List<DetectedVideo>> = _detectedVideos.asStateFlow()

    private val _defaultHistory = MutableStateFlow<List<BrowsingHistoryItem>>(emptyList())
    val defaultHistory: StateFlow<List<BrowsingHistoryItem>> = _defaultHistory.asStateFlow()

    private val _secondaryHistory = MutableStateFlow<List<BrowsingHistoryItem>>(emptyList())
    val secondaryHistory: StateFlow<List<BrowsingHistoryItem>> = _secondaryHistory.asStateFlow()

    private val _serverState = MutableStateFlow(ServerConnectionState())
    val serverState: StateFlow<ServerConnectionState> = _serverState.asStateFlow()

    private val _vpnState = MutableStateFlow(VpnConnectionState())
    val vpnState: StateFlow<VpnConnectionState> = _vpnState.asStateFlow()

    private val _telegramUploadState = MutableStateFlow(TelegramUploadState())
    val telegramUploadState: StateFlow<TelegramUploadState> = _telegramUploadState.asStateFlow()

    suspend fun restoreHistory() {
        restoreDownloadsHistory()
        _defaultHistory.value = restoreBrowsingHistoryForSlot(UrlSlot.DEFAULT)
        _secondaryHistory.value = restoreBrowsingHistoryForSlot(UrlSlot.SECONDARY)
        val (uploaded, skipped) = configRepository.getTelegramStats()
        val tgConfig = configRepository.loadTelegramConfig()
        _telegramUploadState.update {
            it.copy(
                isConfigured = tgConfig.isConfigured,
                totalUploadedAllTime = uploaded,
                totalSkippedAllTime = skipped
            )
        }
    }

    private suspend fun restoreDownloadsHistory() {
        val raw = configRepository.loadDownloadsHistoryJson() ?: return
        try {
            val array = JSONArray(raw)
            val restored = buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    val status = runCatching {
                        DownloadStatus.valueOf(obj.optString("status", DownloadStatus.COMPLETED.name))
                    }.getOrDefault(DownloadStatus.COMPLETED)
                    val normalizedStatus = if (status == DownloadStatus.DOWNLOADING || status == DownloadStatus.SAVING_TO_GALLERY) {
                        DownloadStatus.FAILED
                    } else {
                        status
                    }
                    add(
                        DownloadItem(
                            id = obj.optString("id"),
                            url = obj.optString("url"),
                            fileName = obj.optString("fileName"),
                            mimeType = obj.optString("mimeType", "video/mp4"),
                            status = normalizedStatus,
                            bytesDownloaded = obj.optLong("bytesDownloaded", 0L),
                            totalBytes = obj.optLong("totalBytes", -1L),
                            savedToGallery = obj.optBoolean("savedToGallery", false),
                            isPrivateVault = obj.optBoolean("isPrivateVault", false),
                            sourceSlot = UrlSlot.fromKey(obj.optString("sourceSlot", UrlSlot.DEFAULT.key)),
                            galleryUri = obj.optString("galleryUri").ifBlank { null },
                            localPath = obj.optString("localPath").ifBlank { null },
                            errorMessage = obj.optString("errorMessage").ifBlank { null },
                            createdAtMillis = obj.optLong("createdAtMillis", System.currentTimeMillis())
                        )
                    )
                }
            }
            _downloads.value = restored
        } catch (_: Exception) {
        }
    }

    private suspend fun restoreBrowsingHistoryForSlot(slot: UrlSlot): List<BrowsingHistoryItem> {
        val raw = configRepository.loadBrowsingHistoryJson(slot) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        BrowsingHistoryItem(
                            id = obj.optString("id", UUID.randomUUID().toString()),
                            url = obj.optString("url"),
                            title = obj.optString("title"),
                            visitedAtMillis = obj.optLong("visitedAtMillis", System.currentTimeMillis()),
                            slot = slot
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addBrowsingHistoryEntry(url: String, title: String, slot: UrlSlot) {
        if (!url.startsWith("http://") && !url.startsWith("https://")) return
        val cleanTitle = title.ifBlank { url }
        val entry = BrowsingHistoryItem(
            url = url,
            title = cleanTitle,
            slot = slot
        )
        val flow = if (slot == UrlSlot.DEFAULT) _defaultHistory else _secondaryHistory
        flow.update { current ->
            val filtered = current.filterNot { it.url == url }
            (listOf(entry) + filtered).take(100)
        }
        persistBrowsingHistory(slot, flow.value)
    }

    suspend fun clearBrowsingHistory(slot: UrlSlot) {
        val flow = if (slot == UrlSlot.DEFAULT) _defaultHistory else _secondaryHistory
        flow.value = emptyList()
        persistBrowsingHistory(slot, emptyList())
    }

    private suspend fun persistBrowsingHistory(slot: UrlSlot, items: List<BrowsingHistoryItem>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("title", item.title)
                    put("visitedAtMillis", item.visitedAtMillis)
                }
            )
        }
        configRepository.saveBrowsingHistoryJson(slot, array.toString())
    }

    suspend fun upsertDownload(item: DownloadItem) {
        _downloads.update { list ->
            val index = list.indexOfFirst { it.id == item.id }
            if (index >= 0) {
                list.toMutableList().apply { set(index, item) }
            } else {
                listOf(item) + list
            }
        }
        persistHistoryIfSettled(item.status)
    }

    suspend fun removeDownload(id: String) {
        _downloads.update { list -> list.filterNot { it.id == id } }
        persistHistory()
    }

    private suspend fun persistHistoryIfSettled(status: DownloadStatus) {
        if (status == DownloadStatus.COMPLETED || status == DownloadStatus.FAILED || status == DownloadStatus.CANCELLED) {
            persistHistory()
        }
    }

    private suspend fun persistHistory() {
        val array = JSONArray()
        _downloads.value.take(100).forEach { item ->
            array.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("url", item.url)
                    put("fileName", item.fileName)
                    put("mimeType", item.mimeType)
                    put("status", item.status.name)
                    put("bytesDownloaded", item.bytesDownloaded)
                    put("totalBytes", item.totalBytes)
                    put("savedToGallery", item.savedToGallery)
                    put("isPrivateVault", item.isPrivateVault)
                    put("sourceSlot", item.sourceSlot.key)
                    put("galleryUri", item.galleryUri ?: "")
                    put("localPath", item.localPath ?: "")
                    put("errorMessage", item.errorMessage ?: "")
                    put("createdAtMillis", item.createdAtMillis)
                }
            )
        }
        configRepository.saveDownloadsHistoryJson(array.toString())
    }

    fun setDetectedVideos(videos: List<DetectedVideo>) {
        _detectedVideos.update { current ->
            val youtubeStreams = current.filter { it.sourceTag == "youtube" }
            val nonYt = videos.filterNot { it.url.startsWith("blob:") }
            (youtubeStreams + nonYt).distinctBy { it.url }
        }
    }

    fun setYouTubeDetectedVideos(videos: List<DetectedVideo>) {
        _detectedVideos.update { current ->
            val others = current.filterNot { it.sourceTag == "youtube" }
            (videos + others).distinctBy { it.url }
        }
    }

    fun addDetectedVideo(video: DetectedVideo) {
        if (!video.url.startsWith("http://") && !video.url.startsWith("https://")) return
        _detectedVideos.update { current ->
            if (current.any { it.url == video.url }) current
            else (listOf(video) + current).take(25)
        }
    }

    fun clearDetectedVideos() {
        _detectedVideos.value = emptyList()
    }

    fun updateServerState(transform: (ServerConnectionState) -> ServerConnectionState) {
        _serverState.update(transform)
    }

    fun updateVpnState(transform: (VpnConnectionState) -> VpnConnectionState) {
        _vpnState.update(transform)
    }

    fun updateTelegramUploadState(transform: (TelegramUploadState) -> TelegramUploadState) {
        _telegramUploadState.update(transform)
    }
}
