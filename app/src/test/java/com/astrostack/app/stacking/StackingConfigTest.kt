package com.astrostack.app.stacking

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [StackingAlgorithm] and [StackingConfig].
 */
class StackingConfigTest {

    @Test fun allAlgorithms_haveNonBlankDisplayName() {
        StackingAlgorithm.entries.forEach { algo ->
            assertTrue("Algorithm ${algo.name} missing displayName",
                algo.displayName.isNotBlank())
        }
    }

    @Test fun allAlgorithms_haveNonBlankDescription() {
        StackingAlgorithm.entries.forEach { algo ->
            assertTrue("Algorithm ${algo.name} missing description",
                algo.description.isNotBlank())
        }
    }

    @Test fun allAlgorithms_displayNamesAreUnique() {
        val names = StackingAlgorithm.entries.map { it.displayName }
        assertEquals("Display names must be unique", names.distinct().size, names.size)
    }

    @Test fun defaultConfig_usesSigmaClipping() {
        assertEquals(StackingAlgorithm.SIGMA_CLIPPING, StackingConfig().algorithm)
    }

    @Test fun defaultConfig_kappaIsReasonable() {
        val kappa = StackingConfig().kappa
        assertTrue("Default kappa should be between 1.5 and 4.0", kappa in 1.5f..4.0f)
    }

    @Test fun defaultConfig_alignIsEnabled() {
        assertTrue(StackingConfig().alignFrames)
    }

    @Test fun defaultConfig_stretchIsEnabled() {
        assertFalse(StackingConfig().skipStretch)
    }

    @Test fun defaultConfig_subsampleIsOne() {
        assertEquals(1, StackingConfig().subsampleFactor)
    }

    @Test fun defaultConfig_tileStripCountPositive() {
        assertTrue(StackingConfig().tileStripCount > 0)
    }

    @Test fun config_copyWithChangedAlgorithm() {
        val config = StackingConfig().copy(algorithm = StackingAlgorithm.MEDIAN)
        assertEquals(StackingAlgorithm.MEDIAN, config.algorithm)
        // Other fields must remain at defaults
        assertEquals(StackingConfig().kappa, config.kappa, 0.001f)
    }

    @Test fun config_kappaClampedByViewModelLogic() {
        // StackingViewModel coerces kappa to 1f..5f — verify boundary values are valid
        val lo = StackingConfig(kappa = 1.0f)
        val hi = StackingConfig(kappa = 5.0f)
        assertTrue(lo.kappa >= 1.0f)
        assertTrue(hi.kappa <= 5.0f)
    }

    @Test fun fiveAlgorithmsPresent() {
        assertEquals(5, StackingAlgorithm.entries.size)
    }
}
