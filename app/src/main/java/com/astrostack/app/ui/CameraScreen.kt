package com.astrostack.app.ui

import android.Manifest
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrostack.app.camera.CameraCapabilities
import com.astrostack.app.camera.CaptureSessionState
import com.astrostack.app.camera.EXPOSURE_PRESETS
import com.astrostack.app.camera.EXPOSURE_TIME_STEPS
import com.astrostack.app.camera.ExposurePreset
import com.astrostack.app.camera.ISO_STEPS
import com.astrostack.app.camera.PreviewState
import com.astrostack.app.viewmodel.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToStacking: (Long) -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    // When capture finishes, navigate to stacking screen
    LaunchedEffect(uiState.sessionState) {
        val state = uiState.sessionState
        if (state is CaptureSessionState.Done) {
            onNavigateToStacking(state.sessionId)
            viewModel.resetSessionState()
        }
    }

    if (!cameraPermission.status.isGranted) {
        PermissionRequestScreen(
            shouldShowRationale = cameraPermission.status.shouldShowRationale,
            onRequestPermission = { cameraPermission.launchPermissionRequest() },
        )
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        // ── Live viewfinder ──────────────────────────────────────────────────
        CameraPreview(
            modifier = Modifier.fillMaxSize(),
            // Sensor landscape aspect ratio (e.g. 4080/3072 = 4/3).
            // Capabilities arrive asynchronously; default to 4:3 until known.
            sensorAspectRatio = uiState.capabilities?.let {
                it.rawSensorWidth.toFloat() / it.rawSensorHeight.toFloat()
            } ?: (4f / 3f),
            onSurfaceReady = { surface -> viewModel.openCamera(surface) },
        )

        // ── Top bar ──────────────────────────────────────────────────────────
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .statusBarsPadding(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "AstroStack",
                color = MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            IconButton(onClick = onNavigateToGallery) {
                Icon(Icons.Filled.Photo, contentDescription = "Gallery", tint = MaterialTheme.colorScheme.primary)
            }
        }

        // ── Bottom controls ──────────────────────────────────────────────────
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.6f))
                .navigationBarsPadding()
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // RAW support warning
            if (!uiState.rawSupported) {
                Text(
                    text = "⚠ Camera does not support RAW capture",
                    color = MaterialTheme.colorScheme.error,
                    fontSize = 12.sp,
                )
            }

            // Exposure preset selector
            ManualControlsPanel(
                exposureTimeNs = uiState.exposureTimeNs,
                iso = uiState.iso,
                capabilities = uiState.capabilities,
                onExposureChanged = viewModel::setExposureTimeNs,
                onIsoChanged = viewModel::setIso,
                onPresetSelected = viewModel::setExposurePreset,
            )

            // Frame count
            FrameCountRow(
                frameCount = uiState.frameCount,
                onDecrement = { viewModel.setFrameCount(uiState.frameCount - 1) },
                onIncrement = { viewModel.setFrameCount(uiState.frameCount + 1) },
            )

            Spacer(modifier = Modifier.height(4.dp))

            // Capture / cancel button
            when (val state = uiState.sessionState) {
                is CaptureSessionState.Idle ->
                    CaptureButton(onClick = viewModel::startCapture)

                is CaptureSessionState.Capturing -> {
                    CaptureProgressIndicator(
                        current = state.framesCompleted,
                        total = state.framesTotal,
                        onCancel = viewModel::cancelCapture,
                    )
                }

                is CaptureSessionState.Error -> {
                    Text(
                        text = "Error: ${state.message}",
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 12.sp,
                    )
                    TextButton(onClick = viewModel::resetSessionState) {
                        Text("Dismiss", color = MaterialTheme.colorScheme.primary)
                    }
                }

                else -> Unit
            }
        }
    }
}

@Composable
private fun CameraPreview(
    modifier: Modifier,
    sensorAspectRatio: Float,   // landscape W/H of the RAW sensor, e.g. 4080/3072 = 1.333
    onSurfaceReady: (android.view.Surface) -> Unit,
) {
    // In portrait mode the camera image is rotated 90°, so the effective
    // portrait aspect ratio is  height / width  =  1 / sensorAspectRatio  (~0.75 for 4:3).
    // A tall phone screen (e.g. 9:20) is narrower than the camera's portrait frame,
    // so we fill the screen HEIGHT and let the sides be centre-cropped.
    BoxWithConstraints(modifier.clipToBounds()) {
        val portraitAspect = 1f / sensorAspectRatio          // ~0.75 for 4:3 sensor
        val screenAspect   = maxWidth.value / maxHeight.value // ~0.45 for a typical phone

        val viewWidth: Dp
        val viewHeight: Dp
        if (portraitAspect > screenAspect) {
            // Camera portrait frame is wider than the screen → fill screen height,
            // overflow left/right (clipped by parent).
            viewHeight = maxHeight
            viewWidth  = maxHeight * portraitAspect
        } else {
            // Camera portrait frame is taller than the screen → fill screen width,
            // overflow top/bottom (clipped by parent).
            viewWidth  = maxWidth
            viewHeight = maxWidth / portraitAspect
        }

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceReady(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {}
                    })
                }
            },
            modifier = Modifier
                .size(viewWidth, viewHeight)
                .align(Alignment.Center),
        )
    }
}

@Composable
private fun ManualControlsPanel(
    exposureTimeNs: Long,
    iso: Int,
    capabilities: CameraCapabilities?,
    onExposureChanged: (Long) -> Unit,
    onIsoChanged: (Int) -> Unit,
    onPresetSelected: (ExposurePreset) -> Unit,
) {
    // Filter steps to device hardware limits (or use full list if capabilities not yet available)
    val exposureSteps = remember(capabilities) {
        val min = capabilities?.minExposureNs ?: Long.MIN_VALUE
        val max = capabilities?.maxExposureNs ?: Long.MAX_VALUE
        EXPOSURE_TIME_STEPS.filter { (ns, _) -> ns in min..max }
            .ifEmpty { EXPOSURE_TIME_STEPS }
    }
    val isoSteps = remember(capabilities) {
        val min = capabilities?.minIso ?: 0
        val max = capabilities?.maxIso ?: Int.MAX_VALUE
        ISO_STEPS.filter { it in min..max }.ifEmpty { ISO_STEPS }
    }

    // Current slider indices — nearest step to the current value
    val exposureIdx = remember(exposureTimeNs, exposureSteps) {
        exposureSteps.indices.minByOrNull { abs(exposureSteps[it].first - exposureTimeNs) } ?: 0
    }
    val isoIdx = remember(iso, isoSteps) {
        isoSteps.indices.minByOrNull { abs(isoSteps[it] - iso) } ?: 0
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // ── Quick-fill preset chips (horizontal scroll) ───────────────────
        Text("Presets", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EXPOSURE_PRESETS.forEach { preset ->
                val active = exposureTimeNs == preset.exposureTimeNs && iso == preset.iso
                FilterChip(
                    selected = active,
                    onClick = { onPresetSelected(preset) },
                    label = { Text(preset.label, fontSize = 10.sp) },
                )
            }
        }

        // ── Exposure time slider ──────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Shutter", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.width(56.dp))
            Text(
                text = exposureSteps[exposureIdx].second,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.width(64.dp),
            )
        }
        if (exposureSteps.size > 1) {
            Slider(
                value = exposureIdx.toFloat(),
                onValueChange = { onExposureChanged(exposureSteps[it.roundToInt()].first) },
                valueRange = 0f..(exposureSteps.size - 1).toFloat(),
                steps = exposureSteps.size - 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // ── ISO slider ────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("ISO", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp,
                modifier = Modifier.width(56.dp))
            Text(
                text = isoSteps[isoIdx].toString(),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                modifier = Modifier.width(64.dp),
            )
        }
        if (isoSteps.size > 1) {
            Slider(
                value = isoIdx.toFloat(),
                onValueChange = { onIsoChanged(isoSteps[it.roundToInt()]) },
                valueRange = 0f..(isoSteps.size - 1).toFloat(),
                steps = isoSteps.size - 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
private fun FrameCountRow(
    frameCount: Int,
    onDecrement: () -> Unit,
    onIncrement: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Frames:", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        IconButton(onClick = onDecrement, modifier = Modifier.size(32.dp)) {
            Text("−", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
        }
        Text(
            text = frameCount.toString(),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 18.sp,
            modifier = Modifier.defaultMinSize(minWidth = 36.dp),
        )
        IconButton(onClick = onIncrement, modifier = Modifier.size(32.dp)) {
            Text("+", color = MaterialTheme.colorScheme.primary, fontSize = 20.sp)
        }
    }
}

@Composable
private fun CaptureButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
    ) {
        Icon(Icons.Filled.CameraAlt, contentDescription = "Start capture", modifier = Modifier.size(36.dp))
    }
}

@Composable
private fun CaptureProgressIndicator(current: Int, total: Int, onCancel: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Capturing frame ${current + 1} / $total",
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
        LinearProgressIndicator(
            progress = { current.toFloat() / total },
            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
            color = MaterialTheme.colorScheme.primary,
        )
        TextButton(onClick = onCancel) {
            Icon(Icons.Filled.Close, contentDescription = null, tint = MaterialTheme.colorScheme.error)
            Spacer(Modifier.width(4.dp))
            Text("Cancel", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun PermissionRequestScreen(shouldShowRationale: Boolean, onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(72.dp))
        Spacer(Modifier.height(24.dp))
        Text("Camera Permission Required", color = MaterialTheme.colorScheme.onBackground, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(
            if (shouldShowRationale) "AstroStack needs camera access to capture RAW astrophotography frames."
            else "Please grant camera permission to use AstroStack.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(32.dp))
        Button(onClick = onRequestPermission) { Text("Grant Permission") }
    }
}
