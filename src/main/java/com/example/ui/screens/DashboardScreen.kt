package com.example.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ReceiptLong
import androidx.compose.material.icons.automirrored.filled.ShowChart
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AttachMoney
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.dao.VoucherWithItems
import com.example.data.model.OperationAuditLog
import com.example.data.cloud.LinkedSubDevice
import com.example.data.model.VoucherType
import com.example.data.model.WarehouseStockSummary
import com.example.data.repository.StockSnapshot
import com.example.ui.theme.DangerRed
import com.example.ui.theme.InfoBlue
import com.example.ui.theme.SuccessGreen
import com.example.ui.theme.WarningOrange
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    snapshot: StockSnapshot?,
    operationAuditLogs: List<OperationAuditLog> = emptyList(),
    linkedSubDevices: List<LinkedSubDevice> = emptyList(),
    isMainDevice: Boolean = true,
    currentDeviceName: String = "",
    onNavigateToTransactions: () -> Unit,
    onNavigateToInward: () -> Unit,
    onNavigateToWarehouses: () -> Unit,
    onNavigateToItems: () -> Unit
) {
    if (snapshot == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text("جاري تحميل بيانات المخازن...", style = MaterialTheme.typography.bodyLarge)
        }
        return
    }

    var selectedWarehouseFilterId by remember(snapshot.warehouses) {
        mutableLongStateOf(snapshot.warehouses.firstOrNull()?.id ?: 0L)
    }

    val entryAnim = remember { Animatable(0f) }
    LaunchedEffect(
        snapshot.items.size,
        snapshot.vouchers.size,
        snapshot.totalInventoryQuantity
    ) {
        entryAnim.snapTo(0f)
        entryAnim.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 950, easing = FastOutSlowInEasing)
        )
    }
    val animProgress = entryAnim.value

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .testTag("dashboard_screen_scroll"),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 1. Animated Hero Executive Overview Banner
        item {
            ExecutivePortfolioHero(
                snapshot = snapshot,
                animProgress = animProgress
            )
        }

        // 2. KPI Top Summary Grid
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KpiCard(
                    title = "إجمالي الأصناف",
                    value = "${(snapshot.items.size * animProgress).toInt()} صنف",
                    icon = Icons.Default.Category,
                    tint = MaterialTheme.colorScheme.primary,
                    onClick = onNavigateToItems,
                    modifier = Modifier.weight(1f)
                )
                KpiCard(
                    title = "الكمية المتاحة",
                    value = "%.0f وحدة".format(snapshot.totalInventoryQuantity * animProgress),
                    icon = Icons.Default.Inventory2,
                    tint = SuccessGreen,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                KpiCard(
                    title = "المخازن النشطة",
                    value = "${snapshot.warehouses.size} مخازن",
                    icon = Icons.Default.Warehouse,
                    tint = MaterialTheme.colorScheme.secondary,
                    onClick = onNavigateToWarehouses,
                    modifier = Modifier.weight(1f)
                )
                KpiCard(
                    title = "إجمالي السندات",
                    value = "${(snapshot.vouchers.size * animProgress).toInt()} سند",
                    icon = Icons.AutoMirrored.Filled.ReceiptLong,
                    tint = InfoBlue,
                    onClick = onNavigateToTransactions,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 3. Animated Voucher Movement Donut Chart (تقرير رسومي متحرك لحركة السندات)
        item {
            AnimatedVoucherMovementDonutCard(
                snapshot = snapshot,
                animProgress = animProgress,
                onNavigateToTransactions = onNavigateToTransactions
            )
        }

        // 4. Animated Stock Health Multi-Segment Chart & Alert Card
        item {
            AnimatedStockHealthCard(
                snapshot = snapshot,
                animProgress = animProgress,
                onNavigateToInward = onNavigateToInward
            )
        }

        // Daily activity follows the stock-health indicator for faster access.
        item {
            DailyUserActivityCard(
                logs = operationAuditLogs,
                linkedDevices = linkedSubDevices,
                isMainDevice = isMainDevice,
                currentDeviceName = currentDeviceName
            )
        }

        // 5. Animated Warehouse Comparison Capsule Bar Chart (رسم بياني متحرك للمخازن)
        item {
            AnimatedWarehouseBarChartCard(
                snapshot = snapshot,
                animProgress = animProgress,
                selectedWarehouseId = selectedWarehouseFilterId,
                onSelectWarehouse = { selectedWarehouseFilterId = it },
                onNavigateToWarehouses = onNavigateToWarehouses
            )
        }

        // 6. Inventory Distribution Inside Selected Warehouse by Items (animated bars)
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "تفاصيل أصناف المخزن المحدد",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))

                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(snapshot.warehouses) { w ->
                            FilterChip(
                                selected = selectedWarehouseFilterId == w.id,
                                onClick = { selectedWarehouseFilterId = w.id },
                                label = { Text(w.name) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val itemsInWarehouse = snapshot.warehouseItemStocks
                        .filter { it.warehouseId == selectedWarehouseFilterId && it.currentStock > 0.001 }

                    if (itemsInWarehouse.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(20.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "لا يوجد بضائع أو رصيد مسجل في هذا المخزن حالياً",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    } else {
                        val maxQty = itemsInWarehouse.maxOfOrNull { it.currentStock } ?: 1.0
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            itemsInWarehouse.forEach { stockItem ->
                                val targetProgress = (stockItem.currentStock / maxQty).toFloat().coerceIn(0f, 1f)
                                val animatedRatio by animateFloatAsState(
                                    targetValue = targetProgress * animProgress,
                                    animationSpec = tween(durationMillis = 700),
                                    label = "wh_item_ratio"
                                )
                                val barTint = when {
                                    stockItem.isOutOfStock -> DangerRed
                                    stockItem.isLowStock -> WarningOrange
                                    else -> MaterialTheme.colorScheme.secondary
                                }

                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
                                            RoundedCornerShape(10.dp)
                                        )
                                        .padding(10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = stockItem.itemName,
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.weight(1f),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Text(
                                            text = "${stockItem.currentStock.toInt()} ${stockItem.itemUnit}",
                                            style = MaterialTheme.typography.bodyMedium,
                                            fontWeight = FontWeight.Bold,
                                            color = barTint
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(8.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(MaterialTheme.colorScheme.surface)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .fillMaxWidth(animatedRatio)
                                                .fillMaxHeight()
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(
                                                    Brush.horizontalGradient(
                                                        listOf(barTint.copy(alpha = 0.7f), barTint)
                                                    )
                                                )
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // 7. Recent Operations Feed
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "آخر الحركات والعمليات",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "عرض الكل",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { onNavigateToTransactions() }
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (snapshot.vouchers.isEmpty()) {
                        Text(
                            text = "لا توجد عمليات مسجلة بعد",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        val dateFormat = SimpleDateFormat("yyyy/MM/dd", Locale.getDefault())
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            snapshot.vouchers.take(5).forEach { vWithItems ->
                                RecentOperationItem(vWithItems, dateFormat)
                            }
                        }
                    }
                }
            }
        }

    }
}

@Composable
private fun DailyUserActivityCard(
    logs: List<OperationAuditLog>,
    linkedDevices: List<LinkedSubDevice>,
    isMainDevice: Boolean,
    currentDeviceName: String
) {
    val visibleLogs = if (isMainDevice) logs else logs.filter { it.deviceName == currentDeviceName }
    val now = System.currentTimeMillis()
    val online = if (isMainDevice) linkedDevices.filter { it.isClaimed && !it.isKicked && now - it.lastSeenAt < 15_000L } else emptyList()
    val dayFormat = remember { SimpleDateFormat("MM/dd", Locale.getDefault()) }
    val days = remember(visibleLogs) {
        val calendar = java.util.Calendar.getInstance()
        calendar.set(java.util.Calendar.HOUR_OF_DAY, 0)
        calendar.set(java.util.Calendar.MINUTE, 0)
        calendar.set(java.util.Calendar.SECOND, 0)
        calendar.set(java.util.Calendar.MILLISECOND, 0)
        (6 downTo 0).map { offset ->
            val day = calendar.clone() as java.util.Calendar
            day.add(java.util.Calendar.DAY_OF_YEAR, -offset)
            val start = day.timeInMillis
            val end = start + 86_400_000L
            val dayLogs = visibleLogs.filter { it.timestamp in start until end }
            Triple(dayFormat.format(java.util.Date(start)), dayLogs.size, dayLogs.map { it.actorName }.filter { it.isNotBlank() }.distinct().size)
        }
    }
    val maxCount = days.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column {
                    Text(if (isMainDevice) "نشاط المستخدمين اليومي" else "نشاطي اليومي", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(if (isMainDevice) "عدد العمليات والمستخدمين النشطين خلال آخر 7 أيام" else "عدد عمليات هذا الجهاز فقط خلال آخر 7 أيام", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Surface(shape = RoundedCornerShape(12.dp), color = SuccessGreen.copy(alpha = 0.12f)) {
                    Text(
                        if (isMainDevice) "متصل الآن: ${online.size}" else "عمليات اليوم: ${days.lastOrNull()?.second ?: 0}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        color = SuccessGreen, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                days.forEach { (label, count, users) ->
                    Column(modifier = Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("$count", style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                        Box(modifier = Modifier.fillMaxWidth().height(78.dp), contentAlignment = Alignment.BottomCenter) {
                            Box(modifier = Modifier.fillMaxWidth(0.72f).height((count.toFloat() / maxCount * 72f).coerceAtLeast(if (count > 0) 5f else 2f).dp).clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)).background(MaterialTheme.colorScheme.primary))
                        }
                        Text(label, style = MaterialTheme.typography.labelSmall)
                        Text("$users", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (isMainDevice) {
                Text("الأجهزة الفرعية المتصلة", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (online.isEmpty()) {
                    Text("لا توجد أجهزة فرعية متصلة حالياً", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    online.forEach { device ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(device.deviceName, style = MaterialTheme.typography.bodySmall)
                            Text("متصل", style = MaterialTheme.typography.labelSmall, color = SuccessGreen, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutivePortfolioHero(
    snapshot: StockSnapshot,
    animProgress: Float
) {
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.secondary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_hero_banner"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color.Transparent)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(
                    Brush.linearGradient(
                        colors = listOf(primary, secondary)
                    )
                )
                .padding(18.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "إجمالي القيمة التقديرية للمخزون",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "%.0f $".format(snapshot.totalInventoryValue * animProgress),
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White
                        )
                    }
                    Surface(
                        shape = CircleShape,
                        color = Color.White.copy(alpha = 0.18f),
                        modifier = Modifier.size(48.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.AttachMoney,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(26.dp)
                            )
                        }
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HeroMiniPill(
                        label = "مخازن",
                        value = "${snapshot.warehouses.size}",
                        modifier = Modifier.weight(1f)
                    )
                    HeroMiniPill(
                        label = "أصناف",
                        value = "${snapshot.items.size}",
                        modifier = Modifier.weight(1f)
                    )
                    HeroMiniPill(
                        label = "نواقص",
                        value = "${snapshot.lowStockItems.size}",
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroMiniPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color.White.copy(alpha = 0.16f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.85f)
            )
        }
    }
}

@Composable
private fun AnimatedVoucherMovementDonutCard(
    snapshot: StockSnapshot,
    animProgress: Float,
    onNavigateToTransactions: () -> Unit
) {
    val inwardCount = snapshot.vouchers.count { it.voucher.voucherType == VoucherType.INWARD }
    val transferCount = snapshot.vouchers.count { it.voucher.voucherType == VoucherType.TRANSFER }
    val disburseCount = snapshot.vouchers.count { it.voucher.voucherType == VoucherType.DISBURSEMENT }
    val totalVouchers = (inwardCount + transferCount + disburseCount).coerceAtLeast(1)

    val inwardQty = snapshot.vouchers.filter { it.voucher.voucherType == VoucherType.INWARD }
        .sumOf { v -> v.items.sumOf { it.quantity } }
    val transferQty = snapshot.vouchers.filter { it.voucher.voucherType == VoucherType.TRANSFER }
        .sumOf { v -> v.items.sumOf { it.quantity } }
    val disburseQty = snapshot.vouchers.filter { it.voucher.voucherType == VoucherType.DISBURSEMENT }
        .sumOf { v -> v.items.sumOf { it.quantity } }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_voucher_donut_chart"),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.PieChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "تحليل حركة السندات الرسومي",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "السجل",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onNavigateToTransactions() }
                )
            }

            if (snapshot.vouchers.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "ستظهر الرسوم البيانية بمجرد تسجيل أول سند توريد أو صرف",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Animated Donut Ring
                    Box(
                        modifier = Modifier.size(136.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val trackColor = MaterialTheme.colorScheme.surfaceVariant
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeWidth = 20.dp.toPx()
                            val diameter = size.minDimension - strokeWidth
                            val topLeft = Offset(
                                (size.width - diameter) / 2f,
                                (size.height - diameter) / 2f
                            )
                            val arcSize = Size(diameter, diameter)

                            drawArc(
                                color = trackColor,
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeWidth)
                            )

                            val segments = listOf(
                                inwardCount to SuccessGreen,
                                transferCount to InfoBlue,
                                disburseCount to WarningOrange
                            ).filter { it.first > 0 }

                            var startAngle = -90f
                            val totalSweep = 360f * animProgress
                            var consumedSweep = 0f
                            segments.forEach { (count, color) ->
                                val targetSegmentSweep = (count.toFloat() / totalVouchers.toFloat()) * 360f
                                val drawSweep = (totalSweep - consumedSweep).coerceIn(0f, targetSegmentSweep)
                                if (drawSweep > 1f) {
                                    drawArc(
                                        color = color,
                                        startAngle = startAngle,
                                        sweepAngle = (drawSweep - 3f).coerceAtLeast(1f),
                                        useCenter = false,
                                        topLeft = topLeft,
                                        size = arcSize,
                                        style = Stroke(width = strokeWidth, cap = StrokeCap.Round)
                                    )
                                }
                                startAngle += targetSegmentSweep
                                consumedSweep += targetSegmentSweep
                            }
                        }

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${(snapshot.vouchers.size * animProgress).toInt()}",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.ExtraBold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "إجمالي السندات",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    // Legend Rows
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        DonutLegendRow(
                            label = "سندات التوريد",
                            count = inwardCount,
                            units = inwardQty,
                            percent = (inwardCount * 100f / totalVouchers),
                            color = SuccessGreen
                        )
                        DonutLegendRow(
                            label = "تحويلات مخزنية",
                            count = transferCount,
                            units = transferQty,
                            percent = (transferCount * 100f / totalVouchers),
                            color = InfoBlue
                        )
                        DonutLegendRow(
                            label = "أوامر الصرف",
                            count = disburseCount,
                            units = disburseQty,
                            percent = (disburseCount * 100f / totalVouchers),
                            color = WarningOrange
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DonutLegendRow(
    label: String,
    count: Int,
    units: Double,
    percent: Float,
    color: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "$count سند • %.0f وحدة".format(units),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = "%.0f%%".format(percent),
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.ExtraBold,
            color = color
        )
    }
}

@Composable
private fun AnimatedStockHealthCard(
    snapshot: StockSnapshot,
    animProgress: Float,
    onNavigateToInward: () -> Unit
) {
    val outCount = snapshot.itemStockInfos.count { it.isOutOfStock }
    val lowCount = snapshot.itemStockInfos.count { it.isLowStock && !it.isOutOfStock }
    val healthyCount = (snapshot.itemStockInfos.size - outCount - lowCount).coerceAtLeast(0)
    val totalItems = snapshot.itemStockInfos.size.coerceAtLeast(1)

    val healthyRatio = healthyCount.toFloat() / totalItems
    val lowRatio = lowCount.toFloat() / totalItems
    val outRatio = outCount.toFloat() / totalItems

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("smart_stock_alert_card"),
        colors = CardDefaults.cardColors(
            containerColor = if (snapshot.lowStockItems.isNotEmpty()) {
                DangerRed.copy(alpha = 0.06f)
            } else {
                SuccessGreen.copy(alpha = 0.06f)
            }
        ),
        shape = RoundedCornerShape(18.dp),
        border = CardDefaults.outlinedCardBorder().copy(
            brush = androidx.compose.ui.graphics.SolidColor(
                if (snapshot.lowStockItems.isNotEmpty()) DangerRed.copy(alpha = 0.35f)
                else SuccessGreen.copy(alpha = 0.35f)
            )
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                    Surface(
                        shape = CircleShape,
                        color = if (snapshot.lowStockItems.isNotEmpty()) DangerRed else SuccessGreen,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = Icons.Default.NotificationsActive,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(10.dp))
                    Column {
                        Text(
                            text = "مؤشر صحة وكفاية المخزون",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = if (snapshot.lowStockItems.isNotEmpty())
                                "يوجد ${snapshot.lowStockItems.size} أصناف بحاجة إعادة طلب"
                            else
                                "كافة الأصناف متوفرة بمستويات آمنة",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (snapshot.lowStockItems.isNotEmpty()) {
                    Button(
                        onClick = onNavigateToInward,
                        colors = ButtonDefaults.buttonColors(containerColor = DangerRed),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.testTag("quick_inward_btn")
                    ) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("توريد عاجل", fontSize = 12.sp)
                    }
                }
            }

            // Multi-Segment Animated Health Bar
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(14.dp)
                    .clip(RoundedCornerShape(7.dp))
            ) {
                val totalWidth = size.width
                val h = size.height
                drawRect(color = Color.Gray.copy(alpha = 0.16f))

                var cursorX = 0f
                val wHealthy = totalWidth * healthyRatio * animProgress
                val wLow = totalWidth * lowRatio * animProgress
                val wOut = totalWidth * outRatio * animProgress

                if (wHealthy > 0f) {
                    drawRoundRect(
                        color = SuccessGreen,
                        topLeft = Offset(cursorX, 0f),
                        size = Size(wHealthy, h),
                        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                    )
                    cursorX += wHealthy
                }
                if (wLow > 0f) {
                    drawRoundRect(
                        color = WarningOrange,
                        topLeft = Offset(cursorX, 0f),
                        size = Size(wLow, h),
                        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                    )
                    cursorX += wLow
                }
                if (wOut > 0f) {
                    drawRoundRect(
                        color = DangerRed,
                        topLeft = Offset(cursorX, 0f),
                        size = Size(wOut, h),
                        cornerRadius = CornerRadius(7.dp.toPx(), 7.dp.toPx())
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                HealthStatPill("آمن: $healthyCount", SuccessGreen)
                HealthStatPill("منخفض: $lowCount", WarningOrange)
                HealthStatPill("نافد: $outCount", DangerRed)
            }

            if (snapshot.lowStockItems.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    snapshot.lowStockItems.take(3).forEach { low ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    MaterialTheme.colorScheme.surface,
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = low.item.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "الوحدة: ${low.item.unit} | حد الطلب: ${low.item.minStockLevel.toInt()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Surface(
                                color = DangerRed.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "الرصيد: ${low.currentStock.toInt()}",
                                    color = DangerRed,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HealthStatPill(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
private fun AnimatedWarehouseBarChartCard(
    snapshot: StockSnapshot,
    animProgress: Float,
    selectedWarehouseId: Long,
    onSelectWarehouse: (Long) -> Unit,
    onNavigateToWarehouses: () -> Unit
) {
    val summaries = snapshot.warehouseSummaries
    val maxQty = summaries.maxOfOrNull { it.totalQuantity }?.coerceAtLeast(1.0) ?: 1.0
    val barPalette = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary,
        InfoBlue,
        SuccessGreen,
        WarningOrange
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("dashboard_warehouse_bar_chart"),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ShowChart,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "مقارنة أرصدة وقيم المخازن رسومياً",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                Text(
                    text = "إدارة المخازن",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable { onNavigateToWarehouses() }
                )
            }

            if (summaries.isEmpty()) {
                Text(
                    text = "لا توجد مخازن مسجلة لعرض المخطط",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                // Animated Vertical Capsule Columns
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(150.dp)
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            RoundedCornerShape(14.dp)
                        )
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    summaries.take(6).forEachIndexed { idx, summary ->
                        val ratio = (summary.totalQuantity / maxQty).toFloat().coerceIn(0.06f, 1f)
                        val isSelected = summary.warehouseId == selectedWarehouseId
                        val barColor = barPalette[idx % barPalette.size]

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clickable { onSelectWarehouse(summary.warehouseId) },
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            Text(
                                text = "%.0f".format(summary.totalQuantity * animProgress),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isSelected) barColor else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .width(if (isSelected) 28.dp else 22.dp),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(ratio * animProgress)
                                        .clip(RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp, bottomStart = 4.dp, bottomEnd = 4.dp))
                                        .background(
                                            Brush.verticalGradient(
                                                listOf(barColor, barColor.copy(alpha = 0.65f))
                                            )
                                        )
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = summary.warehouseName,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) barColor else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }

                // Detailed Animated Horizontal Breakdown
                summaries.forEachIndexed { idx, summary ->
                    WarehouseRowSummary(
                        summary = summary,
                        totalInventoryQuantity = snapshot.totalInventoryQuantity,
                        animProgress = animProgress,
                        barColor = barPalette[idx % barPalette.size],
                        isSelected = summary.warehouseId == selectedWarehouseId,
                        onClick = { onSelectWarehouse(summary.warehouseId) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WarehouseRowSummary(
    summary: WarehouseStockSummary,
    totalInventoryQuantity: Double,
    animProgress: Float,
    barColor: Color,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val ratio = if (totalInventoryQuantity > 0) {
        (summary.totalQuantity / totalInventoryQuantity).toFloat().coerceIn(0f, 1f)
    } else 0f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isSelected) barColor.copy(alpha = 0.08f) else Color.Transparent
            )
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = summary.warehouseName,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "%.0f وحدة (%.1f%%)".format(summary.totalQuantity * animProgress, ratio * 100f),
                style = MaterialTheme.typography.bodySmall,
                color = barColor,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(ratio * animProgress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }
        Spacer(modifier = Modifier.height(2.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${summary.totalItemsCount} أصناف نشطة",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "القيمة: %.0f $".format(summary.totalValue * animProgress),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RecentOperationItem(
    vWithItems: VoucherWithItems,
    dateFormat: SimpleDateFormat
) {
    val v = vWithItems.voucher
    val badgeColor = when (v.voucherType) {
        VoucherType.INWARD -> SuccessGreen
        VoucherType.TRANSFER -> InfoBlue
        VoucherType.DISBURSEMENT -> WarningOrange
    }
    val icon = when (v.voucherType) {
        VoucherType.INWARD -> Icons.Default.ArrowDownward
        VoucherType.TRANSFER -> Icons.Default.SwapHoriz
        VoucherType.DISBURSEMENT -> Icons.Default.ArrowUpward
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = CircleShape,
            color = badgeColor.copy(alpha = 0.15f),
            modifier = Modifier.size(36.dp)
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = badgeColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(modifier = Modifier.width(10.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = v.voucherNumber,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = dateFormat.format(Date(v.date)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = when (v.voucherType) {
                    VoucherType.INWARD -> "توريد من: ${v.supplierName.ifEmpty { "مورد" }}"
                    VoucherType.TRANSFER -> "تحويل بين المخازن"
                    VoucherType.DISBURSEMENT -> "صرف لمستفيد: ${v.beneficiaryName.ifEmpty { "مستفيد" }}"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "عدد الأصناف: ${vWithItems.items.size} | إجمالي الكمية: ${vWithItems.items.sumOf { it.quantity }}",
                style = MaterialTheme.typography.labelSmall,
                color = badgeColor,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun KpiCard(
    title: String,
    value: String,
    icon: ImageVector,
    tint: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    Card(
        modifier = modifier.then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Surface(
                shape = CircleShape,
                color = tint.copy(alpha = 0.12f),
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = tint,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}
