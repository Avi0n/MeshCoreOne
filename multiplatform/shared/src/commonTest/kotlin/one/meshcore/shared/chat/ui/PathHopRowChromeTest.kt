package one.meshcore.shared.chat.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** iOS `PathHopRowView` shows trailing bars + compact SNR only on the receiver hop. */
class PathHopRowChromeTest {
    @Test
    fun onlyTheReceiverWithSnrShowsTrailingSignal() {
        assertTrue(pathHopShowsTrailingSignal(isReceiver = true, hasSnr = true))
        assertFalse(pathHopShowsTrailingSignal(isReceiver = true, hasSnr = false))
        assertFalse(pathHopShowsTrailingSignal(isReceiver = false, hasSnr = true))
    }

    @Test
    fun compactSnrLineMatchesIosOneDecimal() {
        assertEquals(
            "SNR 6.2 dB",
            pathHopCompactSnrVisibleText(snrFormatted = "6.2", snrWord = "SNR", unit = "dB"),
        )
    }
}
