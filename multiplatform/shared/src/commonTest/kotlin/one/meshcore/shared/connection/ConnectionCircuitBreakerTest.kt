package one.meshcore.shared.connection

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Closed → open on failure, 30s cooldown → half-open probe, probe failure → open, success → closed,
 * and the force bypass. Mirrors Swift `ConnectionManagerCircuitBreakerTests`.
 */
class ConnectionCircuitBreakerTest {
    @Test
    fun closedAllowsConnections() {
        val breaker = breaker(now = 0.0)
        assertTrue(breaker.shouldAllow(force = false))
    }

    @Test
    fun failureTripsTheBreakerAndBlocksDuringCooldown() {
        val breaker = breaker(now = 0.0)
        breaker.recordFailure()
        assertFalse(breaker.shouldAllow(force = false))
    }

    @Test
    fun forceBypassesAnOpenBreaker() {
        val breaker = breaker(now = 0.0)
        breaker.recordFailure()
        assertTrue(breaker.shouldAllow(force = true))
    }

    @Test
    fun elapsedCooldownTransitionsToHalfOpenAndAllowsAProbe() {
        val breaker = breaker(now = 60.0)
        breaker.setOpenForTesting(sinceEpochSeconds = 0.0)
        assertTrue(breaker.shouldAllow(force = false))
        assertTrue(breaker.shouldAllow(force = false))
    }

    @Test
    fun failedHalfOpenProbeReopensTheBreaker() {
        var now = 60.0
        val breaker = ConnectionCircuitBreaker(nowEpochSeconds = { now })
        breaker.setOpenForTesting(sinceEpochSeconds = 0.0)
        assertTrue(breaker.shouldAllow(force = false))
        now = 61.0
        breaker.recordFailure()
        assertFalse(breaker.shouldAllow(force = false))
    }

    @Test
    fun successfulConnectionClosesTheBreaker() {
        val breaker = breaker(now = 0.0)
        breaker.recordFailure()
        breaker.recordSuccess()
        assertTrue(breaker.shouldAllow(force = false))
    }

    @Test
    fun repeatedFailuresWhileOpenKeepTheOriginalCooldownStart() {
        val breaker = breaker(now = 60.0)
        breaker.setOpenForTesting(sinceEpochSeconds = 0.0)
        breaker.recordFailure()
        assertTrue(breaker.shouldAllow(force = false))
    }

    private fun breaker(now: Double) = ConnectionCircuitBreaker(nowEpochSeconds = { now })
}
