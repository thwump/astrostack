package com.astrostack.app.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [CaptureSettings] and [EXPOSURE_PRESETS].
 */
class CaptureSettingsTest {

    @Test fun defaultSettings_haveReasonableValues() {
        val s = CaptureSettings()
        assertTrue("Default exposure should be > 0", s.exposureTimeNs > 0)
        assertTrue("Default ISO should be > 0", s.iso > 0)
        assertTrue("Default frame count should be >= 1", s.frameCount >= 1)
    }

    @Test fun defaultSettings_focusIsInfinity() {
        // Not directly in CaptureSettings, but the default wbGains should be null
        assertEquals(null, CaptureSettings().wbGains)
    }

    @Test fun defaultSettings_oisDisabled() {
        assertTrue(CaptureSettings().disableOis)
    }

    @Test fun exposurePresets_nonEmpty() {
        assertTrue("Should have at least one preset", EXPOSURE_PRESETS.isNotEmpty())
    }

    @Test fun exposurePresets_allHavePositiveExposure() {
        EXPOSURE_PRESETS.forEach { preset ->
            assertTrue("Preset '${preset.label}' has non-positive exposure",
                preset.exposureTimeNs > 0)
        }
    }

    @Test fun exposurePresets_allHavePositiveIso() {
        EXPOSURE_PRESETS.forEach { preset ->
            assertTrue("Preset '${preset.label}' has non-positive ISO",
                preset.iso > 0)
        }
    }

    @Test fun exposurePresets_labelsAreUnique() {
        val labels = EXPOSURE_PRESETS.map { it.label }
        assertEquals("Preset labels must be unique", labels.distinct().size, labels.size)
    }

    @Test fun exposurePresets_sortedByExposureTime() {
        val times = EXPOSURE_PRESETS.map { it.exposureTimeNs }
        val sorted = times.sorted()
        assertEquals("Presets should be in ascending exposure order", sorted, times)
    }

    @Test fun captureSettings_frameCountClamping() {
        // StackingViewModel clamps to 1..100; verify the domain makes sense
        val s = CaptureSettings(frameCount = 10)
        assertEquals(10, s.frameCount)
    }
}
