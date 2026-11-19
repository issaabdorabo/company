package com.example.ui.screens

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.net.Uri
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.BatteryChargingFull
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.PhotoLibrary
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Send
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.VpnKey
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.example.data.BrowsingHistoryItem
import com.example.data.DetectedVideo
import com.example.data.ServerConnectionState
import com.example.data.TelegramUploadState
import com.example.data.UrlSlot
import com.example.data.VpnConnectionState
import com.example.ui.BrowserUiState
import com.example.ui.WebAction
import com.example.ui.theme.StatusSuccess
import com.example.ui.theme.StatusWarning
import com.example.util.FormatUtils
import org.json.JSONArray

private const val VIDEO_SCANNER_JS = """
(function() {
  try {
    var results = [];
    var seen = {};
    function addUrl(u, tag) {
      if (!u || typeof u !== 'string') return;
      if (u.startsWith('http://') || u.startsWith('https://')) {
        if (!seen[u]) {
          seen[u] = true;
          results.push({ url: u, title: document.title || 'فيديو ويب', tag: tag });
        }
      }
    }
    var videos = document.querySelectorAll('video');
    for (var i = 0; i < videos.length; i++) {
      var v = videos[i];
      addUrl(v.currentSrc, 'video');
      addUrl(v.src, 'video');
      addUrl(v.getAttribute('data-src'), 'video');
      var sources = v.querySelectorAll('source');
      for (var j = 0; j < sources.length; j++) {
        addUrl(sources[j].src, 'source');
        addUrl(sources[j].getAttribute('data-src'), 'source');
      }
    }
    var links = document.querySelectorAll('a[href]');
    for (var k = 0; k < links.length; k++) {
      var href = links[k].href;
      if (/\.(mp4|webm|mov|m4v|mkv|3gp)(\?|$)/i.test(href)) {
        addUrl(href, 'link');
      }
    }
    if (window.VideoBridge) {
      window.VideoBridge.onVideosDetected(JSON.stringify(results));
    }
  } catch (e) {}
})();
"""

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(
    uiState: BrowserUiState,
    isTabActive: Boolean,
    detectedVideos: List<DetectedVideo>,
    visibleHistory: List<BrowsingHistoryItem>,
    serverState: ServerConnectionState,
    vpnState: VpnConnectionState,
    telegramState: TelegramUploadState,
    autoSaveToGallery: Boolean,
    keepAliveEnabled: Boolean,
    telegramAutoUploadEnabled: Boolean,
    onNavigateInputUrl: (String) -> Unit,
    onGoHome: () -> Unit,
    onReloadOrStop: () -> Unit,
    onGoBack: () -> Unit,
    onGoForward: () -> Unit,
    onOpenPinSwitch: () -> Unit,
    onOpenVideoSheet: () -> Unit,
    onCloseVideoSheet: () -> Unit,
    onOpenHistorySheet: () -> Unit,
    onCloseHistorySheet: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenStatusSheet: () -> Unit,
    onCloseStatusSheet: () -> Unit,
    onToggleKeepAlive: (Boolean) -> Unit,
    onToggleTelegramUpload: (Boolean) -> Unit,
    onRequestBatteryExemption: () -> Unit,
    onActivateVpnNow: () -> Unit,
    onDownloadVideo: (url: String, fileName: String?, mimeType: String?, userAgent: String?) -> Unit,
    onOpenDownloadsTab: () -> Unit,
    onConsumeWebAction: () -> Unit,
    onWebNavigationChanged: (url: String?, title: String?, canGoBack: Boolean, canGoForward: Boolean, slot: UrlSlot) -> Unit,
    onWebProgressChanged: (progress: Int, isLoading: Boolean, slot: UrlSlot) -> Unit,
    onPageStarted: (url: String?, slot: UrlSlot) -> Unit,
    onVideosDetected: (List<DetectedVideo>) -> Unit,
    onNetworkVideoDetected: (url: String, mimeType: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var addressBarText by remember(uiState.currentUrl) { mutableStateOf(uiState.currentUrl) }
    var customVideoView by remember { mutableStateOf<View?>(null) }
    var customViewCallback by remember { mutableStateOf<WebChromeClient.CustomViewCallback?>(null) }
    var fileUploadCallback by remember { mutableStateOf<ValueCallback<Array<Uri>>?>(null) }

    val fileChooserLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        fileUploadCallback?.onReceiveValue(if (uris.isEmpty()) null else uris.toTypedArray())
        fileUploadCallback = null
    }

    // Two completely isolated WebViews so back/forward history never leaks from Secondary -> Default
    val defaultWebView = remember {
        createConfiguredWebView(
            context = context,
            slot = UrlSlot.DEFAULT,
            onPageStarted = { url -> onPageStarted(url, UrlSlot.DEFAULT) },
            onNavigationChanged = { url, title, back, fwd ->
                onWebNavigationChanged(url, title, back, fwd, UrlSlot.DEFAULT)
            },
            onProgressChanged = { progress, loading ->
                onWebProgressChanged(progress, loading, UrlSlot.DEFAULT)
            },
            onVideosDetected = onVideosDetected,
            onNetworkVideoDetected = onNetworkVideoDetected,
            onDownloadTriggered = { url, disposition, mime, ua ->
                val suggestedName = disposition?.let {
                    Regex("filename=\"?([^\";]+)\"?").find(it)?.groupValues?.getOrNull(1)
                }
                onDownloadVideo(url, suggestedName, mime, ua)
            },
            onShowFileChooser = { callback ->
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = callback
                fileChooserLauncher.launch(arrayOf("*/*"))
            },
            onEnterFullscreen = { view, callback ->
                customVideoView = view
                customViewCallback = callback
            },
            onExitFullscreen = {
                customVideoView = null
                customViewCallback = null
            }
        )
    }

    val secondaryWebView = remember {
        createConfiguredWebView(
            context = context,
            slot = UrlSlot.SECONDARY,
            onPageStarted = { url -> onPageStarted(url, UrlSlot.SECONDARY) },
            onNavigationChanged = { url, title, back, fwd ->
                onWebNavigationChanged(url, title, back, fwd, UrlSlot.SECONDARY)
            },
            onProgressChanged = { progress, loading ->
                onWebProgressChanged(progress, loading, UrlSlot.SECONDARY)
            },
            onVideosDetected = onVideosDetected,
            onNetworkVideoDetected = onNetworkVideoDetected,
            onDownloadTriggered = { url, disposition, mime, ua ->
                val suggestedName = disposition?.let {
                    Regex("filename=\"?([^\";]+)\"?").find(it)?.groupValues?.getOrNull(1)
                }
                onDownloadVideo(url, suggestedName, mime, ua)
            },
            onShowFileChooser = { callback ->
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = callback
                fileChooserLauncher.launch(arrayOf("*/*"))
            },
            onEnterFullscreen = { view, callback ->
                customVideoView = view
                customViewCallback = callback
            },
            onExitFullscreen = {
                customVideoView = null
                customViewCallback = null
            }
        )
    }

    val activeWebView = if (uiState.activeSlot == UrlSlot.DEFAULT) defaultWebView else secondaryWebView

    // Pause inactive WebView and sync navigation metadata when switching slots
    LaunchedEffect(uiState.activeSlot) {
        if (uiState.activeSlot == UrlSlot.DEFAULT) {
            secondaryWebView.onPause()
            secondaryWebView.clearHistory()
            defaultWebView.onResume()
            onWebNavigationChanged(
                defaultWebView.url ?: uiState.config.defaultUrl,
                defaultWebView.title,
                defaultWebView.canGoBack(),
                defaultWebView.canGoForward(),
                UrlSlot.DEFAULT
            )
        } else {
            defaultWebView.onPause()
            secondaryWebView.onResume()
            onWebNavigationChanged(
                secondaryWebView.url ?: uiState.config.secondaryUrl,
                secondaryWebView.title,
                secondaryWebView.canGoBack(),
                secondaryWebView.canGoForward(),
                UrlSlot.SECONDARY
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            defaultWebView.destroy()
            secondaryWebView.destroy()
        }
    }

    LaunchedEffect(uiState.webAction, uiState.activeSlot) {
        when (val action = uiState.webAction) {
            is WebAction.LoadUrl -> {
                activeWebView.loadUrl(action.url)
                onConsumeWebAction()
            }
            is WebAction.Reload -> {
                activeWebView.reload()
                onConsumeWebAction()
            }
            is WebAction.StopLoading -> {
                activeWebView.stopLoading()
                onConsumeWebAction()
            }
            is WebAction.GoBack -> {
                if (activeWebView.canGoBack()) activeWebView.goBack()
                onConsumeWebAction()
            }
            is WebAction.GoForward -> {
                if (activeWebView.canGoForward()) activeWebView.goForward()
                onConsumeWebAction()
            }
            is WebAction.ScanVideos -> {
                activeWebView.evaluateJavascript(VIDEO_SCANNER_JS, null)
                onConsumeWebAction()
            }
            null -> Unit
        }
    }

    BackHandler(enabled = isTabActive && (customVideoView != null || uiState.canGoBack)) {
        if (customVideoView != null) {
            customViewCallback?.onCustomViewHidden()
            customVideoView = null
            customViewCallback = null
        } else if (activeWebView.canGoBack()) {
            activeWebView.goBack()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Top Browser Header
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 2.dp
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Background Server + VPN Status Pill
                        Surface(
                            modifier = Modifier
                                .clip(RoundedCornerShape(50))
                                .clickable(onClick = onOpenStatusSheet)
                                .testTag("server_status_pill"),
                            color = if (uiState.activeSlot == UrlSlot.SECONDARY && vpnState.isVpnActive) {
                                MaterialTheme.colorScheme.primaryContainer
                            } else if (serverState.isConnected) {
                                StatusSuccess.copy(alpha = 0.14f)
                            } else {
                                StatusWarning.copy(alpha = 0.14f)
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (uiState.activeSlot == UrlSlot.SECONDARY) {
                                    Icon(
                                        imageVector = Icons.Rounded.VpnKey,
                                        contentDescription = "حالة VPN",
                                        tint = if (vpnState.isVpnActive) StatusSuccess else StatusWarning,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = if (vpnState.isVpnActive) "VPN" else "مقفل",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(if (serverState.isConnected) StatusSuccess else StatusWarning)
                                    )
                                    Spacer(modifier = Modifier.width(5.dp))
                                    Text(
                                        text = "افتراضي",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                        }

                        // Address Bar
                        Surface(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp),
                            shape = RoundedCornerShape(14.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = if (uiState.currentUrl.startsWith("https://")) {
                                        Icons.Rounded.Lock
                                    } else {
                                        Icons.Rounded.Public
                                    },
                                    contentDescription = null,
                                    tint = if (uiState.currentUrl.startsWith("https://")) {
                                        StatusSuccess
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                    BasicTextField(
                                        value = addressBarText,
                                        onValueChange = { addressBarText = it },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            color = MaterialTheme.colorScheme.onSurface,
                                            fontSize = 13.sp
                                        ),
                                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                        keyboardOptions = KeyboardOptions(
                                            keyboardType = KeyboardType.Uri,
                                            imeAction = ImeAction.Go
                                        ),
                                        keyboardActions = KeyboardActions(
                                            onGo = {
                                                focusManager.clearFocus()
                                                onNavigateInputUrl(addressBarText)
                                            }
                                        ),
                                        modifier = Modifier
                                            .weight(1f)
                                            .testTag("url_input_field")
                                    )
                                }
                                IconButton(
                                    onClick = onReloadOrStop,
                                    modifier = Modifier
                                        .size(34.dp)
                                        .testTag("reload_stop_button")
                                ) {
                                    Icon(
                                        imageVector = if (uiState.isLoading) Icons.Rounded.Close else Icons.Rounded.Refresh,
                                        contentDescription = if (uiState.isLoading) "إيقاف التحميل" else "إعادة تحميل",
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }

                        // PIN-Protected URL Switcher Button
                        FilledTonalIconButton(
                            onClick = onOpenPinSwitch,
                            modifier = Modifier
                                .minimumInteractiveComponentSize()
                                .testTag("switch_url_button")
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.SwapHoriz,
                                contentDescription = "تغيير رابط المتصفح برمز التأكيد"
                            )
                        }
                    }

                    if (uiState.isLoading && uiState.loadingProgress in 1..99) {
                        LinearProgressIndicator(
                            progress = { uiState.loadingProgress / 100f },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(2.5.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    } else {
                        HorizontalDivider(
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outline.copy(alpha = 0.4f)
                        )
                    }
                }
            }

            // Isolated Dual WebView Container
            Box(modifier = Modifier.weight(1f)) {
                AndroidView(
                    factory = {
                        (defaultWebView.parent as? ViewGroup)?.removeView(defaultWebView)
                        defaultWebView
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(if (uiState.activeSlot == UrlSlot.DEFAULT) 1f else 0f)
                )

                if (uiState.activeSlot == UrlSlot.SECONDARY) {
                    AndroidView(
                        factory = {
                            (secondaryWebView.parent as? ViewGroup)?.removeView(secondaryWebView)
                            secondaryWebView
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .zIndex(2f)
                    )
                }

                // Floating Download Video Button when videos detected (including YouTube!)
                androidx.compose.animation.AnimatedVisibility(
                    visible = detectedVideos.isNotEmpty() || uiState.isExtractingYouTube,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(16.dp)
                        .zIndex(3f)
                ) {
                    ExtendedFloatingActionButton(
                        onClick = onOpenVideoSheet,
                        modifier = Modifier.testTag("download_detected_video_fab"),
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        icon = {
                            if (uiState.isExtractingYouTube && detectedVideos.isEmpty()) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(20.dp),
                                    color = MaterialTheme.colorScheme.onPrimary,
                                    strokeWidth = 2.dp
                                )
                            } else {
                                BadgedBox(
                                    badge = {
                                        Badge { Text(text = detectedVideos.size.toString()) }
                                    }
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Download,
                                        contentDescription = "تنزيل الفيديو"
                                    )
                                }
                            }
                        },
                        text = {
                            Text(
                                text = if (uiState.isExtractingYouTube && detectedVideos.isEmpty()) {
                                    "جاري تجهيز الفيديو..."
                                } else {
                                    "تنزيل الفيديو"
                                },
                                style = MaterialTheme.typography.labelLarge
                            )
                        }
                    )
                }
            }

            // Browser Control Bar (no File Manager icon)
            Surface(
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = onGoBack,
                        enabled = uiState.canGoBack,
                        modifier = Modifier.testTag("browser_back_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "رجوع"
                        )
                    }

                    IconButton(
                        onClick = onGoForward,
                        enabled = uiState.canGoForward,
                        modifier = Modifier.testTag("browser_forward_button")
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowForward,
                            contentDescription = "تقدم"
                        )
                    }

                    IconButton(
                        onClick = onGoHome,
                        modifier = Modifier.testTag("browser_home_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Home,
                            contentDescription = "الصفحة الرئيسية"
                        )
                    }

                    IconButton(
                        onClick = onOpenHistorySheet,
                        modifier = Modifier.testTag("browser_history_button")
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.History,
                            contentDescription = "سجل التصفح"
                        )
                    }

                    IconButton(
                        onClick = onOpenVideoSheet,
                        modifier = Modifier.testTag("scan_videos_button")
                    ) {
                        BadgedBox(
                            badge = {
                                if (detectedVideos.isNotEmpty()) {
                                    Badge { Text(detectedVideos.size.toString()) }
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.VideoLibrary,
                                contentDescription = "فيديوهات الصفحة"
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .clickable(onClick = onOpenDownloadsTab)
                            .testTag("gallery_autosave_chip"),
                        color = if (uiState.activeSlot == UrlSlot.SECONDARY) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else if (autoSaveToGallery) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = if (uiState.activeSlot == UrlSlot.SECONDARY) {
                                    Icons.Rounded.Lock
                                } else {
                                    Icons.Rounded.PhotoLibrary
                                },
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.secondary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(5.dp))
                            Text(
                                text = if (uiState.activeSlot == UrlSlot.SECONDARY) {
                                    "خزنة خاصة"
                                } else if (autoSaveToGallery) {
                                    "حفظ تلقائي"
                                } else {
                                    "تنزيلات"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }

        // Fullscreen video container
        customVideoView?.let { view ->
            AndroidView(
                factory = { ctx ->
                    FrameLayout(ctx).apply {
                        (view.parent as? ViewGroup)?.removeView(view)
                        addView(
                            view,
                            FrameLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.MATCH_PARENT
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(androidx.compose.ui.graphics.Color.Black)
                    .zIndex(10f)
            )
        }
    }

    // Detected Videos / YouTube Download Sheet
    if (uiState.showVideoSheet) {
        ModalBottomSheet(onDismissRequest = onCloseVideoSheet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "تنزيل الفيديو (شامل يوتيوب)",
                        style = MaterialTheme.typography.titleLarge
                    )
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (uiState.activeSlot == UrlSlot.SECONDARY) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else if (autoSaveToGallery) {
                            StatusSuccess.copy(alpha = 0.14f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        }
                    ) {
                        Text(
                            text = when {
                                uiState.activeSlot == UrlSlot.SECONDARY -> "حفظ مخفي بالخزنة"
                                autoSaveToGallery -> "حفظ تلقائي بالمعرض"
                                else -> "مجلد التنزيلات"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (autoSaveToGallery && uiState.activeSlot == UrlSlot.DEFAULT) {
                                StatusSuccess
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (uiState.isExtractingYouTube && detectedVideos.isEmpty()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text("جاري استخراج جودات الفيديو من يوتيوب...")
                    }
                } else if (detectedVideos.isEmpty()) {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        )
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Movie,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(36.dp)
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "لم يتم رصد روابط فيديو مباشرة بعد",
                                style = MaterialTheme.typography.titleSmall
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "افتح أي فيديو على يوتيوب أو أي موقع آخر وسيتم استخراج روابط التحميل تلقائياً.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(detectedVideos, key = { it.url }) { video ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(16.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(14.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Movie,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(28.dp)
                                    )
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = video.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = video.qualityLabel ?: video.sourceTag.uppercase(),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Button(
                                        onClick = {
                                            val safeName = if (video.sourceTag == "youtube") {
                                                video.title.replace(Regex("[^a-zA-Z0-9\\u0600-\\u06FF._ -]"), "_")
                                                    .take(56) + ".mp4"
                                            } else null
                                            onDownloadVideo(
                                                video.url,
                                                safeName,
                                                video.mimeType,
                                                activeWebView.settings.userAgentString
                                            )
                                            onCloseVideoSheet()
                                        },
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Rounded.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("تنزيل")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Isolated Browsing History Sheet
    if (uiState.showHistorySheet) {
        ModalBottomSheet(onDismissRequest = onCloseHistorySheet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = if (uiState.activeSlot == UrlSlot.DEFAULT) {
                                "سجل التصفح الافتراضي"
                            } else {
                                "سجل المتصفح الخاص (محمي)"
                            },
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = if (uiState.activeSlot == UrlSlot.DEFAULT) {
                                "سجل المتصفح الآخر مخفي تماماً هنا"
                            } else {
                                "سيتم إخفاء هذا السجل عند العودة للمتصفح الافتراضي"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    if (visibleHistory.isNotEmpty()) {
                        TextButton(onClick = onClearHistory) {
                            Icon(
                                imageVector = Icons.Rounded.DeleteSweep,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("مسح")
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                if (visibleHistory.isEmpty()) {
                    Text(
                        text = "لا يوجد سجل تصفح مسجل في هذا الوضع بعد.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp)
                    )
                } else {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(visibleHistory, key = { it.id }) { item ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .clickable {
                                        onNavigateInputUrl(item.url)
                                        onCloseHistorySheet()
                                    },
                                shape = RoundedCornerShape(14.dp),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.Public,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = item.title,
                                            style = MaterialTheme.typography.titleSmall,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                            Text(
                                                text = item.url,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                    Text(
                                        text = FormatUtils.formatTimestamp(item.visitedAtMillis),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // Background Server + Auto-VPN Status Sheet (opened from status pill)
    if (uiState.showStatusSheet) {
        ModalBottomSheet(onDismissRequest = onCloseStatusSheet) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "حالة الاتصال بالخادم و VPN",
                    style = MaterialTheme.typography.titleLarge
                )

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (serverState.isConnected) Icons.Rounded.CloudDone else Icons.Rounded.CloudOff,
                            contentDescription = null,
                            tint = if (serverState.isConnected) StatusSuccess else StatusWarning
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("الاتصال المستمر بالخادم في الخلفية", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (serverState.isConnected) {
                                    "متصل (${serverState.latencyMs}ms) • نبضات: ${serverState.heartbeatCount}"
                                } else {
                                    "نشط ويعيد المحاولة"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = keepAliveEnabled,
                            onCheckedChange = onToggleKeepAlive
                        )
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(18.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.VpnKey,
                            contentDescription = null,
                            tint = if (vpnState.isVpnActive) StatusSuccess else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("خدمة VPN التلقائية للمتصفح المحمي", style = MaterialTheme.typography.titleSmall)
                            Text(
                                text = if (uiState.activeSlot == UrlSlot.SECONDARY) {
                                    "${vpnState.statusMessage} • استعلامات مشفرة: ${vpnState.dnsQueriesEncrypted}"
                                } else {
                                    "تُفعّل تلقائياً عند التبديل إلى المتصفح الآخر بالرمز"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (uiState.activeSlot == UrlSlot.SECONDARY && !vpnState.isVpnActive) {
                            FilledTonalButton(onClick = onActivateVpnNow) {
                                Text("تفعيل")
                            }
                        }
                    }
                }

if (!uiState.isIgnoringBatteryOptimizations) {
                    FilledTonalButton(
                        onClick = onRequestBatteryExemption,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(14.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.BatteryChargingFull,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("استثناء من توفير البطارية للعمل الدائم في الخلفية")
                    }
                }
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private fun createConfiguredWebView(
    context: android.content.Context,
    slot: UrlSlot,
    onPageStarted: (String?) -> Unit,
    onNavigationChanged: (url: String?, title: String?, canGoBack: Boolean, canGoForward: Boolean) -> Unit,
    onProgressChanged: (progress: Int, isLoading: Boolean) -> Unit,
    onVideosDetected: (List<DetectedVideo>) -> Unit,
    onNetworkVideoDetected: (url: String, mimeType: String) -> Unit,
    onDownloadTriggered: (url: String, disposition: String?, mimeType: String?, userAgent: String?) -> Unit,
    onShowFileChooser: (ValueCallback<Array<Uri>>) -> Unit,
    onEnterFullscreen: (View, WebChromeClient.CustomViewCallback) -> Unit,
    onExitFullscreen: () -> Unit
): WebView {
    return WebView(context).apply {
        layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(this, true)

        settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            cacheMode = if (slot == UrlSlot.SECONDARY) WebSettings.LOAD_NO_CACHE else WebSettings.LOAD_DEFAULT
        }

        addJavascriptInterface(
            object {
                @JavascriptInterface
                fun onVideosDetected(json: String) {
                    runCatching {
                        val array = JSONArray(json)
                        val list = buildList {
                            for (i in 0 until array.length()) {
                                val obj = array.getJSONObject(i)
                                val url = obj.optString("url")
                                val title = obj.optString("title", "فيديو ويب")
                                val tag = obj.optString("tag", "video")
                                if (url.isNotBlank()) {
                                    add(
                                        DetectedVideo(
                                            url = url,
                                            title = title,
                                            mimeType = "video/mp4",
                                            sourceTag = tag
                                        )
                                    )
                                }
                            }
                        }
                        post { onVideosDetected(list) }
                    }
                }
            },
            "VideoBridge"
        )

        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                onDownloadTriggered(url, contentDisposition, mimetype, userAgent)
            }
        }

        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                onPageStarted(url)
                onNavigationChanged(url, view?.title, view?.canGoBack() == true, view?.canGoForward() == true)
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                onNavigationChanged(url, view?.title, view?.canGoBack() == true, view?.canGoForward() == true)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                onNavigationChanged(url, view?.title, view?.canGoBack() == true, view?.canGoForward() == true)
                view?.evaluateJavascript(VIDEO_SCANNER_JS, null)
            }

            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?
            ): WebResourceResponse? {
                val reqUrl = request?.url?.toString()
                if (!reqUrl.isNullOrBlank() &&
                    Regex("\\.(mp4|webm|mov|m4v|mkv|3gp)(\\?|$)", RegexOption.IGNORE_CASE).containsMatchIn(reqUrl)
                ) {
                    val mime = when {
                        reqUrl.contains(".webm", true) -> "video/webm"
                        reqUrl.contains(".mov", true) -> "video/quicktime"
                        reqUrl.contains(".mkv", true) -> "video/x-matroska"
                        else -> "video/mp4"
                    }
                    post { onNetworkVideoDetected(reqUrl, mime) }
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                val url = request?.url?.toString() ?: return false
                return !(url.startsWith("http://") || url.startsWith("https://"))
            }
        }

        webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                super.onProgressChanged(view, newProgress)
                onProgressChanged(newProgress, newProgress in 1..99)
            }

            override fun onReceivedTitle(view: WebView?, title: String?) {
                super.onReceivedTitle(view, title)
                onNavigationChanged(view?.url, title, view?.canGoBack() == true, view?.canGoForward() == true)
            }

            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { req ->
                    post {
                        runCatching { req.grant(req.resources) }
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback == null) return false
                onShowFileChooser(filePathCallback)
                return true
            }

            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view != null && callback != null) {
                    onEnterFullscreen(view, callback)
                }
            }

            override fun onHideCustomView() {
                onExitFullscreen()
            }
        }
    }
}
