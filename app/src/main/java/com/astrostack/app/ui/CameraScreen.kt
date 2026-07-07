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
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext

import com.astrostack.app.stacking.DriftHandling
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
import com.astrostack.app.camera.calculateTripodExposureLimits
import com.astrostack.app.viewmodel.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlin.math.abs
import kotlin.math.roundToInt

// ... (skipping some unchanged lines, we will modify the target content precisely)


@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToStacking: (Long) -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var isFullScreenView by remember { mutableStateOf(false) }

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

        // ── Live Stack Preview Overlay ────────────────────────────────────────
        uiState.liveStackedBitmap?.let { bitmap ->
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Live Stack Preview",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
            // Add a badge indicating "LIVE STACKED VIEW"
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .background(Color.Red.copy(alpha = 0.8f), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "LIVE STACKING ACTIVE",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }


        // ── Top bar ──────────────────────────────────────────────────────────
        if (!isFullScreenView) {
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
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { isFullScreenView = true }) {
                        Icon(
                            imageVector = Icons.Filled.Fullscreen,
                            contentDescription = "Fullscreen",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onNavigateToGallery) {
                        Icon(
                            Icons.Filled.Photo,
                            contentDescription = "Gallery",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }

        // ── Bottom controls ──────────────────────────────────────────────────
        if (!isFullScreenView) {
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
                    autoFocusEnabled = uiState.autoFocusEnabled,
                    capabilities = uiState.capabilities,
                    onExposureChanged = viewModel::setExposureTimeNs,
                    onIsoChanged = viewModel::setIso,
                    onPresetSelected = viewModel::setExposurePreset,
                    onAutoFocusChanged = viewModel::setAutoFocus,
                )

                // Toggles
                if (uiState.sessionState is CaptureSessionState.Idle) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Save All RAW Frames", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Conserves space if disabled", color = Color.Gray, fontSize = 10.sp)
                        }
                        Switch(checked = uiState.saveAllPhotos, onCheckedChange = viewModel::setSaveAllPhotos)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Auto Stack Photos", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                            Text("Aligns and stacks on capture", color = Color.Gray, fontSize = 10.sp)
                        }
                        Switch(checked = uiState.stackPhotos, onCheckedChange = viewModel::setStackPhotos)
                    }

                    if (uiState.stackPhotos) {
                        var showStackingSettings by remember { mutableStateOf(false) }

                        TextButton(
                            onClick = { showStackingSettings = !showStackingSettings },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = if (showStackingSettings) "Hide Stacking Settings ⚙" else "Show Stacking Settings ⚙",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 12.sp
                            )
                        }

                        if (showStackingSettings) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                                    .background(Color.White.copy(alpha = 0.05f), shape = RoundedCornerShape(8.dp))
                                    .padding(12.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                // Drift Handling Segmented Control
                                Column {
                                    Text("Drift Handling", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                    Row(
                                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        listOf(DriftHandling.NONE, DriftHandling.CROP, DriftHandling.MOSAIC).forEach { mode ->
                                            val selected = uiState.driftHandling == mode
                                            FilterChip(
                                                selected = selected,
                                                onClick = { viewModel.setDriftHandling(mode) },
                                                label = { Text(mode.name, fontSize = 11.sp) },
                                                colors = FilterChipDefaults.filterChipColors(
                                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                                    selectedLabelColor = MaterialTheme.colorScheme.primary,
                                                    labelColor = Color.LightGray
                                                )
                                            )
                                        }
                                    }
                                }

                                // Min Stars selector
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text("Min Stars for Stacking", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text("Discard frame if fewer stars found", color = Color.DarkGray, fontSize = 9.sp)
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        IconButton(
                                            onClick = { viewModel.setMinStarCount(uiState.minStarCount - 1) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Text("−", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                                        }
                                        Text("${uiState.minStarCount}", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                        IconButton(
                                            onClick = { viewModel.setMinStarCount(uiState.minStarCount + 1) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Text("+", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
                                        }
                                    }
                                }

                                // Star detection Sensitivity
                                Column {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text("Star Sensitivity Threshold", color = Color.Gray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Text(
                                            text = "${uiState.starThreshold} (lower = more sensitive)",
                                            color = MaterialTheme.colorScheme.primary,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                    Slider(
                                        value = uiState.starThreshold.toFloat(),
                                        onValueChange = { viewModel.setStarThreshold(it.roundToInt()) },
                                        valueRange = 20f..255f,
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Capture / cancel / stop controls
                when (val state = uiState.sessionState) {
                    is CaptureSessionState.Idle ->
                        CaptureButton(onClick = viewModel::startCapture)

                    is CaptureSessionState.Capturing -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // Statistics
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("CAPTURED", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("${state.framesCaptured}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("STACKED", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("${state.framesStacked}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text("REJECTED", color = MaterialTheme.colorScheme.error, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                    Text("${state.framesRejected}", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                                }
                            }

                            // Warnings
                            if (state.framesRejected > 0 && state.framesCaptured > 3 && (state.framesRejected.toFloat() / state.framesCaptured.toFloat()) > 0.4f) {
                                Text(
                                    text = "⚠ High rejection rate: Check focus or tripod stability",
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 11.sp
                                )
                            }

                            Spacer(Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                                // Stop & Save button
                                Button(
                                    onClick = viewModel::stopCapture,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                                ) {
                                    Text("Stop & Save", fontWeight = FontWeight.Bold)
                                }
                                // Cancel button (stop without saving)
                                OutlinedButton(
                                    onClick = viewModel::cancelCapture,
                                    modifier = Modifier.weight(1f).height(48.dp)
                                ) {
                                    Text("Cancel", color = Color.White)
                                }
                            }
                        }
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

        if (isFullScreenView) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .statusBarsPadding(),
                contentAlignment = Alignment.TopEnd
            ) {
                IconButton(
                    onClick = { isFullScreenView = false },
                    modifier = Modifier.background(Color.Black.copy(alpha = 0.5f), shape = CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Filled.FullscreenExit,
                        contentDescription = "Exit Fullscreen",
                        tint = Color.White
                    )
                }
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
                .requiredSize(viewWidth, viewHeight)
                .align(Alignment.Center),
        )
    }
}

@Composable
private fun ManualControlsPanel(
    exposureTimeNs: Long,
    iso: Int,
    autoFocusEnabled: Boolean,
    capabilities: CameraCapabilities?,
    onExposureChanged: (Long) -> Unit,
    onIsoChanged: (Int) -> Unit,
    onPresetSelected: (ExposurePreset) -> Unit,
    onAutoFocusChanged: (Boolean) -> Unit,
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
        // ── Focus Control Row ─────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Focus Mode",
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = !autoFocusEnabled,
                    onClick = { onAutoFocusChanged(false) },
                    label = { Text("Infinity Focus 🌌", fontSize = 11.sp) }
                )
                FilterChip(
                    selected = autoFocusEnabled,
                    onClick = { onAutoFocusChanged(true) },
                    label = { Text("Auto Focus 🔍", fontSize = 11.sp) }
                )
            }
        }

        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

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

        // ── Tripod shutter limit chips ────────────────────────────────────
        val limits = remember(capabilities) {
            calculateTripodExposureLimits(capabilities?.characteristics)
        }
        if (limits != null) {
            val (rule500, npfRule) = limits
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Tripod Limit:",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                    modifier = Modifier.weight(1f)
                )
                SuggestionChip(
                    onClick = {
                        val targetNs = (rule500 * 1_000_000_000L).toLong()
                        val closest = exposureSteps.minByOrNull { abs(it.first - targetNs) }
                        if (closest != null) onExposureChanged(closest.first)
                    },
                    label = { Text("500 Rule: ${"%.1fs".format(rule500)}", fontSize = 10.sp) }
                )
                SuggestionChip(
                    onClick = {
                        val targetNs = (npfRule * 1_000_000_000L).toLong()
                        val closest = exposureSteps.minByOrNull { abs(it.first - targetNs) }
                        if (closest != null) onExposureChanged(closest.first)
                    },
                    label = { Text("NPF: ${"%.1fs".format(npfRule)}", fontSize = 10.sp) }
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
