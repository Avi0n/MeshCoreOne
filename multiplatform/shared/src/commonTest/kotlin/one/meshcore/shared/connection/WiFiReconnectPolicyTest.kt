package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The WiFi reconnect schedule values — the separate variant from the BLE [ReconnectPolicy]. Backoff is
 * `min(0.5 · 2^(attempt-1), 4)` seconds: 0.5, 1, 2, 4, then capped at 4.
 */
class WiFiReconnectPolicyTest {
    private val policy = WiFiReconnectPolicy()

    @Test
    fun backoffFollowsCappedExponentialSchedule() {
        assertEquals(500.milliseconds, policy.backoff(1))
        assertEquals(1.seconds, policy.backoff(2))
        assertEquals(2.seconds, policy.backoff(3))
        assertEquals(4.seconds, policy.backoff(4))
        assertEquals(4.seconds, policy.backoff(5))
        assertEquals(4.seconds, policy.backoff(10))
    }

    @Test
    fun backoffRejectsNonPositiveAttempt() {
        assertFailsWith<IllegalArgumentException> { policy.backoff(0) }
    }

    @Test
    fun cooldownAndWindowAndHeartbeatMatchSwift() {
        assertEquals(35.seconds, policy.cooldown)
        assertEquals(30.seconds, policy.window)
        assertEquals(30.seconds, policy.heartbeatInterval)
    }
}
