package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.disconnect
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
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The live-update wiring an open room conversation depends on: incoming messages reaching the screen, a
 * session-state change refreshing the connection banner, and the keep-alive that runs only while the
 * conversation is on screen. Time is stepped explicitly because the keep-alive loop never idles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoomConversationLiveUpdateTest {
    private class Fixture(scope: CoroutineScope) {
        val nodeSession = FakeNodeSession()
        val store = InMemoryRoomStore()
        val passwords = InMemoryNodePasswordStore()
        val roomServerSession = FakeRoomServerSession()
        val remoteNode = RemoteNodeService(nodeSession, store, passwords, scope)
        val service = RoomServerService(
            remoteNode,
            store,
            roomServerSession,
            passwords,
            scope,
            edges = RoomServerEdges(now = { NOW }),
        )
    }

    private fun TestScope.viewModel(f: Fixture, session: RemoteNodeSessionDto): RoomConversationViewModel =
        RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { f.service },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )

    @Test
    fun incomingRoomMessageReachesTheOpenConversation() = runTest {
        val f = Fixture(backgroundScope)
        val session = roomSession(isConnected = true)
        f.store.saveSession(session)
        val vm = viewModel(f, session)
        runCurrent()
        assertTrue(vm.state.value.messages.isEmpty())

        f.service.handleIncomingMessage(
            senderPublicKeyPrefix = testPublicKey().copyOf(6),
            timestamp = NOW,
            authorPrefix = byteArrayOf(9, 9, 9, 9),
            text = "hello room",
        )
        runCurrent()

        assertEquals(listOf("hello room"), vm.state.value.messages.map { it.text })
        assertEquals(0, vm.state.value.unreadWhileScrolledUp)
    }

    @Test
    fun incomingWhileScrolledUpBumpsTheUnreadPill() = runTest {
        val f = Fixture(backgroundScope)
        val session = roomSession(isConnected = true)
        f.store.saveSession(session)
        val vm = viewModel(f, session)
        runCurrent()
        vm.setNearBottom(false)

        f.service.handleIncomingMessage(
            senderPublicKeyPrefix = testPublicKey().copyOf(6),
            timestamp = NOW,
            authorPrefix = byteArrayOf(9, 9, 9, 9),
            text = "later",
        )
        runCurrent()

        assertEquals(1, vm.state.value.unreadWhileScrolledUp)
        assertFalse(vm.state.value.isAtBottom)
        vm.requestScrollToBottom()
        assertEquals(0, vm.state.value.unreadWhileScrolledUp)
        assertTrue(vm.state.value.isAtBottom)
    }

    @Test
    fun sessionDisconnectRefreshesTheConversationBanner() = runTest {
        val f = Fixture(backgroundScope)
        val session = roomSession(isConnected = true)
        f.store.saveSession(session)
        f.store.setContactRouting(session.radioID, session.publicKey, isFloodRouted = false)
        val vm = viewModel(f, session)
        runCurrent()
        assertTrue(vm.state.value.isConnected)
        assertTrue(vm.state.value.canPost)

        f.remoteNode.disconnect(session.id)
        runCurrent()

        assertFalse(vm.state.value.isConnected)
    }

    @Test
    fun keepAliveRunsWhileOnScreenAndStopsOnDispose() = runTest {
        val f = Fixture(backgroundScope)
        val session = roomSession(isConnected = true)
        f.store.saveSession(session)
        f.store.setContactRouting(session.radioID, session.publicKey, isFloodRouted = false)
        val vm = viewModel(f, session)
        runCurrent()

        assertEquals(1, f.nodeSession.sendKeepAliveCalls.size, "the first tick fires immediately")
        advanceTimeBy(KEEP_ALIVE_STEP)
        runCurrent()
        assertEquals(2, f.nodeSession.sendKeepAliveCalls.size)

        vm.dispose()
        runCurrent()
        advanceTimeBy(KEEP_ALIVE_STEP)
        runCurrent()

        assertEquals(2, f.nodeSession.sendKeepAliveCalls.size, "dispose must stop the loop")
    }

    @Test
    fun disconnectedConversationStartsNoKeepAlive() = runTest {
        val f = Fixture(backgroundScope)
        val session = roomSession(isConnected = false)
        f.store.saveSession(session)
        f.store.setContactRouting(session.radioID, session.publicKey, isFloodRouted = false)
        viewModel(f, session)
        runCurrent()
        advanceTimeBy(RELOAD_STEP)
        runCurrent()

        assertTrue(f.nodeSession.sendKeepAliveCalls.isEmpty())
    }

    @Test
    fun graphChangeObservationMovesToReplacementFlow() = runTest {
        val initial = Fixture(backgroundScope)
        val replacement = Fixture(backgroundScope)
        val session = roomSession(isConnected = false)
        initial.store.saveSession(session)
        replacement.store.saveSession(session.copy(name = "Replacement Room"))
        var currentService = initial.service
        val retiredChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val replacementChanges = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val vm = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { currentService },
                servicesChanged = retiredChanges,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        runCurrent()

        currentService = replacement.service
        vm.bindServicesChanged(replacementChanges)
        runCurrent()
        retiredChanges.tryEmit(Unit)
        runCurrent()
        assertEquals("Test Room", vm.state.value.sessionName)

        replacementChanges.tryEmit(Unit)
        runCurrent()
        assertEquals("Replacement Room", vm.state.value.sessionName)
    }

    private companion object {
        const val NOW = 1_720_000_000L
        val KEEP_ALIVE_STEP = 91.seconds
        val RELOAD_STEP = 100.milliseconds
    }
}
