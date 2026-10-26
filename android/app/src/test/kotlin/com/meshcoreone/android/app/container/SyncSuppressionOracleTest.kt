// AndroidOnly: WP-214 Real notification suppression through the production App adapter and coordinator.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.core.contracts.domain.MessagePollingServiceProtocol
import com.meshcoreone.android.core.services.sync.SyncState
import com.meshcoreone.android.core.services.sync.onDisconnected
import com.meshcoreone.android.core.services.sync.performFullSync
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import org.junit.Test

class SyncSuppressionOracleTest : RoomProcessTest() {
    @Test fun disconnectClearsActualNotificationServiceThroughProductionAdapter() = runTest {
        val h = ContainerHarness(this, store)
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            assertEquals(SyncState.Synced, graph.syncCoordinator.state)
            graph.notificationService.isSuppressingNotifications = true
            val notifications = SyncNotificationsAdapter(graph.notificationService)
            assertTrue(graph.notificationService.isSuppressingNotifications)
            assertTrue(notifications.isSuppressingNotifications())

            graph.syncCoordinator.onDisconnected(notifications)

            assertFalse(graph.notificationService.isSuppressingNotifications)
            assertFalse(notifications.isSuppressingNotifications())
            assertEquals(SyncState.Idle, graph.syncCoordinator.state)
        } finally {
            h.container.close()
        }
    }

    @Test fun actualSuppressionRemainsSetWhilePollIsGatedAndClearsAfterCompletion() = runTest {
        val h = ContainerHarness(this, store)
        val release = CompletableDeferred<Unit>()
        try {
            h.manager.connect(h.target())
            h.settle()
            h.assertReady()
            val graph = assertNotNull(h.sessions.current)
            val notifications = SyncNotificationsAdapter(graph.notificationService)
            graph.notificationService.isSuppressingNotifications = true
            assertTrue(graph.notificationService.isSuppressingNotifications)
            assertTrue(notifications.isSuppressingNotifications())
            val entered = CompletableDeferred<Unit>()
            var polls = 0
            val polling = object : MessagePollingServiceProtocol by graph.messagePollingService {
                override suspend fun pollAllMessages(): Long {
                    polls += 1
                    entered.complete(Unit)
                    release.await()
                    return graph.messagePollingService.pollAllMessages()
                }
            }
            val sync = async {
                graph.syncCoordinator.performFullSync(
                    graph.token.radioId, graph.dataStore, graph.contactService, graph.channelService,
                    polling, h.container.foreground, notificationService = notifications,
                )
            }
            h.eventually("full sync reached gated poll") { entered.isCompleted }
            assertEquals(1, polls)
            assertFalse(sync.isCompleted)
            assertTrue(graph.notificationService.isSuppressingNotifications, "new intermediate oracle, not an original assertion")
            assertTrue(notifications.isSuppressingNotifications())

            release.complete(Unit)
            assertTrue(sync.await().isConnectionUsable)

            assertEquals(1, polls)
            assertFalse(graph.notificationService.isSuppressingNotifications)
            assertFalse(notifications.isSuppressingNotifications())
            assertEquals(SyncState.Synced, graph.syncCoordinator.state)
        } finally {
            release.complete(Unit)
            h.container.close()
        }
    }
}
