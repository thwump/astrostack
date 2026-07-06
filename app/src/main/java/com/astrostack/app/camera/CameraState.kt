package com.astrostack.app.camera

import android.hardware.camera2.CameraCharacteristics

// ─── Capture settings sent to Camera2 ────────────────────────────────────────

data class CaptureSettings(
    /** Exposure time in nanoseconds. 1 s = 1_000_000_000 ns */
    val exposureTimeNs: Long = 4_000_000_000L, // 4 seconds default
    /** ISO sensitivity (e.g. 800, 1600, 3200) */
    val iso: Int = 1600,
    /** Number of RAW frames to capture for this session */
    val frameCount: Int = 10,
    /** Disable optical image stabilisation — use a tripod instead */
    val disableOis: Boolean = true,
    /** Manual white balance gains [R, Gr, Gb, B] — null = keep camera default */
    val wbGains: FloatArray? = null,
)

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
)

// ─── Ongoing session state ────────────────────────────────────────────────────

sealed interface CaptureSessionState {
    data object Idle : CaptureSessionState
    data class Capturing(
        val framesCompleted: Int,
        val framesTotal: Int,
        val currentFilePath: String,
    ) : CaptureSessionState
    data class Done(val sessionId: Long, val frameCount: Int) : CaptureSessionState
    data class Error(val message: String, val cause: Throwable? = null) : CaptureSessionState
}

// ─── Live preview state ───────────────────────────────────────────────────────

sealed interface PreviewState {
    data object Loading : PreviewState
    data object Active : PreviewState
    data object NoCameraFound : PreviewState
    data class Error(val message: String) : PreviewState
}
