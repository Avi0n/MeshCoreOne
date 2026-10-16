package one.meshcore.shared.trace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The two hop-hash-size derivations must stay separate: trace is power-of-2 (`1 shl mode` → 1/2/4),
 * routing is linear (`mode + 1` → 1/2/3). Mirrors the Swift power-of-2 vs linear split the project
 * treats as load-bearing.
 */
class TraceHashSizeTest {
    @Test
    fun traceHashSizeIsPowerOfTwoAcrossModes() {
        assertEquals(1, traceHashSize(0))
        assertEquals(2, traceHashSize(1))
        assertEquals(4, traceHashSize(2))
    }

    @Test
    fun routingHashSizeIsLinearAcrossModes() {
        assertEquals(1, routingHashSize(0))
        assertEquals(2, routingHashSize(1))
        assertEquals(3, routingHashSize(2))
    }

    @Test
    fun traceAndRoutingAgreeAtLowModesButDivergeAtModeTwo() {
        // Modes 0 and 1 coincide (1 and 2 bytes); the encodings only diverge at mode 2.
        assertEquals(traceHashSize(0), routingHashSize(0))
        assertEquals(traceHashSize(1), routingHashSize(1))
        assertTrue(traceHashSize(2) != routingHashSize(2))
        assertEquals(4, traceHashSize(2))
        assertEquals(3, routingHashSize(2))
    }

    @Test
    fun validTraceHashSizesArePowersOfTwo() {
        assertEquals(listOf(1, 2, 4), VALID_TRACE_HASH_SIZES)
    }

    @Test
    fun traceHashModeForSizeInvertsTraceHashSize() {
        for (mode in MIN_TRACE_HASH_MODE..MAX_TRACE_HASH_MODE) {
            assertEquals(mode, traceHashModeForSize(traceHashSize(mode)))
        }
    }

    @Test
    fun inferredTraceHashModeReadsUniformWidth() {
        assertEquals(0, inferredTraceHashMode("31,4F,A7"))
        assertEquals(1, inferredTraceHashMode("31A7,4F09"))
        assertEquals(2, inferredTraceHashMode("31A74F09,1122AABB"))
    }

    @Test
    fun inferredTraceHashModeTrimsAndDropsEmptyTokens() {
        assertEquals(1, inferredTraceHashMode(" 31A7 , 4F09 ,"))
    }

    @Test
    fun inferredTraceHashModeRejectsMixedOddNonHexAndEmptyAndInvalidWidth() {
        assertNull(inferredTraceHashMode("31,4F09")) // mixed width
        assertNull(inferredTraceHashMode("31A")) // odd length
        assertNull(inferredTraceHashMode("ZZ")) // non-hex
        assertNull(inferredTraceHashMode("")) // empty
        assertNull(inferredTraceHashMode("  ,  ")) // only empties
        assertNull(inferredTraceHashMode("31A74F")) // 3-byte width is not a valid trace size
    }

    @Test
    fun adoptedTraceHashSizeSwitchesToPastedWidthWhenSupported() {
        assertEquals(2, adoptedTraceHashSize("31A7,4F09", current = 1, supportsOverride = true))
        assertEquals(4, adoptedTraceHashSize("31A74F09", current = 1, supportsOverride = true))
    }

    @Test
    fun adoptedTraceHashSizeKeepsCurrentWhenUnsupportedMalformedOrMatching() {
        // Radio can't override, mixed width, and already-matching all leave the current width untouched.
        assertEquals(1, adoptedTraceHashSize("31A7,4F09", current = 1, supportsOverride = false))
        assertEquals(1, adoptedTraceHashSize("31,4F09", current = 1, supportsOverride = true))
        assertEquals(2, adoptedTraceHashSize("31A7,4F09", current = 2, supportsOverride = true))
    }

    @Test
    fun effectiveTraceModeFollowsTheRadioUntilOverridden() {
        // No override: the radio's configured pathHashMode decides the hop width.
        assertEquals(0, effectiveTraceMode(override = null, devicePathHashMode = 0))
        assertEquals(1, effectiveTraceMode(override = null, devicePathHashMode = 1))
        assertEquals(2, effectiveTraceMode(override = null, devicePathHashMode = 2))
        // Unknown device mode falls back to 1-byte hops.
        assertEquals(0, effectiveTraceMode(override = null, devicePathHashMode = null))
        // An explicit per-trace override wins, including narrowing a 4-byte radio to 1 byte.
        assertEquals(0, effectiveTraceMode(override = 0, devicePathHashMode = 2))
        assertEquals(2, effectiveTraceMode(override = 2, devicePathHashMode = 0))
        // The reserved 2-bit value 3 and any junk clamp into the valid mode range.
        assertEquals(2, effectiveTraceMode(override = null, devicePathHashMode = 3))
    }

    @Test
    fun effectiveTraceHashSizeIsPowerOfTwo() {
        assertEquals(2, traceHashSize(effectiveTraceMode(override = null, devicePathHashMode = 1)))
        assertEquals(4, traceHashSize(effectiveTraceMode(override = null, devicePathHashMode = 2)))
    }
}
