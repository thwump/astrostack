package com.astrostack.app.stacking

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.astrostack.app.TestImageFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HistogramStretchInstrumentedTest {

    private val stretch = HistogramStretch()
    private val DELTA = 5.0

    private fun assertPixel(expected: Int, actual: Int, msg: String = "") =
        assertEquals(msg, expected.toDouble(), actual.toDouble(), DELTA)

    @Test fun autoStretch_outputHasSameDimensions() {
        val bmp = TestImageFactory.solidColor(Color.argb(255, 30, 30, 30))
        val result = stretch.autoStretch(bmp)
        assertEquals(bmp.width, result.width)
        assertEquals(bmp.height, result.height)
        bmp.recycle(); result.recycle()
    }

    @Test fun autoStretch_brightensDarkImage() {
        // Star field: tiny bright source on a near-black sky.
        // AutoSTF raises the black point to the sky median and keeps the star bright.
        // Verify the star centre pixel (32,32) is visible (> 128) after stretching.
        val bmp = TestImageFactory.starField(listOf(32 to 32), width = 64, height = 64)
        val result = stretch.autoStretch(bmp)
        val starR = Color.red(result.getPixel(32, 32))
        assertTrue("Star pixel should remain bright after AutoSTF (got R=$starR)", starR > 128)
        bmp.recycle(); result.recycle()
    }

    private fun averageLuma(bmp: Bitmap): Double {
        val pixels = IntArray(bmp.width * bmp.height)
        bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
        return pixels.map { p ->
            0.2126 * Color.red(p) + 0.7152 * Color.green(p) + 0.0722 * Color.blue(p)
        }.average()
    }

    @Test fun autoStretch_blackImageRemainsBlack() {
        val bmp = TestImageFactory.solidColor(Color.BLACK, 64, 64)
        val result = stretch.autoStretch(bmp)
        val (r, g, b) = TestImageFactory.centerPixelRgb(result)
        assertPixel(0, r, "R"); assertPixel(0, g, "G"); assertPixel(0, b, "B")
        bmp.recycle(); result.recycle()
    }

    @Test fun autoStretch_doesNotMutateInput() {
        val bmp = TestImageFactory.solidColor(Color.argb(255, 40, 40, 40))
        val originalPixel = bmp.getPixel(32, 32)
        stretch.autoStretch(bmp)
        assertEquals("autoStretch must not modify the source bitmap",
            originalPixel.toLong(), bmp.getPixel(32, 32).toLong())
        bmp.recycle()
    }

    @Test fun manualStretch_identityTransform_preservesValues() {
        val bmp = TestImageFactory.solidColor(Color.argb(255, 128, 128, 128))
        val result = stretch.manualStretch(bmp, blackPoint = 0f, midtone = 0.5f, whitePoint = 1f)
        val (r, _, _) = TestImageFactory.centerPixelRgb(result)
        assertPixel(128, r, "midtone=0.5 should be identity")
        bmp.recycle(); result.recycle()
    }

    @Test fun manualStretch_whitePixelStaysWhite() {
        val bmp = TestImageFactory.solidColor(Color.WHITE)
        val result = stretch.manualStretch(bmp, 0f, 0.5f, 1f)
        val (r, g, b) = TestImageFactory.centerPixelRgb(result)
        assertPixel(255, r, "R"); assertPixel(255, g, "G"); assertPixel(255, b, "B")
        bmp.recycle(); result.recycle()
    }

    @Test fun manualStretch_outputHasSameDimensions() {
        val bmp = TestImageFactory.solidColor(Color.GRAY, 100, 80)
        val result = stretch.manualStretch(bmp, 0f, 0.5f, 1f)
        assertEquals(100, result.width); assertEquals(80, result.height)
        bmp.recycle(); result.recycle()
    }

    @Test fun manualStretch_highBlackPoint_darkensImage() {
        val bmp = TestImageFactory.solidColor(Color.argb(255, 80, 80, 80))
        val result = stretch.manualStretch(bmp, blackPoint = 0.4f, midtone = 0.5f, whitePoint = 1f)
        val (r, _, _) = TestImageFactory.centerPixelRgb(result)
        assertPixel(0, r, "value below black point should clip to 0")
        bmp.recycle(); result.recycle()
    }

    @Test fun buildStretchLut_hasLength256() {
        assertEquals(256, stretch.buildStretchLut(0f, 0.5f, 1f).size)
    }

    @Test fun buildStretchLut_valuesInRange0to255() {
        stretch.buildStretchLut(0f, 0.25f, 1f).forEach { v ->
            assertTrue("LUT value $v out of range", v in 0..255)
        }
    }

    @Test fun buildStretchLut_zeroMapsToZero() {
        assertEquals(0L, stretch.buildStretchLut(0f, 0.5f, 1f)[0].toLong())
    }

    @Test fun buildStretchLut_255MapsTo255() {
        assertEquals(255L, stretch.buildStretchLut(0f, 0.5f, 1f)[255].toLong())
    }

    @Test fun applyLut_identityLut_preservesPixels() {
        val identityLut = IntArray(256) { it }
        val bmp = TestImageFactory.solidColor(Color.argb(255, 100, 150, 200))
        val result = stretch.applyLut(bmp, identityLut)
        val (r, g, b) = TestImageFactory.centerPixelRgb(result)
        assertPixel(100, r, "R"); assertPixel(150, g, "G"); assertPixel(200, b, "B")
        bmp.recycle(); result.recycle()
    }

    @Test fun applyLut_invertLut_inverts() {
        val invertLut = IntArray(256) { 255 - it }
        val bmp = TestImageFactory.solidColor(Color.argb(255, 0, 128, 255))
        val result = stretch.applyLut(bmp, invertLut)
        val (r, g, b) = TestImageFactory.centerPixelRgb(result)
        assertPixel(255, r, "R"); assertPixel(127, g, "G"); assertPixel(0, b, "B")
        bmp.recycle(); result.recycle()
    }

    // ── Regression tests for overexposed / uniform input ──────────────────────

    /** All-white image (overexposed) must NOT produce an all-black output.
     *  Root cause was: MAD=0 → blackPoint=1.0 → range=0 → x/0=NaN → toInt()=0. */
    @Test fun autoStretch_allWhiteImage_remainsWhite() {
        val bmp = TestImageFactory.solidColor(Color.argb(255, 255, 255, 255))
        val result = stretch.autoStretch(bmp)
        val (r, g, b) = TestImageFactory.centerPixelRgb(result)
        // Should stay near white (≥ 200) rather than collapsing to black
        assertTrue("R should stay bright, got $r", r >= 200)
        assertTrue("G should stay bright, got $g", g >= 200)
        assertTrue("B should stay bright, got $b", b >= 200)
        bmp.recycle(); result.recycle()
    }

    /** Uniform mid-grey image: MAD=0, blackPoint=median, every pixel would map to 0.
     *  After the fix, the channel is left unchanged. */
    @Test fun autoStretch_uniformGrey_doesNotGoBlack() {
        val bmp = TestImageFactory.solidColor(Color.argb(255, 128, 128, 128))
        val result = stretch.autoStretch(bmp)
        val (r, g, b) = TestImageFactory.centerPixelRgb(result)
        assertTrue("R should stay near 128 ± 5, got $r", r in 100..155)
        assertTrue("G should stay near 128 ± 5, got $g", g in 100..155)
        assertTrue("B should stay near 128 ± 5, got $b", b in 100..155)
        bmp.recycle(); result.recycle()
    }
}
