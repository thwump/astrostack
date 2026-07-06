package com.astrostack.app.stacking

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Star detection and translational frame alignment.
 *
 * Algorithm overview:
 *  1. Convert to grayscale luma.
 *  2. Find local maxima above [starThreshold] (potential star centroids).
 *  3. Refine centroid using a 5×5 intensity-weighted window.
 *  4. [computeTranslation]: for each reference star, find its nearest
 *     counterpart in the target frame. Collect offsets, take the median
 *     (robust to mismatches from variable stars or hot pixels).
 *
 * Limitations:
 *  - Translation-only alignment (no rotation / scale). Suitable for
 *    polar-aligned mounts with small drifts between frames.
 *  - For rotation support, extend to use triangle-similarity matching.
 */
class StarAligner @Inject constructor() {

    data class Star(val x: Float, val y: Float, val brightness: Float)
    data class Offset(val x: Float, val y: Float)

    // ─── Detection ────────────────────────────────────────────────────────────

    /**
     * Detect stars in [bitmap] and return them sorted by brightness (brightest first).
     *
     * @param starThreshold Normalised [0,255] luma threshold. Stars must exceed this.
     * @param minDistance   Minimum pixel distance between two separate star centroids.
     * @param maxStars      Cap on returned stars (keeps only brightest).
     */
    suspend fun detectStars(
        bitmap: Bitmap,
        starThreshold: Int = 180,
        minDistance: Int = 10,
        maxStars: Int = 100,
    ): List<Star> = withContext(Dispatchers.Default) {
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

        // Convert to luma (integer, 0..255)
        val luma = ByteArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            // Rec.709 luma
            luma[i] = ((0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)).toByte()
        }

        // Find local maxima
        val candidates = mutableListOf<Star>()
        val halfWin = 5

        for (y in halfWin until height - halfWin) {
            for (x in halfWin until width - halfWin) {
                val v = luma[y * width + x].toInt() and 0xFF
                if (v < starThreshold) continue
                if (!isLocalMax(luma, x, y, width, halfWin)) continue

                // Weighted centroid over 5×5 window
                var sumX = 0.0
                var sumY = 0.0
                var sumW = 0.0
                for (dy in -2..2) for (dx in -2..2) {
                    val w = (luma[(y + dy) * width + (x + dx)].toInt() and 0xFF).toDouble()
                    sumX += (x + dx) * w
                    sumY += (y + dy) * w
                    sumW += w
                }
                if (sumW > 0) {
                    candidates.add(Star((sumX / sumW).toFloat(), (sumY / sumW).toFloat(), v.toFloat()))
                }
            }
        }

        // Non-maximum suppression (remove stars too close together)
        val filtered = suppress(candidates, minDistance.toFloat())
        filtered.sortedByDescending { it.brightness }.take(maxStars)
    }

    private fun isLocalMax(luma: ByteArray, cx: Int, cy: Int, width: Int, radius: Int): Boolean {
        val center = luma[cy * width + cx].toInt() and 0xFF
        for (dy in -radius..radius) for (dx in -radius..radius) {
            if (dx == 0 && dy == 0) continue
            val neighbor = luma[(cy + dy) * width + (cx + dx)].toInt() and 0xFF
            if (neighbor >= center) return false
        }
        return true
    }

    private fun suppress(stars: List<Star>, minDist: Float): List<Star> {
        val kept = mutableListOf<Star>()
        outer@ for (candidate in stars.sortedByDescending { it.brightness }) {
            for (existing in kept) {
                val dx = existing.x - candidate.x
                val dy = existing.y - candidate.y
                if (sqrt(dx * dx + dy * dy) < minDist) continue@outer
            }
            kept.add(candidate)
        }
        return kept
    }

    // ─── Alignment ────────────────────────────────────────────────────────────

    /**
     * Compute the translational offset that maps [targetStars] onto [referenceStars].
     *
     * Strategy: for each reference star, find the nearest target star within a
     * search radius. Collect (dx, dy) pairs and return the median — this is
     * robust against a few mismatched pairs.
     *
     * Returns [Offset] (0, 0) if fewer than 3 pairs are found.
     */
    fun computeTranslation(
        referenceStars: List<Star>,
        targetStars: List<Star>,
        searchRadius: Float = 50f,
    ): Offset {
        if (referenceStars.isEmpty() || targetStars.isEmpty()) return Offset(0f, 0f)

        val dxList = mutableListOf<Float>()
        val dyList = mutableListOf<Float>()

        for (ref in referenceStars) {
            val nearest = targetStars.minByOrNull { t ->
                val dx = t.x - ref.x; val dy = t.y - ref.y; dx * dx + dy * dy
            } ?: continue

            val dist = sqrt((nearest.x - ref.x).let { it * it } + (nearest.y - ref.y).let { it * it })
            if (dist <= searchRadius) {
                dxList.add(nearest.x - ref.x)
                dyList.add(nearest.y - ref.y)
            }
        }

        if (dxList.size < 3) return Offset(0f, 0f)

        return Offset(median(dxList), median(dyList))
    }

    /** Quality score [0, 1]: ratio of matched pairs to reference stars. */
    fun alignmentQuality(
        referenceStars: List<Star>,
        targetStars: List<Star>,
        searchRadius: Float = 50f,
    ): Float {
        if (referenceStars.isEmpty()) return 0f
        val offset = computeTranslation(referenceStars, targetStars, searchRadius)
        var matches = 0
        for (ref in referenceStars) {
            val aligned = targetStars.minByOrNull { t ->
                abs(t.x - ref.x - offset.x) + abs(t.y - ref.y - offset.y)
            } ?: continue
            val d = sqrt((aligned.x - ref.x - offset.x).let { it * it } +
                    (aligned.y - ref.y - offset.y).let { it * it })
            if (d < searchRadius * 0.3f) matches++
        }
        return matches.toFloat() / referenceStars.size
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun median(list: List<Float>): Float {
        val sorted = list.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }
}
