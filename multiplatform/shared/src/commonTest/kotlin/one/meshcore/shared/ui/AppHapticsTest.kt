package one.meshcore.shared.ui

import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [AppHaptics] maps iOS sensory kinds onto Compose [HapticFeedbackType]s. The platform delegate
 * is a no-op on desktop; these tests lock the mapping, not vibration hardware.
 */
class AppHapticsTest {
    @Test
    fun mapsIosSensoryKindsOntoDistinctComposeTypes() {
        val recorder = RecordingHapticFeedback()
        val haptics = AppHaptics(recorder)

        haptics.success()
        haptics.failure()
        haptics.warning()
        haptics.selection()
        haptics.start()
        haptics.tap()

        assertEquals(
            listOf(
                HapticFeedbackType.Confirm,
                HapticFeedbackType.Reject,
                HapticFeedbackType.ContextClick,
                HapticFeedbackType.SegmentTick,
                HapticFeedbackType.GestureThresholdActivate,
                HapticFeedbackType.LongPress,
            ),
            recorder.types,
        )
    }
}
