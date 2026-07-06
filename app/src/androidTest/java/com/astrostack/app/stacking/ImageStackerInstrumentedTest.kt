package com.astrostack.app.stacking

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.astrostack.app.TestImageFactory
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Instrumented end-to-end tests for [ImageStacker].
 *
 * Uses synthetic PNG files so no real DNG / telescope required.
 * [StackingConfig.skipStretch] = true to avoid AutoSTF modifying known pixel values.
 */
@RunWith(AndroidJUnit4::class)
class ImageStackerInstrumentedTest {

    private lateinit var stacker: ImageStacker
    private lateinit var testDir: File

    @Before fun setUp() {
        stacker = ImageStacker(StarAligner(), HistogramStretch())
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        testDir = File(ctx.cacheDir, "stacker_test").also { it.mkdirs() }
    }

    @After fun tearDown() { testDir.deleteRecursively() }

    // ─── Output properties ────────────────────────────────────────────────────

    @Test fun output_hasSameDimensionsAsInput() = runTest {
        val files = TestImageFactory.solidColorFiles(testDir, 3, Color.DKGRAY, 128, 64)
        val result = stacker.stack(files, StackingConfig(skipStretch = true, alignFrames = false))
        assertEquals(128, result.width)
        assertEquals(64, result.height)
        result.recycle()
    }

    @Test fun output_isNotNull() = runTest {
        val files = TestImageFactory.solidColorFiles(testDir, 2, Color.DKGRAY)
        val result = stacker.stack(files, StackingConfig(skipStretch = true, alignFrames = false))
        assertNotNull(result)
        result.recycle()
    }

    // ─── Mean stacking ────────────────────────────────────────────────────────

    @Test fun meanStack_ofIdenticalImages_returnsSameColor() = runTest {
        val color = Color.argb(255, 80, 80, 80)
        val files = TestImageFactory.solidColorFiles(testDir, 5, color)
        val result = stacker.stack(files,
            StackingConfig(algorithm = StackingAlgorithm.MEAN, skipStretch = true, alignFrames = false))
        val (r, g, b) = TestImageFactory.centerPixelRgb(result)
        // Allow ±4 for sRGB↔linear round-trip rounding
        assertEquals("R channel", 80.0, r.toDouble(), 4.0)
        assertEquals("G channel", 80.0, g.toDouble(), 4.0)
        assertEquals("B channel", 80.0, b.toDouble(), 4.0)
        result.recycle()
    }

    // ─── Median stacking ──────────────────────────────────────────────────────

    @Test fun medianStack_rejectsBrightOutlier() = runTest {
        val sky = Color.argb(255, 20, 20, 20)
        val trail = Color.argb(255, 240, 240, 240)
        // 4 dark frames + 1 very bright frame (satellite trail)
        val files = TestImageFactory.solidColorFiles(testDir, 4, sky) +
                TestImageFactory.solidColorFiles(File(testDir, "trail"), 1, trail)
        val result = stacker.stack(files,
            StackingConfig(algorithm = StackingAlgorithm.MEDIAN, skipStretch = true, alignFrames = false))
        val (r, _, _) = TestImageFactory.centerPixelRgb(result)
        // Median of [20,20,20,20,240] = 20 → result should be dark
        assertTrue("Median result ($r) should be close to sky value (20)", r < 60)
        result.recycle()
    }

    // ─── Sigma-clipping ───────────────────────────────────────────────────────

    @Test fun sigmaClip_rejectsBrightOutlier_betterThanMean() = runTest {
        val sky = Color.argb(255, 20, 20, 20)
        val trail = Color.argb(255, 220, 220, 220)
        val dir2 = File(testDir, "trail2")
        val files = TestImageFactory.solidColorFiles(testDir, 9, sky) +
                TestImageFactory.solidColorFiles(dir2, 1, trail)

        val meanResult  = stacker.stack(files,
            StackingConfig(algorithm = StackingAlgorithm.MEAN,          skipStretch = true, alignFrames = false))
        val sigmaResult = stacker.stack(files,
            StackingConfig(algorithm = StackingAlgorithm.SIGMA_CLIPPING, skipStretch = true, alignFrames = false))

        val (meanR, _, _)  = TestImageFactory.centerPixelRgb(meanResult)
        val (sigmaR, _, _) = TestImageFactory.centerPixelRgb(sigmaResult)

        // Sigma-clipped result should be closer to sky (20) than mean result
        assertTrue("Sigma ($sigmaR) should be closer to sky than mean ($meanR)",
            Math.abs(sigmaR - 20) < Math.abs(meanR - 20))

        meanResult.recycle()
        sigmaResult.recycle()
    }

    // ─── Maximum stacking ─────────────────────────────────────────────────────

    @Test fun maximumStack_returnsHighestValue() = runTest {
        val dark   = Color.argb(255, 10, 10, 10)
        val bright = Color.argb(255, 200, 200, 200)
        val dir2   = File(testDir, "bright")
        val files  = TestImageFactory.solidColorFiles(testDir, 3, dark) +
                TestImageFactory.solidColorFiles(dir2, 1, bright)
        val result = stacker.stack(files,
            StackingConfig(algorithm = StackingAlgorithm.MAXIMUM, skipStretch = true, alignFrames = false))
        val (r, _, _) = TestImageFactory.centerPixelRgb(result)
        // Maximum should keep the bright frame value
        assertTrue("Max stack result ($r) should be close to bright value (200)", r > 150)
        result.recycle()
    }

    // ─── Progress callback ────────────────────────────────────────────────────

    @Test fun progressCallback_startsAtZeroAndEndsAtOne() = runTest {
        val files = TestImageFactory.solidColorFiles(testDir, 3, Color.GRAY)
        val progressValues = mutableListOf<Float>()
        stacker.stack(files,
            StackingConfig(skipStretch = true, alignFrames = false),
            onProgress = { progressValues.add(it) })
        assertTrue("No progress callbacks received", progressValues.isNotEmpty())
        assertEquals(0f, progressValues.first(), 0.01f)
        assertEquals(1f, progressValues.last(), 0.01f)
    }

    @Test fun progressCallback_isMonotonicallyIncreasing() = runTest {
        val files = TestImageFactory.solidColorFiles(testDir, 3, Color.GRAY)
        val progressValues = mutableListOf<Float>()
        stacker.stack(files,
            StackingConfig(skipStretch = true, alignFrames = false),
            onProgress = { progressValues.add(it) })
        for (i in 1 until progressValues.size) {
            assertTrue("Progress went backward at step $i: " +
                    "${progressValues[i-1]} → ${progressValues[i]}",
                progressValues[i] >= progressValues[i - 1])
        }
    }
}
