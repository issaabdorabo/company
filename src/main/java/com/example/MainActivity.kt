package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Assessment
import androidx.compose.material.icons.filled.CloudDone
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.CloudSync
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.ReceiptLong
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.credentials.CredentialManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.VoucherType
import com.example.ui.components.DeviceRoleStatusChip
import com.example.ui.components.KickedDeviceDialog
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.ItemsScreen
import com.example.ui.screens.ReportsScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.screens.SplashScreen
import com.example.ui.screens.TransactionsScreen
import com.example.ui.screens.WarehousesScreen
import com.example.ui.screens.attemptSilentGoogleSignIn
import com.example.ui.screens.findActivity
import com.example.ui.theme.AppTheme
import com.example.ui.theme.SmartInventoryTheme
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import com.example.ui.viewmodel.InventoryViewModel
import com.example.ui.viewmodel.InventoryViewModelFactory
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import kotlinx.coroutines.tasks.await

enum class AppNavDestination(val titleAr: String, val icon: ImageVector) {
    DASHBOARD("الرئيسية", Icons.Default.Dashboard),
    WAREHOUSES("المخازن", Icons.Default.Warehouse),
    ITEMS("الأصناف", Icons.Default.Inventory2),
    TRANSACTIONS("العمليات", Icons.Default.ReceiptLong),
    REPORTS("التقارير", Icons.Default.Assessment),
    SETTINGS("الإعدادات", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    private val viewModel: InventoryViewModel by viewModels {
        InventoryViewModelFactory(application)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val currentTheme by viewModel.theme.collectAsState()
            SmartInventoryTheme(theme = currentTheme) {
                // Ensure RTL layout for Arabic
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    MainApp(viewModel = viewModel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp(viewModel: InventoryViewModel) {
    var showSplash by remember { mutableStateOf(true) }
    var currentDestination by remember { mutableStateOf(AppNavDestination.DASHBOARD) }
    var transactionFilterPreset by remember { mutableStateOf<VoucherType?>(null) }

    val auth = remember { Firebase.auth }
    var currentUser by remember { mutableStateOf(auth.currentUser) }

    DisposableEffect(auth) {
        val listener = FirebaseAuth.AuthStateListener { firebaseAuth ->
            currentUser = firebaseAuth.currentUser
        }
        auth.addAuthStateListener(listener)
        onDispose {
            auth.removeAuthStateListener(listener)
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val stockSnapshot by viewModel.stockSnapshot.collectAsState()
    val currentTheme by viewModel.theme.collectAsState()
    val isMainDevice by viewModel.isMainDevice.collectAsState()
    val devicePermissions by viewModel.devicePermissions.collectAsState()
    val isSyncingCloud by viewModel.isSyncingCloud.collectAsState()
    val recoveryWorkspace by viewModel.cloudRecoveryPromptWorkspace.collectAsState()
    val kickedAlertMessage by viewModel.kickedAlertMessage.collectAsState()

    // Handle ViewModel snackbar messages
    LaunchedEffect(Unit) {
        viewModel.userMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
        }
    }

    // Silently restore an already-authorized Google account session or pending OAuth result
    val rootContext = LocalContext.current
    LaunchedEffect(Unit) {
        if (auth.currentUser == null) {
            runCatching { auth.pendingAuthResult?.await() }
        }
        if (auth.currentUser == null) {
            val uiCtx = rootContext.findActivity() ?: rootContext
            val cm = CredentialManager.create(uiCtx)
            attemptSilentGoogleSignIn(uiCtx, cm)
        }
    }

    // Sync Firebase Auth session changes without blocking offline/local usage
    LaunchedEffect(currentUser?.uid) {
        val activeUser = currentUser
        if (activeUser != null) {
            viewModel.onAuthenticatedSessionStarted(activeUser.uid, activeUser.email)
        } else {
            viewModel.onSignedOut()
        }
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
        val isAuthenticated = currentUser != null

        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isExpandedWidth = maxWidth >= 700.dp

            Scaffold(
                snackbarHost = { SnackbarHost(snackbarHostState) },
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
                                            painter = painterResource(id = R.drawable.ic_inventory_master_1794215182343),
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
                                IconButton(
                                    onClick = {
                                        if (isAuthenticated) {
                                            if (!isSyncingCloud) viewModel.performManualSync()
                                        } else {
                                            currentDestination = AppNavDestination.SETTINGS
                                        }
                                    },
                                    modifier = Modifier.testTag("top_bar_sync_btn")
                                ) {
                                    if (isSyncingCloud) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(20.dp),
                                            strokeWidth = 2.dp,
                                            color = MaterialTheme.colorScheme.primary
                                        )
                                    } else {
                                        Icon(
                                            imageVector = if (isAuthenticated) Icons.Default.CloudSync else Icons.Default.CloudOff,
                                            contentDescription = if (isAuthenticated) "مزامنة مع السحابة الموحدة" else "التحقق والربط السحابي في الإعدادات",
                                            tint = if (isAuthenticated) SuccessGreen else WarningOrange
                                        )
                                    }
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

                            // Compact live status strip showing device mode, permissions, and cloud link
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                DeviceRoleStatusChip(
                                    isMainDevice = isMainDevice,
                                    permissions = devicePermissions,
                                    modifier = Modifier.testTag("top_device_role_chip"),
                                    onClick = { currentDestination = AppNavDestination.SETTINGS }
                                )

                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable {
                                        currentDestination = AppNavDestination.SETTINGS
                                    }
                                ) {
                                    Icon(
                                        imageVector = if (isAuthenticated) Icons.Default.CloudDone else Icons.Default.CloudOff,
                                        contentDescription = null,
                                        tint = if (isAuthenticated) SuccessGreen else WarningOrange,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = when {
                                            isSyncingCloud -> "جاري المزامنة..."
                                            isAuthenticated -> "قاعدة موحدة متصلة"
                                            else -> "وضع محلي • التحقق والربط"
                                        },
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isAuthenticated) SuccessGreen else WarningOrange
                                    )
                                }
                            }
                        }
                    },
                    bottomBar = {
                        if (!isExpandedWidth) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 6.dp
                            ) {
                                AppNavDestination.values().forEach { destination ->
                                    val isSelected = currentDestination == destination
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = {
                                            if (destination == AppNavDestination.TRANSACTIONS) {
                                                transactionFilterPreset = null
                                            }
                                            currentDestination = destination
                                        },
                                        icon = {
                                            Icon(
                                                imageVector = destination.icon,
                                                contentDescription = destination.titleAr,
                                                modifier = Modifier.size(22.dp)
                                            )
                                        },
                                        label = {
                                            Text(
                                                text = destination.titleAr,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                            )
                                        },
                                        modifier = Modifier.testTag("nav_item_${destination.name.lowercase()}")
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
                                            currentDestination = destination
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
                                    AppNavDestination.SETTINGS -> SettingsScreen(
                                        viewModel = viewModel
                                    )
                                }
                            }
                        }
                    }
                }

            // Reinstall Cloud Recovery Prompt Dialog
            if (recoveryWorkspace != null) {
                val ws = recoveryWorkspace!!
                AlertDialog(
                    onDismissRequest = {},
                    icon = {
                        Icon(
                            imageVector = Icons.Default.CloudDownload,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(40.dp)
                        )
                    },
                    title = {
                        Text(
                            text = "تم العثور على قاعدة بيانات مخزنية سحابية",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                text = "مرحباً بك مجدداً! يبدو أنك أعدت تثبيت التطبيق أو تستخدم جهازاً جديداً بنفس حساب Google.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.45f)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            ) {
                                Column(modifier = Modifier.padding(10.dp)) {
                                    Text(
                                        text = "المنشأة: ${ws.orgName.ifBlank { "المؤسسة العامة للتوريد والمخازن" }}",
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        text = "رمز القاعدة الموحدة: ${ws.workspaceCode}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                            Text(
                                text = "هل تريد استرداد كافة المخازن والأصناف والسندات والأرصدة من السحابة الآن؟",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    confirmButton = {
                        Button(
                            onClick = { viewModel.confirmCloudRecovery() },
                            modifier = Modifier.testTag("confirm_cloud_recovery_btn")
                        ) {
                            Text("استرداد البيانات الآن")
                        }
                    },
                    dismissButton = {
                        OutlinedButton(
                            onClick = { viewModel.dismissRecoveryPrompt() },
                            modifier = Modifier.testTag("dismiss_cloud_recovery_btn")
                        ) {
                            Text("البدء ببيانات جديدة")
                        }
                    },
                    shape = RoundedCornerShape(18.dp)
                )
            }

            // Instant Kick / Revocation Blocking Dialog
            if (kickedAlertMessage != null) {
                KickedDeviceDialog(
                    message = kickedAlertMessage!!,
                    onAcknowledge = { viewModel.acknowledgeKickAndDisconnect() }
                )
            }
        }
    }
}
