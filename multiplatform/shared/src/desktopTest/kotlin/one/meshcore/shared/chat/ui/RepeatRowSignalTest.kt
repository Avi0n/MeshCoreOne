package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.pathdetail.rssiFormatted
import one.meshcore.shared.chat.pathdetail.snrFormatted
import one.meshcore.shared.chat.pathdetail.snrQuality
import one.meshcore.shared.map.SnrQuality
import one.meshcore.shared.ui.filledBarCount
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A heard-repeat row is a diagnostic: it tells a responder which repeater heard them and how well. The
 * signal column the row renders must therefore carry the bar level, the SNR and the RSSI, matching the
 * right-hand column of the Swift `RepeatRowView`.
 */
class RepeatRowSignalTest {
    private fun repeat(snr: Double? = null, rssi: Int? = null): MessageRepeatDto = MessageRepeatDto(
        id = ChatFixtures.freshUuid(),
        messageID = ChatFixtures.freshUuid(),
        receivedAt = 1_700_000_000.0,
        pathNodes = byteArrayOf(0x31),
        pathLength = 0x01,
        snr = snr,
        rssi = rssi,
    )

    @Test
    fun everyRowCarriesBarsSnrAndRssiInThatOrder() {
        assertEquals(
            listOf(RepeatSignalLine.BARS, RepeatSignalLine.SNR, RepeatSignalLine.RSSI),
            repeatSignalLines,
        )
    }

    @Test
    fun aRowWithNoReadingsShowsThePlaceholderRatherThanDroppingTheLine() {
        val blank = repeat()
        assertEquals("—", blank.snrFormatted("dB"))
        assertEquals("—", blank.rssiFormatted("dBm"))
        assertEquals(SnrQuality.UNKNOWN, blank.snrQuality)
    }

    @Test
    fun theBarFillDensifiesWithSignalQuality() {
        assertEquals(4, SnrQuality.EXCELLENT.filledBarCount)
        assertEquals(3, SnrQuality.GOOD.filledBarCount)
        assertEquals(2, SnrQuality.FAIR.filledBarCount)
        assertEquals(1, SnrQuality.POOR.filledBarCount)
        assertEquals(0, SnrQuality.UNKNOWN.filledBarCount)
    }
}
