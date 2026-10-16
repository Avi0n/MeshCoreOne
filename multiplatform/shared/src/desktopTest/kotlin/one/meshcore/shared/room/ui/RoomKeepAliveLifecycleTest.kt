package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.disconnect
import one.meshcore.shared.remotenode.stopAllKeepAlives
import one.meshcore.shared.remotenode.testPublicKey
import one.meshcore.shared.room.FakeRoomServerSession
import one.meshcore.shared.room.InMemoryRoomStore
import one.meshcore.shared.room.RoomServerEdges
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.handleIncomingMessage
import one.meshcore.shared.room.roomSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The two signals that keep an open room logged in across the app's lifecycle, both of which iOS gets from
 * `RoomConversationView` (`.task(id: session.isConnected)` and the `scenePhase` observer): a reconnect
 * rebuilds the service container, and a foreground follows a background that stopped every keep-alive.
 * Without either, the room's login expires while its conversation sits on screen looking connected.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomKeepAliveLifecycleTest {
    /** One per-connection service graph. A reconnect builds a second one and abandons the first. */
    private class Container(scope: CoroutineScope) {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRoomStore()
        val passwords = InMemoryNodePasswordStore()
        val remoteNode = RemoteNodeService(nodeSession, store, passwords, scope)
        val service = RoomServerService(
            remoteNode,
            store,
            FakeRoomServerSession(),
            passwords,
            scope,
            edges = RoomServerEdges(now = { NOW }),
        )

        suspend fun seed(session: RemoteNodeSessionDto) {
            store.saveSession(session)
            // Keep-alives only transmit over a direct route, so the fixture has to look direct-routed.
            store.setContactRouting(session.radioID, session.publicKey, isFloodRouted = false)
        }
    }

    @Test
    fun aReconnectMovesTheOpenRoomOntoTheRebuiltServiceGraph() = runTest {
        val session = roomSession(isConnected = true)
        val first = Container(backgroundScope).apply { seed(session) }
        val second = Container(backgroundScope).apply { seed(session) }
        var live = first.service
        val servicesChanged = MutableStateFlow(0)

        val viewModel = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { live },
                servicesChanged = servicesChanged,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        runCurrent()
        assertEquals(1, first.nodeSession.sendKeepAliveCalls.size, "the room on screen keeps its login alive")

        // The reconnect: the manager tore the first container down and built a second one.
        live = second.service
        servicesChanged.value = 1
        runCurrent()

        assertEquals(
            1,
            second.nodeSession.sendKeepAliveCalls.size,
            "the keep-alive must restart on the container that is actually connected",
        )
        advanceTimeBy(KEEP_ALIVE_STEP)
        runCurrent()
        assertEquals(2, second.nodeSession.sendKeepAliveCalls.size, "and it must keep ticking there")

        second.service.handleIncomingMessage(
            senderPublicKeyPrefix = testPublicKey().copyOf(PREFIX_BYTES),
            timestamp = NOW,
            authorPrefix = byteArrayOf(9, 9, 9, 9),
            text = "after the reconnect",
        )
        runCurrent()
        assertEquals(
            listOf("after the reconnect"),
            viewModel.state.value.messages.map { it.text },
            "the conversation must be listening to the live container, not the dead one",
        )
    }

    @Test
    fun theForegroundEdgeRestartsTheKeepAliveBackgroundingStopped() = runTest {
        val session = roomSession(isConnected = true)
        val container = Container(backgroundScope).apply { seed(session) }

        val viewModel = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { container.service },
                servicesChanged = emptyFlow(),
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        runCurrent()
        assertEquals(1, container.nodeSession.sendKeepAliveCalls.size)

        // What backgrounding does: the app-state hook stops every loop without touching isConnected, so
        // nothing about the session changes and no other signal can bring the loop back.
        container.remoteNode.stopAllKeepAlives()
        runCurrent()
        advanceTimeBy(KEEP_ALIVE_STEP)
        runCurrent()
        assertEquals(1, container.nodeSession.sendKeepAliveCalls.size, "background must stop the loop")

        viewModel.onEnterForeground()
        runCurrent()

        assertEquals(2, container.nodeSession.sendKeepAliveCalls.size, "foreground must restart it")
        advanceTimeBy(KEEP_ALIVE_STEP)
        runCurrent()
        assertTrue(container.nodeSession.sendKeepAliveCalls.size >= 3, "and the restarted loop must keep ticking")
    }

    @Test
    fun inboundConnectionRecoveryRestartsKeepAlive() = runTest {
        val session = roomSession(isConnected = true)
        val container = Container(backgroundScope).apply { seed(session) }

        val viewModel = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { container.service },
                servicesChanged = emptyFlow(),
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        runCurrent()
        assertEquals(1, container.nodeSession.sendKeepAliveCalls.size)

        container.remoteNode.disconnect(session.id)
        runCurrent()
        assertFalse(viewModel.state.value.isConnected)
        advanceTimeBy(KEEP_ALIVE_STEP)
        runCurrent()
        assertEquals(1, container.nodeSession.sendKeepAliveCalls.size, "disconnect must stop the loop")

        container.service.handleIncomingMessage(
            senderPublicKeyPrefix = testPublicKey().copyOf(PREFIX_BYTES),
            timestamp = NOW,
            authorPrefix = byteArrayOf(9, 9, 9, 9),
            text = "after recovery",
        )
        runCurrent()

        assertTrue(viewModel.state.value.isConnected)
        assertEquals(
            2,
            container.nodeSession.sendKeepAliveCalls.size,
            "inbound recovery must restart keep-alive",
        )
        advanceTimeBy(KEEP_ALIVE_STEP)
        runCurrent()
        assertTrue(
            container.nodeSession.sendKeepAliveCalls.size >= 3,
            "and the restarted loop must keep ticking",
        )
    }

    @Test
    fun keepAliveDoesNotStartAgainstAnOfflineServiceWhenTheRadioIsNotReady() = runTest {
        val session = roomSession(isConnected = true)
        val container = Container(backgroundScope).apply { seed(session) }
        RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { container.service },
                isRadioReady = { false },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        runCurrent()
        assertEquals(0, container.nodeSession.sendKeepAliveCalls.size)
        advanceTimeBy(KEEP_ALIVE_STEP)
        runCurrent()
        assertEquals(0, container.nodeSession.sendKeepAliveCalls.size)
    }

    private companion object {
        const val NOW = 1_720_000_000L
        const val PREFIX_BYTES = 6

        /** One tick past the 90-second cadence both platforms share. */
        val KEEP_ALIVE_STEP = 91.seconds
    }
}
