package com.astrostack.app.stacking

import com.astrostack.app.camera.StretchType

/**
 * Available frame-stacking algorithms.
 *
 * All algorithms operate on linear-light float arrays (not gamma-encoded).
 * The pipeline is:
 *  1. Load DNG → decode to ARGB Bitmap
 *  2. Convert to linear float [0, 1] (undo sRGB gamma)
 *  3. Align frames (translate to match reference)
 *  4. Stack frames using selected algorithm
 *  5. Apply histogram stretch
 *  6. Re-apply sRGB gamma → save as PNG/TIFF
 */
enum class StackingAlgorithm(val displayName: String, val description: String) {
    /**
     * Mean (average) stack — simple, fast. Reduces noise by √N.
     * Vulnerable to satellites and airplane trails.
     */
    MEAN(
        displayName = "Mean",
        description = "Average all frames. Best SNR gain but sensitive to outliers."
    ),

    /**
     * Median stack — slower than mean but immune to single-frame artefacts
     * (satellite trails, hot pixels, cosmic rays). Requires ≥ 3 frames.
     */
    MEDIAN(
        displayName = "Median",
        description = "Middle value per pixel. Rejects satellite trails automatically."
    ),

    /**
     * Sigma-clipping — iteratively rejects pixels that deviate more than
     * κ×σ from the mean. Excellent balance of noise reduction and artefact
     * rejection. Recommended for most targets. Requires ≥ 5 frames.
     */
    SIGMA_CLIPPING(
        displayName = "Sigma Clipping",
        description = "Rejects outliers beyond κ·σ. Best all-round algorithm."
    ),

    /**
     * Winsorized sigma-clipping — like sigma-clipping but replaces rejected
     * values with the clipping boundary instead of removing them. Preserves
     * SNR better when few frames are available (≥ 3).
     */
    WINSORIZED_SIGMA(
        displayName = "Winsorized Sigma",
        description = "Sigma clip + replace outliers at boundary. Good for ≥3 frames."
    ),

    /**
     * Maximum value stack — keeps the brightest pixel at each position.
     * Useful for star trails or comet imaging. Does NOT reduce noise.
     */
    MAXIMUM(
        displayName = "Maximum",
        description = "Keeps brightest pixel per position. Use for star trails."
    ),
}

enum class DriftHandling(val displayName: String, val description: String) {
    NONE(
        displayName = "None",
        description = "Keep original boundaries (out-of-bounds area ignored)."
    ),
    CROP(
        displayName = "Crop",
        description = "Crop to the overlapping region common to all frames."
    ),
    MOSAIC(
        displayName = "Mosaic",
        description = "Expand canvas to fit all shifted frames."
    )
}

/**
 * Configuration for a stacking run.
 */
data class StackingConfig(
    val algorithm: StackingAlgorithm = StackingAlgorithm.SIGMA_CLIPPING,
    /** κ multiplier for sigma-clipping algorithms. Default 2.0 for most skies. */
    val kappa: Float = 2.0f,
    /** Number of sigma-clipping iterations. */
    val sigmaIterations: Int = 3,
    /** Whether to run star alignment before stacking. */
    val alignFrames: Boolean = true,
    /** Downsample factor during stacking to reduce memory use (1 = full res). */
    val subsampleFactor: Int = 1,
    /** Number of horizontal tile strips to process at once (reduces peak RAM). */
    val tileStripCount: Int = 8,
    /**
     * Skip the automatic histogram stretch at the end of the pipeline.
     * Useful for testing (known pixel values) or when manual stretch is preferred.
     */
    val skipStretch: Boolean = false,
    /** Drift handling mode (Crop, Mosaic, None) */
    val driftHandling: DriftHandling = DriftHandling.CROP,
    /** Minimum number of stars required to stack a frame (quality rejection). */
    val minStarCount: Int = 8,
    /** Star detection threshold sensitivity [20, 255] */
    val starThreshold: Int = 180,
    val stretchType: StretchType = StretchType.HISTOGRAM,
    val enableGradientRemoval: Boolean = false,
)

