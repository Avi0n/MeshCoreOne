package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionSyncSuppressionFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionSyncSuppressionFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionSyncSuppressionFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.notification.NotificationFixtures
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionSyncSuppressionOwnershipTest {
    @Test
    fun oldInitialReturnCannotUnmuteQualifiedSameGenerationRebuild() = exercise {
        primeA()
        replacementReturn(sameGeneration = true)
    }

    @Test
    fun oldInitialReturnKeepsPublicNewerGenerationMuted() = exercise {
        replacementReturn(sameGeneration = false)
    }

    @Test
    fun ownedInitialCompletionUnmutesAndPostsEligibleNotice() = exercise {
        val old = start()
        backlog(old)
        notifier.probe()
        assertTrue(notifier.poster.directPosts.isEmpty())
        val rows = snapshot()
        finish(old)
        assertEquals(rows, snapshot())
        assertUnmutedNotice(expectedHistory = listOf(true, false))
    }

    @Test
    fun ownedRetiredInitialReturnUnmutesWithoutSuccessor() = exercise {
        val old = start()
        backlog(old)
        manager.disconnect()
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertFalse(manager.wantsConnection)
        assertNull(manager.services)
        assertNull(manager.session)
        assertNull(manager.link)
        assertNull(manager.resyncJob)
        assertTrue(old.caller.isActive)
        assertTrue(notifier.coordinator.isSuppressingNotifications)
        val rows = snapshot()
        val prefs = preferenceSnapshot()
        joinOld(old)
        assertEquals(ConnectionState.Disconnected, manager.state.value)
        assertNull(manager.services)
        assertNull(manager.session)
        assertNull(manager.link)
        assertEquals(rows, snapshot())
        assertEquals(prefs, preferenceSnapshot())
        noOutgoing()
        assertUnmutedNotice(expectedHistory = listOf(true, false))
    }

    @Test
    fun activeScopeResyncReturnCannotUnmutePublicReplacementInitialWindow() = exercise(resync = true) {
        replacementReturn(sameGeneration = false)
    }

    @Test
    fun ownedScopeResyncCompletionUnmutesAndPostsEligibleNotice() = exercise(resync = true) {
        val old = start()
        backlog(old)
        notifier.probe()
        assertTrue(notifier.poster.directPosts.isEmpty())
        assertTrue(requireNotNull(loop).isActive)
        val rows = snapshot()
        val prefs = preferenceSnapshot()
        finish(old)
        assertEquals(rows, snapshot())
        assertEquals(prefs, preferenceSnapshot())
        assertFalse(requireNotNull(loop).isCancelled)
        assertEquals(RESYNC_CALLS, old.session.getMessageCount)
        assertEquals(RESYNC_CALLS, old.session.getContactsSince.size)
        assertEquals(RESYNC_CALLS, old.session.getChannelsCallCount)
        assertUnmutedNotice(expectedHistory = listOf(true, false, true, false))
    }

    private suspend fun ConnectionSyncSuppressionFixture.replacementReturn(sameGeneration: Boolean) {
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
        assertFalse(old.container.isAutoFetching)
        if (resync) {
            assertSame(loop, manager.resyncJob)
            assertTrue(requireNotNull(loop).isActive)
        }
        assertTrue(notifier.coordinator.isSuppressingNotifications)
        val boundary = notifier.history.size
        notifier.probe()
        assertTrue(notifier.poster.directPosts.isEmpty())
        val rows = snapshot()
        val prefs = preferenceSnapshot()
        joinOld(old)
        assertFalse(old.container.contactService.isSyncingContacts)
        assertEquals(if (resync) RESYNC_CALLS else INITIAL_CALLS, old.session.getMessageCount)
        assertEquals(if (resync) INITIAL_CALLS else NO_CALLS, old.session.autoFetchStartCount)
        assertEquals(NO_CALLS, old.session.getTimeCount)
        assertHeld(replacement, if (sameGeneration) DEVICE_A else DEVICE_B)
        assertEquals(rows, snapshot())
        assertEquals(prefs, preferenceSnapshot())
        assertTrue(manager.initialSyncInProgress)
        val priorLoop = manager.resyncJob
        manager.checkSyncHealth()
        assertSame(priorLoop, manager.resyncJob)
        notifier.probe()
        assertHeld(replacement, if (sameGeneration) DEVICE_A else DEVICE_B)
        assertEquals(rows, snapshot())
        assertEquals(prefs, preferenceSnapshot())
        assertTrue(
            notifier.coordinator.isSuppressingNotifications &&
                notifier.history.drop(boundary).none { !it } &&
                notifier.poster.directPosts.isEmpty(),
            "held mute=${notifier.coordinator.isSuppressingNotifications}, " +
                "late=${notifier.history.drop(boundary)}, posts=${notifier.poster.directPosts.size}",
        )
    }

    private suspend fun ConnectionSyncSuppressionFixture.assertUnmutedNotice(expectedHistory: List<Boolean>) {
        assertFalse(notifier.coordinator.isSuppressingNotifications)
        assertNull(manager.syncSuppressionOwner)
        assertEquals(expectedHistory, notifier.history)
        notifier.probe()
        val post = notifier.poster.directPosts.single()
        assertEquals(NotificationFixtures.CONTACT, post.contactID)
        assertEquals(NotificationFixtures.CONTACT, post.messageID)
        assertEquals(ConnectionSyncSuppressionNotifier.PROBE_TEXT, post.body)
        noOutgoing()
    }

    private fun exercise(resync: Boolean = false, block: suspend ConnectionSyncSuppressionFixture.() -> Unit) {
        val fixture = ConnectionSyncSuppressionFixture(resync)
        try {
            runBlocking(fixture.dispatcher) { withTimeout(TIMEOUT_MS) { fixture.block() } }
        } finally {
            fixture.close()
        }
    }

    private companion object {
        const val INITIAL_CALLS = 1
        const val RESYNC_CALLS = 2
        const val NO_CALLS = 0
    }
}
