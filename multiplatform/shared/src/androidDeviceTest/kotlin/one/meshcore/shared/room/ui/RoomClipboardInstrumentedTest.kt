package one.meshcore.shared.room.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.chat.ui.TimestampFormatting
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.NodeSession
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_message_action_copy
import one.meshcore.shared.room.InMemoryRoomStore
import one.meshcore.shared.room.RoomMessageRetry
import one.meshcore.shared.room.RoomServerService
import one.meshcore.shared.room.RoomServerSession
import one.meshcore.shared.ui.HoldingClipboard
import org.jetbrains.compose.resources.stringResource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomClipboardInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val visible = mutableStateOf(true)
    private val clipboard = HoldingClipboard()
    private val fixtureScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val store = InMemoryRoomStore()
    private val node = OfflineSession()
    private lateinit var service: RoomServerService
    private lateinit var copyLabel: String

    @Before
    fun seedRoom() = runBlocking {
        store.seedSession(SESSION)
        store.saveRoomMessage(MESSAGE)
        val passwords = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(node, store, passwords, fixtureScope)
        service = RoomServerService(remote, store, node, passwords, fixtureScope)
    }

    @After
    fun closeFixture() {
        fixtureScope.cancel()
        clipboard.release.complete(Unit)
        assertEquals("Clipboard fixture must not issue radio commands", 0, node.calls)
    }

    @Test
    fun copySurvivesActionSheetDismissalAndRetainsExactDisplayedText() {
        showRoom()
        copyMessage()
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(TEXT, requireNotNull(clipboard.entries.single()).clipData.getItemAt(0).text.toString())
            assertEquals(0, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.completed)
            assertEquals(0, clipboard.cancelled)
        }
    }

    @Test
    fun leavingRoomCancelsWriteThatSurvivedSheetDismissal() {
        showRoom()
        copyMessage()
        compose.onNodeWithText(copyLabel).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(1, clipboard.entries.size)
            assertEquals(0, clipboard.cancelled)
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(1, clipboard.cancelled)
            assertEquals(0, clipboard.completed)
            clipboard.release.complete(Unit)
        }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(0, clipboard.completed) }
    }

    private fun showRoom() {
        compose.setContent {
            TimestampFormatting {
                copyLabel = stringResource(Res.string.chats_message_action_copy)
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    MaterialTheme {
                        if (visible.value) {
                            RoomDetailPane(
                                RoomDetailInputs(
                                    sessionId = SESSION.id.value,
                                    roomServerService = { service },
                                    contactService = { null },
                                    radioId = { SESSION.radioID },
                                    conversationListStore = { null },
                                    roomAdminService = { null },
                                    nodePasswordStore = { null },
                                    servicesChanged = emptyFlow(),
                                    appForeground = emptyFlow(),
                                    onBack = {},
                                ),
                            )
                        }
                    }
                }
            }
        }
    }

    private fun copyMessage() {
        compose.onNodeWithText(TEXT, useUnmergedTree = true).performTouchInput { longClick() }
        compose.onNodeWithText(copyLabel).performClick()
    }

    @Suppress("TooManyFunctions") // Both radio session contracts must reject every command.
    private class OfflineSession :
        NodeSession,
        RoomServerSession {
        var calls = 0
            private set

        override val events = MutableSharedFlow<MeshEvent>()
        override suspend fun addContact(contact: MeshContact): Nothing = unexpected()
        override suspend fun resetPath(publicKey: ByteArray): Nothing = unexpected()
        override suspend fun getMessage(): Nothing = unexpected()
        override suspend fun sendLogin(destination: ByteArray, password: String): Nothing = unexpected()
        override suspend fun sendLogout(destination: ByteArray): Nothing = unexpected()
        override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): Nothing = unexpected()
        override suspend fun sendCommand(destination: ByteArray, command: String): Nothing = unexpected()
        override suspend fun requestStatus(publicKey: ByteArray): Nothing = unexpected()
        override suspend fun requestTelemetry(publicKey: ByteArray): Nothing = unexpected()
        override suspend fun requestOwnerInfo(publicKey: ByteArray): Nothing = unexpected()
        override suspend fun requestNeighbours(
            publicKey: ByteArray,
            count: Int,
            offset: Int,
            orderBy: Int,
            pubkeyPrefixLength: Int,
        ): Nothing = unexpected()
        override suspend fun sendMessageWithRetry(
            destination: ByteArray,
            text: String,
            timestamp: Long,
            retry: RoomMessageRetry,
        ): Nothing = unexpected()
        override suspend fun sendPathDiscovery(destination: ByteArray): Nothing = unexpected()
        override suspend fun requestHistorySync(destination: ByteArray): Nothing = unexpected()

        private fun unexpected(): Nothing {
            calls++
            error("A disconnected clipboard fixture must not issue radio commands")
        }
    }

    private companion object {
        const val TEXT = "日本語 😀\nsecond line"
        val SESSION = RemoteNodeSessionDto(
            id = Uuid.normalized("00000000-0000-0000-0000-000000000001"),
            radioID = Uuid.normalized("00000000-0000-0000-0000-000000000002"),
            publicKey = ByteArray(32) { 0x22 },
            name = "Clipboard fixture",
            role = RemoteNodeRole.ROOM_SERVER,
            latitude = 0.0,
            longitude = 0.0,
            isConnected = false,
            permissionLevel = RoomPermissionLevel.READ_WRITE,
            unreadCount = 0,
            notificationLevel = NotificationLevel.MUTED,
            isPinned = false,
            neighborCount = 0,
            lastSyncTimestamp = 0,
        )
        val MESSAGE = RoomMessageDto(
            id = Uuid.normalized("00000000-0000-0000-0000-000000000003"),
            sessionID = SESSION.id,
            authorKeyPrefix = byteArrayOf(0x12),
            text = TEXT,
            timestamp = 1,
            createdAt = 1.0,
            isFromSelf = false,
            deduplicationKey = "clipboard fixture",
            statusRawValue = MessageStatus.DELIVERED.raw,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            authorName = "Sender",
        )
    }
}
