package com.astrostack.app.stacking

import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Stateless, pure-Kotlin pixel-level stacking algorithm functions.
 *
 * Extracted as an `internal` object so that the math can be unit-tested on the
 * JVM without requiring any Android framework classes (no Bitmap, no Context).
 *
 * All values are assumed to be in linear-light space [0, ∞).
 */
internal object StackingMath {

    /** Arithmetic mean of all values in the array. */
    fun mean(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        return values.sum() / values.size
    }

    /**
     * Sample median. Allocates a sorted copy to avoid mutating the input.
     * Returns the average of the two middle values for even-length arrays.
     */
    fun median(values: FloatArray): Float {
        if (values.isEmpty()) return 0f
        val sorted = values.copyOf()
        sorted.sort()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2f else sorted[mid]
    }

    /**
     * Iterative κ-σ clipping.
     *
     * Each iteration: compute mean + stddev of survivors, reject values
     * further than [kappa]·σ from the mean.  Returns the mean of survivors.
     * If fewer than 2 values survive an iteration the previous set is kept.
     */
    fun sigmaClip(values: FloatArray, kappa: Float, iterations: Int): Float {
        if (values.size < 2) return values.firstOrNull() ?: 0f
        val working = values.copyOf()
        var count = working.size

        repeat(iterations) {
            val m = mean(working.copyOf(count))
            val std = stdDev(working, count, m)
            val threshold = kappa * std
            var newCount = 0
            for (i in 0 until count) {
                if (abs(working[i] - m) <= threshold) {
                    working[newCount++] = working[i]
                }
            }
            if (newCount >= 2) count = newCount
        }
        return mean(working.copyOf(count))
    }

    /**
     * Winsorized σ-clipping.
     *
     * Like [sigmaClip] but instead of removing outliers, replaces them with
     * the clipping boundary (lo = mean − κ·σ, hi = mean + κ·σ).
     * Preserves the array length, which helps SNR when few frames are available.
     */
    fun winsorizedSigma(values: FloatArray, kappa: Float, iterations: Int): Float {
        if (values.size < 2) return values.firstOrNull() ?: 0f
        val working = values.copyOf()
        repeat(iterations) {
            val m = mean(working)
            val std = stdDev(working, working.size, m)
            val lo = m - kappa * std
            val hi = m + kappa * std
            for (i in working.indices) working[i] = working[i].coerceIn(lo, hi)
        }
        return mean(working)
    }

    /** Maximum value in the array. */
    fun maximum(values: FloatArray): Float = values.maxOrNull() ?: 0f

    /**
     * Bessel-corrected (sample) standard deviation over the first [count]
     * elements of [values], given a pre-computed [mean].
     */
    fun stdDev(values: FloatArray, count: Int, mean: Float): Float {
        if (count <= 1) return 0f
        var sum = 0.0
        for (i in 0 until count) {
            val d = (values[i] - mean).toDouble()
            sum += d * d
        }
        return sqrt(sum / (count - 1)).toFloat()
    }

}
