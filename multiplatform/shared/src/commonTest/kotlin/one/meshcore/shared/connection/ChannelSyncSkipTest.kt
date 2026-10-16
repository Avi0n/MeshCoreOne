package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChannelSyncSkipTest {
    @Test
    fun esp32HasAThirtySecondWindowAndOthersHaveNone() {
        assertEquals(30.0, DevicePlatform.ESP32.channelSyncSkipWindowSeconds)
        assertEquals(0.0, DevicePlatform.NRF52.channelSyncSkipWindowSeconds)
        assertEquals(0.0, DevicePlatform.UNKNOWN.channelSyncSkipWindowSeconds)
    }

    @Test
    fun aRecentCleanPassIsSkipped() {
        assertTrue(
            shouldSkipChannelSync(
                forceFullSync = false,
                skipWindowSeconds = WINDOW,
                nowEpochSeconds = 100.0,
                lastCleanAt = 90.0,
                lastAttemptedAt = null,
            ),
        )
    }

    @Test
    fun aRecentAttemptIsSkipped() {
        assertTrue(
            shouldSkipChannelSync(
                forceFullSync = false,
                skipWindowSeconds = WINDOW,
                nowEpochSeconds = 100.0,
                lastCleanAt = null,
                lastAttemptedAt = 95.0,
            ),
        )
    }

    @Test
    fun anExpiredCleanPassRuns() {
        assertFalse(
            shouldSkipChannelSync(
                forceFullSync = false,
                skipWindowSeconds = WINDOW,
                nowEpochSeconds = 100.0,
                lastCleanAt = 60.0,
                lastAttemptedAt = null,
            ),
        )
    }

    @Test
    fun forceFullSyncBypassesTheWindow() {
        assertFalse(
            shouldSkipChannelSync(
                forceFullSync = true,
                skipWindowSeconds = WINDOW,
                nowEpochSeconds = 100.0,
                lastCleanAt = 99.0,
                lastAttemptedAt = 99.0,
            ),
        )
    }

    @Test
    fun aZeroWindowNeverSkips() {
        assertFalse(
            shouldSkipChannelSync(
                forceFullSync = false,
                skipWindowSeconds = 0.0,
                nowEpochSeconds = 100.0,
                lastCleanAt = 99.0,
                lastAttemptedAt = 99.0,
            ),
        )
    }

    private companion object {
        const val WINDOW = 30.0
    }
}
