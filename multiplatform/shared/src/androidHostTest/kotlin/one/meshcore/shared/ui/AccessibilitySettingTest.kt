package one.meshcore.shared.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AccessibilitySettingTest {
    @Test
    fun highTextContrastAloneIsIncreasedContrast() {
        assertTrue(isHighContrastEnabled(highTextContrast = true, systemContrast = 0f))
    }

    @Test
    fun systemContrastAtThresholdIsIncreasedContrast() {
        assertTrue(isHighContrastEnabled(highTextContrast = false, systemContrast = CONTRAST_THRESHOLD))
        assertFalse(isHighContrastEnabled(highTextContrast = false, systemContrast = CONTRAST_THRESHOLD - 0.01f))
    }

    @Test
    fun neitherToggleLeavesContrastOff() {
        assertFalse(isHighContrastEnabled(highTextContrast = false, systemContrast = 0f))
    }

    @Test
    fun zeroAnimatorScaleIsReduceMotion() {
        assertTrue(isReduceMotionEnabled(animatorDurationScale = 0f))
        assertFalse(isReduceMotionEnabled(animatorDurationScale = 1f))
        assertFalse(isReduceMotionEnabled(animatorDurationScale = 0.5f))
    }
}
