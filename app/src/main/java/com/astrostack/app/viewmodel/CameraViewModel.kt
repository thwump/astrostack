package com.astrostack.app.viewmodel

import android.Manifest
import android.graphics.Bitmap
import android.view.Surface

import androidx.annotation.RequiresPermission
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astrostack.app.camera.CameraCapabilities
import com.astrostack.app.camera.CaptureController
import com.astrostack.app.camera.CaptureSessionState
import com.astrostack.app.camera.CaptureSettings
import com.astrostack.app.camera.ExposurePreset
import com.astrostack.app.camera.PreviewState
import com.astrostack.app.stacking.DriftHandling
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CameraUiState(
    val previewState: PreviewState = PreviewState.Loading,
    val sessionState: CaptureSessionState = CaptureSessionState.Idle,
    val capabilities: CameraCapabilities? = null,
    val exposureTimeNs: Long = 4_000_000_000L,  // 4 s default
    val iso: Int = 1600,
    val frameCount: Int = 10,
    val disableOis: Boolean = true,
    val rawSupported: Boolean = false,
    val liveStackedBitmap: Bitmap? = null,
    val autoFocusEnabled: Boolean = false,
    val saveAllPhotos: Boolean = false,
    val stackPhotos: Boolean = true,
    val starThreshold: Int = 180,
    val minStarCount: Int = 5,
    val driftHandling: DriftHandling = DriftHandling.CROP,
)

@HiltViewModel
class CameraViewModel @Inject constructor(
    private val captureController: CaptureController,
) : ViewModel() {

    private val _uiState = MutableStateFlow(CameraUiState())
    val uiState: StateFlow<CameraUiState> = _uiState.asStateFlow()

    init {
        // Mirror controller states into UI state
        viewModelScope.launch {
            captureController.previewState.collect { ps ->
                _uiState.update { it.copy(previewState = ps) }
            }
        }
        viewModelScope.launch {
            captureController.sessionState.collect { ss ->
                _uiState.update { it.copy(sessionState = ss) }
            }
        }
        // Observe capabilities once findBestRawCamera() resolves asynchronously
        viewModelScope.launch {
            captureController.capabilitiesFlow.collect { caps ->
                _uiState.update {
                    it.copy(
                        capabilities = caps,
                        rawSupported = caps?.supportsRaw == true,
                    )
                }
            }
        }
        // Observe live stacked bitmap
        viewModelScope.launch {
            captureController.liveStackedBitmap.collect { bmp ->
                _uiState.update { it.copy(liveStackedBitmap = bmp) }
            }
        }
    }


    // ─── Camera lifecycle ─────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(previewSurface: Surface) {
        // capabilities are populated asynchronously via capabilitiesFlow — no sync read here
        captureController.openCamera(previewSurface)
    }

    fun closeCamera() = captureController.closeCamera()

    // ─── Settings ─────────────────────────────────────────────────────────────

    fun setExposurePreset(preset: ExposurePreset) =
        _uiState.update { it.copy(exposureTimeNs = preset.exposureTimeNs, iso = preset.iso) }

    fun setExposureTimeNs(ns: Long) =
        _uiState.update { it.copy(exposureTimeNs = ns) }

    fun setIso(iso: Int) =
        _uiState.update { it.copy(iso = iso) }

    fun setSaveAllPhotos(save: Boolean) {
        _uiState.update { it.copy(saveAllPhotos = save) }
    }

    fun setStackPhotos(stack: Boolean) {
        _uiState.update { it.copy(stackPhotos = stack) }
    }

    fun setDisableOis(disabled: Boolean) =
        _uiState.update { it.copy(disableOis = disabled) }

    fun setAutoFocus(enabled: Boolean) =
        _uiState.update { 
            it.copy(autoFocusEnabled = enabled).also {
                captureController.setAutoFocusEnabled(enabled)
            }
        }

    fun setStarThreshold(threshold: Int) {
        _uiState.update { it.copy(starThreshold = threshold.coerceIn(20, 255)) }
    }

    fun setMinStarCount(count: Int) {
        _uiState.update { it.copy(minStarCount = count.coerceIn(3, 30)) }
    }

    fun setDriftHandling(drift: DriftHandling) {
        _uiState.update { it.copy(driftHandling = drift) }
    }

    // ─── Capture ──────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.CAMERA)
    fun startCapture() {
        val state = _uiState.value
        val settings = CaptureSettings(
            exposureTimeNs = state.exposureTimeNs,
            iso = state.iso,
            saveAllPhotos = state.saveAllPhotos,
            stackPhotos = state.stackPhotos,
            disableOis = state.disableOis,
            autoFocus = state.autoFocusEnabled,
            starThreshold = state.starThreshold,
            minStarCount = state.minStarCount,
            driftHandling = state.driftHandling,
        )
        captureController.startCaptureSession(settings)
    }

    fun stopCapture() = captureController.stopCaptureSession()

    fun cancelCapture() = captureController.cancelSession()

    fun resetSessionState() = captureController.resetSessionState()

    override fun onCleared() {
        super.onCleared()
        captureController.closeCamera()
    }
}
