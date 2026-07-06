package com.astrostack.app.stacking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Unit tests for [StackingMath] — all pure JVM, no Android classes needed.
 *
 * Each stacking algorithm is verified with:
 *  - A trivial case (uniform values)
 *  - A case with a known outlier to confirm rejection/winsorization
 *  - Edge cases (2-element arrays, single-element behaviour)
 */
class StackingMathTest {

    private val DELTA = 0.001f   // acceptable float comparison tolerance

    // ─── mean ─────────────────────────────────────────────────────────────────

    @Test fun mean_of_identical_values_returns_that_value() {
        val arr = floatArrayOf(0.5f, 0.5f, 0.5f, 0.5f)
        assertEquals(0.5f, StackingMath.mean(arr), DELTA)
    }

    @Test fun mean_of_zero_and_one_is_half() {
        assertEquals(0.5f, StackingMath.mean(floatArrayOf(0f, 1f)), DELTA)
    }

    @Test fun mean_of_ascending_sequence() {
        // [1,2,3,4,5] → mean = 3
        assertEquals(3f, StackingMath.mean(floatArrayOf(1f, 2f, 3f, 4f, 5f)), DELTA)
    }

    @Test fun mean_is_skewed_by_outlier() {
        // Without rejection the large outlier pulls the mean up
        val clean  = floatArrayOf(1f, 1f, 1f, 1f, 1f)
        val noisy  = floatArrayOf(1f, 1f, 1f, 1f, 100f)
        assertTrue(StackingMath.mean(noisy) > StackingMath.mean(clean) * 5)
    }

    // ─── median ───────────────────────────────────────────────────────────────

    @Test fun median_odd_length() {
        assertEquals(3f, StackingMath.median(floatArrayOf(5f, 1f, 3f)), DELTA)
    }

    @Test fun median_even_length_averages_middle_pair() {
        // sorted [1,2,3,4] → (2+3)/2 = 2.5
        assertEquals(2.5f, StackingMath.median(floatArrayOf(3f, 1f, 4f, 2f)), DELTA)
    }

    @Test fun median_is_robust_to_single_extreme_outlier() {
        // 4 sky pixels at 0.1 and one satellite at 1.0  →  median ≈ 0.1
        val arr = floatArrayOf(0.1f, 0.1f, 1.0f, 0.1f, 0.1f)
        assertEquals(0.1f, StackingMath.median(arr), DELTA)
    }

    @Test fun median_does_not_mutate_input() {
        val original = floatArrayOf(3f, 1f, 2f)
        val copy = original.copyOf()
        StackingMath.median(original)
        // input array must be unchanged
        original.forEachIndexed { i, v -> assertEquals(copy[i], v, DELTA) }
    }

    // ─── sigma clipping ───────────────────────────────────────────────────────

    @Test fun sigmaClip_uniform_values_unchanged() {
        val arr = floatArrayOf(0.2f, 0.2f, 0.2f, 0.2f, 0.2f)
        assertEquals(0.2f, StackingMath.sigmaClip(arr, kappa = 2f, iterations = 3), DELTA)
    }

    @Test fun sigmaClip_rejects_bright_satellite_trail() {
        // 9 sky pixels at 0.05, one satellite at 1.0
        val arr = floatArrayOf(0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 0.05f, 1.0f)
        val result = StackingMath.sigmaClip(arr, kappa = 2f, iterations = 3)
        // After clipping the satellite, result should be very close to 0.05
        assertEquals(0.05f, result, 0.01f)
    }

    @Test fun sigmaClip_with_no_outliers_matches_mean() {
        // Mild spread, nothing should be clipped
        val arr = floatArrayOf(0.3f, 0.31f, 0.29f, 0.30f, 0.32f)
        val expected = StackingMath.mean(arr)
        assertEquals(expected, StackingMath.sigmaClip(arr, kappa = 3f, iterations = 3), DELTA)
    }

    @Test fun sigmaClip_two_element_array_returns_mean() {
        assertEquals(0.5f, StackingMath.sigmaClip(floatArrayOf(0f, 1f), kappa = 2f, iterations = 3), DELTA)
    }

    // ─── winsorized sigma ─────────────────────────────────────────────────────

    @Test fun winsorizedSigma_suppresses_outlier_more_than_mean() {
        // 9 values near 0.1, one mild outlier at 0.9 (not extreme enough to collapse winsorization)
        val arr = floatArrayOf(0.1f, 0.1f, 0.12f, 0.09f, 0.11f, 0.1f, 0.1f, 0.1f, 0.1f, 0.9f)
        val winsorized = StackingMath.winsorizedSigma(arr, kappa = 2f, iterations = 3)
        val mean       = StackingMath.mean(arr)
        // Winsorized result should be closer to the cluster (0.1) than the plain mean
        assertTrue("winsorized ($winsorized) should be closer to 0.1 than mean ($mean)",
            Math.abs(winsorized - 0.1f) < Math.abs(mean - 0.1f))
    }

    @Test fun winsorizedSigma_preserves_array_length_semantics() {
        // Uniform → clipping boundaries collapse on the mean → same value returned
        val arr = floatArrayOf(0.4f, 0.4f, 0.4f, 0.4f)
        assertEquals(0.4f, StackingMath.winsorizedSigma(arr, kappa = 2f, iterations = 3), DELTA)
    }

    // ─── maximum ──────────────────────────────────────────────────────────────

    @Test fun maximum_returns_highest_value() {
        assertEquals(0.9f, StackingMath.maximum(floatArrayOf(0.1f, 0.9f, 0.4f, 0.6f)), DELTA)
    }

    @Test fun maximum_with_negative_values() {
        assertEquals(0.0f, StackingMath.maximum(floatArrayOf(-1f, -0.5f, 0f)), DELTA)
    }

    // ─── stdDev ───────────────────────────────────────────────────────────────

    @Test fun stdDev_of_identical_values_is_zero() {
        val arr = floatArrayOf(5f, 5f, 5f, 5f)
        assertEquals(0f, StackingMath.stdDev(arr, arr.size, 5f), DELTA)
    }

    @Test fun stdDev_known_value() {
        // [2, 4, 4, 4, 5, 5, 7, 9]
        // population σ = 2.0, sample σ = sqrt(32/7) ≈ 2.138 (Bessel-corrected)
        val arr = floatArrayOf(2f, 4f, 4f, 4f, 5f, 5f, 7f, 9f)
        val mean = StackingMath.mean(arr)
        assertEquals(2.138f, StackingMath.stdDev(arr, arr.size, mean), 0.01f)
    }

    @Test fun stdDev_single_element_returns_zero() {
        assertEquals(0f, StackingMath.stdDev(floatArrayOf(1f), 1, 1f), DELTA)
    }

    // ─── Algorithm consistency ────────────────────────────────────────────────

    @Test fun all_algorithms_return_value_within_input_range() {
        val arr = floatArrayOf(0.1f, 0.15f, 0.12f, 0.11f, 0.9f)
        val min = arr.min()
        val max = arr.max()
        listOf(
            StackingMath.mean(arr),
            StackingMath.median(arr),
            StackingMath.sigmaClip(arr, 2f, 3),
            StackingMath.winsorizedSigma(arr, 2f, 3),
            StackingMath.maximum(arr),
        ).forEachIndexed { i, result ->
            assertTrue("Algorithm $i result $result outside [$min, $max]", result in min..max)
        }
    }
}
