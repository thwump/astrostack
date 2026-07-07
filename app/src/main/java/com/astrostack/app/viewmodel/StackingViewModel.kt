package com.astrostack.app.viewmodel

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrostack.app.data.CaptureSession
import com.astrostack.app.data.ImageRepository
import com.astrostack.app.stacking.ImageStacker
import com.astrostack.app.stacking.StackingAlgorithm
import com.astrostack.app.stacking.StackingConfig
import com.astrostack.app.stacking.DriftHandling
import com.astrostack.app.stacking.TiffWriter
import com.astrostack.app.stacking.FitsWriter
import com.astrostack.app.stacking.AstrometryNetClient

import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject

data class PlateSolveState(
    val status: String = "",
    val results: List<String>? = null,
    val isSolving: Boolean = false,
    val error: String? = null
)

sealed interface StackingUiState {
    data object Idle : StackingUiState
    data class Loading(val message: String = "Loading session…") : StackingUiState
    data class Ready(
        val session: CaptureSession,
        val frameCount: Int,
        val config: StackingConfig,
    ) : StackingUiState
    data class Stacking(
        val progress: Float,
        val session: CaptureSession,
        val config: StackingConfig,
    ) : StackingUiState
    data class Done(
        val resultBitmap: Bitmap,
        val resultPath: String,
        val session: CaptureSession,
        /** Set to the public URI/path after a successful save-to-gallery. */
        val publicPath: String? = null,
        val isSavingToGallery: Boolean = false,
        val plateSolveState: PlateSolveState = PlateSolveState(),
    ) : StackingUiState
    data class Error(val message: String) : StackingUiState
}

@HiltViewModel
class StackingViewModel @Inject constructor(
    private val repository: ImageRepository,
    private val stacker: ImageStacker,
) : ViewModel() {

    private val _uiState = MutableStateFlow<StackingUiState>(StackingUiState.Idle)
    val uiState: StateFlow<StackingUiState> = _uiState.asStateFlow()

    // ─── Load session ─────────────────────────────────────────────────────────

    fun loadSession(sessionId: Long) {
        viewModelScope.launch {
            _uiState.value = StackingUiState.Loading()
            val session = repository.getSessionById(sessionId)
            if (session == null) {
                _uiState.value = StackingUiState.Error("Session $sessionId not found")
                return@launch
            }

            val stackedPath = session.stackedImagePath
            if (stackedPath != null) {
                val file = File(stackedPath)
                if (file.exists()) {
                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    }
                    if (bitmap != null) {
                        _uiState.value = StackingUiState.Done(
                            resultBitmap = bitmap,
                            resultPath = file.absolutePath,
                            session = session,
                        )
                        return@launch
                    }
                }
            }

            val frames = repository.getFramesForSession(sessionId)
            _uiState.value = StackingUiState.Ready(
                session = session,
                frameCount = frames.size,
                config = StackingConfig(),
            )
        }
    }

    // ─── Config ───────────────────────────────────────────────────────────────

    fun setAlgorithm(algorithm: StackingAlgorithm) {
        val ready = _uiState.value as? StackingUiState.Ready ?: return
        _uiState.update { ready.copy(config = ready.config.copy(algorithm = algorithm)) }
    }

    fun setKappa(kappa: Float) {
        val ready = _uiState.value as? StackingUiState.Ready ?: return
        _uiState.update { ready.copy(config = ready.config.copy(kappa = kappa.coerceIn(1f, 5f))) }
    }

    fun setAlignFrames(align: Boolean) {
        val ready = _uiState.value as? StackingUiState.Ready ?: return
        _uiState.update { ready.copy(config = ready.config.copy(alignFrames = align)) }
    }

    fun setDriftHandling(drift: DriftHandling) {
        val ready = _uiState.value as? StackingUiState.Ready ?: return
        _uiState.update { ready.copy(config = ready.config.copy(driftHandling = drift)) }
    }

    fun setMinStarCount(count: Int) {
        val ready = _uiState.value as? StackingUiState.Ready ?: return
        _uiState.update { ready.copy(config = ready.config.copy(minStarCount = count.coerceIn(1, 100))) }
    }

    // ─── Stack ────────────────────────────────────────────────────────────────

    fun startStacking() {
        val ready = _uiState.value as? StackingUiState.Ready ?: return

        viewModelScope.launch {
            _uiState.value = StackingUiState.Stacking(
                progress = 0f,
                session = ready.session,
                config = ready.config,
            )

            try {
                val files = repository.getDngFilesForSession(ready.session.id)
                if (files.size < 2) {
                    _uiState.value = StackingUiState.Error("Need at least 2 captured frames to stack")
                    return@launch
                }

                val result = stacker.stack(
                    files = files,
                    config = ready.config,
                    onProgress = { p ->
                        _uiState.update { current ->
                            if (current is StackingUiState.Stacking)
                                current.copy(progress = p)
                            else current
                        }
                    },
                )

                // Save result PNG
                val outFile = withContext(Dispatchers.IO) {
                    val dir = repository.getStackedOutputDir()
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = File(dir, "stacked_${ready.session.id}_$ts.png")
                    FileOutputStream(file).use { out ->
                        result.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    repository.saveStackedResult(
                        sessionId = ready.session.id,
                        imagePath = file.absolutePath,
                        algorithm = ready.config.algorithm.name,
                    )
                    file
                }

                _uiState.value = StackingUiState.Done(
                    resultBitmap = result,
                    resultPath = outFile.absolutePath,
                    session = ready.session,
                )
            } catch (e: Exception) {
                _uiState.value = StackingUiState.Error(e.message ?: "Stacking failed")
            }
        }
    }

    fun saveToGallery(format: String = "PNG") {
        val done = _uiState.value as? StackingUiState.Done ?: return
        if (done.isSavingToGallery) return
        viewModelScope.launch {
            _uiState.update { if (it is StackingUiState.Done) it.copy(isSavingToGallery = true) else it }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val displayName = "AstroStack_${ts}"
            
            val publicPath = withContext(Dispatchers.IO) {
                val width = done.resultBitmap.width
                val height = done.resultBitmap.height
                val pixels = IntArray(width * height)
                done.resultBitmap.getPixels(pixels, 0, width, 0, 0, width, height)
                
                when (format) {
                    "TIFF" -> {
                        repository.saveExportFileToPublicPictures(displayName, "image/tiff", "tiff") { out ->
                            TiffWriter.writeRgbTiff(out, width, height, pixels)
                        }
                    }
                    "FITS" -> {
                        repository.saveExportFileToPublicPictures(displayName, "image/fits", "fits") { out ->
                            FitsWriter.writeRgbFits(out, width, height, pixels)
                        }
                    }
                    else -> { // PNG
                        repository.saveToPublicPictures(done.resultBitmap, displayName)
                    }
                }
            }
            _uiState.update {
                if (it is StackingUiState.Done)
                    it.copy(isSavingToGallery = false, publicPath = publicPath)
                else it
            }
        }
    }

    fun runPlateSolve(apiKey: String) {
        val done = _uiState.value as? StackingUiState.Done ?: return
        if (done.plateSolveState.isSolving) return

        viewModelScope.launch {
            _uiState.update { state ->
                if (state is StackingUiState.Done) {
                    state.copy(
                        plateSolveState = PlateSolveState(
                            isSolving = true,
                            status = "Initializing..."
                        )
                    )
                } else state
            }

            try {
                val results = if (apiKey.isBlank()) {
                    // MOCK solve mode for testing offline or without key
                    delay(3000)
                    listOf(
                        "Orion Nebula (M42)",
                        "Running Man Nebula (NGC 1977)",
                        "Theta1 Orionis Cluster",
                        "Great Orion Nebula (NGC 1976)"
                    )
                } else {
                    AstrometryNetClient.solveImage(
                        apiKey = apiKey,
                        bitmap = done.resultBitmap,
                        onProgress = { status ->
                            _uiState.update { state ->
                                if (state is StackingUiState.Done) {
                                    state.copy(
                                        plateSolveState = state.plateSolveState.copy(
                                            status = status
                                        )
                                    )
                                } else state
                            }
                        }
                    )
                }

                _uiState.update { state ->
                    if (state is StackingUiState.Done) {
                        state.copy(
                            plateSolveState = PlateSolveState(
                                isSolving = false,
                                status = "Success",
                                results = results
                            )
                        )
                    } else state
                }
            } catch (e: Exception) {
                _uiState.update { state ->
                    if (state is StackingUiState.Done) {
                        state.copy(
                            plateSolveState = PlateSolveState(
                                isSolving = false,
                                status = "Failed",
                                error = e.message ?: "Unknown error"
                            )
                        )
                    } else state
                }
            }
        }
    }


    fun resetToReady() {
        val done = _uiState.value as? StackingUiState.Done ?: return
        _uiState.value = StackingUiState.Ready(
            session = done.session,
            frameCount = 0,
            config = StackingConfig(),
        )
        loadSession(done.session.id)
    }
}
