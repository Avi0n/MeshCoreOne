package one.meshcore.shared.tools.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * iOS `PathActionsSectionView` shows `hashSizeFooter` only when `effectiveTraceMode > 0`
 * (2-byte and 4-byte hops). One-byte traces keep the warning off.
 */
class TraceHashSizeFooterVisibleTest {
    @Test
    fun oneByteHidesTheCompatibilityFooter() {
        assertFalse(traceHashSizeFooterVisible(hashSize = 1))
    }

    @Test
    fun twoAndFourByteShowTheCompatibilityFooter() {
        assertTrue(traceHashSizeFooterVisible(hashSize = 2))
        assertTrue(traceHashSizeFooterVisible(hashSize = 4))
    }
}
