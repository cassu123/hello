package org.hello.reader.feature.onboarding.qr

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Log
import org.hello.reader.core.util.findActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import org.hello.reader.ui.theme.Radius
import org.hello.reader.ui.theme.Spacing
import java.util.concurrent.Executors
import androidx.compose.ui.res.stringResource
import org.hello.reader.R
import org.hello.reader.feature.onboarding.text

private const val TAG = "QrScanScreen"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrScanScreen(
    onConnected: () -> Unit,
    onBack: () -> Unit,
    viewModel: QrScanViewModel = viewModel(factory = QrScanViewModel.Factory),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is QrScanState.Connected) onConnected()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.scan_connect_code)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            CameraPermissionGate {
                ScannerViewport(
                    scanning = state is QrScanState.Scanning,
                    onPayload = viewModel::onPayload,
                    onInvalidCode = viewModel::onInvalidCode,
                )
            }
            ScanStatusPanel(
                state = state,
                onScanAgain = viewModel::scanAgain,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun CameraPermissionGate(content: @Composable () -> Unit) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasCameraPermission()) }
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        requestedOnce = true
    }

    // Re-check after returning from system settings.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        granted = context.hasCameraPermission()
    }
    LaunchedEffect(Unit) {
        if (!granted && !requestedOnce) launcher.launch(Manifest.permission.CAMERA)
    }

    if (granted) {
        content()
        return
    }

    // After a denial, Android stops showing the dialog once the user picks
    // "Don't ask again"; at that point only system settings can grant it.
    val permanentlyDenied = requestedOnce &&
        context.findActivity()?.shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) == false

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.L, vertical = Spacing.XL),
        verticalArrangement = Arrangement.spacedBy(Spacing.M, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(stringResource(R.string.camera_needed_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Text(
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            text = stringResource(R.string.camera_needed_body),
            textAlign = TextAlign.Center,
        )
        if (permanentlyDenied) {
            Button(onClick = { context.openAppSettings() }) { Text(stringResource(R.string.open_settings)) }
        } else {
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text(stringResource(R.string.allow_camera)) }
        }
    }
}

@Composable
private fun ScannerViewport(
    scanning: Boolean,
    onPayload: (QrConnectPayload) -> Unit,
    onInvalidCode: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnPayload by rememberUpdatedState(onPayload)
    val currentOnInvalid by rememberUpdatedState(onInvalidCode)
    var cameraError by remember { mutableStateOf<String?>(null) }
    val cameraFailed = stringResource(R.string.camera_failed)

    val previewView = remember {
        PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
    }
    val analyzer = remember {
        QrCodeAnalyzer(
            onPayload = { currentOnPayload(it) },
            onInvalidCode = { currentOnInvalid() },
        )
    }

    // When the view model returns to Scanning after a failed connect, accept new codes again.
    LaunchedEffect(scanning) {
        if (scanning) analyzer.reset()
    }

    DisposableEffect(lifecycleOwner) {
        val executor = Executors.newSingleThreadExecutor()
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener({
            try {
                val cameraProvider = providerFuture.get().also { provider = it }
                val preview = Preview.Builder().build().also {
                    it.surfaceProvider = previewView.surfaceProvider
                }
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                    .also { it.setAnalyzer(executor, analyzer) }
                val selector = if (cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    CameraSelector.DEFAULT_BACK_CAMERA
                } else {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                }
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
            } catch (e: Exception) {
                // ExecutionException, IllegalStateException, IllegalArgumentException, CameraInfoUnavailableException
                Log.e(TAG, "Unable to start camera", e)
                cameraError = cameraFailed
            }
        }, ContextCompat.getMainExecutor(context))

        onDispose {
            provider?.unbindAll()
            executor.shutdown()
            analyzer.close()
        }
    }

    Box(Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        ScanOverlay(animate = scanning, modifier = Modifier.fillMaxSize())
        cameraError?.let {
            Text(
                it,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(16.dp),
            )
        }
    }
}

/** Dims everything outside a square viewfinder and sweeps a scan line through it. */
@Composable
private fun ScanOverlay(animate: Boolean, modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val transition = rememberInfiniteTransition(label = "scan-line")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Reverse),
        label = "scan-line-progress",
    )

    Canvas(modifier.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }) {
        val side = size.minDimension * 0.7f
        val topLeft = Offset((size.width - side) / 2f, (size.height - side) / 2.4f)
        val frame = Size(side, side)
        val radius = CornerRadius(24.dp.toPx())

        drawRect(Color.Black.copy(alpha = 0.55f))
        drawRoundRect(Color.Transparent, topLeft, frame, radius, blendMode = BlendMode.Clear)
        drawRoundRect(accent, topLeft, frame, radius, style = Stroke(width = 3.dp.toPx()))

        if (animate) {
            val inset = 16.dp.toPx()
            val y = topLeft.y + inset + (side - inset * 2) * progress
            drawLine(
                color = accent,
                start = Offset(topLeft.x + inset, y),
                end = Offset(topLeft.x + side - inset, y),
                strokeWidth = 3.dp.toPx(),
            )
        }
    }
}

@Composable
private fun ScanStatusPanel(state: QrScanState, onScanAgain: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = Spacing.M, vertical = Spacing.M),
        shape = RoundedCornerShape(Radius.Card),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier.padding(Spacing.Gutter),
            verticalArrangement = Arrangement.spacedBy(Spacing.S),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            when (state) {
                is QrScanState.Scanning -> {
                    Text(stringResource(R.string.scan_instructions), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                    if (state.rejectedCode) Text(stringResource(R.string.qr_not_connect_code), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                }
                is QrScanState.Connecting -> {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.connecting_to, state.baseUrl), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
                }
                is QrScanState.Failed -> {
                    Text(state.error.text(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    OutlinedButton(onClick = onScanAgain) { Text(stringResource(R.string.scan_again)) }
                }
                QrScanState.Connected -> Text(stringResource(R.string.connected), style = MaterialTheme.typography.titleMedium)
            }
        }
    }
}

private fun Context.hasCameraPermission(): Boolean =
    ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

private fun Context.openAppSettings() {
    startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}
