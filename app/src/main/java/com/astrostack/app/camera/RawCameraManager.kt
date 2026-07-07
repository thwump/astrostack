package com.astrostack.app.camera

import android.Manifest
import android.content.Context
import android.graphics.ImageFormat
import android.hardware.camera2.*
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.SessionConfiguration
import android.hardware.camera2.DngCreator
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Build
import android.hardware.camera2.CameraExtensionCharacteristics
import android.view.Surface
import androidx.annotation.RequiresPermission
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "AstroStack"
private const val MAX_RAW_IMAGES = 3

/**
 * Manages a Camera2 session with full RAW_SENSOR capture capability.
 *
 * Responsibilities:
 *  - Enumerate cameras and confirm RAW support
 *  - Open the camera device and configure capture + preview surfaces
 *  - Issue CaptureRequests with fully manual exposure settings
 *  - Write captured RAW Images to disk as DNG files
 */
@Singleton
class RawCameraManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val cameraManager: CameraManager =
        context.getSystemService(Context.CAMERA_SERVICE) as CameraManager

    // Dedicated background thread for camera callbacks
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null
    private var cameraExecutor: Executor? = null

    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var rawImageReader: ImageReader? = null

    // The characteristics of the currently open camera (set after openCamera)
    var activeCharacteristics: CameraCharacteristics? = null
        private set

    private var activePhysicalCameraId: String? = null

    // ─── Camera discovery ─────────────────────────────────────────────────────

    /**
     * Returns the [CameraCapabilities] of the best rear-facing camera.
     *
     * Selection priority:
     *  1. Rear-facing camera with REQUEST_AVAILABLE_CAPABILITIES_RAW AND RAW_SENSOR output sizes
     *  2. Rear-facing camera with RAW_SENSOR output sizes (even without explicit RAW capability flag)
     *  3. Any rear-facing camera as a fallback
     *
     * On devices like the Pixel 9 that expose multiple rear cameras (wide, ultrawide, tele),
     * the camera with the largest RAW output resolution is preferred.
     */
    fun findAllRawCameras(): List<CameraCapabilities> {
        data class Candidate(val caps: CameraCapabilities, val priority: Int)
        val candidates = mutableListOf<Candidate>()

        for (id in cameraManager.cameraIdList) {
            val chars = runCatching {
                cameraManager.getCameraCharacteristics(id)
            }.getOrNull() ?: continue

            val facing = chars.get(CameraCharacteristics.LENS_FACING)
            val availableCaps = chars.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            val hasRawCapability = availableCaps
                ?.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) == true

            val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            val rawSizes = map?.getOutputSizes(ImageFormat.RAW_SENSOR)
            val largest = rawSizes?.maxByOrNull { it.width * it.height }

            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                chars.physicalCameraIds
            } else {
                emptySet()
            }

            android.util.Log.i("AstroStack", "Discovered Camera ID: $id, facing=$facing (back=0, front=1, external=2), hasRawCapability=$hasRawCapability, largestRawSensorSize=${largest?.width}x${largest?.height}, physicalCameraIds=$physicalIds")

            if (facing != CameraCharacteristics.LENS_FACING_BACK) continue

            val isoRange = chars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            val expRange = chars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val oisModes = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION)
            val hasOis = oisModes?.contains(
                CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON
            ) == true

            val priority = when {
                hasRawCapability && largest != null -> 0
                largest != null                    -> 1
                else                               -> 2
            }

            val hasNightExtension = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                runCatching {
                    val extChars = cameraManager.getCameraExtensionCharacteristics(id)
                    extChars.supportedExtensions.contains(CameraExtensionCharacteristics.EXTENSION_NIGHT)
                }.getOrDefault(false)
            } else {
                false
            }

            var physicalCandidatesAdded = 0
            if (physicalIds.isNotEmpty()) {
                for (pid in physicalIds) {
                    val pChars = runCatching {
                        cameraManager.getCameraCharacteristics(pid)
                    }.onFailure { ex ->
                        android.util.Log.e("AstroStack", "Failed to get characteristics for physical camera ID $pid", ex)
                    }.getOrNull() ?: continue

                    val pMap = pChars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
                    val pRawSizes = pMap?.getOutputSizes(ImageFormat.RAW_SENSOR)
                    val pLargest = pRawSizes?.maxByOrNull { it.width * it.height } ?: largest // fallback to logical largest raw size

                    if (pLargest == null) continue // skip physical camera if no raw configurations at all

                    val pIsoRange = pChars.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE) ?: isoRange
                    val pExpRange = pChars.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE) ?: expRange
                    val pOisModes = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION) ?: oisModes
                    val pHasOis = pOisModes?.contains(CameraCharacteristics.LENS_OPTICAL_STABILIZATION_MODE_ON) == true
                    val pApertures = pChars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                    val pAperture = pApertures?.firstOrNull() ?: 1.8f

                    android.util.Log.i("AstroStack", "Adding Physical Camera Candidate: pid=$pid, logicalId=$id, rawSize=${pLargest.width}x${pLargest.height}")

                    val cap = CameraCapabilities(
                        cameraId = id,
                        physicalCameraId = pid,
                        supportsRaw = true,
                        minExposureNs = pExpRange?.lower ?: 1_000_000L,
                        maxExposureNs = pExpRange?.upper ?: 30_000_000_000L,
                        maxIso = pIsoRange?.upper ?: 3200,
                        minIso = pIsoRange?.lower ?: 100,
                        rawSensorWidth = pLargest.width,
                        rawSensorHeight = pLargest.height,
                        hasOis = pHasOis,
                        characteristics = pChars,
                        supportsNightExtension = hasNightExtension,
                        aperture = pAperture,
                    )
                    candidates.add(Candidate(cap, priority))
                    physicalCandidatesAdded++
                }
            }

            // Fallback: If no raw physical sensors were successfully added, add the logical camera device itself
            if (physicalCandidatesAdded == 0 && (hasRawCapability || largest != null)) {
                val apertures = chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
                val aperture = apertures?.firstOrNull() ?: 1.8f
                
                val cap = CameraCapabilities(
                    cameraId = id,
                    physicalCameraId = null,
                    supportsRaw = true,
                    minExposureNs = expRange?.lower ?: 1_000_000L,
                    maxExposureNs = expRange?.upper ?: 30_000_000_000L,
                    maxIso = isoRange?.upper ?: 3200,
                    minIso = isoRange?.lower ?: 100,
                    rawSensorWidth = largest?.width ?: 0,
                    rawSensorHeight = largest?.height ?: 0,
                    hasOis = hasOis,
                    characteristics = chars,
                    supportsNightExtension = hasNightExtension,
                    aperture = aperture,
                )
                candidates.add(Candidate(cap, priority))
            }
        }

        return candidates
            .sortedWith(compareBy<Candidate> { it.priority }
                .thenByDescending { it.caps.rawSensorWidth * it.caps.rawSensorHeight })
            .map { it.caps }
    }

    fun findBestRawCamera(): CameraCapabilities? {
        val list = findAllRawCameras()
        list.forEach { cap ->
            android.util.Log.d(TAG, "Camera ${cap.cameraId}: RAW=${cap.supportsRaw} ${cap.rawSensorWidth}x${cap.rawSensorHeight} ISO=${cap.minIso}-${cap.maxIso}")
        }
        val best = list.firstOrNull()
        android.util.Log.d(TAG, "Selected camera: ${best?.cameraId} RAW=${best?.supportsRaw}")
        return best
    }

    // ─── Open & close ─────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.CAMERA)
    suspend fun openCamera(capabilities: CameraCapabilities, previewSurface: Surface) {
        startBackgroundThread()

        // Set up the RAW ImageReader
        rawImageReader = ImageReader.newInstance(
            capabilities.rawSensorWidth,
            capabilities.rawSensorHeight,
            ImageFormat.RAW_SENSOR,
            MAX_RAW_IMAGES,
        )

        openCameraDevice(capabilities.cameraId)
        activeCharacteristics = capabilities.characteristics
        activePhysicalCameraId = capabilities.physicalCameraId
        createCaptureSession(previewSurface)
    }

    @RequiresPermission(Manifest.permission.CAMERA)
    private suspend fun openCameraDevice(cameraId: String) =
        suspendCancellableCoroutine { cont ->
            cameraManager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        cont.resume(Unit)
                    }

                    override fun onDisconnected(camera: CameraDevice) {
                        camera.close()
                        cameraDevice = null
                    }

                    override fun onError(camera: CameraDevice, error: Int) {
                        camera.close()
                        cameraDevice = null
                        if (cont.isActive) {
                            cont.resumeWithException(
                                CameraAccessException(error, "Camera device error $error")
                            )
                        }
                    }
                },
                cameraHandler,
            )
        }

    private suspend fun createCaptureSession(previewSurface: Surface) =
        suspendCancellableCoroutine { cont ->
            val device = cameraDevice ?: run {
                cont.resumeWithException(IllegalStateException("Camera device not open"))
                return@suspendCancellableCoroutine
            }
            val rawSurface = rawImageReader!!.surface
            val previewConfig = OutputConfiguration(previewSurface)
            val rawConfig = OutputConfiguration(rawSurface)

            if (activePhysicalCameraId != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                previewConfig.setPhysicalCameraId(activePhysicalCameraId)
                rawConfig.setPhysicalCameraId(activePhysicalCameraId)
            }

            val outputs = listOf(previewConfig, rawConfig)
            val sessionConfig = SessionConfiguration(
                SessionConfiguration.SESSION_REGULAR,
                outputs,
                cameraExecutor!!,
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(session: CameraCaptureSession) {
                        captureSession = session
                        cont.resume(Unit)
                    }

                    override fun onConfigureFailed(session: CameraCaptureSession) {
                        cont.resumeWithException(
                            IllegalStateException("Capture session configuration failed")
                        )
                    }
                },
            )
            device.createCaptureSession(sessionConfig)
        }

    fun close() {
        captureSession?.close()
        captureSession = null
        cameraDevice?.close()
        cameraDevice = null
        rawImageReader?.close()
        rawImageReader = null
        activeCharacteristics = null
        stopBackgroundThread()
    }

    // ─── Preview ──────────────────────────────────────────────────────────────

    /**
     * Starts a repeating preview request targeting [previewSurface].
     * Uses auto-exposure for the live viewfinder, which helps frame the scene.
     */
    fun startPreview(previewSurface: Surface, autoFocus: Boolean = false) {
        val session = captureSession ?: return
        val device = cameraDevice ?: return

        val previewRequest = device
            .createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            .apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
                if (autoFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // infinity
                }
            }
            .build()

        session.setRepeatingRequest(previewRequest, null, cameraHandler)
    }

    fun stopPreview() {
        captureSession?.stopRepeating()
    }

    // ─── RAW capture ──────────────────────────────────────────────────────────

    /**
     * Captures a single RAW frame with fully manual exposure settings and
     * saves it as a DNG file at [outputFile].
     *
     * Suspends until the image has been written to disk.
     */
    suspend fun captureAndSaveDng(
        settings: CaptureSettings,
        outputFile: File,
        onShutterCallback: () -> Unit = {},
    ) {
        val (image, captureResult) = captureRawImage(settings, onShutterCallback)
        try {
            saveImageAsDng(image, captureResult, outputFile)
        } finally {
            image.close()
        }
    }

    /** Holds the Image and its matching TotalCaptureResult from onCaptureCompleted. */
    private data class RawCapture(val image: Image, val result: TotalCaptureResult)

    private suspend fun captureRawImage(
        settings: CaptureSettings,
        onShutter: () -> Unit,
    ): RawCapture = suspendCancellableCoroutine { cont ->
        val session = captureSession
            ?: return@suspendCancellableCoroutine cont.resumeWithException(
                IllegalStateException("No active capture session")
            )
        val device = cameraDevice
            ?: return@suspendCancellableCoroutine cont.resumeWithException(
                IllegalStateException("Camera device not open")
            )

        val captureRequest = device
            .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            .apply {
                addTarget(rawImageReader!!.surface)

                // ── Full manual control ──────────────────────────────────────
                set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_OFF)
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)

                // ── Exposure ─────────────────────────────────────────────────
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, settings.exposureTimeNs)
                set(CaptureRequest.SENSOR_SENSITIVITY, settings.iso)
                // Frame duration ≥ exposure time
                set(CaptureRequest.SENSOR_FRAME_DURATION, settings.exposureTimeNs)

                // ── Focus ────────────────────────────────────────────────────
                if (settings.autoFocus) {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO)
                } else {
                    set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    set(CaptureRequest.LENS_FOCUS_DISTANCE, 0f) // infinity
                }

                // ── Disable all on-device processing — stacking handles it ───
                set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF)
                set(CaptureRequest.SHADING_MODE, CaptureRequest.SHADING_MODE_OFF)
                set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF)
                set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
                set(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE,
                    CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_OFF)
                set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST)
                set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE,
                    CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_OFF)

                // ── OIS off (camera should be on a tripod/tracker) ───────────
                if (settings.disableOis) {
                    set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE,
                        CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_OFF)
                }

                // ── Manual WB gains ──────────────────────────────────────────
                settings.wbGains?.let { gains ->
                    if (gains.size == 4) {
                        set(CaptureRequest.COLOR_CORRECTION_MODE,
                            CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
                        set(CaptureRequest.COLOR_CORRECTION_GAINS,
                            android.hardware.camera2.params.RggbChannelVector(
                                gains[0], gains[1], gains[2], gains[3]
                            ))
                    }
                }
            }
            .build()

        session.capture(
            captureRequest,
            object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureStarted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    timestamp: Long,
                    frameNumber: Long,
                ) {
                    onShutter()
                }

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    val image = rawImageReader?.acquireLatestImage()
                    if (image != null) {
                        cont.resume(RawCapture(image, result))
                    } else {
                        cont.resumeWithException(IllegalStateException("Failed to acquire RAW image"))
                    }
                }

                override fun onCaptureFailed(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    failure: CaptureFailure,
                ) {
                    cont.resumeWithException(
                        IllegalStateException("Capture failed: reason=${failure.reason}")
                    )
                }
            },
            cameraHandler,
        )
    }

    private fun saveImageAsDng(image: Image, result: TotalCaptureResult, outputFile: File) {
        val chars = activeCharacteristics
            ?: throw IllegalStateException("No camera characteristics available")

        outputFile.parentFile?.mkdirs()
        DngCreator(chars, result).use { dng ->
            FileOutputStream(outputFile).use { out ->
                dng.writeImage(out, image)
            }
        }
        android.util.Log.d(TAG, "DNG saved: ${outputFile.name} (${outputFile.length() / 1024} KB)")
    }

    // ─── Background thread ────────────────────────────────────────────────────

    private fun startBackgroundThread() {
        cameraThread = HandlerThread("CameraBackground").also { it.start() }
        cameraHandler = Handler(cameraThread!!.looper)
        cameraExecutor = Executor { runnable -> cameraHandler!!.post(runnable) }
    }

    private fun stopBackgroundThread() {
        cameraThread?.quitSafely()
        cameraThread?.join()
        cameraThread = null
        cameraHandler = null
        cameraExecutor = null
    }
}
