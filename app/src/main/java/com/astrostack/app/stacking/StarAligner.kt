package com.astrostack.app.stacking

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.max
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
    data class RigidTransform(val tx: Float, val ty: Float, val angleRad: Float)

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
        starThreshold: Int = -1,
        minDistance: Int = 8,
        maxStars: Int = 100,
        skyMask: FloatArray? = null,
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
            luma[i] = ((0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)).toByte()
        }

        // Adaptive thresholding: pick 98.5th percentile of luma if threshold not specified
        val actualThreshold = if (starThreshold < 0) {
            val sortedLuma = luma.map { it.toInt() and 0xFF }.sorted()
            val p98 = sortedLuma[(sortedLuma.size * 0.985).toInt().coerceIn(0, sortedLuma.size - 1)]
            p98.coerceAtLeast(40)
        } else {
            starThreshold
        }

        // Find local maxima
        val candidates = mutableListOf<Star>()
        val halfWin = 3

        for (y in halfWin until height - halfWin) {
            for (x in halfWin until width - halfWin) {
                if (skyMask != null && skyMask[y * width + x] < 0.5f) continue
                val v = luma[y * width + x].toInt() and 0xFF
                if (v < actualThreshold) continue
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

        // Non-maximum suppression
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
     * Compute the translational offset mapping [targetStars] onto [referenceStars]
     * using a 2D star-pair displacement histogram matching algorithm.
     */
    fun computeTranslation(
        referenceStars: List<Star>,
        targetStars: List<Star>,
        searchRadius: Float = 250f,
    ): Offset {
        if (referenceStars.isEmpty() || targetStars.isEmpty()) return Offset(0f, 0f)

        // 1. Calculate displacement vectors for all star pair combinations
        val dxList = mutableListOf<Float>()
        val dyList = mutableListOf<Float>()

        for (ref in referenceStars) {
            for (t in targetStars) {
                val dx = t.x - ref.x
                val dy = t.y - ref.y
                if (abs(dx) <= searchRadius && abs(dy) <= searchRadius) {
                    dxList.add(dx)
                    dyList.add(dy)
                }
            }
        }

        if (dxList.isEmpty()) return Offset(0f, 0f)

        // 2. 2D displacement histogram binning (4px bin size)
        val binSize = 4f
        val modeMap = mutableMapOf<Pair<Int, Int>, Int>()
        for (i in dxList.indices) {
            val binX = (dxList[i] / binSize).toInt()
            val binY = (dyList[i] / binSize).toInt()
            val key = Pair(binX, binY)
            modeMap[key] = (modeMap[key] ?: 0) + 1
        }

        val bestBin = modeMap.maxByOrNull { it.value }?.key ?: return Offset(0f, 0f)
        val targetBinX = bestBin.first
        val targetBinY = bestBin.second

        // 3. Take robust median of all displacement inliers within winning bin
        val inlierDx = mutableListOf<Float>()
        val inlierDy = mutableListOf<Float>()
        for (i in dxList.indices) {
            val binX = (dxList[i] / binSize).toInt()
            val binY = (dyList[i] / binSize).toInt()
            if (binX == targetBinX && binY == targetBinY) {
                inlierDx.add(dxList[i])
                inlierDy.add(dyList[i])
            }
        }

        if (inlierDx.size < 2) return Offset(0f, 0f)

        return Offset(median(inlierDx), median(inlierDy))
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

    // ─── Rigid Transform Estimation (Translation + Rotation) ──────────────────

    /**
     * Estimate rigid transformation (translation and rotation) mapping [targetStars] to [referenceStars].
     * Uses closed-form covariance solver over centroids, with RANSAC-like outlier rejection.
     */
    /**
     * Estimate rigid transformation (translation and rotation) mapping [targetStars] to [referenceStars].
     * Uses 2D pair displacement histogram for initial translation, followed by closed-form SVD solver for rotation.
     */
    fun estimateRigidTransform(
        referenceStars: List<Star>,
        targetStars: List<Star>,
        width: Int,
        height: Int,
        searchRadius: Float = 250f,
    ): RigidTransform {
        if (referenceStars.isEmpty() || targetStars.isEmpty()) return RigidTransform(0f, 0f, 0f)

        val cx = width / 2f
        val cy = height / 2f

        // Step 1: Compute coarse global translation using 2D pair displacement histogram
        val initialTrans = computeTranslation(referenceStars, targetStars, searchRadius)

        // Step 2: Establish matches with initial translation applied
        val matches = mutableListOf<Pair<Star, Star>>()
        val matchRadius = 15f
        for (ref in referenceStars) {
            val shiftedRefX = ref.x + initialTrans.x
            val shiftedRefY = ref.y + initialTrans.y

            val nearest = targetStars.minByOrNull { t ->
                val dx = t.x - shiftedRefX
                val dy = t.y - shiftedRefY
                dx * dx + dy * dy
            } ?: continue

            val dist = sqrt((nearest.x - shiftedRefX).let { it * it } + (nearest.y - shiftedRefY).let { it * it })
            if (dist <= matchRadius) {
                matches.add(Pair(ref, nearest))
            }
        }

        if (matches.size < 3) {
            // Fall back to pure translation
            return RigidTransform(initialTrans.x, initialTrans.y, 0f)
        }

        // Step 3: Solve rigid transform on raw matches
        var transform = solveRigid(matches, cx, cy)

        // Step 4: Filter outliers (keep errors < 4px)
        val inliers = matches.filter { (ref, target) ->
            val rx = ref.x - cx
            val ry = ref.y - cy
            val cos = kotlin.math.cos(transform.angleRad.toDouble()).toFloat()
            val sin = kotlin.math.sin(transform.angleRad.toDouble()).toFloat()

            val projX = (rx * cos - ry * sin) + cx + transform.tx
            val projY = (rx * sin + ry * cos) + cy + transform.ty

            val dx = target.x - projX
            val dy = target.y - projY
            sqrt(dx * dx + dy * dy) < 4f
        }

        if (inliers.size >= 3 && inliers.size < matches.size) {
            // Re-solve on clean set
            transform = solveRigid(inliers, cx, cy)
        }

        return transform
    }

    /**
     * Applies [transform] (translation + rotation around center) to [src] bitmap.
     */
    fun applyRigidTransform(src: Bitmap, transform: RigidTransform): Bitmap {
        if (transform.tx == 0f && transform.ty == 0f && transform.angleRad == 0f) return src
        val dst = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(dst)
        val matrix = android.graphics.Matrix().also {
            val cx = src.width / 2f
            val cy = src.height / 2f
            it.postTranslate(-cx, -cy)
            val angleDeg = Math.toDegrees(transform.angleRad.toDouble()).toFloat()
            it.postRotate(angleDeg)
            it.postTranslate(cx + transform.tx, cy + transform.ty)
        }
        canvas.drawBitmap(src, matrix, android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return dst
    }

    private fun solveRigid(matches: List<Pair<Star, Star>>, cx: Float, cy: Float): RigidTransform {
        var refSumX = 0.0
        var refSumY = 0.0
        var targetSumX = 0.0
        var targetSumY = 0.0
        for ((ref, target) in matches) {
            refSumX += (ref.x - cx)
            refSumY += (ref.y - cy)
            targetSumX += (target.x - cx)
            targetSumY += (target.y - cy)
        }
        val refMeanX = refSumX / matches.size
        val refMeanY = refSumY / matches.size
        val targetMeanX = targetSumX / matches.size
        val targetMeanY = targetSumY / matches.size

        var sxx = 0.0
        var syy = 0.0
        var sxy = 0.0
        var syx = 0.0
        for ((ref, target) in matches) {
            val rx = (ref.x - cx) - refMeanX
            val ry = (ref.y - cy) - refMeanY
            val tx = (target.x - cx) - targetMeanX
            val ty = (target.y - cy) - targetMeanY
            sxx += rx * tx
            syy += ry * ty
            sxy += rx * ty
            syx += ry * tx
        }

        val numSin = sxy - syx
        val numCos = sxx + syy
        val angleRad = kotlin.math.atan2(numSin, numCos).toFloat()

        val cos = kotlin.math.cos(angleRad.toDouble()).toFloat()
        val sin = kotlin.math.sin(angleRad.toDouble()).toFloat()

        val tx = (targetMeanX - (refMeanX * cos - refMeanY * sin)).toFloat()
        val ty = (targetMeanY - (refMeanX * sin + refMeanY * cos)).toFloat()

        return RigidTransform(tx, ty, angleRad)
    }

    /** Quality score [0, 1] using rigid alignment projection. */
    fun rigidAlignmentQuality(
        referenceStars: List<Star>,
        targetStars: List<Star>,
        width: Int,
        height: Int,
        searchRadius: Float = 50f,
    ): Float {
        if (referenceStars.isEmpty()) return 0f
        val transform = estimateRigidTransform(referenceStars, targetStars, width, height, searchRadius)
        val cx = width / 2f
        val cy = height / 2f
        val cos = kotlin.math.cos(transform.angleRad.toDouble()).toFloat()
        val sin = kotlin.math.sin(transform.angleRad.toDouble()).toFloat()

        var matches = 0
        for (ref in referenceStars) {
            val rx = ref.x - cx
            val ry = ref.y - cy
            val projX = (rx * cos - ry * sin) + cx + transform.tx
            val projY = (rx * sin + ry * cos) + cy + transform.ty

            val aligned = targetStars.minByOrNull { t ->
                val dx = t.x - projX; val dy = t.y - projY; dx * dx + dy * dy
            } ?: continue
            val d = sqrt((aligned.x - projX).let { it * it } + (aligned.y - projY).let { it * it })
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

    /**
     * Estimate the FWHM (Full Width at Half Maximum) of a single star.
     * Computes moment-based standard deviation in a 7x7 window around centroid.
     */
    fun estimateFwhm(luma: ByteArray, width: Int, height: Int, star: Star): Float {
        val cx = star.x.toInt()
        val cy = star.y.toInt()
        val radius = 3

        var minVal = 255
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val px = cx + dx
                val py = cy + dy
                if (px in 0 until width && py in 0 until height) {
                    val v = luma[py * width + px].toInt() and 0xFF
                    if (v < minVal) minVal = v
                }
            }
        }

        var sumW = 0.0
        var sumDistSqW = 0.0
        for (dy in -radius..radius) {
            for (dx in -radius..radius) {
                val px = cx + dx
                val py = cy + dy
                if (px in 0 until width && py in 0 until height) {
                    val v = luma[py * width + px].toInt() and 0xFF
                    val w = max(0f, (v - minVal).toFloat()).toDouble()
                    val distSq = (px - star.x) * (px - star.x) + (py - star.y) * (py - star.y)
                    sumW += w
                    sumDistSqW += distSq * w
                }
            }
        }

        if (sumW <= 0) return 0f
        val sigmaSq = sumDistSqW / sumW
        val sigma = Math.sqrt(sigmaSq)
        return (2.35482 * sigma).toFloat()
    }

    /**
     * Compute average FWHM of top stars to evaluate frame sharpness.
     */
    fun calculateAverageFwhm(bitmap: Bitmap, stars: List<Star>): Float {
        if (stars.isEmpty()) return 0f
        val width = bitmap.width
        val height = bitmap.height
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        val luma = ByteArray(width * height)
        for (i in pixels.indices) {
            val r = (pixels[i] shr 16) and 0xFF
            val g = (pixels[i] shr 8) and 0xFF
            val b = pixels[i] and 0xFF
            luma[i] = ((0.2126 * r + 0.7152 * g + 0.0722 * b).toInt().coerceIn(0, 255)).toByte()
        }

        val targetStars = stars.take(10)
        var sumFwhm = 0f
        var count = 0
        for (star in targetStars) {
            val f = estimateFwhm(luma, width, height, star)
            if (f > 0.1f && f < 20f) {
                sumFwhm += f
                count++
            }
        }
        return if (count > 0) sumFwhm / count else 0f
    }
}
