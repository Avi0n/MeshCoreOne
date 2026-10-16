package one.meshcore.shared.room.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.chat.ChatConstants
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.room.RoomNotificationSink
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Disconnected no-op coverage for [RoomConversationViewModel]. */
class RoomConversationViewModelTest {
    private lateinit var scope: CoroutineScope

    @BeforeTest
    fun setUp() {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun composingTextUpdatesStateWhenDisconnected() = runBlocking {
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = scope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        vm.setComposingText("hello")
        assertEquals("hello", vm.state.value.composingText)
        assertEquals("Room", vm.state.value.sessionName)
        assertFalse(vm.state.value.canPost)
        assertFalse(vm.state.value.isConnected)
        assertEquals(one.meshcore.shared.model.RoomPermissionLevel.GUEST, vm.state.value.permissionLevel)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendStartsCooldownSoASecondTapIsIgnored() = runTest {
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                isRadioReady = { true },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        vm.setComposingText("hello")
        vm.send()
        assertEquals("", vm.state.value.composingText)
        assertTrue(vm.state.value.isCoolingDown)
        assertEquals(1, vm.state.value.scrollToBottomVersion)
        assertTrue(vm.state.value.isAtBottom)
        vm.setComposingText("again")
        vm.send()
        assertEquals("again", vm.state.value.composingText)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun sendWhileScrolledUpDoesNotYankTheViewport() = runTest {
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                isRadioReady = { true },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        vm.setNearBottom(false)
        val version = vm.state.value.scrollToBottomVersion
        vm.setComposingText("hello")
        vm.send()
        assertEquals("", vm.state.value.composingText)
        assertEquals(version, vm.state.value.scrollToBottomVersion)
        assertFalse(vm.state.value.isAtBottom)
        assertEquals(0, vm.state.value.unreadWhileScrolledUp)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun composerFocusWhileScrolledUpDoesNotYankTheViewport() = runTest {
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        vm.setNearBottom(false)
        val version = vm.state.value.scrollToBottomVersion
        vm.requestPinToBottom()
        assertEquals(version, vm.state.value.scrollToBottomVersion)
        assertFalse(vm.state.value.isAtBottom)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun composerFocusWhileAtTheBottomRePinsTheNewestRow() = runTest {
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        val version = vm.state.value.scrollToBottomVersion
        vm.requestPinToBottom()
        assertEquals(version + 1, vm.state.value.scrollToBottomVersion)
        assertTrue(vm.state.value.isAtBottom)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun requestComposerFocusIncrementsAfterTheSheetDismissalDelay() = runTest {
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = backgroundScope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        assertEquals(0, vm.state.value.focusRequest)
        vm.requestComposerFocus()
        assertEquals(0, vm.state.value.focusRequest)
        advanceTimeBy(ChatConstants.MESSAGE_ACTIONS_DISMISSAL_MS)
        runCurrent()
        assertEquals(1, vm.state.value.focusRequest)
        assertEquals(1, vm.state.value.composer.focusRequest)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun disposeCancelsTheDelayedComposerFocus() = runTest {
        val vm = RoomConversationViewModel(
            scope = backgroundScope,
            session = fakeSession(),
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        runCurrent()
        vm.requestComposerFocus()

        vm.dispose()
        advanceTimeBy(ChatConstants.MESSAGE_ACTIONS_DISMISSAL_MS)
        runCurrent()

        assertEquals(0, vm.state.value.focusRequest)
    }

    @Test
    fun sendLeavesComposerTextWhenTheRadioIsNotReady() = runBlocking {
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = scope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = { null },
                isRadioReady = { false },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        vm.setComposingText("hello")
        vm.send()
        delay(1)
        assertEquals("hello", vm.state.value.composingText)
        assertEquals(0, vm.state.value.scrollToBottomVersion)
    }

    @Test
    fun retryWhenNotReadyDoesNotTouchTheOfflineService() = runBlocking {
        var calls = 0
        val vm = RoomConversationViewModel(
            scope = scope,
            session = fakeSession(),
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = {
                    calls += 1
                    null
                },
                isRadioReady = { false },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        val before = calls
        vm.retry(Uuid.normalized("E0000000-0000-0000-0000-000000000001"))
        delay(1)
        assertEquals(before, calls)
    }

    @Test
    fun sendAgainWhenNotReadyDoesNotTouchTheOfflineService() = runBlocking {
        var calls = 0
        val vm = RoomConversationViewModel(
            scope = scope,
            session = fakeSession(),
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = {
                    calls += 1
                    null
                },
                isRadioReady = { false },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        val before = calls
        vm.sendText("again")
        delay(1)
        assertEquals(before, calls)
    }

    @Test
    fun blankSendAgainDoesNotTouchTheRoomService() = runBlocking {
        var calls = 0
        val vm = RoomConversationViewModel(
            scope = scope,
            session = fakeSession(),
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = {
                    calls += 1
                    null
                },
                isRadioReady = { true },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        val before = calls
        vm.sendText("   ")
        vm.sendText("")
        delay(1)
        assertEquals(before, calls)
        assertEquals("", vm.state.value.composingText)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun clearingARoomConversationErrorDoesNotRadio() = runBlocking {
        var serviceReads = 0
        var readyReads = 0
        val vm = RoomConversationViewModel(
            scope = scope,
            session = fakeSession(),
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = {
                    serviceReads += 1
                    null
                },
                isRadioReady = {
                    readyReads += 1
                    false
                },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        val serviceAfterInit = serviceReads
        val readyAfterInit = readyReads
        assertTrue(serviceAfterInit > 0)
        assertEquals(true, vm.state.value.hasLoadedOnce)

        vm.clearError()
        delay(1)

        val state = vm.state.value
        assertEquals(serviceAfterInit, serviceReads)
        assertEquals(readyAfterInit, readyReads)
        assertNull(state.errorMessage)
        assertEquals("", state.composingText)
        assertTrue(state.messages.isEmpty())
        assertEquals(false, state.isSending)
        assertEquals(false, state.canPost)
        assertEquals("Room", state.sessionName)
    }

    @Test
    fun returningToADisconnectedRoomDoesNotStartKeepAlive() = runBlocking {
        var serviceReads = 0
        var readyReads = 0
        val vm = RoomConversationViewModel(
            scope = scope,
            session = fakeSession(),
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = {
                    serviceReads += 1
                    null
                },
                isRadioReady = {
                    readyReads += 1
                    false
                },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        val serviceAfterInit = serviceReads
        val readyAfterInit = readyReads
        assertTrue(serviceAfterInit > 0)
        assertEquals(0, readyAfterInit)
        assertEquals(false, vm.state.value.isConnected)

        vm.onEnterForeground()
        delay(1)

        val state = vm.state.value
        assertEquals(serviceAfterInit, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(false, state.isConnected)
        assertNull(state.errorMessage)
        assertTrue(state.messages.isEmpty())
        assertEquals(false, state.isSending)
        assertEquals("Room", state.sessionName)
    }

    @Test
    fun disposingADisconnectedRoomDoesNotRadio() = runBlocking {
        var serviceReads = 0
        var readyReads = 0
        var clears = 0
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = scope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = {
                    serviceReads += 1
                    null
                },
                isRadioReady = {
                    readyReads += 1
                    false
                },
                notifications = object : RoomNotificationSink {
                    override suspend fun setActiveRoom(sessionID: Uuid) = Unit

                    override fun clearActiveRoomAsync(sessionID: Uuid) {
                        clears += 1
                    }
                },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        val serviceAfterInit = serviceReads
        assertTrue(serviceAfterInit > 0)
        assertEquals(0, readyReads)
        assertEquals(0, clears)

        vm.dispose()
        delay(1)

        val state = vm.state.value
        assertEquals(serviceAfterInit, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(1, clears)
        assertNull(state.errorMessage)
        assertTrue(state.messages.isEmpty())
        assertEquals(false, state.isConnected)
        assertEquals(false, state.isSending)
        assertEquals("Room", state.sessionName)
    }

    @Test
    fun keepingARoomTranscriptAtTheBottomDoesNotRadio() = runBlocking {
        var serviceReads = 0
        var readyReads = 0
        var clears = 0
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = scope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = {
                    serviceReads += 1
                    null
                },
                isRadioReady = {
                    readyReads += 1
                    false
                },
                notifications = object : RoomNotificationSink {
                    override suspend fun setActiveRoom(sessionID: Uuid) = Unit

                    override fun clearActiveRoomAsync(sessionID: Uuid) {
                        clears += 1
                    }
                },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        val serviceAfterInit = serviceReads
        assertTrue(serviceAfterInit > 0)
        assertEquals(0, readyReads)
        assertEquals(0, clears)
        assertTrue(vm.state.value.isAtBottom)
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.isConnected)
        assertNull(vm.state.value.errorMessage)

        vm.setNearBottom(true)
        delay(1)

        assertEquals(serviceAfterInit, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(0, clears)
        assertTrue(vm.state.value.isAtBottom)
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.isConnected)
        assertNull(vm.state.value.errorMessage)
        assertEquals("Room", vm.state.value.sessionName)
    }

    @Test
    fun keepingAnEmptyRoomComposerDoesNotRadio() = runBlocking {
        var serviceReads = 0
        var readyReads = 0
        var clears = 0
        val session = fakeSession()
        val vm = RoomConversationViewModel(
            scope = scope,
            session = session,
            dependencies = RoomConversationViewModel.Dependencies(
                roomServerService = {
                    serviceReads += 1
                    null
                },
                isRadioReady = {
                    readyReads += 1
                    false
                },
                notifications = object : RoomNotificationSink {
                    override suspend fun setActiveRoom(sessionID: Uuid) = Unit

                    override fun clearActiveRoomAsync(sessionID: Uuid) {
                        clears += 1
                    }
                },
                loadFailedMessage = { ROOM_TEST_LOAD_ERROR },
                sendFailedMessage = { ROOM_TEST_SEND_ERROR },
            ),
        )
        delay(1)
        val serviceAfterInit = serviceReads
        assertTrue(serviceAfterInit > 0)
        assertEquals(0, readyReads)
        assertEquals(0, clears)
        assertEquals("", vm.state.value.composingText)
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.isConnected)
        assertNull(vm.state.value.errorMessage)

        vm.setComposingText("")
        delay(1)

        assertEquals(serviceAfterInit, serviceReads)
        assertEquals(0, readyReads)
        assertEquals(0, clears)
        assertEquals("", vm.state.value.composingText)
        assertTrue(vm.state.value.messages.isEmpty())
        assertFalse(vm.state.value.isConnected)
        assertNull(vm.state.value.errorMessage)
        assertEquals("Room", vm.state.value.sessionName)
    }

    private fun fakeSession() = one.meshcore.shared.backup.RemoteNodeSessionDto(
        id = Uuid.normalized("D0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("D0000000-0000-0000-0000-000000000002"),
        publicKey = ByteArray(32) { 3 },
        name = "Room",
        role = one.meshcore.shared.model.RemoteNodeRole.ROOM_SERVER,
        latitude = 0.0,
        longitude = 0.0,
        isConnected = false,
        permissionLevel = one.meshcore.shared.model.RoomPermissionLevel.GUEST,
        unreadCount = 0,
        notificationLevel = one.meshcore.shared.model.NotificationLevel.ALL,
        isPinned = false,
        neighborCount = 0,
        lastSyncTimestamp = 0,
    )
}
