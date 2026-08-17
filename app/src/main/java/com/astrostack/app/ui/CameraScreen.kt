package com.astrostack.app.ui

import android.Manifest
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Photo
import androidx.compose.material.icons.filled.Settings
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
import com.astrostack.app.camera.StretchType
import com.astrostack.app.camera.calculateTripodExposureLimits
import com.astrostack.app.stacking.DriftHandling
import com.astrostack.app.viewmodel.CameraUiState
import com.astrostack.app.viewmodel.CameraViewModel
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState
import com.google.accompanist.permissions.shouldShowRationale
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(
    onNavigateToGallery: () -> Unit,
    onNavigateToStacking: (Long) -> Unit,
    viewModel: CameraViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)
    var isFullScreenView by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }

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
            previewWidth = uiState.capabilities?.previewWidth ?: 1440,
            previewHeight = uiState.capabilities?.previewHeight ?: 1080,
            scaleMode = uiState.previewScaleMode,
            onSurfaceReady = { surface -> viewModel.openCamera(surface) },
        )

        // ── Live Stack Preview Overlay ────────────────────────────────────────
        uiState.liveStackedBitmap?.let { bitmap ->
            androidx.compose.foundation.Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Live Stack Preview",
                contentScale = if (uiState.previewScaleMode == com.astrostack.app.camera.PreviewScaleMode.FILL) ContentScale.Crop else ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 80.dp)
                    .background(Color.Red.copy(alpha = 0.85f), shape = RoundedCornerShape(8.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "LIVE STACKING ACTIVE",
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // ── Minimalist Clean UI Overlay ───────────────────────────────────────
        if (!isFullScreenView) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                // ── Sleek Top Bar ─────────────────────────────────────────────
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "AstroStack",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Theme Switcher button
                        IconButton(
                            onClick = { com.astrostack.app.ui.theme.ThemeConfig.isRedScreenMode = !com.astrostack.app.ui.theme.ThemeConfig.isRedScreenMode }
                        ) {
                            Text(
                                text = if (com.astrostack.app.ui.theme.ThemeConfig.isRedScreenMode) "🔴" else "☀️",
                                fontSize = 18.sp
                            )
                        }
                        // Quick Scout button — single stretched frame for target finding
                        Button(
                            onClick = { viewModel.startScoutingCapture() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.25f),
                                contentColor = MaterialTheme.colorScheme.tertiary
                            ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                            modifier = Modifier.height(32.dp)
                        ) {
                            Text("Scout 🔭", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
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
                        IconButton(onClick = { showSettingsSheet = true }) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = "Settings",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // ── Bottom Floating Viewfinder Controls ───────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // ── Compact Lens Selector (Floating Pill) ─────────────────
                    if (uiState.availableCameras.size > 1) {
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 3.dp),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                uiState.availableCameras.forEach { cap ->
                                    val selected = uiState.capabilities?.let { active ->
                                        (active.physicalCameraId ?: active.cameraId) == (cap.physicalCameraId ?: cap.cameraId)
                                    } ?: false
                                    val shortLabel = when {
                                        cap.userLabel.contains("Ultrawide", ignoreCase = true) -> "0.5x"
                                        cap.userLabel.contains("Telephoto", ignoreCase = true) -> "5x"
                                        cap.userLabel.contains("Wide", ignoreCase = true) -> "1x"
                                        else -> cap.userLabel
                                    }
                                    Surface(
                                        shape = CircleShape,
                                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                                        modifier = Modifier.clickable { viewModel.selectCamera(cap.physicalCameraId ?: cap.cameraId) }
                                    ) {
                                        Text(
                                            text = shortLabel,
                                            color = if (selected) Color.Black else Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ── Quick Info & Focus Strip ──────────────────────────────
                    val exposureStr = EXPOSURE_TIME_STEPS.find { it.first == uiState.exposureTimeNs }?.second
                        ?: "${"%.1f".format(uiState.exposureTimeNs / 1_000_000_000.0)}s"

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Quick Exposure Pill
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier.clickable { showSettingsSheet = true }
                        ) {
                            Text(
                                text = "⏱ $exposureStr  •  ISO ${uiState.iso}",
                                color = Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        // Quick Focus Pill
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier.clickable { viewModel.setAutoFocus(!uiState.autoFocusEnabled) }
                        ) {
                            Text(
                                text = if (uiState.autoFocusEnabled) "🔍 Auto Focus" else "🌌 Infinity Focus",
                                color = if (uiState.autoFocusEnabled) Color(0xFFFFD54F) else MaterialTheme.colorScheme.primary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }

                        // Quick Gear Button
                        Surface(
                            shape = CircleShape,
                            color = Color.Black.copy(alpha = 0.65f),
                            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                            modifier = Modifier.clickable { showSettingsSheet = true }
                        ) {
                            Box(modifier = Modifier.padding(6.dp), contentAlignment = Alignment.Center) {
                                Icon(
                                    Icons.Filled.Settings,
                                    contentDescription = "Settings",
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // ── Primary Shutter & Session Action ──────────────────────
                    when (val state = uiState.sessionState) {
                        is CaptureSessionState.Idle ->
                            CaptureButton(onClick = viewModel::startCapture)

                        is CaptureSessionState.CalibratingDark -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(12.dp)).padding(12.dp)
                            ) {
                                Text("Calibrating Master Dark", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("Keep lens covered completely!", color = Color.Gray, fontSize = 10.sp)
                                LinearProgressIndicator(
                                    progress = { state.framesCaptured.toFloat() / state.totalFrames.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                                Text("Captured ${state.framesCaptured} of ${state.totalFrames} frames", color = Color.White, fontSize = 11.sp)
                                TextButton(onClick = viewModel::resetSessionState) {
                                    Text("Cancel Calibration", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }

                        is CaptureSessionState.CalibratingFlat -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(12.dp)).padding(12.dp)
                            ) {
                                Text("Calibrating Master Flat", color = MaterialTheme.colorScheme.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                                Text("Point at an evenly lit white surface!", color = Color.Gray, fontSize = 10.sp)
                                LinearProgressIndicator(
                                    progress = { state.framesCaptured.toFloat() / state.totalFrames.toFloat() },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = MaterialTheme.colorScheme.primary,
                                    trackColor = Color.White.copy(alpha = 0.1f)
                                )
                                Text("Captured ${state.framesCaptured} of ${state.totalFrames} frames", color = Color.White, fontSize = 11.sp)
                                TextButton(onClick = viewModel::resetSessionState) {
                                    Text("Cancel Calibration", color = Color.Gray, fontSize = 11.sp)
                                }
                            }
                        }

                        is CaptureSessionState.Capturing -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(12.dp)).padding(12.dp)
                            ) {
                                Text(
                                    text = if (uiState.stackPhotos) "Live Stacking Active" else "Capturing RAW Frames",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                )
                                Text(
                                    text = "Captured: ${state.framesCaptured}  •  Stacked: ${state.framesStacked}",
                                    color = Color.White,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                                Button(
                                    onClick = viewModel::stopCapture,
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.fillMaxWidth(0.6f)
                                ) {
                                    Text("Stop & Finish Stack", fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        is CaptureSessionState.Error -> {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.8f), shape = RoundedCornerShape(12.dp)).padding(12.dp)
                            ) {
                                Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error, fontSize = 11.sp)
                                TextButton(onClick = viewModel::resetSessionState) {
                                    Text("Dismiss", color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }

                        is CaptureSessionState.Done -> Unit
                    }
                }
            }
        }

        // ── Fullscreen Exit Overlay ───────────────────────────────────────────
        if (isFullScreenView) {
            IconButton(
                onClick = { isFullScreenView = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    imageVector = Icons.Filled.Close,
                    contentDescription = "Exit Fullscreen",
                    tint = Color.White
                )
            }
        }

        // ── All Settings Modal Sheet ⚙️ ────────────────────────────────────────
        if (showSettingsSheet) {
            AstroSettingsBottomSheet(
                uiState = uiState,
                viewModel = viewModel,
                onDismiss = { showSettingsSheet = false }
            )
        }
    }
}

// ─── Settings Modal Bottom Sheet ⚙️ ────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AstroSettingsBottomSheet(
    uiState: CameraUiState,
    viewModel: CameraViewModel,
    onDismiss: () -> Unit,
) {
    val hasMasterDark by viewModel.hasMasterDark.collectAsState()
    val hasMasterFlat by viewModel.hasMasterFlat.collectAsState()
    var showDarkCalibrationWizard by remember { mutableStateOf(false) }
    var showFlatCalibrationWizard by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF181818),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color.Gray) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Astrophotography Settings ⚙️", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Close, contentDescription = "Close", tint = Color.Gray)
                }
            }

            // ── Section 1: Manual Exposure & Focus ────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Exposure & Focus", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    // Presets
                    Text("Quick Presets", color = Color.Gray, fontSize = 10.sp)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EXPOSURE_PRESETS.forEach { preset ->
                            val active = uiState.exposureTimeNs == preset.exposureTimeNs && uiState.iso == preset.iso
                            FilterChip(
                                selected = active,
                                onClick = { viewModel.setExposurePreset(preset) },
                                label = { Text(preset.label, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                    selectedLabelColor = MaterialTheme.colorScheme.primary
                                )
                            )
                        }
                    }

                    // Tripod NPF Limit suggestion
                    val limits = remember(uiState.capabilities) {
                        calculateTripodExposureLimits(uiState.capabilities?.characteristics)
                    }
                    if (limits != null) {
                        val (rule500, npfRule) = limits
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Tripod Limit:", color = Color.Gray, fontSize = 10.sp)
                            SuggestionChip(
                                onClick = {
                                    val targetNs = (rule500 * 1_000_000_000L).toLong()
                                    val closest = EXPOSURE_TIME_STEPS.minByOrNull { abs(it.first - targetNs) }
                                    if (closest != null) viewModel.setExposureTimeNs(closest.first)
                                },
                                label = { Text("500 Rule: ${"%.1fs".format(rule500)}", fontSize = 10.sp) }
                            )
                            SuggestionChip(
                                onClick = {
                                    val targetNs = (npfRule * 1_000_000_000L).toLong()
                                    val closest = EXPOSURE_TIME_STEPS.minByOrNull { abs(it.first - targetNs) }
                                    if (closest != null) viewModel.setExposureTimeNs(closest.first)
                                },
                                label = { Text("NPF: ${"%.1fs".format(npfRule)}", fontSize = 10.sp) }
                            )
                        }
                    }

                    // Manual Controls Panel (Sliders)
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
                }
            }

            // ── Section 2: Stacking & Alignment ───────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Stacking & Processing", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    // Auto Stack Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Live Stacking", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("Aligns and stacks frames in real time", color = Color.Gray, fontSize = 9.sp)
                        }
                        Switch(checked = uiState.stackPhotos, onCheckedChange = viewModel::setStackPhotos)
                    }

                    // Dual-Layer Landscape Toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Dual-Layer Landscape Mode", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text(if (uiState.enableDualLayer) "Tracks sky while keeping trees & ground sharp" else "Off (Full-frame star tracking)", color = Color.Gray, fontSize = 9.sp)
                        }
                        Switch(checked = uiState.enableDualLayer, onCheckedChange = viewModel::setEnableDualLayer)
                    }

                    // Stretch Type chips
                    Column {
                        Text("Stretch Type", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StretchType.values().forEach { stretch ->
                                val selected = uiState.stretchType == stretch
                                FilterChip(
                                    selected = selected,
                                    onClick = { viewModel.setStretchType(stretch) },
                                    label = {
                                        Text(
                                            when (stretch) {
                                                StretchType.HISTOGRAM -> "Histogram (STF)"
                                                StretchType.ARCSINH -> "Arcsinh (Color)"
                                            },
                                            fontSize = 11.sp
                                        )
                                    },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }

                    // Light Pollution Gradient Removal
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Gradient Removal", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("Subtracts background light pollution", color = Color.Gray, fontSize = 9.sp)
                        }
                        Switch(checked = uiState.enableGradientRemoval, onCheckedChange = viewModel::setEnableGradientRemoval)
                    }

                    // Drift Handling chips
                    Column {
                        Text("Drift Handling", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
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
                                        selectedLabelColor = MaterialTheme.colorScheme.primary
                                    )
                                )
                            }
                        }
                    }

                    // Star detection Sensitivity
                    Column {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("Star Sensitivity", color = Color.Gray, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                            Text(
                                text = if (uiState.starThreshold < 0) "Auto (Adaptive)" else "${uiState.starThreshold}",
                                color = MaterialTheme.colorScheme.primary,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Slider(
                            value = if (uiState.starThreshold < 0) 100f else uiState.starThreshold.toFloat(),
                            onValueChange = { viewModel.setStarThreshold(it.roundToInt()) },
                            valueRange = 20f..255f,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            // ── Section 3: Storage & RAW ──────────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Storage & Hardware", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Save All RAW DNGs", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("Saves uncompressed sub-frames to gallery", color = Color.Gray, fontSize = 9.sp)
                        }
                        Switch(checked = uiState.saveAllPhotos, onCheckedChange = viewModel::setSaveAllPhotos)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Disable OIS (Tripod Mode)", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Text("Prevents stabilizer jitter on fixed mounts", color = Color.Gray, fontSize = 9.sp)
                        }
                        Switch(checked = uiState.disableOis, onCheckedChange = viewModel::setDisableOis)
                    }
                }
            }

            // ── Section 4: Viewfinder Display Mode ────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Viewfinder Aspect Ratio", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    Text("Choose how the camera sensor frame fits your phone screen", color = Color.Gray, fontSize = 9.sp)

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        FilterChip(
                            selected = uiState.previewScaleMode == com.astrostack.app.camera.PreviewScaleMode.FIT,
                            onClick = { viewModel.setPreviewScaleMode(com.astrostack.app.camera.PreviewScaleMode.FIT) },
                            label = { Text("Fit (Black Bars / Full FOV)", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                        FilterChip(
                            selected = uiState.previewScaleMode == com.astrostack.app.camera.PreviewScaleMode.FILL,
                            onClick = { viewModel.setPreviewScaleMode(com.astrostack.app.camera.PreviewScaleMode.FILL) },
                            label = { Text("Fill (Crop to Screen)", fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.2f),
                                selectedLabelColor = MaterialTheme.colorScheme.primary
                            )
                        )
                    }
                }
            }

            // ── Section 5: Sensor Calibration ─────────────────────────────────
            Card(
                colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.04f)),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sensor Calibration", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)

                    // Master Dark
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Master Dark Frame", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(color = if (hasMasterDark) Color.Green else Color.Gray, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (hasMasterDark) "Profile Active" else "No Dark Profile",
                                    color = Color.Gray,
                                    fontSize = 9.sp
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (hasMasterDark) {
                                TextButton(onClick = viewModel::clearMasterDark, modifier = Modifier.height(26.dp)) {
                                    Text("Clear", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                                }
                            }
                            Button(
                                onClick = { showDarkCalibrationWizard = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), contentColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("Calibrate", fontSize = 10.sp)
                            }
                        }
                    }

                    HorizontalDivider(color = Color.White.copy(alpha = 0.05f))

                    // Master Flat
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("Master Flat Frame", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .background(color = if (hasMasterFlat) Color.Green else Color.Gray, shape = CircleShape)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = if (hasMasterFlat) "Profile Active" else "Synthetic Vignetting Fallback",
                                    color = Color.Gray,
                                    fontSize = 9.sp
                                )
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            if (hasMasterFlat) {
                                TextButton(onClick = viewModel::clearMasterFlat, modifier = Modifier.height(26.dp)) {
                                    Text("Clear", color = MaterialTheme.colorScheme.primary, fontSize = 10.sp)
                                }
                            }
                            Button(
                                onClick = { showFlatCalibrationWizard = true },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f), contentColor = MaterialTheme.colorScheme.primary),
                                shape = RoundedCornerShape(4.dp),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                modifier = Modifier.height(26.dp)
                            ) {
                                Text("Calibrate", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDarkCalibrationWizard) {
        AlertDialog(
            onDismissRequest = { showDarkCalibrationWizard = false },
            title = { Text("Capture Master Dark Reference", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Please cover your camera lens COMPLETELY (or place it face-down on a dark surface) and tap Start. The app will capture 5 dark frames to construct the thermal noise subtraction profile.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    showDarkCalibrationWizard = false
                    onDismiss()
                    viewModel.startDarkCalibration()
                }) { Text("Start", fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showDarkCalibrationWizard = false }) { Text("Cancel", color = Color.Gray, fontSize = 12.sp) }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }

    if (showFlatCalibrationWizard) {
        AlertDialog(
            onDismissRequest = { showFlatCalibrationWizard = false },
            title = { Text("Capture Master Flat Reference", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    text = "Point your camera at a bright, evenly lit surface (like a white computer screen or a white t-shirt stretched over the lens pointed at the sky) and tap Start. The app will capture 10 frames to compute the flat calibration profile.",
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                Button(onClick = {
                    showFlatCalibrationWizard = false
                    onDismiss()
                    viewModel.startFlatCalibration()
                }) { Text("Start", fontSize = 12.sp) }
            },
            dismissButton = {
                TextButton(onClick = { showFlatCalibrationWizard = false }) { Text("Cancel", color = Color.Gray, fontSize = 12.sp) }
            },
            containerColor = Color(0xFF1E1E1E)
        )
    }
}

// ─── Camera Preview Surface ───────────────────────────────────────────────────

@Composable
private fun CameraPreview(
    modifier: Modifier,
    previewWidth: Int,
    previewHeight: Int,
    scaleMode: com.astrostack.app.camera.PreviewScaleMode,
    onSurfaceReady: (android.view.Surface) -> Unit,
) {
    // In portrait orientation on Android, the Camera HAL outputs the preview in 3:4 aspect ratio (previewHeight / previewWidth).
    val portraitTargetAspect = if (previewWidth > 0) previewHeight.toFloat() / previewWidth.toFloat() else 0.75f

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        val screenAspect = maxWidth.value / maxHeight.value
        val (viewWidth, viewHeight) = if (scaleMode == com.astrostack.app.camera.PreviewScaleMode.FILL) {
            // Fill mode: expands to fill screen, cropping excess edges uniformly without distortion
            if (portraitTargetAspect > screenAspect) {
                maxHeight * portraitTargetAspect to maxHeight
            } else {
                maxWidth to maxWidth / portraitTargetAspect
            }
        } else {
            // Fit mode: black bars (letterbox/pillarbox) showing 100% of the sensor frame without distortion
            if (portraitTargetAspect > screenAspect) {
                maxWidth to maxWidth / portraitTargetAspect
            } else {
                maxHeight * portraitTargetAspect to maxHeight
            }
        }

        AndroidView(
            factory = { ctx ->
                SurfaceView(ctx).also { sv ->
                    sv.holder.setFixedSize(previewWidth, previewHeight)
                    sv.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onSurfaceReady(holder.surface)
                        }
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                        override fun surfaceDestroyed(holder: SurfaceHolder) {}
                    })
                }
            },
            update = { sv ->
                sv.holder.setFixedSize(previewWidth, previewHeight)
            },
            modifier = Modifier.size(viewWidth, viewHeight),
        )
    }
}

// ─── Manual Controls Sliders Panel ────────────────────────────────────────────

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

    val exposureIdx = remember(exposureTimeNs, exposureSteps) {
        exposureSteps.indices.minByOrNull { abs(exposureSteps[it].first - exposureTimeNs) } ?: 0
    }
    val isoIdx = remember(iso, isoSteps) {
        isoSteps.indices.minByOrNull { abs(isoSteps[it] - iso) } ?: 0
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        // Shutter slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("Shutter", color = Color.Gray, fontSize = 11.sp)
            Text(
                text = exposureSteps[exposureIdx].second,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
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

        // ISO slider
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text("ISO", color = Color.Gray, fontSize = 11.sp)
            Text(
                text = isoSteps[isoIdx].toString(),
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
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

// ─── Shutter Button ───────────────────────────────────────────────────────────

@Composable
private fun CaptureButton(onClick: () -> Unit) {
    FloatingActionButton(
        onClick = onClick,
        containerColor = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(72.dp),
        shape = CircleShape,
    ) {
        Icon(Icons.Filled.CameraAlt, contentDescription = "Start capture", modifier = Modifier.size(36.dp), tint = Color.Black)
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
