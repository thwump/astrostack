package com.astrostack.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LayersClear
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.astrostack.app.stacking.StackingAlgorithm
import com.astrostack.app.viewmodel.StackingUiState
import com.astrostack.app.viewmodel.StackingViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun StackingScreen(
    sessionId: Long,
    onNavigateBack: () -> Unit,
    viewModel: StackingViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(sessionId) {
        viewModel.loadSession(sessionId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Stack Frames") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background),
        ) {
            when (val state = uiState) {
                is StackingUiState.Idle, is StackingUiState.Loading ->
                    LoadingIndicator(
                        message = if (state is StackingUiState.Loading) state.message else "Loading…"
                    )

                is StackingUiState.Ready ->
                    ReadyContent(
                        state = state,
                        onAlgorithmSelected = viewModel::setAlgorithm,
                        onKappaChanged = viewModel::setKappa,
                        onAlignChanged = viewModel::setAlignFrames,
                        onStartStacking = viewModel::startStacking,
                    )

                is StackingUiState.Stacking ->
                    StackingProgress(state = state)

                is StackingUiState.Done ->
                    ResultContent(state = state, onNavigateBack = onNavigateBack, viewModel = viewModel)

                is StackingUiState.Error ->
                    ErrorContent(message = state.message, onBack = onNavigateBack)
            }
        }
    }
}

// ─── Ready state ─────────────────────────────────────────────────────────────

@Composable
private fun ReadyContent(
    state: StackingUiState.Ready,
    onAlgorithmSelected: (StackingAlgorithm) -> Unit,
    onKappaChanged: (Float) -> Unit,
    onAlignChanged: (Boolean) -> Unit,
    onStartStacking: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Session summary
        SessionSummaryCard(session = state.session, frameCount = state.frameCount)

        // Algorithm selector
        AlgorithmSelector(
            selected = state.config.algorithm,
            onSelected = onAlgorithmSelected,
        )

        // Sigma-clipping kappa slider
        if (state.config.algorithm in listOf(StackingAlgorithm.SIGMA_CLIPPING, StackingAlgorithm.WINSORIZED_SIGMA)) {
            KappaSlider(kappa = state.config.kappa, onKappaChanged = onKappaChanged)
        }

        // Alignment toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text("Star Alignment", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                Text("Correct for drift between frames", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
            }
            Switch(checked = state.config.alignFrames, onCheckedChange = onAlignChanged)
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onStartStacking,
            modifier = Modifier.fillMaxWidth().height(52.dp),
            enabled = state.frameCount >= 2,
        ) {
            Icon(Icons.Filled.LayersClear, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("Stack ${state.frameCount} Frames", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun SessionSummaryCard(session: com.astrostack.app.data.CaptureSession, frameCount: Int) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(session.name, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text("$frameCount frames  •  ISO ${session.iso}  •  ${session.exposureTimeNs / 1_000_000_000L}s exposure",
                color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        }
    }
}

@Composable
private fun AlgorithmSelector(selected: StackingAlgorithm, onSelected: (StackingAlgorithm) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Stacking Algorithm", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        StackingAlgorithm.entries.forEach { algo ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = algo == selected, onClick = { onSelected(algo) })
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(algo.displayName, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
                    Text(algo.description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun KappaSlider(kappa: Float, onKappaChanged: (Float) -> Unit) {
    Column {
        Text("κ (kappa) = ${"%.1f".format(kappa)}", fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurface)
        Text("Higher κ = less aggressive clipping", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = kappa,
            onValueChange = onKappaChanged,
            valueRange = 1f..5f,
            steps = 7,
        )
    }
}

// ─── Stacking progress ───────────────────────────────────────────────────────

@Composable
private fun StackingProgress(state: StackingUiState.Stacking) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Stacking…", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(8.dp))
        Text(state.config.algorithm.displayName, color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(32.dp))
        CircularProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.size(80.dp),
            strokeWidth = 6.dp,
        )
        Spacer(Modifier.height(16.dp))
        Text("${(state.progress * 100).toInt()}%", color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold, fontSize = 20.sp)
    }
}

// ─── Result ──────────────────────────────────────────────────────────────────

@Composable
private fun ResultContent(
    state: StackingUiState.Done,
    onNavigateBack: () -> Unit,
    viewModel: StackingViewModel,
) {
    val snackbarHostState = remember { SnackbarHostState() }

    // Show snackbar when publicPath changes (save completes)
    LaunchedEffect(state.publicPath) {
        if (state.publicPath != null) {
            snackbarHostState.showSnackbar(
                message = "Saved to Pictures/AstroStack ✓",
                duration = SnackbarDuration.Short,
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Result", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onBackground, fontWeight = FontWeight.Bold)

            // Stacked image preview
            androidx.compose.foundation.Image(
                bitmap = state.resultBitmap.asImageBitmap(),
                contentDescription = "Stacked result",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(state.resultBitmap.width.toFloat() / state.resultBitmap.height)
                    .clip(RoundedCornerShape(12.dp)),
            )

            Text("Saved to:\n${state.resultPath}", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 11.sp)

            // Save to Gallery button
            Button(
                onClick = { viewModel.saveToGallery() },
                enabled = !state.isSavingToGallery && state.publicPath == null,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isSavingToGallery) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text("Saving…")
                } else if (state.publicPath != null) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Saved to Gallery ✓")
                } else {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Save to Gallery")
                }
            }

            Button(onClick = onNavigateBack, modifier = Modifier.fillMaxWidth()) {
                Text("Done")
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        )
    }
}

// ─── Helpers ─────────────────────────────────────────────────────────────────

@Composable
private fun LoadingIndicator(message: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            Text(message, color = MaterialTheme.colorScheme.onBackground)
        }
    }
}

@Composable
private fun ErrorContent(message: String, onBack: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("Error", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.onBackground)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onBack) { Text("Go Back") }
    }
}
