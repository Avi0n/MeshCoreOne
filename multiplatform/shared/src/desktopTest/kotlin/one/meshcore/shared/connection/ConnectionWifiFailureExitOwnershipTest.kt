package one.meshcore.shared.connection

import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionWifiFailureExitFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionWifiFailureExitFixture.Companion.HOST_A
import one.meshcore.shared.connection.ConnectionWifiFailureExitFixture.Companion.PORT_A
import one.meshcore.shared.connection.ConnectionWifiFailureExitFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.connection.ConnectionWifiFailureExitFixture.Companion.WIFI_A
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionWifiFailureExitOwnershipTest {
    @Test
    fun oldWifiFailureCannotClearReplacementAfterTeardown() = withFixture {
        verifyReplacement(holdHandleRead = false)
    }

    @Test
    fun oldWifiFailureCannotClearReplacementAfterSavedHandleRead() = withFixture {
        verifyReplacement(holdHandleRead = true)
    }

    @Test
    fun currentWifiFailureRetainsOrdinaryCleanupAndOriginalError() = withFixture {
        val old = start()
        failedCleanup(old)
        val before = storedRows()
        val prefsBefore = preferenceSnapshot()
        first.stopRelease.complete(Unit)
        joinedFailure(old)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertEquals(listOf(null, null, null, null), reachability())
        assertNull(manager.services)
        assertNull(manager.session)
        assertNull(manager.link)
        assertTrue(manager.wantsConnection)
        assertTrue(first.closed)
        assertTrue(old.link.disconnected)
        assertEquals(1, first.closeCount)
        assertEquals(1, first.autoFetchStopCount)
        assertTrue(old.container.sendQueue.isRetired)
        assertFalse(old.container.isEventMonitoringActive)
        assertEquals(before, storedRows())
        assertEquals(prefsBefore, preferenceSnapshot())
        assertEquals(WIFI_A, last.handle())
        assertNoOutgoing()
    }

    @Test
    fun successfulWifiStillKeepsItsLiveGraphAndEndpoints() = withFixture {
        failCallback = false
        val live = start()
        first.contactsGate?.complete(Unit)
        live.caller.await().getOrThrow()
        barrier()
        assertResources(live)
        assertEquals(ConnectionState.Ready(WIFI_A), manager.state.value)
        assertEquals(listOf(WIFI_A, TransportType.WIFI, HOST_A, PORT_A), reachability())
        assertEquals(WIFI_A, last.handle())
        assertEquals(0, first.closeCount)
        assertEquals(0, first.autoFetchStopCount)
    }

    private suspend fun ConnectionWifiFailureExitFixture.verifyReplacement(holdHandleRead: Boolean) {
        val old = start()
        failedCleanup(old)
        if (holdHandleRead) {
            prefs.armed = true
            first.stopRelease.complete(Unit)
            val (context, captured) = prefs.entered.await()
            assertActiveOwner(old.caller, context.job)
            assertEquals(WIFI_A, captured)
            assertNull(manager.services)
            assertNull(manager.session)
            assertNull(manager.link)
            assertEquals(listOf(WIFI_A, TransportType.WIFI, HOST_A, PORT_A), reachability())
            assertTrue(first.closed)
            assertTrue(old.link.disconnected)
            assertEquals(1, first.autoFetchStopCount)
        }
        val fresh = replacement()
        assertTrue(fresh.generation > old.generation)
        assertNotEquals(old.container.radioId, fresh.container.radioId)
        assertNotEquals(old.container, fresh.container)
        assertNotEquals(old.session, fresh.session)
        assertResources(fresh)
        assertEquals(ConnectionState.Ready(DEVICE_B), manager.state.value)
        assertEquals(listOf(DEVICE_B, TransportType.BLUETOOTH, null, null), reachability())
        assertTrue(first.closed)
        assertTrue(old.link.disconnected)
        assertEquals(1, first.closeCount)
        val stopCount = if (holdHandleRead) 1 else 2
        assertEquals(stopCount, first.autoFetchStopCount)
        val before = storedRows()
        val prefsBefore = preferenceSnapshot()
        val boundary = states.size
        if (holdHandleRead) prefs.release.complete(Unit) else first.stopRelease.complete(Unit)
        joinedFailure(old)
        assertResources(fresh)
        assertEquals(1, first.closeCount)
        assertEquals(stopCount, first.autoFetchStopCount)
        assertEquals(before, storedRows())
        assertEquals(prefsBefore, preferenceSnapshot())
        val observed = states.drop(boundary) + manager.state.value
        assertTrue(
            reachability() == listOf(DEVICE_B, TransportType.BLUETOOTH, null, null) &&
                observed.all { it == ConnectionState.Ready(DEVICE_B) },
            "Ordinary WiFi failure successor survival: ${reachability()}, $observed",
        )
    }

    private fun withFixture(block: suspend ConnectionWifiFailureExitFixture.() -> Unit) {
        val fixture = ConnectionWifiFailureExitFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
