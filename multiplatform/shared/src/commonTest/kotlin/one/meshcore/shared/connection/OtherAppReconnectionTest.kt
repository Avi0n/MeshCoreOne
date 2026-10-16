package one.meshcore.shared.connection

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OtherAppReconnectionTest {
    @Test
    fun firstCheckDetectsWithoutSleeping() = runTest {
        var delays = 0
        val held = waitForOtherAppReconnection(
            isHeldNow = { true },
            delayMs = { delays++ },
        )
        assertTrue(held)
        assertEquals(0, delays)
    }

    @Test
    fun laterCheckDetectsAfterSleeps() = runTest {
        var polls = 0
        var delays = 0
        val held = waitForOtherAppReconnection(
            isHeldNow = {
                polls++
                polls >= 3
            },
            delayMs = { delays++ },
        )
        assertTrue(held)
        assertEquals(3, polls)
        assertEquals(2, delays)
    }

    @Test
    fun sixEmptyChecksReturnFalse() = runTest {
        var polls = 0
        var delays = 0
        val held = waitForOtherAppReconnection(
            isHeldNow = {
                polls++
                false
            },
            delayMs = { delays++ },
        )
        assertFalse(held)
        assertEquals(OTHER_APP_RECONNECTION_MAX_CHECKS, polls)
        assertEquals(OTHER_APP_RECONNECTION_MAX_CHECKS - 1, delays)
    }

    @Test
    fun cancelOnFirstCheckDoesNotPollOrSleep() = runTest {
        var polls = 0
        var delays = 0
        val held = waitForOtherAppReconnection(
            isHeldNow = {
                polls++
                false
            },
            isCancelled = { true },
            delayMs = { delays++ },
        )
        assertFalse(held)
        assertEquals(0, polls)
        assertEquals(0, delays)
    }

    @Test
    fun companionPairUsesLiveGattWhileDisconnected() = runTest {
        val handle = "AA:BB:CC:DD:EE:FF"
        var polls = 0
        val held = waitForOtherAppReconnection(
            isHeldNow = {
                polls++
                bleHandleIsHeldByOtherApp(
                    handle,
                    DeviceConnectionState.DISCONNECTED,
                    if (polls >= 2) setOf("aa:bb:cc:dd:ee:ff") else emptySet(),
                )
            },
            delayMs = {},
        )
        assertTrue(held)
        assertEquals(2, polls)
    }

    @Test
    fun aLinkedSessionIsNeverTreatedAsOtherApp() = runTest {
        val held = waitForOtherAppReconnection(
            isHeldNow = {
                bleHandleIsHeldByOtherApp(
                    "AA:BB:CC:DD:EE:FF",
                    DeviceConnectionState.READY,
                    setOf("AA:BB:CC:DD:EE:FF"),
                )
            },
            delayMs = {},
        )
        assertFalse(held)
    }
}
