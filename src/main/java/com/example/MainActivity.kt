package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VoucherType
import com.example.data.model.OperationAuditLog
import com.example.util.AppNotificationManager
import com.example.ui.components.DeviceRoleStatusChip
import com.example.ui.components.KickedDeviceDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.CloudCollaborationScreen
import com.example.ui.screens.DeviceManagementScreen
import com.example.ui.screens.AuditLogScreen
import com.example.ui.screens.ItemsScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TransactionsScreen
import com.example.ui.screens.WarehousesScreen
import com.example.ui.theme.AppTheme
import com.example.ui.theme.SmartInventoryTheme
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import com.example.ui.viewmodel.DeviceAction
import com.example.ui.viewmodel.InventoryViewModelFactory
import com.example.ui.viewmodel.CloudCollaborationViewModel
import com.example.ui.viewmodel.CloudCollaborationViewModelFactory

enum class AppNavDestination(val titleAr: String, val icon: ImageVector) {
    DASHBOARD("الرئيسية", Icons.Default.Dashboard),
    WAREHOUSES("المخازن", Icons.Default.Warehouse),
    ITEMS("الأصناف", Icons.Default.Inventory2),
    TRANSACTIONS("العمليات", Icons.AutoMirrored.Filled.ReceiptLong),
    REPORTS("التقارير", Icons.Default.Assessment),
    AUDIT_LOG("سجل العمليات", Icons.AutoMirrored.Filled.ReceiptLong),
    SETTINGS("الإعدادات", Icons.Default.Settings),
    DEVICES("الأجهزة", Icons.Default.Devices),
    CLOUD_COLLABORATION("التعاون السحابي", Icons.Default.Cloud)
}

class MainActivity : ComponentActivity() {
    private val viewModel: InventoryViewModel by viewModels {
        InventoryViewModelFactory(application)
    }
    private val cloudViewModel: CloudCollaborationViewModel by viewModels {
        CloudCollaborationViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val currentTheme by viewModel.theme.collectAsState()
            SmartInventoryTheme(theme = currentTheme) {
                // Ensure RTL layout for Arabic
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MainApp(viewModel = viewModel, cloudViewModel = cloudViewModel)
                }
            }
        }
    }

}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(
    viewModel: InventoryViewModel,
    cloudViewModel: CloudCollaborationViewModel
) {
    var showSplash by remember { mutableStateOf(true) }
    var currentDestination by remember { mutableStateOf(AppNavDestination.DASHBOARD) }
    var transactionFilterPreset by remember { mutableStateOf<VoucherType?>(null) }

    val context = LocalContext.current
    fun showVisibleMessage(message: String) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
    }
    fun navigateTo(destination: AppNavDestination) {
        if (destination == AppNavDestination.REPORTS) {
            viewModel.checkPermission(
                action = DeviceAction.VIEW_REPORTS,
                onDenied = { showVisibleMessage("لا تملك صلاحية عرض التقارير لأي مخزن.") }
            ) { currentDestination = destination }
        } else {
            currentDestination = destination
        }
    }
    val stockSnapshot by viewModel.stockSnapshot.collectAsState()
    val currentTheme by viewModel.theme.collectAsState()
    val isMainDevice by viewModel.isMainDevice.collectAsState()
    val devicePermissions by viewModel.devicePermissions.collectAsState()
    val isSubConnected by viewModel.isSubConnected.collectAsState()
    val serverStatus by viewModel.serverStatus.collectAsState()
    val kickedAlertMessage by viewModel.kickedAlertMessage.collectAsState()
    val operationAuditLogs by viewModel.operationAuditLogs.collectAsState()
    val linkedSubDevices by viewModel.linkedSubDevices.collectAsState()
    val currentDeviceName by viewModel.deviceName.collectAsState()
    val isOperationRunning by viewModel.isOperationRunning.collectAsState()

    var hasAskedNotificationPermission by remember { mutableStateOf(false) }
    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(showSplash) {
        if (!showSplash && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !hasAskedNotificationPermission) {
            hasAskedNotificationPermission = true
            if (!AppNotificationManager.hasNotificationPermission(context)) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    // Background sync already runs on the sub-device. Avoid an extra network/database
    // update specifically when opening the device-management screen.
    LaunchedEffect(currentDestination, isSubConnected, isMainDevice) {
        if (
            !isMainDevice &&
            isSubConnected &&
            !showSplash &&
            currentDestination != AppNavDestination.DEVICES
        ) {
            viewModel.syncOnNavigation()
        }
    }

    // Android system toasts are rendered above Compose dialogs and modal windows.
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { message ->
            showVisibleMessage(message)
        }
    }

    // Show immediate notification when kicked from Main Device
    if (!showSplash && kickedAlertMessage != null) {
        KickedDeviceDialog(
            message = kickedAlertMessage!!,
            onDismiss = { viewModel.dismissKickedAlert() }
        )
    }

    // Handle Back Press on Subscreens
    BackHandler(enabled = !showSplash && currentDestination != AppNavDestination.DASHBOARD) {
        currentDestination = AppNavDestination.DASHBOARD
    }

    if (showSplash) {
        SplashScreen(
            onSplashFinished = { showSplash = false }
        )
    } else {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpandedWidth = maxWidth >= 700.dp

            Scaffold(
                topBar = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(MaterialTheme.colorScheme.surface)
                    ) {
                        CenterAlignedTopAppBar(
                            title = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Surface(
                                        shape = CircleShape,
                                        modifier = Modifier.size(32.dp),
                                        color = MaterialTheme.colorScheme.primaryContainer
                                    ) {
                                        Image(
                                            painter = painterResource(id = R.drawable.ic_warehouse_launcher_fg),
                                            contentDescription = null,
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .clip(CircleShape)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "مخزني الذكي",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            },
                            navigationIcon = {
                                val syncReady = if (isMainDevice) serverStatus.isRunning else isSubConnected
                                IconButton(
                                    onClick = { navigateTo(AppNavDestination.DEVICES) },
                                    modifier = Modifier.testTag("top_bar_sync_status_btn")
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Hub,
                                        contentDescription = "إعدادات ربط الأجهزة",
                                        tint = if (syncReady) SuccessGreen else WarningOrange
                                    )
                                }
                            },
                            actions = {
                                // Quick Theme Switcher Action
                                var themeMenuExpanded by remember { mutableStateOf(false) }
                                Box {
                                    IconButton(
                                        onClick = { themeMenuExpanded = true },
                                        modifier = Modifier.testTag("top_bar_theme_btn")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Palette,
                                            contentDescription = "تغيير الثيم",
                                            tint = MaterialTheme.colorScheme.primary
                                        )
                                    }
                                    DropdownMenu(
                                        expanded = themeMenuExpanded,
                                        onDismissRequest = { themeMenuExpanded = false }
                                    ) {
                                        AppTheme.values().forEach { thm ->
                                            DropdownMenuItem(
                                                text = {
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        Box(
                                                            modifier = Modifier
                                                                .size(16.dp)
                                                                .background(Color(thm.primaryColorHex), CircleShape)
                                                        )
                                                        Spacer(modifier = Modifier.width(8.dp))
                                                        Text(
                                                            text = thm.titleAr,
                                                            fontWeight = if (currentTheme == thm) FontWeight.Bold else FontWeight.Normal
                                                        )
                                                    }
                                                },
                                                onClick = {
                                                    viewModel.setTheme(thm)
                                                    themeMenuExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            },
                            colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                                containerColor = MaterialTheme.colorScheme.surface
                            )
                        )
                        if (isOperationRunning) {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }

                        // Role & LAN Link Status Strip (Click opens Settings -> MultiDevice Control)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { navigateTo(AppNavDestination.DEVICES) }
                                .padding(horizontal = 14.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            DeviceRoleStatusChip(
                                isMainDevice = isMainDevice,
                                permissions = devicePermissions
                            )
                            val linkLabel = when {
                                isMainDevice && serverStatus.isRunning && serverStatus.hostAddresses.isNotEmpty() ->
                                    "🌐 خادم الربط نشط (${serverStatus.primaryAddress})"
                                isMainDevice ->
                                    "🌐 جاهز للربط المباشر عبر الشبكة"
                                isSubConnected ->
                                    "🔗 متصل بالجهاز الرئيسي"
                                else ->
                                    "⚠️ بانتظار ربط الجهاز الفرعي"
                            }
                            Text(
                                text = linkLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isMainDevice || isSubConnected) SuccessGreen else WarningOrange
                            )
                        }
                    }
                },
                bottomBar = {
                    if (!isExpandedWidth) {
                        NavigationBar(
                            containerColor = MaterialTheme.colorScheme.surface,
                            tonalElevation = 8.dp
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AppNavDestination.values().forEach { destination ->
                                    val isSelected = currentDestination == destination
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = {
                                            if (destination == AppNavDestination.TRANSACTIONS) transactionFilterPreset = null
                                            navigateTo(destination)
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = destination.icon,
                                                contentDescription = destination.titleAr,
                                                modifier = Modifier.size(if (isSelected) 24.dp else 21.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = destination.titleAr,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                maxLines = 1
                                            )
                                        },
                                        modifier = Modifier.width(82.dp).testTag("nav_item_${destination.name.lowercase()}")
                                    )
                                }
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
                    if (isExpandedWidth) {
                        NavigationRail(
                            modifier = Modifier.fillMaxHeight(),
                            containerColor = MaterialTheme.colorScheme.surface
                        ) {
                            Spacer(modifier = Modifier.size(8.dp))
                            AppNavDestination.values().forEach { destination ->
                                val isSelected = currentDestination == destination
                                NavigationRailItem(
                                    selected = isSelected,
                                    onClick = {
                                        if (destination == AppNavDestination.TRANSACTIONS) {
                                            transactionFilterPreset = null
                                        }
                                        navigateTo(destination)
                                    },
                                    icon = {
                                        Icon(
                                            imageVector = destination.icon,
                                            contentDescription = destination.titleAr
                                        )
                                    },
                                    label = { Text(destination.titleAr, fontSize = 11.sp) }
                                )
                            }
                        }
                    }

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    ) {
                        AnimatedContent(
                            targetState = currentDestination,
                            transitionSpec = { fadeIn() togetherWith fadeOut() },
                            label = "screen_transition"
                        ) { destination ->
                            when (destination) {
                                AppNavDestination.DASHBOARD -> DashboardScreen(
                                    snapshot = stockSnapshot,
                                    operationAuditLogs = operationAuditLogs,
                                    linkedSubDevices = linkedSubDevices,
                                    isMainDevice = isMainDevice,
                                    currentDeviceName = currentDeviceName,
                                    onNavigateToTransactions = {
                                        transactionFilterPreset = null
                                        currentDestination = AppNavDestination.TRANSACTIONS
                                    },
                                    onNavigateToInward = {
                                        transactionFilterPreset = VoucherType.INWARD
                                        currentDestination = AppNavDestination.TRANSACTIONS
                                    },
                                    onNavigateToWarehouses = {
                                        currentDestination = AppNavDestination.WAREHOUSES
                                    },
                                    onNavigateToItems = {
                                        currentDestination = AppNavDestination.ITEMS
                                    }
                                )
                                AppNavDestination.WAREHOUSES -> WarehousesScreen(
                                    viewModel = viewModel,
                                    snapshot = stockSnapshot
                                )
                                AppNavDestination.ITEMS -> ItemsScreen(
                                    viewModel = viewModel,
                                    snapshot = stockSnapshot
                                )
                                AppNavDestination.TRANSACTIONS -> TransactionsScreen(
                                    viewModel = viewModel,
                                    snapshot = stockSnapshot,
                                    initialTypeFilter = transactionFilterPreset
                                )
                                AppNavDestination.REPORTS -> ReportsScreen(
                                    viewModel = viewModel,
                                    snapshot = stockSnapshot
                                )
                                AppNavDestination.AUDIT_LOG -> AuditLogScreen(viewModel = viewModel, isMainDevice = isMainDevice, currentDeviceName = currentDeviceName)
                                AppNavDestination.SETTINGS -> SettingsScreen(
                                    viewModel = viewModel
                                )
                                AppNavDestination.DEVICES -> DeviceManagementScreen(viewModel = viewModel)
                                AppNavDestination.CLOUD_COLLABORATION -> CloudCollaborationScreen(
                                    viewModel = cloudViewModel,
                                    onOpenSettings = { currentDestination = AppNavDestination.SETTINGS }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
