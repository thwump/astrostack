package com.astrostack.app.viewmodel

import android.Manifest
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

    fun setFrameCount(count: Int) =
        _uiState.update { it.copy(frameCount = count.coerceIn(1, 100)) }

    fun setDisableOis(disabled: Boolean) =
        _uiState.update { it.copy(disableOis = disabled) }

    // ─── Capture ──────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.CAMERA)
    fun startCapture() {
        val state = _uiState.value
        val settings = CaptureSettings(
            exposureTimeNs = state.exposureTimeNs,
            iso = state.iso,
            frameCount = state.frameCount,
            disableOis = state.disableOis,
        )
        captureController.startCaptureSession(settings)
    }

    fun cancelCapture() = captureController.cancelSession()

    fun resetSessionState() = captureController.resetSessionState()

    override fun onCleared() {
        super.onCleared()
        captureController.closeCamera()
    }
}
