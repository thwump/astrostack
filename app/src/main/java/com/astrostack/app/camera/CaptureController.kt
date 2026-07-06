package com.astrostack.app.camera

import android.Manifest
import android.content.Context
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.astrostack.app.data.ImageRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * High-level controller that orchestrates a multi-frame RAW capture session.
 *
 * Drives the capture loop: for each frame it requests a RAW capture from
 * [RawCameraManager], saves the DNG to internal storage, and records the
 * session in the [ImageRepository].  Exposes [sessionState] for UI observation.
 */
@Singleton
class CaptureController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cameraManager: RawCameraManager,
    private val repository: ImageRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessionState = MutableStateFlow<CaptureSessionState>(CaptureSessionState.Idle)
    val sessionState: StateFlow<CaptureSessionState> = _sessionState.asStateFlow()

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    // Populated once findBestRawCamera() completes — observed by CameraViewModel via Flow
    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    val capabilitiesFlow: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()

    // Populated after openCamera (kept for legacy access)
    var capabilities: CameraCapabilities? = null
        private set

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(previewSurface: Surface) {
        scope.launch {
            try {
                val caps = withContext(Dispatchers.IO) {
                    cameraManager.findBestRawCamera()
                }
                if (caps == null) {
                    _previewState.value = PreviewState.NoCameraFound
                    return@launch
                }
                capabilities = caps
                _capabilities.value = caps  // push to Flow so ViewModel can observe it
                withContext(Dispatchers.IO) {
                    cameraManager.openCamera(caps, previewSurface)
                }
                cameraManager.startPreview(previewSurface)
                _previewState.value = PreviewState.Active
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Unknown camera error")
            }
        }
    }

    fun closeCamera() {
        cameraManager.close()
        _previewState.value = PreviewState.Loading
    }

    // ─── Capture session ──────────────────────────────────────────────────────

    /**
     * Starts a capture session with the given [settings].
     * Captures [CaptureSettings.frameCount] RAW frames, saves them as DNG files,
     * and creates a session record in the database.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun startCaptureSession(settings: CaptureSettings) {
        if (_sessionState.value is CaptureSessionState.Capturing) return

        scope.launch {
            val sessionId: Long
            val outputDir: File

            withContext(Dispatchers.IO) {
                // Create output directory for this session
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                outputDir = File(context.filesDir, "captures/session_$timestamp").also { it.mkdirs() }
                // Insert session record
                sessionId = repository.createSession(
                    name = "Session $timestamp",
                    frameCount = settings.frameCount,
                    iso = settings.iso,
                    exposureNs = settings.exposureTimeNs,
                    directoryPath = outputDir.absolutePath,
                )
            }

            for (frameIndex in 0 until settings.frameCount) {
                val fileName = "frame_%03d.dng".format(frameIndex + 1)
                val outputFile = File(outputDir, fileName)

                _sessionState.value = CaptureSessionState.Capturing(
                    framesCompleted = frameIndex,
                    framesTotal = settings.frameCount,
                    currentFilePath = outputFile.absolutePath,
                )

                try {
                    withContext(Dispatchers.IO) {
                        cameraManager.captureAndSaveDng(
                            settings = settings,
                            outputFile = outputFile,
                            onShutterCallback = { /* play shutter sound if desired */ },
                        )
                        repository.addFrame(
                            sessionId = sessionId,
                            filePath = outputFile.absolutePath,
                            frameIndex = frameIndex,
                        )
                    }
                } catch (e: Exception) {
                    _sessionState.value = CaptureSessionState.Error(
                        message = "Frame ${frameIndex + 1} failed: ${e.message}",
                        cause = e,
                    )
                    return@launch
                }
            }

            _sessionState.value = CaptureSessionState.Done(
                sessionId = sessionId,
                frameCount = settings.frameCount,
            )
        }
    }

    fun cancelSession() {
        // cancellation is handled implicitly when the coroutine scope is cleared
        _sessionState.value = CaptureSessionState.Idle
    }

    fun resetSessionState() {
        _sessionState.value = CaptureSessionState.Idle
    }
}
