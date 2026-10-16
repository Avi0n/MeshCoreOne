package one.meshcore.shared.room.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.room.RoomConversationController
import one.meshcore.shared.room.RoomConversationInputs
import one.meshcore.shared.room.roomMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class RoomSnapshotAdoptionTest {
    @Test
    fun snapshotBeforeInitialLoadKeepsLatestNamePermissionsAndDraft() = runTest {
        val original = ChatFixtures.room("Original")
        val vm = RoomConversationViewModel(
            backgroundScope,
            original,
            RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        vm.setComposingText("draft")
        val updated = original.copy(
            name = "Updated",
            isConnected = true,
            permissionLevel = RoomPermissionLevel.READ_WRITE,
        )
        vm.adoptSessionSnapshot(updated)
        runCurrent()
        assertEquals(updated, vm.currentSession)
        assertEquals("Updated", vm.state.value.sessionName)
        assertEquals("draft", vm.state.value.composingText)
        assertTrue(vm.state.value.canPost)
        assertTrue(vm.state.value.hasLoadedOnce)
        vm.dispose()
    }

    @Test
    fun suspendedRefreshCannotOverwriteAYoungerSnapshot() = runTest {
        val original = ChatFixtures.room("Original")
        val started = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { null },
                scope = backgroundScope,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
                fetchSession = {
                    started.complete(Unit)
                    release.await()
                    original
                },
            ),
        )
        controller.loadMessages(original)
        controller.composingText = "draft"
        val refresh = launch { controller.refreshSession() }
        started.await()
        val updated = original.copy(name = "Updated")
        assertTrue(controller.adoptSessionSnapshot(updated))
        release.complete(Unit)
        refresh.join()
        assertEquals(updated, controller.session)
        assertEquals("draft", controller.composingText)
    }

    @Test
    fun foreignOwnerRotatedKeyAndClosedControllerCannotAdoptSnapshots() = runTest {
        val original = ChatFixtures.room("Original")
        val controller =
            RoomConversationController(
                RoomConversationInputs(
                    roomServerService = { null },
                    scope = backgroundScope,
                    loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                    sendFailedMessage = { ROOM_TEST_SEND_ERROR },
                ),
            )
        controller.loadMessages(original)
        assertFalse(controller.adoptSessionSnapshot(original.copy(id = Uuid.random())))
        assertFalse(controller.adoptSessionSnapshot(original.copy(radioID = Uuid.random())))
        assertFalse(controller.adoptSessionSnapshot(original.copy(publicKey = byteArrayOf(1))))
        controller.close()
        assertFalse(controller.adoptSessionSnapshot(original.copy(name = "Retired")))
        assertEquals(original, controller.session)
    }

    @Test
    fun adoptingSnapshotKeepsExistingMessageWindowAndDraft() = runTest {
        val original = ChatFixtures.room("Original")
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { null },
                scope = backgroundScope,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        controller.loadMessages(original)
        val message = roomMessage(Uuid.random(), original.id, text = "Already visible")
        controller.appendMessageIfNew(message)
        controller.composingText = "draft"
        assertTrue(
            controller.adoptSessionSnapshot(
                original.copy(name = "Updated", permissionLevel = RoomPermissionLevel.READ_WRITE),
            ),
        )
        assertEquals(listOf(message), controller.messages)
        assertEquals("draft", controller.composingText)
        assertTrue(controller.hasLoadedOnce)
    }

    @Test
    fun anOrdinaryReloadStillAcceptsANewerUnadoptedSessionRow() = runTest {
        val original = ChatFixtures.room("Original")
        val controller = RoomConversationController(
            RoomConversationInputs(
                roomServerService = { null },
                scope = backgroundScope,
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        controller.loadMessages(original)
        val updated = original.copy(name = "Updated")
        controller.loadMessages(updated)
        assertEquals(updated, controller.session)
    }
}
