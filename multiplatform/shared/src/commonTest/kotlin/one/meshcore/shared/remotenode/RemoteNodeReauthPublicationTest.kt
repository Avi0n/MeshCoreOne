package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeReauthPublicationTest {
    @Test
    fun retirementAfterCommittedDisconnectSuppressesItsEventWithoutRollingBackTheRow() = runTest {
        val backing = InMemoryRemoteNodeStore()
        val dto = testSession(isConnected = true, permissionLevel = RoomPermissionLevel.ADMIN).copy(
            lastConnectedDate = CONNECTED_DATE,
            lastMessageDate = CONNECTED_DATE,
            notificationLevel = NotificationLevel.MENTIONS_ONLY,
            isPinned = true,
            unreadCount = UNREAD,
            neighborCount = NEIGHBORS,
        )
        backing.saveSession(dto)
        lateinit var service: RemoteNodeService
        var writes = 0
        val store = object : RemoteNodeStore by backing {
            override suspend fun markSessionDisconnected(id: Uuid) {
                assertEquals(dto.id, id)
                backing.markSessionDisconnected(id)
                writes += 1
                service.retireConnectionOperations()
            }
        }
        val node = FakeNodeSession()
        service = RemoteNodeService(node, store, InMemoryNodePasswordStore(), backgroundScope)
        val events = mutableListOf<RemoteNodeEvent>()
        val subscribed = CompletableDeferred<Unit>()
        val collector = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
            service.events.onSubscription { subscribed.complete(Unit) }.collect { events += it }
        }
        subscribed.await()
        assertTrue(collector.isActive)
        val operation = backgroundScope.async { service.handleBLEReconnection(setOf(dto.id)) }
        runCurrent()
        val failure = runCatching { operation.await() }.exceptionOrNull()
        assertTrue(failure == null || failure is CancellationException)
        runCurrent()

        val actual = requireNotNull(backing.fetchSessionById(dto.id))
        assertContentEquals(dto.publicKey, actual.publicKey)
        assertEquals(dto.copy(isConnected = false, publicKey = actual.publicKey), actual)
        assertEquals(1, writes)
        assertTrue(node.sendLoginCalls.isEmpty())
        assertTrue(operation.isCompleted)
        assertTrue(backgroundScope.coroutineContext.job.isActive)
        assertTrue(collector.isActive)
        assertTrue(events.isEmpty())
    }

    companion object {
        private const val CONNECTED_DATE = 1_700_000_000.0
        private const val UNREAD = 7
        private const val NEIGHBORS = 4
    }
}
