package one.meshcore.shared.meshcore

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the one timeout policy against the iOS `FirmwareSuggestedTimeout`. The policy was previously
 * written out once per caller and the trace copy lost the scale and the grace, so the cases below
 * deliberately sit away from the clamp edges: clamp-only arithmetic cannot satisfy them.
 */
class FirmwareSuggestedTimeoutTest {
    private val flood = FirmwareSuggestedTimeout.Profile.FLOOD
    private val zeroHop = FirmwareSuggestedTimeout.Profile.ZERO_HOP

    @Test
    fun missingHintYieldsProfileDefault() {
        assertEquals(30.0, FirmwareSuggestedTimeout.sanitizedSeconds(0, flood))
        assertEquals(5.0, FirmwareSuggestedTimeout.sanitizedSeconds(0, zeroHop))
        assertEquals(30.0, FirmwareSuggestedTimeout.sanitizedSeconds(-1, flood))
    }

    @Test
    fun floodScalesThenAddsGrace() {
        // 3 s x 1.2 + 8 s grace = 11.6 s, comfortably inside the 5-60 s band.
        assertEquals(11.6, FirmwareSuggestedTimeout.sanitizedSeconds(3_000, flood))
        assertEquals(11_600L, FirmwareSuggestedTimeout.sanitizedMs(3_000, flood))
    }

    @Test
    fun zeroHopScalesWithoutGrace() {
        // One link, so no return-leg grace: 10 s x 1.2 = 12 s exactly.
        assertEquals(12.0, FirmwareSuggestedTimeout.sanitizedSeconds(10_000, zeroHop))
        assertEquals(12_000L, FirmwareSuggestedTimeout.sanitizedMs(10_000, zeroHop))
    }

    @Test
    fun bandsClampBothEnds() {
        // A hint that scales below the floor is clamped up, never replaced by the default.
        assertEquals(1.0, FirmwareSuggestedTimeout.sanitizedSeconds(500, zeroHop))
        assertEquals(30.0, FirmwareSuggestedTimeout.sanitizedSeconds(60_000, zeroHop))
        assertEquals(60.0, FirmwareSuggestedTimeout.sanitizedSeconds(120_000, flood))
    }

    @Test
    fun pathDiscoveryFloorsTheOverallWait() {
        // 1 s x 1.2 + 8 = 9.2 s sanitized, but the overall discovery budget never drops below 20 s.
        assertEquals(9.2, FirmwareSuggestedTimeout.sanitizedSeconds(1_000, flood))
        assertEquals(20.0, FirmwareSuggestedTimeout.pathDiscoverySeconds(1_000))
        // A hint large enough to beat the floor is used as-is.
        assertEquals(38.0, FirmwareSuggestedTimeout.pathDiscoverySeconds(25_000))
    }

    @Test
    fun retransmitIntervalHonoursFloorAndAbsentHint() {
        assertNull(FirmwareSuggestedTimeout.retransmitIntervalMs(0))
        assertEquals(5_000L, FirmwareSuggestedTimeout.retransmitIntervalMs(1_000))
        assertEquals(20_000L, FirmwareSuggestedTimeout.retransmitIntervalMs(10_000))
    }
}
