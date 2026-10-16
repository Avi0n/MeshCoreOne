package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionBleFailureExitFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionBleFailureExitFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionBleFailureExitFixture.Companion.TIMEOUT_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionBleFailureExitOwnershipTest {
    @Test
    fun exhaustedOldRetryCannotDemoteReplacementOrOpenItsCircuit() = withFixture(attempts = ONE) {
        verifyGenericReplacement()
    }

    @Test
    fun oldRetryWithRemainingBudgetCannotLeaveReplacementConnecting() = withFixture(attempts = TWO) {
        verifyGenericReplacement()
    }

    @Test
    fun oldDefinitiveBondExitCannotDemoteReplacement() = withFixture(bond = true) {
        val caller = failedBond()
        val retiredGeneration = manager.connectGeneration
        val fresh = replacement()
        assertTrue(fresh.generation > retiredGeneration)
        assertResources(fresh)
        val before = storedRows()
        val prefsBefore = preferenceSnapshot()
        val boundary = states.size
        val circuitBefore = manager.circuitBreaker.state
        bondLink.release.complete(Unit)
        joinedFailure(caller)
        assertResources(fresh)
        assertEquals(before, storedRows())
        assertEquals(prefsBefore, preferenceSnapshot())
        assertEquals(TWO, linkCount())
        assertEquals(ONE, first.closeCount)
        assertEquals(ONE, firstLink.disconnectCount)
        assertSuccessor(boundary, circuitBefore)
    }

    @Test
    fun currentExhaustedFailureRetainsCircuitAndCleanupPolicy() = withFixture {
        val old = start()
        failedCleanup(old)
        val before = storedRows()
        val prefsBefore = preferenceSnapshot()
        first.stopRelease.complete(Unit)
        joinedFailure(old.caller)
        assertEmptyGraph()
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(ConnectionCircuitBreaker.State.Open(NOW), manager.circuitBreaker.state)
        assertWantedTarget()
        assertTrue(old.container.sendQueue.isRetired)
        assertFalse(old.container.isEventMonitoringActive)
        assertEquals(ONE, first.closeCount)
        assertEquals(ONE, firstLink.disconnectCount)
        assertEquals(ONE, first.autoFetchStopCount)
        assertEquals(ONE, linkCount())
        assertEquals(before, storedRows())
        assertEquals(prefsBefore, preferenceSnapshot())
        assertNoOutgoing()
    }

    @Test
    fun currentOrdinaryFailureStillUsesItsRemainingRetryBudget() = withFixture(attempts = TWO) {
        val old = start()
        failedCleanup(old)
        val before = storedRows()
        first.stopRelease.complete(Unit)
        old.caller.await().getOrThrow()
        barrier()
        val fresh = currentResources(old.caller)
        assertEquals(old.generation, fresh.generation)
        assertEquals(old.container.radioId, fresh.container.radioId)
        assertNotEquals(old.container, fresh.container)
        assertResources(fresh)
        assertWantedTarget()
        assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
        assertEquals(ConnectionCircuitBreaker.State.Closed, manager.circuitBreaker.state)
        assertEquals(TWO, linkCount())
        assertEquals(ONE, first.closeCount)
        assertEquals(ONE, firstLink.disconnectCount)
        assertEquals(ONE, first.autoFetchStopCount)
        assertEquals(before, storedRows())
        assertEquals(DEVICE_A, last.handle())
        assertFalse(old.caller.isCancelled)
    }

    @Test
    fun currentDefinitiveBondFailureRetainsGuidedRepairPolicy() = withFixture(bond = true) {
        val caller = failedBond()
        val before = storedRows()
        val prefsBefore = preferenceSnapshot()
        bondLink.release.complete(Unit)
        joinedFailure(caller)
        assertEmptyGraph()
        assertEquals(ConnectionState.BondLost(DEVICE_A), manager.state.value)
        assertEquals(ConnectionCircuitBreaker.State.Closed, manager.circuitBreaker.state)
        assertWantedTarget()
        assertEquals(ONE, linkCount())
        assertEquals(ONE, first.closeCount)
        assertEquals(ONE, firstLink.disconnectCount)
        assertEquals(before, storedRows())
        assertEquals(prefsBefore, preferenceSnapshot())
        assertNoOutgoing()
    }

    private suspend fun ConnectionBleFailureExitFixture.verifyGenericReplacement() {
        val old = start()
        failedCleanup(old)
        val fresh = replacement()
        assertTrue(fresh.generation > old.generation)
        assertNotEquals(old.container.radioId, fresh.container.radioId)
        assertResources(fresh)
        val before = storedRows()
        val prefsBefore = preferenceSnapshot()
        val boundary = states.size
        val circuitBefore = manager.circuitBreaker.state
        first.stopRelease.complete(Unit)
        joinedFailure(old.caller)
        assertResources(fresh)
        assertEquals(before, storedRows())
        assertEquals(prefsBefore, preferenceSnapshot())
        assertEquals(TWO, linkCount())
        assertEquals(ONE, first.closeCount)
        assertEquals(TWO, first.autoFetchStopCount)
        assertEquals(ONE, firstLink.disconnectCount)
        assertSuccessor(boundary, circuitBefore)
    }

    private fun ConnectionBleFailureExitFixture.assertSuccessor(
        boundary: Int,
        circuitBefore: ConnectionCircuitBreaker.State,
    ) {
        assertEquals(listOf(DEVICE_B, TransportType.BLUETOOTH, null, null), reachability())
        val observed = states.drop(boundary) + manager.state.value
        assertTrue(
            observed.all { it == ConnectionState.Ready(DEVICE_B) } && manager.circuitBreaker.state == circuitBefore,
            "Ordinary BLE failure successor survival: $observed, ${manager.circuitBreaker.state}",
        )
    }

    private fun ConnectionBleFailureExitFixture.assertEmptyGraph() {
        assertNull(manager.services)
        assertNull(manager.session)
        assertNull(manager.link)
        assertTrue(first.closed)
        assertTrue(firstLink.disconnected)
    }

    private fun ConnectionBleFailureExitFixture.assertWantedTarget() {
        assertTrue(manager.wantsConnection)
        assertEquals(listOf(DEVICE_A, TransportType.BLUETOOTH, null, null), reachability())
    }

    private fun withFixture(
        attempts: Int = ONE,
        bond: Boolean = false,
        block: suspend ConnectionBleFailureExitFixture.() -> Unit,
    ) {
        val fixture = ConnectionBleFailureExitFixture(attempts, bond)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }

    companion object {
        private const val ONE = 1
        private const val TWO = 2
        private const val NOW = 1_700_000_000.0
    }
}
