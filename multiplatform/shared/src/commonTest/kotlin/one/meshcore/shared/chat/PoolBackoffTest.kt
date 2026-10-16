package one.meshcore.shared.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Mirrors the Swift `withPoolBackoff` behavior under a virtual clock: immediate success, immediate
 * propagation of a non-transient or wrong-code error, exponential backoff across transient bursts, and
 * re-throw once the attempt cap is spent. Jitter is pinned to 1.0 so the elapsed virtual time is exact.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PoolBackoffTest {
    private val transient = FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND
    private val config = PoolBackoffConfig()
    private val noJitter: () -> Double = { 1.0 }

    @Test
    fun `returns immediately on success without delay`() = runTest {
        var calls = 0
        val result = withPoolBackoff(transient, config, noJitter) {
            calls += 1
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls)
        assertEquals(0L, testScheduler.currentTime)
    }

    @Test
    fun `propagates a non-device error immediately`() = runTest {
        var calls = 0
        assertFailsWith<IllegalStateException> {
            withPoolBackoff(transient, config, noJitter) {
                calls += 1
                error("boom")
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `propagates a device error with a different code immediately`() = runTest {
        var calls = 0
        assertFailsWith<ChatDeviceException> {
            withPoolBackoff(transient, config, noJitter) {
                calls += 1
                throw ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL)
            }
        }
        assertEquals(1, calls)
    }

    @Test
    fun `absorbs transient bursts then succeeds, delaying 500ms then 1s`() = runTest {
        var calls = 0
        val result = withPoolBackoff(transient, config, noJitter) {
            calls += 1
            if (calls <= 2) throw ChatDeviceException(transient)
            "delivered"
        }
        assertEquals("delivered", result)
        assertEquals(3, calls)
        assertEquals(1_500L, testScheduler.currentTime)
    }

    @Test
    fun `rethrows the transient error after exhausting the attempt cap`() = runTest {
        var calls = 0
        assertFailsWith<ChatDeviceException> {
            withPoolBackoff(transient, config, noJitter) {
                calls += 1
                throw ChatDeviceException(transient)
            }
        }
        // cap = 3: four operation calls, delays 500 + 1000 + 2000.
        assertEquals(config.attemptCap + 1, calls)
        assertEquals(3_500L, testScheduler.currentTime)
    }

    @Test
    fun `records the iOS dump line when the attempt cap is spent`() = runTest {
        val logs = mutableListOf<String>()
        assertFailsWith<ChatDeviceException> {
            withPoolBackoff(transient, config, noJitter, log = { logs += it }) {
                throw ChatDeviceException(transient)
            }
        }
        assertEquals(
            listOf(poolBackoffExhaustedLine(config.attemptCap, transient)),
            logs,
        )
    }

    @Test
    fun `does not log when a different device code is thrown`() = runTest {
        val logs = mutableListOf<String>()
        assertFailsWith<ChatDeviceException> {
            withPoolBackoff(transient, config, noJitter, log = { logs += it }) {
                throw ChatDeviceException(FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL)
            }
        }
        assertEquals(emptyList(), logs)
    }
}
