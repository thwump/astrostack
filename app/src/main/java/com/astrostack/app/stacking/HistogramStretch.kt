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

        // Apply automatic background sky neutralization to eliminate purple/pink/green casts
        neutralizeBackground(r, g, b)

        // Compute stretch parameters from LUMINANCE
        val luma = FloatArray(pixels.size) { i ->
            0.299f * r[i] + 0.587f * g[i] + 0.114f * b[i]
        }
        val params = computeStretchParams(luma, shadowClip)
            ?: return bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)

        // Apply the stretch to every channel
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
     * Arcsinh color-preserving stretch with background neutralization.
     */
    fun arcsinhStretch(bitmap: Bitmap, shadowClip: Float = 1.5f, stretchFactor: Float = 50f): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        val r = FloatArray(pixels.size)
        val g = FloatArray(pixels.size)
        val b = FloatArray(pixels.size)

        for (i in pixels.indices) {
            r[i] = ((pixels[i] shr 16) and 0xFF) / 255f
            g[i] = ((pixels[i] shr 8) and 0xFF) / 255f
            b[i] = (pixels[i] and 0xFF) / 255f
        }

        neutralizeBackground(r, g, b)

        val luma = FloatArray(pixels.size) { i ->
            0.2126f * r[i] + 0.7152f * g[i] + 0.0722f * b[i]
        }

        val med = median(luma)
        val mad = mad(luma, med)
        val blackPoint = max(0f, med - shadowClip * mad)

        val asinhFactor = asinh(stretchFactor.toDouble())
        val outPixels = IntArray(pixels.size)

        for (i in pixels.indices) {
            val l = max(0f, luma[i] - blackPoint)
            if (l <= 0f) {
                outPixels[i] = 0xFF shl 24 // black
                continue
            }

            val lStretched = (asinh(l.toDouble() * stretchFactor) / asinhFactor).toFloat().coerceIn(0f, 1f)
            val ratio = lStretched / l

            val rc = max(0f, r[i] - blackPoint) * ratio
            val gc = max(0f, g[i] - blackPoint) * ratio
            val bc = max(0f, b[i] - blackPoint) * ratio

            val ri = (rc.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            val gi = (gc.coerceIn(0f, 1f) * 255 + 0.5f).toInt()
            val bi = (bc.coerceIn(0f, 1f) * 255 + 0.5f).toInt()

            outPixels[i] = (0xFF shl 24) or (ri shl 16) or (gi shl 8) or bi
        }

        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        out.setPixels(outPixels, 0, width, 0, 0, width, height)
        return out
    }

    /**
     * Equalizes median background intensities across R, G, and B channels to neutralize sky color casts.
     */
    fun neutralizeBackground(r: FloatArray, g: FloatArray, b: FloatArray) {
        val medR = median(r)
        val medG = median(g)
        val medB = median(b)
        val targetMed = medG.coerceAtLeast(1e-4f)

        val scaleR = targetMed / max(medR, 1e-4f)
        val scaleB = targetMed / max(medB, 1e-4f)

        for (i in r.indices) {
            r[i] = (r[i] * scaleR).coerceIn(0f, 1f)
            b[i] = (b[i] * scaleB).coerceIn(0f, 1f)
        }
    }

    private fun asinh(x: Double): Double {
        return kotlin.math.ln(x + kotlin.math.sqrt(x * x + 1.0))
    }

    // ─── Internal helpers ─────────────────────────────────────────────────────

    private data class StretchParams(val blackPoint: Float, val midtone: Float, val range: Float)

    private fun computeStretchParams(channel: FloatArray, shadowClip: Float): StretchParams? {
        val med = median(channel)
        val mad = mad(channel, med)

        if (mad < 1e-4f) return null

        val blackPoint = max(0f, med - shadowClip * mad)
        val range = 1f - blackPoint

        if (range < 1e-3f) return null

        val midtone = calculateMidtone(med - blackPoint)
        if (midtone <= 0f || !midtone.isFinite()) return null

        return StretchParams(blackPoint, midtone, range)
    }

    private fun applyMtf(x: Float, midtone: Float): Float {
        if (x == 0f) return 0f
        if (x == 1f) return 1f
        if (midtone == 0.5f) return x
        return ((midtone - 1) * x) / ((2 * midtone - 1) * x - midtone)
    }

    private fun calculateMidtone(normalizedMedian: Float): Float {
        if (normalizedMedian <= 0f) return 0.5f
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
}
