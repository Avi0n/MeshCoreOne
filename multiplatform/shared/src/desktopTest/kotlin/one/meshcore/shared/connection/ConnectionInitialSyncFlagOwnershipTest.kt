package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionInitialSyncFlagFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionInitialSyncFlagFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionInitialSyncFlagFixture.Companion.TIMEOUT_MS
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionInitialSyncFlagOwnershipTest {
    @Test
    fun oldCompletedBacklogCannotClearPublicReplacementInitialSync() = exercise {
        obsoleteReturn(sameGeneration = false)
    }

    @Test
    fun oldCompletedBacklogCannotClearQualifiedSameGenerationRebuildInitialSync() = exercise {
        primeA()
        obsoleteReturn(sameGeneration = true)
    }

    @Test
    fun ownedCompletedBacklogClearsItsFlagAndReachesReady() = exercise {
        val old = start()
        backlog(old)
        val before = snapshot()
        manager.checkSyncHealth()
        assertTrue(manager.initialSyncInProgress)
        assertNull(manager.resyncJob)
        finish(old)
        val after = snapshot()
        assertEquals(before, after)
    }

    @Test
    fun heldCurrentContactsRejectHealthRecoveryAndCompleteNormally() = exercise {
        val held = start()
        val rows = snapshot()
        val prefs = preferenceSnapshot()
        manager.checkSyncHealth()
        barrier()
        assertHeld(held, DEVICE_A)
        assertEquals(rows, snapshot())
        assertEquals(prefs, preferenceSnapshot())
        assertTrue(manager.initialSyncInProgress && manager.resyncJob == null)
        finish(held)
    }

    private suspend fun ConnectionInitialSyncFlagFixture.obsoleteReturn(sameGeneration: Boolean) {
        val old = start()
        backlog(old)
        val replacement = replace(sameGeneration)
        assertNotSame(old.container, replacement.container)
        assertNotSame(old.session, replacement.session)
        if (sameGeneration) {
            assertEquals(old.generation, replacement.generation)
            assertSame(old.link, replacement.link)
            assertEquals(old.container.radioId, replacement.container.radioId)
        } else {
            assertTrue(replacement.generation > old.generation)
            assertNotSame(old.link, replacement.link)
            assertTrue(old.container.radioId != replacement.container.radioId)
            assertTrue(old.link.disconnected)
        }
        assertTrue(old.session.closed)
        assertEquals(1, old.session.closeCount)
        assertTrue(old.container.sendQueue.isRetired)
        assertFalse(old.container.isEventMonitoringActive)
        val rows = snapshot()
        val prefs = preferenceSnapshot()
        first.backlogRelease.complete(Unit)
        old.caller.await().getOrThrow()
        barrier()
        assertTrue(old.caller.isCompleted)
        assertFalse(old.caller.isCancelled)
        assertFalse(old.container.contactService.isSyncingContacts)
        assertEquals(1, old.session.getMessageCount)
        assertEquals(0, old.session.autoFetchStartCount)
        assertEquals(0, old.session.getTimeCount)
        assertHeld(replacement, if (sameGeneration) DEVICE_A else DEVICE_B)
        assertEquals(rows, snapshot())
        assertEquals(prefs, preferenceSnapshot())
        assertNull(manager.resyncJob)
        manager.checkSyncHealth()
        barrier()
        assertHeld(replacement, if (sameGeneration) DEVICE_A else DEVICE_B)
        assertEquals(rows, snapshot())
        assertEquals(prefs, preferenceSnapshot())
        assertTrue(
            manager.initialSyncInProgress && manager.resyncJob == null,
            "held flag=${manager.initialSyncInProgress}, resync=${manager.resyncJob != null}",
        )
    }

    private fun exercise(block: suspend ConnectionInitialSyncFlagFixture.() -> Unit) {
        val fixture = ConnectionInitialSyncFlagFixture()
        try {
            runBlocking(fixture.dispatcher) { withTimeout(TIMEOUT_MS) { fixture.block() } }
        } finally {
            fixture.close()
        }
    }
}
