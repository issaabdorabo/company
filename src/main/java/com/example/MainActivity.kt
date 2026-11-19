package com.example

import android.app.Activity
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.DownloadStatus
import com.example.ui.AppTab
import com.example.ui.BrowserViewModel
import com.example.ui.components.PinSwitchDialog
import com.example.ui.screens.BrowserScreen
import com.example.ui.screens.DownloadsGalleryScreen
import com.example.ui.screens.PermissionGateScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.util.PermissionHelper

class MainActivity : ComponentActivity() {

    private val viewModel: BrowserViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                SmartBrowserRoot(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun SmartBrowserRoot(viewModel: BrowserViewModel) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val downloads by viewModel.visibleDownloads.collectAsStateWithLifecycle()
    val visibleHistory by viewModel.visibleHistory.collectAsStateWithLifecycle()
    val detectedVideos by viewModel.detectedVideos.collectAsStateWithLifecycle()
    val serverState by viewModel.serverState.collectAsStateWithLifecycle()
    val vpnState by viewModel.vpnState.collectAsStateWithLifecycle()
    val telegramState by viewModel.telegramUploadState.collectAsStateWithLifecycle()
    val autoSaveToGallery by viewModel.autoSaveToGallery.collectAsStateWithLifecycle()
    val telegramAutoUploadEnabled by viewModel.telegramAutoUploadEnabled.collectAsStateWithLifecycle()

    val snackbarHostState = remember { SnackbarHostState() }

    val runtimePermissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) {
        viewModel.refreshPermissionsAndService()
    }

    val settingsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.refreshPermissionsAndService()
    }

    val vpnConsentLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        viewModel.onVpnConsentResult(result.resultCode == Activity.RESULT_OK)
    }

    LaunchedEffect(uiState.pendingVpnConsentIntent) {
        val intent = uiState.pendingVpnConsentIntent ?: return@LaunchedEffect
        viewModel.consumeVpnConsentIntent()
        vpnConsentLauncher.launch(intent)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissionsAndService()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(uiState.snackbarMessage) {
        val msg = uiState.snackbarMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        viewModel.clearMessage()
    }

    val activeDownloadCount = downloads.count {
        it.status == DownloadStatus.DOWNLOADING || it.status == DownloadStatus.SAVING_TO_GALLERY
    }

    if (!uiState.permissionStatus.allMandatoryGranted) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
        ) { innerPadding ->
            PermissionGateScreen(
                status = uiState.permissionStatus,
                onRequestRuntimePermissions = {
                    runtimePermissionsLauncher.launch(PermissionHelper.runtimePermissionsToRequest())
                },
                onOpenFileManagerPermission = {
                    runCatching {
                        settingsLauncher.launch(PermissionHelper.createAllFilesAccessIntent(context))
                    }.onFailure {
                        runCatching {
                            settingsLauncher.launch(PermissionHelper.createAllFilesAccessFallbackIntent())
                        }
                    }
                },
                onOpenNotificationAccessSettings = {
                    runCatching {
                        settingsLauncher.launch(PermissionHelper.createNotificationAccessIntent())
                    }.onFailure {
                        settingsLauncher.launch(PermissionHelper.createAppSettingsIntent(context))
                    }
                },
                onOpenAppSettings = {
                    settingsLauncher.launch(PermissionHelper.createAppSettingsIntent(context))
                },
                modifier = Modifier.padding(innerPadding)
            )
        }
        return
    }

    BackHandler(enabled = uiState.activeTab != AppTab.BROWSER) {
        viewModel.selectTab(AppTab.BROWSER)
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val useRail = maxWidth >= 600.dp

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
            bottomBar = {
                if (!useRail) {
                    NavigationBar {
                        AppTab.entries.forEach { tab ->
                            val selected = uiState.activeTab == tab
                            NavigationBarItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                modifier = Modifier.testTag("nav_tab_${tab.name.lowercase()}"),
                                icon = {
                                    TabIcon(
                                        tab = tab,
                                        selected = selected,
                                        activeDownloadCount = activeDownloadCount
                                    )
                                },
                                label = { Text(tab.label, maxLines = 1) }
                            )
                        }
                    }
                }
            }
        ) { innerPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                if (useRail) {
                    NavigationRail {
                        AppTab.entries.forEach { tab ->
                            val selected = uiState.activeTab == tab
                            NavigationRailItem(
                                selected = selected,
                                onClick = { viewModel.selectTab(tab) },
                                modifier = Modifier.testTag("nav_rail_${tab.name.lowercase()}"),
                                icon = {
                                    TabIcon(
                                        tab = tab,
                                        selected = selected,
                                        activeDownloadCount = activeDownloadCount
                                    )
                                },
                                label = { Text(tab.label) }
                            )
                        }
                    }
                }

                Box(modifier = Modifier.weight(1f).fillMaxSize()) {
                    BrowserScreen(
                        uiState = uiState,
                        isTabActive = uiState.activeTab == AppTab.BROWSER,
                        detectedVideos = detectedVideos,
                        visibleHistory = visibleHistory,
                        serverState = serverState,
                        vpnState = vpnState,
                        telegramState = telegramState,
                        autoSaveToGallery = autoSaveToGallery,
                        telegramAutoUploadEnabled = telegramAutoUploadEnabled,
                        onNavigateInputUrl = viewModel::navigateToInputUrl,
                        onGoHome = viewModel::navigateHome,
                        onReloadOrStop = viewModel::reloadOrStop,
                        onGoBack = viewModel::goBack,
                        onGoForward = viewModel::goForward,
                        onOpenPinSwitch = viewModel::openPinDialog,
                        onOpenVideoSheet = viewModel::openVideoSheet,
                        onCloseVideoSheet = viewModel::closeVideoSheet,
                        onOpenHistorySheet = viewModel::openHistorySheet,
                        onCloseHistorySheet = viewModel::closeHistorySheet,
                        onClearHistory = viewModel::clearActiveBrowsingHistory,
                        onOpenStatusSheet = viewModel::openStatusSheet,
                        onCloseStatusSheet = viewModel::closeStatusSheet,
                        onToggleTelegramUpload = viewModel::setTelegramAutoUploadEnabled,
                        onRequestBatteryExemption = {
                            runCatching {
                                settingsLauncher.launch(PermissionHelper.createIgnoreBatteryOptimizationIntent(context))
                            }
                        },
                        onActivateVpnNow = viewModel::activateVpnAutomatically,
                        onDownloadVideo = viewModel::enqueueVideoDownload,
                        onOpenDownloadsTab = { viewModel.selectTab(AppTab.DOWNLOADS) },
                        onConsumeWebAction = viewModel::consumeWebAction,
                        onWebNavigationChanged = viewModel::onWebNavigationChanged,
                        onWebProgressChanged = viewModel::onWebProgressChanged,
                        onPageStarted = viewModel::onPageStarted,
                        onVideosDetected = viewModel::updateDetectedVideos,
                        onNetworkVideoDetected = viewModel::addDetectedVideoFromNetwork,
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(if (uiState.activeTab == AppTab.BROWSER) 1f else 0f)
                    )

                    if (uiState.activeTab == AppTab.DOWNLOADS) {
                        DownloadsGalleryScreen(
                            downloads = downloads,
                            activeSlot = uiState.activeSlot,
                            autoSaveToGallery = autoSaveToGallery,
                            telegramState = telegramState,
                            telegramAutoUploadEnabled = telegramAutoUploadEnabled,
                            onToggleAutoSave = viewModel::setAutoSaveToGallery,
                            onToggleTelegramUpload = viewModel::setTelegramAutoUploadEnabled,
                            onTriggerTelegramUpload = viewModel::triggerTelegramUploadNow,
                            onStartDirectDownload = { url -> viewModel.enqueueVideoDownload(url) },
                            onCancelDownload = viewModel::cancelDownload,
                            onRemoveRecord = viewModel::removeDownloadRecord,
                            onShowMessage = viewModel::showMessage,
                            modifier = Modifier
                                .fillMaxSize()
                                .zIndex(2f)
                                .background(MaterialTheme.colorScheme.background)
                        )
                    }
                }
            }
        }
    }

    if (uiState.showPinDialog) {
        PinSwitchDialog(
            config = uiState.config,
            activeSlot = uiState.activeSlot,
            pinInput = uiState.pinInput,
            pinError = uiState.pinError,
            onDigitClick = viewModel::appendPinDigit,
            onBackspaceClick = viewModel::backspacePin,
            onConfirmClick = { viewModel.verifyPinAndSwitchUrl() },
            onDismiss = viewModel::dismissPinDialog
        )
    }
}

@Composable
private fun TabIcon(
    tab: AppTab,
    selected: Boolean,
    activeDownloadCount: Int
) {
    val icon = when (tab) {
        AppTab.BROWSER -> if (selected) Icons.Rounded.Public else Icons.Outlined.Public
        AppTab.DOWNLOADS -> if (selected) Icons.Rounded.Download else Icons.Outlined.Download
    }

    if (tab == AppTab.DOWNLOADS && activeDownloadCount > 0) {
        BadgedBox(
            badge = { Badge { Text(activeDownloadCount.toString()) } }
        ) {
            Icon(imageVector = icon, contentDescription = tab.label)
        }
    } else {
        Icon(imageVector = icon, contentDescription = tab.label)
    }
}
