package com.astrostack.app.stacking

import com.astrostack.app.stacking.StarAligner.Offset
import com.astrostack.app.stacking.StarAligner.Star
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure-Kotlin parts of [StarAligner]:
 *  - [StarAligner.computeTranslation] — no Bitmap needed, pure data classes
 *  - [StarAligner.alignmentQuality]
 *
 * Star detection (which requires a Bitmap) is tested in the instrumented suite.
 */
class StarAlignerUnitTest {

    private val aligner = StarAligner()
    private val DELTA = 0.5f     // pixel tolerance

    // ─── Helper ───────────────────────────────────────────────────────────────

    /** Build a list of [Star] at integer positions with brightness 200. */
    private fun stars(vararg positions: Pair<Int, Int>): List<Star> =
        positions.map { (x, y) -> Star(x.toFloat(), y.toFloat(), 200f) }

    // ─── computeTranslation ───────────────────────────────────────────────────

    @Test fun zeroOffset_when_frames_are_identical() {
        val s = stars(100 to 100, 200 to 150, 300 to 80)
        val offset = aligner.computeTranslation(s, s)
        assertEquals(0f, offset.x, DELTA)
        assertEquals(0f, offset.y, DELTA)
    }

    @Test fun detects_positive_translation() {
        val reference = stars(100 to 100, 200 to 200, 300 to 100)
        val shifted   = stars(110 to 115, 210 to 215, 310 to 115)
        val offset = aligner.computeTranslation(reference, shifted)
        assertEquals(10f, offset.x, DELTA)
        assertEquals(15f, offset.y, DELTA)
    }

    @Test fun detects_negative_translation() {
        val reference = stars(200 to 200, 400 to 300, 100 to 350)
        val shifted   = stars(193 to 194, 393 to 294, 93 to 344)
        val offset = aligner.computeTranslation(reference, shifted)
        assertEquals(-7f, offset.x, DELTA)
        assertEquals(-6f, offset.y, DELTA)
    }

    @Test fun robust_to_one_mismatched_star() {
        // 4 stars shifted by (+5, +3), 1 wildly different star in target
        val reference = stars(100 to 100, 200 to 200, 300 to 150, 400 to 250)
        val shifted   = stars(
            105 to 103,   // +5, +3
            205 to 203,   // +5, +3
            305 to 153,   // +5, +3
            999 to 999,   // outlier — different star / false match
        )
        val offset = aligner.computeTranslation(reference, shifted)
        // Median should cancel the one outlier
        assertEquals(5f, offset.x, DELTA)
        assertEquals(3f, offset.y, DELTA)
    }

    @Test fun returns_zero_offset_for_empty_reference() {
        val offset = aligner.computeTranslation(emptyList(), stars(10 to 10))
        assertEquals(0f, offset.x, DELTA)
        assertEquals(0f, offset.y, DELTA)
    }

    @Test fun returns_zero_offset_for_empty_target() {
        val offset = aligner.computeTranslation(stars(10 to 10), emptyList())
        assertEquals(0f, offset.x, DELTA)
        assertEquals(0f, offset.y, DELTA)
    }

    @Test fun returns_zero_offset_when_no_pairs_match_within_search_radius() {
        val reference = stars(100 to 100)
        val target    = stars(500 to 500)   // far outside default 50px search radius
        val offset = aligner.computeTranslation(reference, target, searchRadius = 50f)
        assertEquals(0f, offset.x, DELTA)
        assertEquals(0f, offset.y, DELTA)
    }

    @Test fun larger_search_radius_finds_large_drift() {
        val reference = stars(100 to 100, 300 to 200, 200 to 350)
        val shifted   = stars(200 to 100, 400 to 200, 300 to 350)  // +100 in X
        val offset = aligner.computeTranslation(reference, shifted, searchRadius = 150f)
        assertEquals(100f, offset.x, DELTA)
        assertEquals(0f,   offset.y, DELTA)
    }

    // ─── alignmentQuality ────────────────────────────────────────────────────

    @Test fun quality_is_one_when_all_stars_match() {
        val s = stars(100 to 100, 200 to 200, 300 to 300)
        val quality = aligner.alignmentQuality(s, s)
        assertEquals(1.0f, quality, 0.01f)
    }

    @Test fun quality_is_zero_for_empty_reference() {
        assertEquals(0f, aligner.alignmentQuality(emptyList(), stars(10 to 10)), 0.01f)
    }

    @Test fun quality_drops_with_unmatched_stars() {
        val reference = stars(100 to 100, 200 to 200, 300 to 300, 400 to 400)
        // Target only has 2 of the 4 matching stars (shifted by +2)
        val target = stars(102 to 102, 202 to 202, 900 to 900, 950 to 950)
        val quality = aligner.alignmentQuality(reference, target)
        assertTrue("Quality ($quality) should be < 1.0", quality < 1.0f)
    }

    @Test fun detects_rigid_translation_and_rotation() {
        val width = 1000
        val height = 1000
        val cx = width / 2f
        val cy = height / 2f
        
        val reference = listOf(
            Star(400f, 400f, 200f),
            Star(600f, 400f, 200f),
            Star(500f, 600f, 200f),
            Star(300f, 500f, 200f)
        )
        
        // Rotate reference around center by 10 degrees (0.1745 rad) and translate by (+15, -10)
        val angleRad = Math.toRadians(10.0)
        val cos = Math.cos(angleRad)
        val sin = Math.sin(angleRad)
        val tx = 15f
        val ty = -10f
        
        val target = reference.map { ref ->
            val rx = ref.x - cx
            val ry = ref.y - cy
            val rotX = (rx * cos - ry * sin).toFloat()
            val rotY = (rx * sin + ry * cos).toFloat()
            Star(rotX + cx + tx, rotY + cy + ty, 200f)
        }
        
        val transform = aligner.estimateRigidTransform(reference, target, width, height)
        
        assertEquals(tx, transform.tx, 0.5f)
        assertEquals(ty, transform.ty, 0.5f)
        assertEquals(angleRad.toFloat(), transform.angleRad, 0.01f)
    }
}
