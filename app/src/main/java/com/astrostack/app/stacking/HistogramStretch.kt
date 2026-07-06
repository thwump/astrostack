package com.astrostack.app.stacking

import android.graphics.Bitmap
import javax.inject.Inject
import kotlin.math.max
import kotlin.math.min

/**
 * Histogram stretching / tone-mapping for deep-sky images.
 *
 * After stacking, the result typically has very low contrast — most of the
 * interesting signal is bunched at the dark end of the histogram.
 * Stretching redistributes pixel values across the full output range.
 */
class HistogramStretch @Inject constructor() {

    /**
     * Automatic midtone stretch (similar to PixInsight's AutoSTF).
     *
     * Steps:
     *  1. Compute per-channel median and normalised MAD (median absolute deviation).
     *  2. Set black point at median − [shadowClip] × MAD.
     *  3. Compute midtone transfer function (MTF) value targeting a midtone of 0.25.
     *  4. Apply black-point clip → MTF → white-clip.
     *
     * @param bitmap  Source bitmap (will not be modified).
     * @param shadowClip  Number of MADs below median for the black point.
     * @return A new stretched bitmap.
     */
    fun autoStretch(bitmap: Bitmap, shadowClip: Float = 1.5f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Separate channels into float arrays [0, 1]
        val r = FloatArray(pixels.size)
        val g = FloatArray(pixels.size)
        val b = FloatArray(pixels.size)
        for (i in pixels.indices) {
            r[i] = ((pixels[i] shr 16) and 0xFF) / 255f
            g[i] = ((pixels[i] shr 8) and 0xFF) / 255f
            b[i] = (pixels[i] and 0xFF) / 255f
        }

        // Compute stretch parameters from LUMINANCE so that R, G, B all receive
        // the SAME black-point and midtone.  This preserves colour ratios and
        // prevents per-channel clipping artefacts (e.g. blue going to zero under
        // warm indoor lighting when channels have different distributions).
        val luma = FloatArray(pixels.size) { i ->
            0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }
        val params = computeStretchParams(luma, shadowClip)
            ?: return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        // Apply the shared stretch to every channel
        for (ch in arrayOf(r, g, b)) {
            for (i in ch.indices) {
                val v = ((ch[i] - params.blackPoint) / params.range).coerceIn(0f, 1f)
                ch[i] = applyMtf(v, params.midtone)
            }
        }

        // Write back to ARGB
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(pixels.size)
        for (i in pixels.indices) {
            val ri = (r[i].coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            val gi = (g[i].coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            val bi = (b[i].coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            outPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }
        out.setPixels(outPixels, 0, width, 0, 0, width, height)
        return out
    }

    /**
     * Manual stretch with explicit black, midtone, and white points.
     *
     * @param blackPoint  Values ≤ this map to 0 (normalised [0,1]).
     * @param midtone     Target output value for the input midtone (gamma).
     * @param whitePoint  Values ≥ this map to 1 (normalised [0,1]).
     */
    fun manualStretch(
        bitmap: Bitmap,
        blackPoint: Float = 0.0f,
        midtone: Float = 0.5f,
        whitePoint: Float = 1.0f,
    ): Bitmap {
        require(blackPoint < whitePoint) { "blackPoint must be < whitePoint" }
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val outPixels = IntArray(pixels.size)
        val range = whitePoint - blackPoint

        for (i in pixels.indices) {
            val ri = applyMtf(((((pixels[i] shr 16) and 0xFF) / 255f - blackPoint) / range).coerceIn(0f, 1f), midtone)
            val gi = applyMtf(((((pixels[i] shr 8) and 0xFF) / 255f - blackPoint) / range).coerceIn(0f, 1f), midtone)
            val bi = applyMtf((((pixels[i] and 0xFF) / 255f - blackPoint) / range).coerceIn(0f, 1f), midtone)

            outPixels[i] = (0xFF shl 24) or
                    ((ri * 255 + 0.5f).toInt() shl 16) or
                    ((gi * 255 + 0.5f).toInt() shl 8) or
                    (bi * 255 + 0.5f).toInt()
        }
        out.setPixels(outPixels, 0, width, 0, 0, width, height)
        return out
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    /** Encapsulates the three numbers needed to apply a stretch. */
    private data class StretchParams(val blackPoint: Float, val midtone: Float, val range: Float)

    /**
     * Compute black-point, midtone and range for [channel].
     * Returns null for degenerate inputs (uniform, white-clipped, or bad midtone).
     */
    private fun computeStretchParams(channel: FloatArray, shadowClip: Float): StretchParams? {
        val med = median(channel)
        val mad = mad(channel, med)

        // Guard 1: uniform / nearly-uniform (MAD ≈ 0) → nothing to stretch.
        if (mad < 1e-4f) return null

        val blackPoint = max(0f, med - shadowClip * mad)
        val range = 1f - blackPoint

        // Guard 2: white-clipped (range → 0) → would cause x/0 = NaN.
        if (range < 1e-3f) return null

        val midtone = calculateMidtone(med - blackPoint)

        // Guard 3: degenerate midtone from calculateMidtone.
        if (midtone <= 0f || !midtone.isFinite()) return null

        return StretchParams(blackPoint, midtone, range)
    }

    /** Per-channel stretch (used only for manual/LUT paths; autoStretch uses luminance-linked). */
    private fun stretchChannel(channel: FloatArray, shadowClip: Float) {
        val p = computeStretchParams(channel, shadowClip) ?: return
        for (i in channel.indices) {
            val v = ((channel[i] - p.blackPoint) / p.range).coerceIn(0f, 1f)
            channel[i] = applyMtf(v, p.midtone)
        }
    }

    /**
     * Midtone transfer function (MTF).
     * Maps input [x] through a gamma-like curve targeting [midtone].
     */
    private fun applyMtf(x: Float, midtone: Float): Float {
        if (x == 0f) return 0f
        if (x == 1f) return 1f
        if (midtone == 0.5f) return x
        return ((midtone - 1) * x) / ((2 * midtone - 1) * x - midtone)
    }

    /**
     * Compute auto-MTF midtone value targeting output of 0.25 (keeps
     * background dark, stretches faint nebulosity up).
     */
    private fun calculateMidtone(normalizedMedian: Float): Float {
        if (normalizedMedian <= 0f) return 0.5f
        // Solve for m: 0.25 = MTF(normalizedMedian, m)
        // m = normalizedMedian / (2 * normalizedMedian - 1 + 4 * normalizedMedian / 1)
        // Simplified target: use 0.25 as the desired midpoint output
        val target = 0.25f
        val x = normalizedMedian.coerceIn(0.0001f, 0.9999f)
        return (target * x) / ((2 * target - 1) * x + target)
    }

    private fun median(arr: FloatArray): Float {
        val sorted = arr.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    private fun mad(arr: FloatArray, median: Float): Float {
        val deviations = FloatArray(arr.size) { Math.abs(arr[it] - median) }
        return median(deviations)
    }

    // ─── Build an 8-bit LUT for fast per-pixel application ────────────────────

    fun buildStretchLut(blackPoint: Float, midtone: Float, whitePoint: Float): IntArray {
        val lut = IntArray(256)
        val range = whitePoint - blackPoint
        for (i in 0..255) {
            val v = ((i / 255f - blackPoint) / range).coerceIn(0f, 1f)
            lut[i] = (applyMtf(v, midtone) * 255 + 0.5f).toInt().coerceIn(0, 255)
        }
        return lut
    }

    /** Apply a pre-built [lut] to every channel of [bitmap]. */
    fun applyLut(bitmap: Bitmap, lut: IntArray): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        for (i in pixels.indices) {
            val r = lut[(pixels[i] shr 16) and 0xFF]
            val g = lut[(pixels[i] shr 8) and 0xFF]
            val b = lut[pixels[i] and 0xFF]
            pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(pixels, 0, width, 0, 0, width, height)
        return out
    }
}
