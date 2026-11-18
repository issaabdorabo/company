package com.example.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashlightOff
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.util.QrPairingHelper
import com.example.util.QrPairingPayload
import java.util.concurrent.Executors

@Composable
fun QrScannerDialog(
    onQrScanned: (QrPairingPayload) -> Unit,
    onInvalidQrDetected: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        BackHandler { onDismiss() }

        Surface(
            modifier = Modifier
                .fillMaxSize()
                .testTag("qr_scanner_dialog"),
            color = Color.Black
        ) {
            if (!hasCameraPermission) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.surface)
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        Surface(
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            modifier = Modifier.size(64.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.CameraAlt,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }
                        Text(
                            text = "إذن الكاميرا مطلوب لمسح باركود الربط",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                        Text(
                            text = "تُستخدم الكاميرا فقط لقراءة باركود عنوان الجهاز الرئيسي ورمز القاعدة وكلمة المرور بشكل مباشر.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                                modifier = Modifier.testTag("grant_camera_permission_btn")
                            ) {
                                Text("منح صلاحية الكاميرا")
                            }
                            OutlinedButton(onClick = onDismiss) {
                                Text("إغلاق")
                            }
                        }
                    }
                }
            } else {
                CameraQrPreviewContent(
                    onQrScanned = onQrScanned,
                    onInvalidQrDetected = onInvalidQrDetected,
                    onDismiss = onDismiss
                )
            }
        }
    }
}

@Composable
private fun CameraQrPreviewContent(
    onQrScanned: (QrPairingPayload) -> Unit,
    onInvalidQrDetected: () -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    var boundCameraProvider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var boundCamera by remember { mutableStateOf<Camera?>(null) }
    var torchEnabled by remember { mutableStateOf(false) }
    var hasDetected by remember { mutableStateOf(false) }
    var lastInvalidWarnAt by remember { mutableStateOf(0L) }

    val infiniteTransition = rememberInfiniteTransition(label = "qr_laser")
    val laserProgress by infiniteTransition.animateFloat(
        initialValue = 0.08f,
        targetValue = 0.92f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_y"
    )

    DisposableEffect(Unit) {
        onDispose {
            runCatching { boundCameraProvider?.unbindAll() }
            analysisExecutor.shutdownNow()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(
            factory = { ctx ->
                val previewView = PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                }
                val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                cameraProviderFuture.addListener({
                    val cameraProvider = cameraProviderFuture.get()
                    boundCameraProvider = cameraProvider
                    val preview = Preview.Builder().build().apply {
                        setSurfaceProvider(previewView.surfaceProvider)
                    }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()
                        .apply {
                            setAnalyzer(analysisExecutor) { imageProxy ->
                                val rawText = QrPairingHelper.decodeQrFromImageProxy(imageProxy)
                                if (!rawText.isNullOrBlank() && !hasDetected) {
                                    val parsed = QrPairingHelper.parsePairingQr(rawText)
                                    if (parsed != null) {
                                        hasDetected = true
                                        previewView.post { onQrScanned(parsed) }
                                    } else {
                                        val now = System.currentTimeMillis()
                                        if (now - lastInvalidWarnAt > 2500L) {
                                            lastInvalidWarnAt = now
                                            previewView.post { onInvalidQrDetected() }
                                        }
                                    }
                                }
                            }
                        }

                    runCatching {
                        cameraProvider.unbindAll()
                        boundCamera = cameraProvider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            analysis
                        )
                    }
                }, ContextCompat.getMainExecutor(ctx))
                previewView
            },
            modifier = Modifier.fillMaxSize()
        )

        // Reticle & Laser Overlay
        Canvas(modifier = Modifier.fillMaxSize()) {
            val boxSide = size.minDimension * 0.66f
            val left = (size.width - boxSide) / 2f
            val top = (size.height - boxSide) / 2f

            // Dimmed surrounding scrim
            drawRect(color = Color.Black.copy(alpha = 0.48f))
            // Cutout clear area feel by framing bright border
            drawRoundRect(
                color = Color.White.copy(alpha = 0.12f),
                topLeft = Offset(left, top),
                size = Size(boxSide, boxSide),
                cornerRadius = CornerRadius(24f, 24f)
            )
            drawRoundRect(
                color = Color(0xFF38BDF8),
                topLeft = Offset(left, top),
                size = Size(boxSide, boxSide),
                cornerRadius = CornerRadius(24f, 24f),
                style = Stroke(width = 5f)
            )

            // Animated scan beam
            val beamY = top + boxSide * laserProgress
            drawLine(
                color = Color(0xFF22C55E),
                start = Offset(left + 18f, beamY),
                end = Offset(left + boxSide - 18f, beamY),
                strokeWidth = 6f,
                cap = StrokeCap.Round
            )
        }

        // Top Bar Controls
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.testTag("close_qr_scanner_btn")
                ) {
                    Icon(Icons.Default.Close, contentDescription = "إغلاق الماسح", tint = Color.White)
                }
            }

            Surface(
                shape = RoundedCornerShape(20.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = Color(0xFF38BDF8),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "مسح باركود الربط السريع",
                        color = Color.White,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Surface(
                shape = CircleShape,
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                IconButton(
                    onClick = {
                        val next = !torchEnabled
                        torchEnabled = next
                        boundCamera?.cameraControl?.enableTorch(next)
                    },
                    modifier = Modifier.testTag("toggle_qr_torch_btn")
                ) {
                    Icon(
                        imageVector = if (torchEnabled) Icons.Default.FlashlightOn else Icons.Default.FlashlightOff,
                        contentDescription = "تشغيل الفلاش",
                        tint = if (torchEnabled) Color(0xFFFBBF24) else Color.White
                    )
                }
            }
        }

        // Bottom Hint
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = Color.Black.copy(alpha = 0.7f),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(24.dp)
        ) {
            Text(
                text = "وجّه الكاميرا نحو باركود الربط الظاهر في شاشة الجهاز الرئيسي ليتم تعبئة العنوان والرمز وكلمة المرور والربط تلقائياً",
                color = Color.White,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
            )
        }
    }
}
