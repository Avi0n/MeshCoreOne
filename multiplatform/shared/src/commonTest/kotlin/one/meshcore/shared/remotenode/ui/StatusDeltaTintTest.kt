package one.meshcore.shared.remotenode.ui

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Improvement vs degradation for a status delta. Paint uses theme primary/tertiary.
 */
class StatusDeltaTintTest {
    @Test
    fun nearZeroIsMuted() {
        assertEquals(StatusDeltaTint.MUTED, statusDeltaTint(delta = 0.0, higherIsBetter = true))
        assertEquals(StatusDeltaTint.MUTED, statusDeltaTint(delta = 0.005, higherIsBetter = false))
    }

    @Test
    fun higherIsBetterTintsGreenWhenUp() {
        assertEquals(StatusDeltaTint.GREEN, statusDeltaTint(delta = 1.0, higherIsBetter = true))
        assertEquals(StatusDeltaTint.ORANGE, statusDeltaTint(delta = -1.0, higherIsBetter = true))
    }

    @Test
    fun lowerIsBetterTintsGreenWhenDown() {
        assertEquals(StatusDeltaTint.GREEN, statusDeltaTint(delta = -2.0, higherIsBetter = false))
        assertEquals(StatusDeltaTint.ORANGE, statusDeltaTint(delta = 2.0, higherIsBetter = false))
    }
}
