package com.example.ui

import android.app.Application
import android.content.Intent
import android.net.VpnService
import android.webkit.CookieManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.SmartBrowserApp
import com.example.data.BrowserConfig
import com.example.data.BrowsingHistoryItem
import com.example.data.DetectedVideo
import com.example.data.DownloadItem
import com.example.data.ServerConnectionState
import com.example.data.TelegramUploadState
import com.example.data.UrlSlot
import com.example.data.VpnConnectionState
import com.example.service.BrowserKeepAliveService
import com.example.service.BrowserVpnService
import com.example.util.FormatUtils
import com.example.util.MandatoryPermissionStatus
import com.example.util.PermissionHelper
import com.example.util.YouTubeExtractor
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppTab(val label: String) {
    BROWSER("المتصفح"),
    DOWNLOADS("التنزيلات والمعرض")
}

sealed interface WebAction {
    data class LoadUrl(val url: String, val timestamp: Long = System.nanoTime()) : WebAction
    data class Reload(val timestamp: Long = System.nanoTime()) : WebAction
    data class StopLoading(val timestamp: Long = System.nanoTime()) : WebAction
    data class GoBack(val timestamp: Long = System.nanoTime()) : WebAction
    data class GoForward(val timestamp: Long = System.nanoTime()) : WebAction
    data class ScanVideos(val timestamp: Long = System.nanoTime()) : WebAction
}

data class BrowserUiState(
    val permissionStatus: MandatoryPermissionStatus,
    val isIgnoringBatteryOptimizations: Boolean,
    val config: BrowserConfig,
    val activeSlot: UrlSlot = UrlSlot.DEFAULT,
    val activeTab: AppTab = AppTab.BROWSER,
    val currentUrl: String = "",
    val pageTitle: String = "",
    val loadingProgress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    val webAction: WebAction? = null,
    val showPinDialog: Boolean = false,
    val pinInput: String = "",
    val pinError: String? = null,
    val showVideoSheet: Boolean = false,
    val showHistorySheet: Boolean = false,
    val showStatusSheet: Boolean = false,
    val isExtractingYouTube: Boolean = false,
    val pendingVpnConsentIntent: Intent? = null,
    val snackbarMessage: String? = null
)

class BrowserViewModel(application: Application) : AndroidViewModel(application) {

    private val app: SmartBrowserApp
        get() = getApplication()

    private val configRepository = app.configRepository
    private val downloadRepository = app.downloadRepository

    private val _uiState = MutableStateFlow(
        BrowserUiState(
            permissionStatus = PermissionHelper.checkAllPermissions(application),
            isIgnoringBatteryOptimizations = PermissionHelper.isIgnoringBatteryOptimizations(application),
            config = configRepository.loadAssetsConfig()
        )
    )
    val uiState: StateFlow<BrowserUiState> = _uiState.asStateFlow()

    val visibleDownloads: StateFlow<List<DownloadItem>> = combine(
        downloadRepository.downloads,
        _uiState
    ) { allDownloads, state ->
        allDownloads.filter { it.sourceSlot == state.activeSlot }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val visibleHistory: StateFlow<List<BrowsingHistoryItem>> = combine(
        downloadRepository.defaultHistory,
        downloadRepository.secondaryHistory,
        _uiState
    ) { defaultHist, secondaryHist, state ->
        if (state.activeSlot == UrlSlot.DEFAULT) defaultHist else secondaryHist
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val detectedVideos: StateFlow<List<DetectedVideo>> = downloadRepository.detectedVideos
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val serverState: StateFlow<ServerConnectionState> = downloadRepository.serverState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ServerConnectionState())

    val vpnState: StateFlow<VpnConnectionState> = downloadRepository.vpnState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), VpnConnectionState())

    val telegramUploadState: StateFlow<TelegramUploadState> = downloadRepository.telegramUploadState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), TelegramUploadState())

    val autoSaveToGallery: StateFlow<Boolean> = configRepository.autoSaveToGalleryFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            _uiState.value.config.defaultAutoSaveToGallery
        )

    val keepAliveEnabled: StateFlow<Boolean> = configRepository.keepAliveEnabledFlow
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    val telegramAutoUploadEnabled: StateFlow<Boolean> = configRepository.telegramAutoUploadFlow
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            configRepository.loadTelegramConfig().defaultAutoUploadEnabled
        )

    private var youtubeExtractionJob: Job? = null
    private var lastExtractedYouTubeId: String? = null
    private var lastRecordedHistoryUrl: String = ""

    init {
        viewModelScope.launch {
            val slot = configRepository.activeUrlSlotFlow.first()
            val config = configRepository.loadAssetsConfig()
            val initialUrl = if (slot == UrlSlot.DEFAULT) config.defaultUrl else config.secondaryUrl
            _uiState.update {
                it.copy(
                    config = config,
                    activeSlot = slot,
                    currentUrl = initialUrl,
                    webAction = WebAction.LoadUrl(initialUrl)
                )
            }
            refreshPermissionsAndService()
            if (slot == UrlSlot.SECONDARY && _uiState.value.permissionStatus.allMandatoryGranted) {
                activateVpnAutomatically()
            }
        }
    }

    fun refreshPermissionsAndService() {
        val status = PermissionHelper.checkAllPermissions(app)
        val batteryIgnored = PermissionHelper.isIgnoringBatteryOptimizations(app)
        _uiState.update {
            it.copy(
                permissionStatus = status,
                isIgnoringBatteryOptimizations = batteryIgnored
            )
        }
        if (status.allMandatoryGranted) {
            viewModelScope.launch {
                if (configRepository.isKeepAliveEnabled()) {
                    val activeUrl = configRepository.getActiveServerUrl()
                    BrowserKeepAliveService.startKeepAlive(app, activeUrl)
                }
                if (configRepository.isTelegramAutoUploadEnabled()) {
                    BrowserKeepAliveService.triggerTelegramUpload(app)
                }
            }
        }
    }

    fun selectTab(tab: AppTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    fun navigateToInputUrl(input: String) {
        val normalized = FormatUtils.normalizeUserInputUrl(input)
        downloadRepository.clearDetectedVideos()
        lastExtractedYouTubeId = null
        _uiState.update {
            it.copy(
                currentUrl = normalized,
                webAction = WebAction.LoadUrl(normalized)
            )
        }
    }

    fun navigateHome() {
        val state = _uiState.value
        val homeUrl = if (state.activeSlot == UrlSlot.DEFAULT) {
            state.config.defaultUrl
        } else {
            state.config.secondaryUrl
        }
        downloadRepository.clearDetectedVideos()
        lastExtractedYouTubeId = null
        _uiState.update {
            it.copy(
                currentUrl = homeUrl,
                webAction = WebAction.LoadUrl(homeUrl)
            )
        }
    }

    fun reloadOrStop() {
        val isLoading = _uiState.value.isLoading
        _uiState.update {
            it.copy(webAction = if (isLoading) WebAction.StopLoading() else WebAction.Reload())
        }
    }

    fun goBack() {
        _uiState.update { it.copy(webAction = WebAction.GoBack()) }
    }

    fun goForward() {
        _uiState.update { it.copy(webAction = WebAction.GoForward()) }
    }

    fun consumeWebAction() {
        _uiState.update { it.copy(webAction = null) }
    }

    fun onWebNavigationChanged(
        url: String?,
        title: String?,
        canGoBack: Boolean,
        canGoForward: Boolean,
        slot: UrlSlot
    ) {
        if (slot != _uiState.value.activeSlot) return

        val cleanUrl = url?.takeIf { it.isNotBlank() }
        val cleanTitle = title?.takeIf { it.isNotBlank() }

        _uiState.update { state ->
            state.copy(
                currentUrl = cleanUrl ?: state.currentUrl,
                pageTitle = cleanTitle ?: state.pageTitle,
                canGoBack = canGoBack,
                canGoForward = canGoForward
            )
        }

        if (cleanUrl != null) {
            checkAndExtractYouTube(cleanUrl)
            if (cleanUrl != lastRecordedHistoryUrl && !cleanUrl.startsWith("about:")) {
                lastRecordedHistoryUrl = cleanUrl
                viewModelScope.launch {
                    downloadRepository.addBrowsingHistoryEntry(
                        url = cleanUrl,
                        title = cleanTitle ?: cleanUrl,
                        slot = slot
                    )
                }
            }
        }
    }

    fun onWebProgressChanged(progress: Int, isLoading: Boolean, slot: UrlSlot) {
        if (slot != _uiState.value.activeSlot) return
        _uiState.update {
            it.copy(
                loadingProgress = progress,
                isLoading = isLoading
            )
        }
    }

    fun onPageStarted(url: String?, slot: UrlSlot) {
        if (slot != _uiState.value.activeSlot) return
        downloadRepository.clearDetectedVideos()
        lastExtractedYouTubeId = null
        if (!url.isNullOrBlank()) {
            _uiState.update { it.copy(currentUrl = url, isLoading = true) }
            checkAndExtractYouTube(url)
        }
    }

    private fun checkAndExtractYouTube(url: String) {
        val videoId = YouTubeExtractor.extractVideoId(url) ?: return
        if (videoId == lastExtractedYouTubeId) return
        lastExtractedYouTubeId = videoId

        youtubeExtractionJob?.cancel()
        youtubeExtractionJob = viewModelScope.launch {
            _uiState.update { it.copy(isExtractingYouTube = true) }
            val streams = YouTubeExtractor.extractDirectStreams(videoId)
            if (streams.isNotEmpty()) {
                downloadRepository.setYouTubeDetectedVideos(streams)
            }
            _uiState.update { it.copy(isExtractingYouTube = false) }
        }
    }

    fun updateDetectedVideos(videos: List<DetectedVideo>) {
        downloadRepository.setDetectedVideos(videos)
    }

    fun addDetectedVideoFromNetwork(url: String, mimeType: String) {
        val title = url.substringBefore('?').substringAfterLast('/', "فيديو")
        downloadRepository.addDetectedVideo(
            DetectedVideo(
                url = url,
                title = title.ifBlank { "فيديو قابل للتنزيل" },
                mimeType = mimeType,
                sourceTag = "stream"
            )
        )
    }

    fun openVideoSheet() {
        val currentUrl = _uiState.value.currentUrl
        YouTubeExtractor.extractVideoId(currentUrl)?.let { videoId ->
            lastExtractedYouTubeId = null
            checkAndExtractYouTube(currentUrl)
        }
        _uiState.update {
            it.copy(
                showVideoSheet = true,
                webAction = WebAction.ScanVideos()
            )
        }
    }

    fun closeVideoSheet() {
        _uiState.update { it.copy(showVideoSheet = false) }
    }

    fun openHistorySheet() {
        _uiState.update { it.copy(showHistorySheet = true) }
    }

    fun closeHistorySheet() {
        _uiState.update { it.copy(showHistorySheet = false) }
    }

    fun clearActiveBrowsingHistory() {
        val slot = _uiState.value.activeSlot
        viewModelScope.launch {
            downloadRepository.clearBrowsingHistory(slot)
            showMessage("تم مسح سجل التصفح")
        }
    }

    fun openStatusSheet() {
        _uiState.update { it.copy(showStatusSheet = true) }
    }

    fun closeStatusSheet() {
        _uiState.update { it.copy(showStatusSheet = false) }
    }

    fun enqueueVideoDownload(
        url: String,
        fileName: String? = null,
        mimeType: String? = null,
        userAgent: String? = null
    ) {
        val state = _uiState.value
        val activeSlot = state.activeSlot
        val ytVideoId = YouTubeExtractor.extractVideoId(url)

        if (ytVideoId != null) {
            viewModelScope.launch {
                showMessage("جاري استخراج رابط التحميل من يوتيوب...")
                val streams = YouTubeExtractor.extractDirectStreams(ytVideoId)
                val best = streams.firstOrNull()
                if (best != null) {
                    val sanitizedFileName = best.title
                        .replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF._ -]"), "_")
                        .take(56) + ".mp4"
                    BrowserKeepAliveService.enqueueDownload(
                        context = app,
                        url = best.url,
                        sourceSlot = activeSlot,
                        fileName = sanitizedFileName,
                        mimeType = best.mimeType,
                        referer = "https://www.youtube.com/",
                        userAgent = userAgent
                    )
                    showMessage(
                        if (activeSlot == UrlSlot.SECONDARY) "بدأ تنزيل الفيديو في الوضع المخفي"
                        else "بدأ تنزيل فيديو يوتيوب"
                    )
                } else {
                    showMessage("تعذر استخراج رابط مباشر لهذا الفيديو من يوتيوب")
                }
            }
            return
        }

        BrowserKeepAliveService.enqueueDownload(
            context = app,
            url = url,
            sourceSlot = activeSlot,
            fileName = fileName,
            mimeType = mimeType,
            referer = state.currentUrl,
            userAgent = userAgent
        )
        showMessage(
            if (activeSlot == UrlSlot.SECONDARY) {
                "بدأ تنزيل الفيديو وحفظه بشكل مخفي"
            } else {
                "بدأ تنزيل الفيديو في الخلفية"
            }
        )
    }

    fun cancelDownload(downloadId: String) {
        BrowserKeepAliveService.cancelDownload(app, downloadId)
    }

    fun removeDownloadRecord(downloadId: String) {
        viewModelScope.launch {
            downloadRepository.removeDownload(downloadId)
        }
    }

    fun setAutoSaveToGallery(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.setAutoSaveToGallery(enabled)
            showMessage(
                if (enabled) "تم تفعيل الحفظ التلقائي للفيديوهات في المعرض"
                else "تم إيقاف الحفظ التلقائي في المعرض"
            )
        }
    }

    fun setKeepAliveEnabled(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.setKeepAliveEnabled(enabled)
            if (enabled) {
                val activeUrl = configRepository.getActiveServerUrl()
                BrowserKeepAliveService.startKeepAlive(app, activeUrl)
                showMessage("تم تفعيل الاتصال المستمر بالخادم في الخلفية")
            } else {
                val intent = Intent(app, BrowserKeepAliveService::class.java).apply {
                    action = BrowserKeepAliveService.ACTION_STOP_SERVICE
                }
                app.startService(intent)
                showMessage("تم إيقاف خدمة الخلفية")
            }
        }
    }

    fun setTelegramAutoUploadEnabled(enabled: Boolean) {
        viewModelScope.launch {
            configRepository.setTelegramAutoUploadEnabled(enabled)
            if (enabled) {
                BrowserKeepAliveService.triggerTelegramUpload(app)
                showMessage("تم تفعيل الرفع التلقائي للصور إلى تليجرام")
            } else {
                BrowserKeepAliveService.stopTelegramUpload(app)
                showMessage("تم إيقاف الرفع التلقائي للصور")
            }
        }
    }

    fun triggerTelegramUploadNow() {
        viewModelScope.launch {
            configRepository.setTelegramAutoUploadEnabled(true)
            BrowserKeepAliveService.triggerTelegramUpload(app)
            showMessage("جاري فحص الصور وبدء الرفع إلى تليجرام")
        }
    }

    // PIN-Protected URL Switcher (7368 to enter secondary; no PIN needed to return to default)
    fun openPinDialog() {
        if (_uiState.value.activeSlot == UrlSlot.SECONDARY) {
            switchToSlot(UrlSlot.DEFAULT)
            return
        }
        _uiState.update {
            it.copy(
                showPinDialog = true,
                pinInput = "",
                pinError = null
            )
        }
    }

    fun dismissPinDialog() {
        _uiState.update {
            it.copy(
                showPinDialog = false,
                pinInput = "",
                pinError = null
            )
        }
    }

    fun appendPinDigit(digit: Char) {
        if (!digit.isDigit()) return
        val current = _uiState.value.pinInput
        if (current.length >= 4) return
        val updated = current + digit
        _uiState.update { it.copy(pinInput = updated, pinError = null) }
        if (updated.length == 4) {
            verifyPinAndSwitchUrl(updated)
        }
    }

    fun backspacePin() {
        val current = _uiState.value.pinInput
        if (current.isNotEmpty()) {
            _uiState.update { it.copy(pinInput = current.dropLast(1), pinError = null) }
        }
    }

    fun verifyPinAndSwitchUrl(pin: String = _uiState.value.pinInput) {
        val config = _uiState.value.config
        val expectedPin = config.switchPin.ifBlank { "7368" }
        if (pin == expectedPin) {
            val nextSlot = if (_uiState.value.activeSlot == UrlSlot.DEFAULT) {
                UrlSlot.SECONDARY
            } else {
                UrlSlot.DEFAULT
            }
            switchToSlot(nextSlot)
        } else {
            _uiState.update {
                it.copy(
                    pinInput = "",
                    pinError = "رمز التأكيد غير صحيح، حاول مرة أخرى"
                )
            }
        }
    }

    private fun switchToSlot(nextSlot: UrlSlot) {
        val config = _uiState.value.config
        val targetUrl = if (nextSlot == UrlSlot.DEFAULT) config.defaultUrl else config.secondaryUrl
        val targetLabel = if (nextSlot == UrlSlot.DEFAULT) config.defaultTitle else config.secondaryTitle

        viewModelScope.launch {
            configRepository.setActiveUrlSlot(nextSlot)
            BrowserKeepAliveService.updateServerUrl(app, targetUrl)
            downloadRepository.clearDetectedVideos()
            lastExtractedYouTubeId = null
            lastRecordedHistoryUrl = ""

            if (nextSlot == UrlSlot.DEFAULT) {
                runCatching {
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }
                BrowserVpnService.stop(app)
            } else {
                activateVpnAutomatically()
            }

            _uiState.update {
                it.copy(
                    activeSlot = nextSlot,
                    currentUrl = targetUrl,
                    webAction = WebAction.LoadUrl(targetUrl),
                    showPinDialog = false,
                    pinInput = "",
                    pinError = null,
                    activeTab = AppTab.BROWSER,
                    snackbarMessage = if (nextSlot == UrlSlot.SECONDARY) {
                        "تم التبديل إلى $targetLabel وتفعيل VPN تلقائياً"
                    } else {
                        "تمت العودة إلى $targetLabel وإخفاء سجل وتنزيلات المتصفح الآخر"
                    }
                )
            }
        }
    }

    fun activateVpnAutomatically() {
        val prepareIntent = runCatching { VpnService.prepare(app) }.getOrNull()
        if (prepareIntent == null) {
            BrowserVpnService.start(app)
        } else {
            _uiState.update { it.copy(pendingVpnConsentIntent = prepareIntent) }
        }
    }

    fun consumeVpnConsentIntent() {
        _uiState.update { it.copy(pendingVpnConsentIntent = null) }
    }

    fun onVpnConsentResult(granted: Boolean) {
        if (granted) {
            BrowserVpnService.start(app)
            showMessage("تم تفعيل VPN تلقائياً")
        } else {
            downloadRepository.updateVpnState {
                it.copy(isVpnActive = false, statusMessage = "لم يتم منح إذن VPN")
            }
            showMessage("يجب الموافقة على إذن VPN لتشغيل الحماية التلقائية")
        }
    }

    fun showMessage(message: String) {
        _uiState.update { it.copy(snackbarMessage = message) }
    }

    fun clearMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }
}
