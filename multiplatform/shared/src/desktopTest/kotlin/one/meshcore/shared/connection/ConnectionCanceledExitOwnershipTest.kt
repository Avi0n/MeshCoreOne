package one.meshcore.shared.connection

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionCanceledExitFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionCanceledExitFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionCanceledExitFixture.Companion.TIMEOUT_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionCanceledExitOwnershipTest {
    @Test
    fun canceledInitialExitCannotDemotePublicReplacement() = withFixture {
        verifyReplacement(rebuild = false)
    }

    @Test
    fun canceledInitialExitCannotDemoteInternalSameGenerationRebuild() = withFixture {
        verifyReplacement(rebuild = true)
    }

    @Test
    fun currentCancellationStillCleansItsHalfBuiltConnection() = withFixture {
        val old = start()
        val before = storedRows()
        val intentBefore = persistedIntent()
        canceledCleanup(old)
        first.stopRelease.complete(Unit)
        joinedCancellation(old)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.services)
        assertNull(manager.session)
        assertNull(manager.link)
        assertTrue(first.closed)
        assertTrue(old.link.disconnected)
        assertTrue(old.container.sendQueue.isRetired)
        assertFalse(old.container.isEventMonitoringActive)
        assertTrue(manager.wantsConnection)
        assertEquals(DEVICE_A, manager.currentDeviceId)
        assertEquals(TransportType.BLUETOOTH, manager.currentTransportType)
        assertEquals(1, first.closeCount)
        assertEquals(1, first.autoFetchStopCount)
        assertEquals(before, storedRows())
        assertEquals(intentBefore, persistedIntent())
        assertNoOutgoing()
    }

    @Test
    fun cancellationAfterReadyPreservesLiveConnectionDuringClockAwait() = withFixture {
        first.getTimeGate = CompletableDeferred()
        val live = start()
        first.contactsGate?.complete(Unit)
        assertActiveOwner(live.caller, first.clockEntered.await())
        barrier()
        assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
        val before = storedRows()
        val intentBefore = persistedIntent()
        val boundary = states.size
        live.caller.cancel(cancellation)
        joinedCancellation(live)
        assertResources(live, DEVICE_A)
        assertEquals(0, first.closeCount)
        assertEquals(0, first.autoFetchStopCount)
        assertEquals(before, storedRows())
        assertEquals(intentBefore, persistedIntent())
        val observed = states.drop(boundary) + manager.state.value
        assertTrue(observed.all { it == ConnectionState.Ready(DEVICE_A) })
    }

    private suspend fun ConnectionCanceledExitFixture.verifyReplacement(rebuild: Boolean) {
        val old = start()
        canceledCleanup(old)
        val retirementGeneration = manager.connectGeneration
        val fresh = replacement(rebuild)
        val handle = if (rebuild) DEVICE_A else DEVICE_B
        if (rebuild) {
            assertEquals(retirementGeneration, fresh.generation)
            assertEquals(old.container.radioId, fresh.container.radioId)
            assertSame(old.link, fresh.link)
        } else {
            assertTrue(fresh.generation > retirementGeneration)
            assertNotEquals(old.container.radioId, fresh.container.radioId)
            assertTrue(old.link.disconnected)
        }
        assertNotEquals(old.container, fresh.container)
        assertNotEquals(old.session, fresh.session)
        assertResources(fresh, handle)
        assertEquals(ConnectionState.Ready(handle), manager.state.value)
        assertTrue(first.closed)
        assertEquals(1, first.closeCount)
        assertEquals(2, first.autoFetchStopCount)
        val before = storedRows()
        val intentBefore = persistedIntent()
        val boundary = states.size
        first.stopRelease.complete(Unit)
        joinedCancellation(old)
        assertResources(fresh, handle)
        assertEquals(1, first.closeCount)
        assertEquals(2, first.autoFetchStopCount)
        assertEquals(before, storedRows())
        assertEquals(intentBefore, persistedIntent())
        val observed = states.drop(boundary) + manager.state.value
        assertTrue(
            observed.all { it == ConnectionState.Ready(handle) },
            "Canceled exit successor state survival: $observed",
        )
    }

    private fun withFixture(block: suspend ConnectionCanceledExitFixture.() -> Unit) {
        val fixture = ConnectionCanceledExitFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
