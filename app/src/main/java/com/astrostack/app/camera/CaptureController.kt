package com.astrostack.app.camera

import android.Manifest
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.view.Surface
import androidx.annotation.RequiresPermission
import com.astrostack.app.data.ImageRepository
import com.astrostack.app.stacking.StarAligner
import com.astrostack.app.stacking.HistogramStretch
import com.astrostack.app.stacking.ImageStacker
import java.io.FileOutputStream
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
    private val starAligner: StarAligner,
    private val histogramStretch: HistogramStretch,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val _sessionState = MutableStateFlow<CaptureSessionState>(CaptureSessionState.Idle)
    val sessionState: StateFlow<CaptureSessionState> = _sessionState.asStateFlow()

    private val _hasMasterDark = MutableStateFlow(false)
    val hasMasterDark: StateFlow<Boolean> = _hasMasterDark.asStateFlow()

    init {
        val masterDarkFile = File(context.filesDir, "calibration/master_dark_full.png")
        _hasMasterDark.value = masterDarkFile.exists()
    }

    fun clearMasterDark() {
        val dir = File(context.filesDir, "calibration")
        if (dir.exists()) {
            dir.deleteRecursively()
        }
        _hasMasterDark.value = false
    }

    private val _previewState = MutableStateFlow<PreviewState>(PreviewState.Loading)
    val previewState: StateFlow<PreviewState> = _previewState.asStateFlow()

    // Populated once findBestRawCamera() completes — observed by CameraViewModel via Flow
    private val _capabilities = MutableStateFlow<CameraCapabilities?>(null)
    val capabilitiesFlow: StateFlow<CameraCapabilities?> = _capabilities.asStateFlow()

    // Live preview stack generated in real-time
    private val _liveStackedBitmap = MutableStateFlow<Bitmap?>(null)
    val liveStackedBitmap: StateFlow<Bitmap?> = _liveStackedBitmap.asStateFlow()


    private var activePreviewSurface: Surface? = null
    private var isAutoFocusEnabled = false
    var capabilities: CameraCapabilities? = null
        private set


    fun setAutoFocusEnabled(enabled: Boolean) {
        isAutoFocusEnabled = enabled
        val surface = activePreviewSurface ?: return
        try {
            cameraManager.startPreview(surface, enabled)
        } catch (e: Exception) {
            android.util.Log.e("AstroStack", "Failed to update preview focus", e)
        }
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @RequiresPermission(Manifest.permission.CAMERA)
    fun openCamera(previewSurface: Surface) {
        activePreviewSurface = previewSurface
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
                cameraManager.startPreview(previewSurface, isAutoFocusEnabled)
                _previewState.value = PreviewState.Active
            } catch (e: Exception) {
                _previewState.value = PreviewState.Error(e.message ?: "Unknown camera error")
            }
        }
    }

    fun closeCamera() {
        activePreviewSurface = null
        cameraManager.close()
        _previewState.value = PreviewState.Loading
    }

    private var captureJob: kotlinx.coroutines.Job? = null
    private var currentSessionId: Long? = null
    private var currentOutputDir: File? = null
    private var lastSettings: CaptureSettings? = null
    private var liveStackedCount = 0

    // ─── Capture session ──────────────────────────────────────────────────────

    /**
     * Starts a continuous capture session with the given [settings].
     * Captures RAW frames indefinitely until stopCaptureSession() or cancelSession() is called.
     */
    @RequiresPermission(Manifest.permission.CAMERA)
    fun startCaptureSession(settings: CaptureSettings) {
        if (_sessionState.value is CaptureSessionState.Capturing) return

        lastSettings = settings
        liveStackedCount = 0
        clearLiveStack()

        captureJob = scope.launch {
            val sessionId: Long
            val outputDir: File

            var totalCaptured = 0
            var totalStacked = 0
            var totalRejected = 0

            var referenceStars: List<com.astrostack.app.stacking.StarAligner.Star>? = null
            var liveAccumulator: FloatArray? = null
            var liveWidth = 0
            var liveHeight = 0

            var diagWriter: java.io.PrintWriter? = null

            try {
                withContext(Dispatchers.IO) {
                    val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    outputDir = File(context.filesDir, "captures/session_$timestamp").also { it.mkdirs() }
                    currentOutputDir = outputDir

                    // Create diagnostics file
                    val diagnosticsFile = File(outputDir, "alignment_diagnostics.txt")
                    diagWriter = java.io.PrintWriter(java.io.FileWriter(diagnosticsFile))
                    diagWriter?.println("--- Stacking Session Diagnostics ---")
                    diagWriter?.println("Timestamp: $timestamp")
                    diagWriter?.println("Settings: $settings")
                    diagWriter?.flush()

                    // Insert session record
                    sessionId = repository.createSession(
                        name = "Session $timestamp",
                        frameCount = 0, // dynamic
                        iso = settings.iso,
                        exposureNs = settings.exposureTimeNs,
                        directoryPath = outputDir.absolutePath,
                    )
                    currentSessionId = sessionId
                }

                while (coroutineContext[kotlinx.coroutines.Job]?.isActive == true) {
                    totalCaptured++
                    val fileName = "frame_%03d.dng".format(totalCaptured)
                    val outputFile = File(outputDir, fileName)

                    // Post capturing status update with current stats
                    _sessionState.value = CaptureSessionState.Capturing(
                        framesCaptured = totalCaptured,
                        framesStacked = totalStacked,
                        framesRejected = totalRejected,
                        currentFilePath = outputFile.absolutePath,
                    )

                    // 1. Capture the DNG frame
                    withContext(Dispatchers.IO) {
                        cameraManager.captureAndSaveDng(
                            settings = settings,
                            outputFile = outputFile,
                            onShutterCallback = { /* Shutter sound optional */ },
                        )
                    }

                    // 2. Perform live stacking/alignment in background thread sequentially to avoid races
                    if (settings.stackPhotos) {
                        val stackSuccess = withContext(Dispatchers.Default) {
                            try {
                                val opts = BitmapFactory.Options().apply {
                                    inSampleSize = 4
                                    inPreferredConfig = Bitmap.Config.ARGB_8888
                                }
                                val bmp = BitmapFactory.decodeFile(outputFile.absolutePath, opts) ?: return@withContext false
                                
                                // Subtract master dark if available
                                if (_hasMasterDark.value) {
                                    val previewDarkFile = File(context.filesDir, "calibration/master_dark_preview.png")
                                    if (previewDarkFile.exists()) {
                                        val darkBmp = BitmapFactory.decodeFile(previewDarkFile.absolutePath)
                                        if (darkBmp != null) {
                                            subtractDark(bmp, darkBmp)
                                            darkBmp.recycle()
                                        }
                                    }
                                }

                                val stars = starAligner.detectStars(bmp, starThreshold = settings.starThreshold, maxStars = 50)
                                if (stars.size < settings.minStarCount) {
                                    bmp.recycle()
                                    val msg = "Frame $totalCaptured: REJECTED. Detected ${stars.size} stars < minimum ${settings.minStarCount}."
                                    android.util.Log.w("AstroStack", msg)
                                    withContext(Dispatchers.IO) {
                                        diagWriter?.println(msg)
                                        diagWriter?.flush()
                                    }
                                    return@withContext false // Rejected
                                }

                                if (liveAccumulator == null || referenceStars == null) {
                                    // Init accumulator
                                    liveWidth = bmp.width
                                    liveHeight = bmp.height
                                    val size = liveWidth * liveHeight
                                    val pixels = IntArray(size)
                                    bmp.getPixels(pixels, 0, liveWidth, 0, 0, liveWidth, liveHeight)
                                    
                                    val floats = FloatArray(size * 3)
                                    for (i in 0 until size) {
                                        val pix = pixels[i]
                                        val r = ((pix shr 16) and 0xFF) / 255f
                                        val g = ((pix shr 8) and 0xFF) / 255f
                                        val b = (pix and 0xFF) / 255f
                                        floats[i * 3] = if (r <= 0.04045f) r / 12.92f else Math.pow(((r + 0.055) / 1.055), 2.4).toFloat()
                                        floats[i * 3 + 1] = if (g <= 0.04045f) g / 12.92f else Math.pow(((g + 0.055) / 1.055), 2.4).toFloat()
                                        floats[i * 3 + 2] = if (b <= 0.04045f) b / 12.92f else Math.pow(((b + 0.055) / 1.055), 2.4).toFloat()
                                    }
                                    
                                    liveAccumulator = floats
                                    referenceStars = stars
                                    liveStackedCount = 1
                                    
                                    val stretchBmp = Bitmap.createBitmap(liveWidth, liveHeight, Bitmap.Config.ARGB_8888)
                                    stretchBmp.setPixels(pixels, 0, liveWidth, 0, 0, liveWidth, liveHeight)
                                    val stretched = histogramStretch.autoStretch(stretchBmp)
                                    if (stretched !== stretchBmp) stretchBmp.recycle()
                                    
                                    _liveStackedBitmap.value = stretched
                                    bmp.recycle()

                                    val msg = "Frame $totalCaptured: INITIALIZED reference frame. Detected ${stars.size} stars."
                                    android.util.Log.d("AstroStack", msg)
                                    withContext(Dispatchers.IO) {
                                        diagWriter?.println(msg)
                                        diagWriter?.flush()
                                    }
                                } else {
                                    val offset = starAligner.computeTranslation(referenceStars!!, stars)
                                    val qual = starAligner.alignmentQuality(referenceStars!!, stars)
                                    val msg = "Frame $totalCaptured: ALIGNED. Offset = (${offset.x}, ${offset.y}), Match Quality = ${(qual * 100).toInt()}% (Detected ${stars.size} stars)."
                                    android.util.Log.d("AstroStack", msg)
                                    withContext(Dispatchers.IO) {
                                        diagWriter?.println(msg)
                                        diagWriter?.flush()
                                    }

                                    val alignedBmp = if (offset.x != 0f || offset.y != 0f) {
                                        val dst = Bitmap.createBitmap(liveWidth, liveHeight, Bitmap.Config.ARGB_8888)
                                        val canvas = android.graphics.Canvas(dst)
                                        val matrix = android.graphics.Matrix().also { it.setTranslate(offset.x, offset.y) }
                                        canvas.drawBitmap(bmp, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                                        dst
                                    } else {
                                        bmp
                                    }

                                    val size = liveWidth * liveHeight
                                    val pixels = IntArray(size)
                                    alignedBmp.getPixels(pixels, 0, liveWidth, 0, 0, liveWidth, liveHeight)
                                    
                                    val count = liveStackedCount
                                    val acc = liveAccumulator!!
                                    for (i in 0 until size) {
                                        val pix = pixels[i]
                                        val alpha = (pix shr 24) and 0xFF
                                        if (alpha == 0) continue

                                        val r = ((pix shr 16) and 0xFF) / 255f
                                        val g = ((pix shr 8) and 0xFF) / 255f
                                        val b = (pix and 0xFF) / 255f
                                        
                                        val lr = if (r <= 0.04045f) r / 12.92f else Math.pow(((r + 0.055) / 1.055), 2.4).toFloat()
                                        val lg = if (g <= 0.04045f) g / 12.92f else Math.pow(((g + 0.055) / 1.055), 2.4).toFloat()
                                        val lb = if (b <= 0.04045f) b / 12.92f else Math.pow(((b + 0.055) / 1.055), 2.4).toFloat()

                                        acc[i * 3] = (acc[i * 3] * count + lr) / (count + 1)
                                        acc[i * 3 + 1] = (acc[i * 3 + 1] * count + lg) / (count + 1)
                                        acc[i * 3 + 2] = (acc[i * 3 + 2] * count + lb) / (count + 1)
                                    }
                                    
                                    liveStackedCount++

                                    val displayPixels = IntArray(size)
                                    for (i in 0 until size) {
                                        val lr = acc[i * 3]
                                        val lg = acc[i * 3 + 1]
                                        val lb = acc[i * 3 + 2]
                                        
                                        val r = if (lr <= 0.0031308f) lr * 12.92f else 1.055f * Math.pow(lr.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
                                        val g = if (lg <= 0.0031308f) lg * 12.92f else 1.055f * Math.pow(lg.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
                                        val b = if (lb <= 0.0031308f) lb * 12.92f else 1.055f * Math.pow(lb.toDouble(), 1.0 / 2.4).toFloat() - 0.055f
                                        
                                        val ri = (r.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
                                        val gi = (g.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
                                        val bi = (b.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
                                        displayPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
                                    }
                                    
                                    val displayBmp = Bitmap.createBitmap(liveWidth, liveHeight, Bitmap.Config.ARGB_8888)
                                    displayBmp.setPixels(displayPixels, 0, liveWidth, 0, 0, liveWidth, liveHeight)
                                    
                                    val stretched = histogramStretch.autoStretch(displayBmp)
                                    if (stretched !== displayBmp) displayBmp.recycle()
                                    
                                    val oldBmp = _liveStackedBitmap.value
                                    _liveStackedBitmap.value = stretched
                                    oldBmp?.recycle()

                                    if (alignedBmp !== bmp) alignedBmp.recycle()
                                    bmp.recycle()
                                }
                                true
                            } catch (e: Exception) {
                                android.util.Log.e("AstroStack", "Error during live frame integration", e)
                                false
                            }
                        }

                        if (stackSuccess) {
                            totalStacked++
                        } else {
                            totalRejected++
                        }
                    }

                    // 3. Save DNG metadata or Delete from disk to preserve space
                    if (settings.saveAllPhotos) {
                        withContext(Dispatchers.IO) {
                            repository.addFrame(
                                sessionId = sessionId,
                                filePath = outputFile.absolutePath,
                                frameIndex = totalCaptured - 1,
                            )
                        }
                    } else {
                        // Delete frame file immediately since we don't save sub-frames
                        outputFile.delete()
                    }

                    // Push final frame statistics for UI reflection
                    _sessionState.value = CaptureSessionState.Capturing(
                        framesCaptured = totalCaptured,
                        framesStacked = totalStacked,
                        framesRejected = totalRejected,
                        currentFilePath = outputFile.absolutePath,
                    )
                }
            } catch (e: Exception) {
                _sessionState.value = CaptureSessionState.Error(
                    message = "Capture loop interrupted: ${e.message}",
                    cause = e,
                )
            } finally {
                withContext(Dispatchers.IO) {
                    try {
                        diagWriter?.println("Session ended. Integrated $totalStacked frames, rejected $totalRejected frames.")
                        diagWriter?.close()
                    } catch (e: Exception) {}
                }
            }
        }
    }

    /**
     * Runs calibration loop capturing 5 frames with lens covered, averages them, and saves Master Dark.
     */
    fun startDarkCalibration(settings: CaptureSettings) {
        if (_sessionState.value !is CaptureSessionState.Idle) return

        captureJob = scope.launch {
            _sessionState.value = CaptureSessionState.CalibratingDark(0, 5)
            val calDir = File(context.filesDir, "calibration").also { it.mkdirs() }
            val tempDir = File(context.filesDir, "calibration_temp").also { it.mkdirs() }
            val darkBitmaps = mutableListOf<Bitmap>()
            
            try {
                for (i in 1..5) {
                    if (coroutineContext[kotlinx.coroutines.Job]?.isActive != true) break
                    
                    _sessionState.value = CaptureSessionState.CalibratingDark(i - 1, 5)
                    val tempFile = File(tempDir, "temp_dark_$i.dng")
                    
                    // Capture RAW frame with lens covered
                    withContext(Dispatchers.IO) {
                        cameraManager.captureAndSaveDng(
                            settings = settings,
                            outputFile = tempFile,
                            onShutterCallback = {}
                        )
                    }

                    // Decode DNG frame
                    val decoded = withContext(Dispatchers.Default) {
                        val opts = BitmapFactory.Options().apply {
                            inPreferredConfig = Bitmap.Config.ARGB_8888
                        }
                        BitmapFactory.decodeFile(tempFile.absolutePath, opts)
                    }
                    if (decoded != null) {
                        darkBitmaps.add(decoded)
                    }
                    
                    // Cleanup temp file
                    tempFile.delete()
                }

                if (darkBitmaps.size >= 3) {
                    // Average the captured dark bitmaps
                    _sessionState.value = CaptureSessionState.CalibratingDark(5, 5)
                    withContext(Dispatchers.Default) {
                        val width = darkBitmaps[0].width
                        val height = darkBitmaps[0].height
                        val size = width * height
                        
                        val sumR = FloatArray(size)
                        val sumG = FloatArray(size)
                        val sumB = FloatArray(size)
                        
                        val pixels = IntArray(size)
                        for (bmp in darkBitmaps) {
                            bmp.getPixels(pixels, 0, width, 0, 0, width, height)
                            for (p in 0 until size) {
                                val pix = pixels[p]
                                sumR[p] += ((pix shr 16) and 0xFF).toFloat()
                                sumG[p] += ((pix shr 8) and 0xFF).toFloat()
                                sumB[p] += (pix and 0xFF).toFloat()
                            }
                        }

                        val avgPixels = IntArray(size)
                        val count = darkBitmaps.size.toFloat()
                        for (p in 0 until size) {
                            val r = (sumR[p] / count + 0.5f).toInt().coerceIn(0, 255)
                            val g = (sumG[p] / count + 0.5f).toInt().coerceIn(0, 255)
                            val b = (sumB[p] / count + 0.5f).toInt().coerceIn(0, 255)
                            avgPixels[p] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                        }

                        val masterDarkFull = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                        masterDarkFull.setPixels(avgPixels, 0, width, 0, 0, width, height)

                        // Save full resolution Master Dark as PNG
                        val fullFile = File(calDir, "master_dark_full.png")
                        java.io.FileOutputStream(fullFile).use { out ->
                            masterDarkFull.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }

                        // Create 1/4 scaled down version for live preview subtraction
                        val previewWidth = width / 4
                        val previewHeight = height / 4
                        val masterDarkPreview = Bitmap.createScaledBitmap(masterDarkFull, previewWidth, previewHeight, true)
                        val previewFile = File(calDir, "master_dark_preview.png")
                        java.io.FileOutputStream(previewFile).use { out ->
                            masterDarkPreview.compress(Bitmap.CompressFormat.PNG, 100, out)
                        }

                        masterDarkFull.recycle()
                        masterDarkPreview.recycle()
                    }

                    _hasMasterDark.value = true
                    _sessionState.value = CaptureSessionState.Idle
                } else {
                    _sessionState.value = CaptureSessionState.Error("Dark calibration failed: failed to decode captured frames.")
                }
            } catch (e: Exception) {
                _sessionState.value = CaptureSessionState.Error("Dark calibration failed: ${e.message}", e)
            } finally {
                darkBitmaps.forEach { it.recycle() }
                tempDir.deleteRecursively()
            }
        }
    }

    /**
     * Stops the continuous capture session and compiles the final high-resolution stacked image.
     */
    fun stopCaptureSession() {
        val job = captureJob
        captureJob = null
        job?.cancel()

        val state = _sessionState.value as? CaptureSessionState.Capturing ?: return
        val sessionId = currentSessionId ?: return
        val settings = lastSettings ?: return

        scope.launch {
            try {
                // Show loading indicator
                _previewState.value = PreviewState.Loading

                val finalBmp = if (settings.saveAllPhotos && settings.stackPhotos) {
                    // Full resolution stack from saved DNGs
                    val files = repository.getDngFilesForSession(sessionId)
                    if (files.size >= 2) {
                        withContext(Dispatchers.Default) {
                            val config = com.astrostack.app.stacking.StackingConfig(
                                driftHandling = settings.driftHandling,
                                minStarCount = settings.minStarCount,
                            )
                            ImageStacker(context, starAligner, histogramStretch).stack(
                                files = files,
                                config = config,
                                onProgress = {}
                            )
                        }
                    } else {
                        // Fallback to live stretched preview if not enough frames
                        _liveStackedBitmap.value?.copy(Bitmap.Config.ARGB_8888, true)
                            ?: throw Exception("No stacked preview available.")
                    }
                } else if (settings.stackPhotos) {
                    // Save in-memory live stacked preview
                    _liveStackedBitmap.value?.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw Exception("No stacked preview available.")
                } else {
                    // No stacking was requested; just exit without final result
                    _sessionState.value = CaptureSessionState.Idle
                    clearLiveStack()
                    return@launch
                }

                // Save final output PNG to local app storage
                val outFile = withContext(Dispatchers.IO) {
                    val dir = repository.getStackedOutputDir()
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val file = File(dir, "stacked_${sessionId}_$ts.png")
                    FileOutputStream(file).use { out ->
                        finalBmp.compress(Bitmap.CompressFormat.PNG, 100, out)
                    }
                    repository.saveStackedResult(
                        sessionId = sessionId,
                        imagePath = file.absolutePath,
                        algorithm = "LIVE_MEAN",
                    )
                    file
                }

                _sessionState.value = CaptureSessionState.Done(
                    sessionId = sessionId,
                    frameCount = liveStackedCount,
                )
            } catch (e: Exception) {
                _sessionState.value = CaptureSessionState.Error(
                    message = "Failed to compile final stack: ${e.message}",
                    cause = e,
                )
            }
        }
    }

    fun cancelSession() {
        val job = captureJob
        captureJob = null
        job?.cancel()
        _sessionState.value = CaptureSessionState.Idle
        clearLiveStack()
    }

    fun resetSessionState() {
        val job = captureJob
        captureJob = null
        job?.cancel()
        _sessionState.value = CaptureSessionState.Idle
        clearLiveStack()
    }

    private fun subtractDark(src: Bitmap, dark: Bitmap) {
        val width = src.width
        val height = src.height
        if (dark.width != width || dark.height != height) return
        
        val size = width * height
        val srcPixels = IntArray(size)
        val darkPixels = IntArray(size)
        src.getPixels(srcPixels, 0, width, 0, 0, width, height)
        dark.getPixels(darkPixels, 0, width, 0, 0, width, height)
        
        for (i in 0 until size) {
            val sp = srcPixels[i]
            val dp = darkPixels[i]
            
            val sr = (sp shr 16) and 0xFF
            val sg = (sp shr 8) and 0xFF
            val sb = sp and 0xFF
            
            val dr = (dp shr 16) and 0xFF
            val dg = (dp shr 8) and 0xFF
            val db = dp and 0xFF
            
            val r = (sr - dr).coerceAtLeast(0)
            val g = (sg - dg).coerceAtLeast(0)
            val b = (sb - db).coerceAtLeast(0)
            
            srcPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        src.setPixels(srcPixels, 0, width, 0, 0, width, height)
    }

    private fun clearLiveStack() {
        val oldBmp = _liveStackedBitmap.value
        _liveStackedBitmap.value = null
        oldBmp?.recycle()
    }
}

