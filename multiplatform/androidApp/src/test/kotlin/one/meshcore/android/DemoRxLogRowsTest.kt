package one.meshcore.android

import one.meshcore.shared.settings.ScreenshotChatFixtures
import one.meshcore.shared.tools.DecryptStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DemoRxLogRowsTest {
    @Test
    fun demoRowsKeepFloodThenDirectOrder() {
        val now = DEMO_NOW_MS
        val radio = ScreenshotChatFixtures.RADIO_ID
        val rows = demoFloodRxLogRows(radio, now) + demoDirectRxLogRows(radio, now)

        assertEquals(
            listOf("DEMOFLOOD001", "DEMOFLOOD002", "DEMODIRECT01", "DEMODIRECTOK"),
            rows.map { it.packetHash },
        )
        assertEquals(
            listOf(
                DecryptStatus.SUCCESS,
                DecryptStatus.NO_MATCHING_KEY,
                DecryptStatus.DM_NO_MATCHING_KEY,
                DecryptStatus.SUCCESS,
            ),
            rows.map { it.decryptStatus },
        )
        assertEquals(
            listOf(now, now - ONE_SECOND_MS, now - TWO_SECONDS_MS, now - THREE_SECONDS_MS),
            rows.map { it.receivedAtEpochMs },
        )
    }
}

private const val DEMO_NOW_MS = 1_700_000_000_000L
private const val ONE_SECOND_MS = 1_000L
private const val TWO_SECONDS_MS = 2_000L
private const val THREE_SECONDS_MS = 3_000L
