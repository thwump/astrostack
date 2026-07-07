package com.astrostack.app.camera

import android.hardware.camera2.CameraCharacteristics
import com.astrostack.app.stacking.DriftHandling
import kotlin.math.roundToInt

// ─── Capture settings sent to Camera2 ────────────────────────────────────────

data class CaptureSettings(
    /** Exposure time in nanoseconds. 1 s = 1_000_000_000 ns */
    val exposureTimeNs: Long = 4_000_000_000L, // 4 seconds default
    /** ISO sensitivity (e.g. 800, 1600, 3200) */
    val iso: Int = 1600,
    /** Whether to save all captured raw frames permanently */
    val saveAllPhotos: Boolean = false,
    /** Whether to run live aligned stacking */
    val stackPhotos: Boolean = true,
    /** Disable optical image stabilisation — use a tripod instead */
    val disableOis: Boolean = true,
    /** Manual white balance gains [R, Gr, Gb, B] — null = keep camera default */
    val wbGains: FloatArray? = null,
    /** Auto focus mode enabled (e.g. for daytime tests) */
    val autoFocus: Boolean = false,
    /** Star detection threshold (lower means more sensitive, e.g. 50-100 for screens) */
    val starThreshold: Int = 180,
    /** Minimum stars required to align and stack the frame */
    val minStarCount: Int = 5,
    /** Alignment drift handling: None, Crop, or Mosaic */
    val driftHandling: DriftHandling = DriftHandling.CROP,
    val stretchType: StretchType = StretchType.HISTOGRAM,
    val enableGradientRemoval: Boolean = false,
)

enum class StretchType {
    HISTOGRAM,
    ARCSINH
}

// ─── Exposure presets (shutter speed, ISO pairs) ─────────────────────────────

data class ExposurePreset(
    val label: String,
    val exposureTimeNs: Long,
    val iso: Int,
)

val EXPOSURE_PRESETS = listOf(
    ExposurePreset("1s / ISO 800",  1_000_000_000L,  800),
    ExposurePreset("2s / ISO 800",  2_000_000_000L,  800),
    ExposurePreset("4s / ISO 1600", 4_000_000_000L, 1600),
    ExposurePreset("8s / ISO 1600", 8_000_000_000L, 1600),
    ExposurePreset("15s / ISO 3200",15_000_000_000L, 3200),
    ExposurePreset("30s / ISO 3200",30_000_000_000L, 3200),
)

/**
 * Discrete exposure time steps for the manual slider.
 * Spans from fast indoor/test shutter speeds up to long astrophotography exposures.
 */
val EXPOSURE_TIME_STEPS: List<Pair<Long, String>> = listOf(
    1_000_000L       to "1/1000s",
    2_000_000L       to "1/500s",
    4_000_000L       to "1/250s",
    10_000_000L      to "1/100s",
    16_666_667L      to "1/60s",
    33_333_333L      to "1/30s",
    66_666_667L      to "1/15s",
    125_000_000L     to "1/8s",
    250_000_000L     to "1/4s",
    500_000_000L     to "1/2s",
    1_000_000_000L   to "1s",
    2_000_000_000L   to "2s",
    4_000_000_000L   to "4s",
    8_000_000_000L   to "8s",
    15_000_000_000L  to "15s",
    30_000_000_000L  to "30s",
)

/** Discrete ISO steps for the manual slider. */
val ISO_STEPS = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400, 12800)

// ─── Camera capability query result ──────────────────────────────────────────

data class CameraCapabilities(
    val cameraId: String,
    val supportsRaw: Boolean,
    val minExposureNs: Long,
    val maxExposureNs: Long,
    val maxIso: Int,
    val minIso: Int,
    val rawSensorWidth: Int,
    val rawSensorHeight: Int,
    val hasOis: Boolean,
    val characteristics: CameraCharacteristics,
    val supportsNightExtension: Boolean,
) {
    val userLabel: String get() {
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focalLengths?.firstOrNull() ?: 4.0f
        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
        val label = if (physicalSize != null) {
            val sensorWidth = physicalSize.width
            val sensorHeight = physicalSize.height
            val sensorDiagonal = kotlin.math.sqrt((sensorWidth * sensorWidth + sensorHeight * sensorHeight).toDouble()).toFloat()
            val diagonal35mm = kotlin.math.sqrt(36.0 * 36.0 + 24.0 * 24.0).toFloat()
            val cropFactor = diagonal35mm / sensorDiagonal
            val focalLength35 = (focalLength * cropFactor).roundToInt()
            when {
                focalLength35 < 20 -> "Ultrawide (${focalLength35}mm eq.)"
                focalLength35 in 20..35 -> "Main (${focalLength35}mm eq.)"
                else -> "Telephoto (${focalLength35}mm eq.)"
            }
        } else {
            "Camera $cameraId"
        }
        return label
    }
}

// ─── Ongoing session state ────────────────────────────────────────────────────

sealed interface CaptureSessionState {
    data object Idle : CaptureSessionState
    data class Capturing(
        val framesCaptured: Int,
        val framesStacked: Int,
        val framesRejected: Int,
        val currentFilePath: String,
    ) : CaptureSessionState
    data class Done(val sessionId: Long, val frameCount: Int) : CaptureSessionState
    data class Error(val message: String, val cause: Throwable? = null) : CaptureSessionState
    data class CalibratingDark(val framesCaptured: Int, val totalFrames: Int) : CaptureSessionState
    data class CalibratingFlat(val framesCaptured: Int, val totalFrames: Int) : CaptureSessionState
}

// ─── Live preview state ───────────────────────────────────────────────────────

sealed interface PreviewState {
    data object Loading : PreviewState
    data object Active : PreviewState
    data object NoCameraFound : PreviewState
    data class Error(val message: String) : PreviewState
}

// ─── Tripod Exposure Limit Calculations ────────────────────────────────────────

fun calculateTripodExposureLimits(characteristics: CameraCharacteristics?): Pair<Float, Float>? {
    if (characteristics == null) return null
    try {
        val focalLengths = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
        val focalLength = focalLengths?.firstOrNull() ?: return null

        val physicalSize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
        val pixelArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE) ?: return null

        val sensorWidth = physicalSize.width
        val sensorHeight = physicalSize.height
        val pixelWidth = pixelArraySize.width

        val sensorDiagonal = kotlin.math.sqrt((sensorWidth * sensorWidth + sensorHeight * sensorHeight).toDouble()).toFloat()
        val diagonal35mm = kotlin.math.sqrt(36.0 * 36.0 + 24.0 * 24.0).toFloat() // ~43.27
        val cropFactor = diagonal35mm / sensorDiagonal
        val focalLength35 = focalLength * cropFactor

        // 500 Rule
        val rule500 = 500.0f / focalLength35

        // NPF Rule: (35 * aperture + 30 * pixelPitchMicrons) / focalLength
        val apertures = characteristics.get(CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES)
        val aperture = apertures?.firstOrNull() ?: 1.8f
        val pixelPitch = (sensorWidth * 1000.0f) / pixelWidth
        val npfRule = (35.0f * aperture + 30.0f * pixelPitch) / focalLength

        return Pair(rule500, npfRule)
    } catch (e: Exception) {
        return null
    }
}

