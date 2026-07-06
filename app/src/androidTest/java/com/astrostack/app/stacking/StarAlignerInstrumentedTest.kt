package com.astrostack.app.stacking

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.astrostack.app.TestImageFactory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [StarAligner.detectStars].
 *
 * [StarAligner.computeTranslation] and [StarAligner.alignmentQuality] are
 * covered by [com.astrostack.app.stacking.StarAlignerUnitTest] (JVM, no device needed).
 */
@RunWith(AndroidJUnit4::class)
class StarAlignerInstrumentedTest {

    private val aligner = StarAligner()

    // ─── detectStars ─────────────────────────────────────────────────────────

    @Test fun detectStars_returnsEmpty_onBlackImage() = runTest {
        val bmp = TestImageFactory.solidColor(Color.BLACK, 128, 128)
        val stars = aligner.detectStars(bmp)
        assertEquals("All-black image should have no stars", 0, stars.size)
        bmp.recycle()
    }

    @Test fun detectStars_findsIsolatedBrightBlob() = runTest {
        // One bright star blob near centre on a dark background
        val bmp = TestImageFactory.starField(listOf(64 to 64), width = 128, height = 128)
        val stars = aligner.detectStars(bmp)
        assertTrue("Should detect at least one star", stars.size >= 1)
        bmp.recycle()
    }

    @Test fun detectStars_findsMultipleStars() = runTest {
        val positions = listOf(40 to 40, 100 to 40, 40 to 100, 100 to 100)
        val bmp = TestImageFactory.starField(positions, width = 160, height = 160)
        val stars = aligner.detectStars(bmp, minDistance = 20)
        assertTrue("Should detect all 4 stars; found ${stars.size}", stars.size >= 4)
        bmp.recycle()
    }

    @Test fun detectStars_respects_maxStars_limit() = runTest {
        // 10 bright stars
        val positions = (0 until 10).map { i -> (20 + i * 22) to 80 }
        val bmp = TestImageFactory.starField(positions, width = 256, height = 160)
        val stars = aligner.detectStars(bmp, maxStars = 3)
        assertTrue("Should return no more than maxStars=3; got ${stars.size}", stars.size <= 3)
        bmp.recycle()
    }

    @Test fun detectStars_returnedStars_sortedByBrightnessDescending() = runTest {
        // 3 stars at varying brightness — we achieve this via overlapping blobs
        // (brighter = more white pixels drawn on top)
        val bmp = TestImageFactory.starField(
            listOf(50 to 80, 130 to 80, 210 to 80),
            width = 256, height = 160
        )
        val stars = aligner.detectStars(bmp)
        for (i in 1 until stars.size) {
            assertTrue(
                "Stars not sorted by brightness: ${stars[i-1].brightness} < ${stars[i].brightness}",
                stars[i - 1].brightness >= stars[i].brightness
            )
        }
        bmp.recycle()
    }

    @Test fun detectStars_centroid_isNearKnownPosition() = runTest {
        val bmp = TestImageFactory.starField(listOf(80 to 80), width = 160, height = 160)
        val stars = aligner.detectStars(bmp)
        assertTrue("Expected at least one star detected", stars.isNotEmpty())
        val s = stars.first()
        // Centroid should be within 5 pixels of the known position
        assertEquals(80f, s.x, 5f)
        assertEquals(80f, s.y, 5f)
        bmp.recycle()
    }

    @Test fun detectStars_allBlue_returnedEmpty() = runTest {
        // Pure blue is not bright in luma — Rec.709: 0.0722*255 ≈ 18 — below threshold
        val bmp = TestImageFactory.solidColor(Color.BLUE, 128, 128)
        val stars = aligner.detectStars(bmp, starThreshold = 40)
        assertEquals("Pure blue image should not register as a star", 0, stars.size)
        bmp.recycle()
    }
}
