// PortedFrom: MC1Tests/Views/Chats/Components/RoomMessageBubbleA11yLabelTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/Rooms/RoomMessageDTO+Status.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/RemoteNodes/NodeAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.rooms

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import com.meshcoreone.android.core.designsystem.AppColors
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.MaterialRoles
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.accessibleForeground
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.designsystem.toThemeColor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as Rooms
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.list.*
import com.meshcoreone.android.feature.chats.rooms.*
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w1080dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomAccessibilityComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var renderedView: View
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000031"))
    private val session = RemoteNodeSessionDTO(
        id = UUID.fromString("00000000-0000-0000-0000-000000000310"),
        radioId = radio, publicKey = Bytes(ByteArray(32) { 0x42 }), name = "Fixture room",
        role = RemoteNodeRole.ROOM_SERVER, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE,
    )
    private val alice = ContactDTO(
        radioId = radio, publicKey = Bytes(ByteArray(32) { 0x0a }), name = "Alice", lastHeardTimestamp = null,
    )

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun destroyHost() {
        scope.cancel()
        host.pause().stop().destroy()
    }

    private fun text(id: Int) = host.get().getString(id)
    private fun message(body: String = "hello", self: Boolean = false, status: MessageStatus = MessageStatus.DELIVERED) =
        RoomMessageDTO(
            sessionID = session.id, authorKeyPrefix = Bytes.of(if (self) 0x42 else 0x0a),
            authorName = if (self) "Me" else "Alice", text = body, timestamp = 100u,
            createdAt = Instant.ofEpochSecond(100), isFromSelf = self, statusRawValue = status.rawValue,
        )

    private fun show(highContrast: Boolean = false, content: @Composable () -> Unit) {
        host.get().setContent {
            MeshCoreTheme(theme = ThemeRegistry.default, systemDark = false, highContrast = highContrast, motionScale = 0f) {
                val view = LocalView.current
                SideEffect { renderedView = view }
                Box(Modifier.width(390.dp).height(1000.dp)) { content() }
            }
        }
        compose.waitForIdle()
    }

    private fun showRows(
        messages: List<RoomMessageDTO>, retry: (UUID) -> Unit = {},
        link: (String) -> Boolean = { false }, highContrast: Boolean = false,
    ) {
        val rows = RoomConversationStateHolder.rows(messages)
        show(highContrast) {
            RoomConversationContent(
                RoomConversationState(session, messages, rows, hasLoadedOnce = true),
                onDraftChanged = {}, onSend = {}, onRetry = retry, onLink = link,
                onReconnect = {}, onDismissError = {}, failureText = { error("Unexpected failure: $it") },
            )
        }
    }

    private fun assertSingleMessageLabel(label: String) {
        for (unmerged in listOf(false, true)) {
            compose.onAllNodesWithContentDescription(label, useUnmergedTree = unmerged).assertCountEquals(1)
            val config = compose.onNodeWithContentDescription(label, useUnmergedTree = unmerged).fetchSemanticsNode().config
            assertEquals(listOf(label), config[SemanticsProperties.ContentDescription])
            assertTrue("Child speech must be replaced, not appended", config.isClearingSemantics)
            assertFalse("Original body must not be spoken twice", config.contains(SemanticsProperties.Text))
            assertFalse(config.contains(SemanticsProperties.StateDescription))
            assertFalse("A timeline row must not be a live region", config.contains(SemanticsProperties.LiveRegion))
        }
    }

    private fun actions(label: String): List<CustomAccessibilityAction> =
        compose.onNodeWithContentDescription(label).fetchSemanticsNode().config[SemanticsActions.CustomActions]

    private fun invoke(label: String, actionName: String) {
        val action = actions(label).single { it.label == actionName }
        compose.runOnIdle { assertTrue(action.action()) }
        compose.waitForIdle()
    }

    @Test fun incomingFollowUpLabelStillIncludesTheAuthor() {
        val first = message("first")
        val followUp = message("follow up").copy(timestamp = 101u)
        assertFalse(RoomConversationStateHolder.rows(listOf(first, followUp)).last().showSenderName)
        showRows(listOf(first, followUp))
        assertSingleMessageLabel("Alice: follow up")
        assertSingleMessageLabel("Alice: first")
        compose.onAllNodesWithText("Alice", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun incomingClusterStartAnnouncesTheAuthorOnce() {
        showRows(listOf(message()))
        assertSingleMessageLabel("Alice: hello")
        assertEquals(1, Regex("Alice").findAll("Alice: hello").count())
        compose.onNodeWithText("Alice", useUnmergedTree = true).assertExists()
    }

    @Test fun outgoingLabelIncludesStatusOnce() {
        showRows(listOf(message(self = true, status = MessageStatus.SENT)))
        val status = text(Rooms.remoteNodesRoomMessageStatusDeliveredLabel)
        val label = "hello, $status"
        assertSingleMessageLabel(label)
        assertEquals(1, label.windowed(status.length).count { it == status })
        compose.onNodeWithText(text(Chats.chatsMessageStatusSent), useUnmergedTree = true).assertExists()
    }

    @Test fun failedOutgoingExposesAndPerformsTheRealRetryAction() {
        val failed = message(self = true, status = MessageStatus.FAILED)
        val fixture = Fixture(listOf(failed))
        showRoute(fixture)
        val label = "hello, ${text(Rooms.remoteNodesRoomMessageStatusFailedLabel)}"
        assertSingleMessageLabel(label)
        assertEquals(listOf(text(Chats.chatsMessageActionRetry), text(Chats.chatsMessageActionDetails)), actions(label).map { it.label })
        invoke(label, text(Chats.chatsMessageActionRetry))
        assertEquals(listOf(session.id to failed.id), fixture.retries)
        assertEquals(MessageStatus.PENDING, fixture.holder.state.value.messages.single().status)
        assertSingleMessageLabel("hello, ${text(Rooms.remoteNodesRoomMessageStatusSendingLabel)}")
    }

    @Test fun sentOutgoingDoesNotExposeARetryAction() {
        val retried = mutableListOf<UUID>()
        showRows(listOf(message(self = true, status = MessageStatus.SENT)), retry = retried::add)
        val label = "hello, ${text(Rooms.remoteNodesRoomMessageStatusDeliveredLabel)}"
        assertTrue(actions(label).none { it.label == text(Chats.chatsMessageActionRetry) })
        compose.onAllNodesWithText(text(Chats.chatsMessageStatusRetry), useUnmergedTree = true).assertCountEquals(0)
        assertTrue(retried.isEmpty())
    }

    @Test fun httpsBodyYieldsANamedOpenLinkActionThatDispatches() {
        val fixture = Fixture(listOf(message("see https://example.com")))
        showRoute(fixture)
        val label = "Alice: see https://example.com"
        val actionName = Chats.chatsMessageActionOpenWebLink(host.get().resources, "example.com")
        assertSingleMessageLabel(label)
        assertEquals(listOf(actionName, text(Chats.chatsMessageActionDetails)), actions(label).map { it.label })
        invoke(label, actionName)
        assertEquals(listOf("https://example.com"), fixture.external)
        assertTrue(fixture.maps.isEmpty())
    }

    @Test fun everyRoomStatusUsesTheFrozenRoomLabelAndDistinctVisibleStatus() {
        val current = mutableStateOf(message(self = true))
        show {
            val messages = listOf(current.value)
            RoomConversationContent(
                RoomConversationState(session, messages, RoomConversationStateHolder.rows(messages), hasLoadedOnce = true),
                onDraftChanged = {}, onSend = {}, onRetry = {}, onLink = { false },
                onReconnect = {}, onDismissError = {}, failureText = { error("Unexpected failure") },
            )
        }
        val expected = listOf(
            Triple(MessageStatus.PENDING, Rooms.remoteNodesRoomMessageStatusSendingLabel, Chats.chatsMessageStatusSending),
            Triple(MessageStatus.SENDING, Rooms.remoteNodesRoomMessageStatusSendingLabel, Chats.chatsMessageStatusSending),
            Triple(MessageStatus.SENT, Rooms.remoteNodesRoomMessageStatusDeliveredLabel, Chats.chatsMessageStatusSent),
            Triple(MessageStatus.DELIVERED, Rooms.remoteNodesRoomMessageStatusDeliveredLabel, Chats.chatsMessageStatusDelivered),
            Triple(MessageStatus.FAILED, Rooms.remoteNodesRoomMessageStatusFailedLabel, Chats.chatsMessageStatusFailed),
            Triple(MessageStatus.RETRYING, Rooms.remoteNodesRoomMessageStatusSendingLabel, Chats.chatsMessageStatusRetrying),
        )
        for ((status, spoken, visible) in expected) {
            compose.runOnIdle { current.value = current.value.copy(statusRawValue = status.rawValue) }
            assertSingleMessageLabel("hello, ${text(spoken)}")
            compose.onNodeWithText(text(visible), useUnmergedTree = true).assertExists()
        }
    }

    @Test fun linksKeepDocumentOrderDeduplicationAndTheEightActionCapAfterRetry() {
        val urls = (1..10).map { "https://example.com/$it" }
        val body = (listOf(urls.first()) + urls).joinToString(" ")
        val opened = mutableListOf<String>()
        val retried = mutableListOf<UUID>()
        val failed = message(body, self = true, status = MessageStatus.FAILED)
        showRows(listOf(failed), retry = retried::add, link = { opened.add(it); true })
        val label = "$body, ${text(Rooms.remoteNodesRoomMessageStatusFailedLabel)}"
        assertSingleMessageLabel(label)
        val actual = actions(label)
        assertEquals(9, actual.size)
        assertEquals(text(Chats.chatsMessageActionRetry), actual.first().label)
        val webName = Chats.chatsMessageActionOpenWebLink(host.get().resources, "example.com")
        assertEquals(List(8) { webName }, actual.drop(1).map { it.label })
        compose.runOnIdle { actual.forEach { assertTrue(it.action()) } }
        assertEquals(listOf(failed.id), retried)
        assertEquals(urls.take(8), opened)
    }

    @Test fun mentionHashtagAndMapActionsUseTheSharedDispatcherAndRouter() {
        val body = "@[Alice] #ops 38.5, -121.5"
        val fixture = Fixture(listOf(message(body)))
        showRoute(fixture)
        val label = "Alice: $body"
        assertSingleMessageLabel(label)
        val names = listOf(
            Chats.chatsMessageActionOpenMention(host.get().resources, "Alice"),
            Chats.chatsMessageActionOpenHashtag(host.get().resources, "ops"),
            text(Chats.chatsMessageActionOpenMapLink),
        )
        assertEquals(names + text(Chats.chatsMessageActionDetails), actions(label).map { it.label })
        names.forEach { invoke(label, it) }
        assertEquals(listOf(alice), fixture.navigatedContacts)
        assertEquals(listOf("#ops"), fixture.hashtags)
        assertEquals(listOf(38.5 to -121.5), fixture.maps)
        assertTrue("Internal links must not open externally", fixture.external.isEmpty())
    }

    @Test fun rememberPasswordHasOneLabelledSwitchAndItsToggleReachesTheJoinService() {
        val fixture = Fixture(emptyList(), session.copy(isConnected = false))
        showRoute(fixture)
        compose.onNodeWithText(text(Chats.chatsRoomTapToReconnect)).performClick()
        val label = text(Rooms.remoteNodesAuthRememberPassword)
        val switches = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch)
        for (unmerged in listOf(false, true)) {
            compose.onAllNodes(switches, useUnmergedTree = unmerged).assertCountEquals(1)
            compose.onNode(switches, useUnmergedTree = unmerged).assertIsOn().assertHasClickAction()
        }
        val toggle = compose.onNodeWithText(label).assertIsToggleable().assertIsOn().assertHeightIsAtLeast(48.dp)
        toggle.performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        toggle.assertIsOff()
        toggle.performClick().assertIsOn()
        toggle.performClick().assertIsOff()
        compose.onNode(hasSetTextAction()).performTextInput("fixture-password")
        compose.onNodeWithText(text(Rooms.remoteNodesAuthJoinRoom)).performClick()
        compose.waitUntil { fixture.joins.isNotEmpty() }
        assertEquals(listOf(Triple(alice, "fixture-password", false)), fixture.joins)
        assertTrue(fixture.holder.state.value.session.isConnected)
    }

    @Test
    @Config(qualifiers = "es-rES-w1080dp-h1000dp-mdpi")
    fun roomStatusAndLinkActionNamesFollowTheResourceLocale() {
        val fixture = Fixture(listOf(message("https://example.com", self = true, status = MessageStatus.SENT)))
        showRoute(fixture)
        val status = text(Rooms.remoteNodesRoomMessageStatusDeliveredLabel)
        assertNotEquals("Message delivered", status)
        val name = Chats.chatsMessageActionOpenWebLink(host.get().resources, "example.com")
        assertNotEquals("Open Link: example.com", name)
        val label = "https://example.com, $status"
        assertSingleMessageLabel(label)
        assertEquals(listOf(name, text(Chats.chatsMessageActionDetails)), actions(label).map { it.label })
        invoke(label, name)
        assertEquals(listOf("https://example.com"), fixture.external)
    }

    @Test fun anUnconsumedLinkActionDoesNotReportSuccess() {
        showRows(listOf(message("https://example.com")), link = { false })
        val action = actions("Alice: https://example.com").single()
        compose.runOnIdle { assertFalse(action.action()) }
    }

    @Test fun aLinkContactLookupFailureIsVisibleAndDoesNotLoseRoomState() {
        val reported = mutableListOf<Pair<String, Throwable>>()
        val fixture = Fixture(listOf(message()), diagnostic = { operation, failure -> reported += operation to failure })
        showRoute(fixture)
        val failure = java.io.IOException("Fixture contact lookup unavailable")
        compose.runOnIdle {
            fixture.holder.updateDraft("keep this draft")
            fixture.holder.reportLinkFailure(failure)
        }
        compose.onNodeWithText(failure.message!!).assertExists().performClick()
        assertEquals(listOf("load room link contacts" to failure), reported)
        assertNull(fixture.holder.state.value.failure)
        assertEquals("keep this draft", fixture.holder.state.value.draft)
        assertEquals(session, fixture.holder.state.value.session)
        assertSingleMessageLabel("Alice: hello")
    }

    @Test fun mountedRoomBodiesUseTheirActualThemeBubbleAndForegroundPairing() {
        assertMountedRoomBubblePairing(highContrast = false)
    }

    @Test fun increasedContrastRoomSurfacesKeepReadableBodyTextWithoutChangingThePalette() {
        assertMountedRoomBubblePairing(highContrast = true)
    }

    private fun assertMountedRoomBubblePairing(highContrast: Boolean) {
        val outgoing = message("MMMM #ops", self = true, status = MessageStatus.SENT)
        val incoming = message("incoming MMMM #ops").copy(timestamp = 101u)
        val failed = message("failed MMMM #ops", self = true, status = MessageStatus.FAILED).copy(timestamp = 102u)
        showRows(listOf(outgoing, incoming, failed), highContrast = highContrast)
        val frame = ThemeRegistry.default.resolve(ColorScheme.LIGHT, highContrast)
        val roles = MaterialRoles.from(frame)
        val bitmap = compose.runOnUiThread {
            assertTrue(renderedView.width > 0 && renderedView.height > 0)
            Bitmap.createBitmap(renderedView.width, renderedView.height, Bitmap.Config.ARGB_8888)
                .also { renderedView.draw(Canvas(it)) }
        }
        try {
            for ((message, background, preferred) in listOf(
                Triple(outgoing, frame.accent, frame.outgoingText),
                Triple(incoming, frame.incomingBubble, roles.onIncomingBubble),
                Triple(failed, AppColors.Message.outgoingBubbleFailed(highContrast).compositeOver(frame.canvas), frame.outgoingText),
            )) {
                val paintedBackground = background.toComposeColor().toThemeColor()
                val expectedForeground = accessibleForeground(
                    preferred.toComposeColor().toThemeColor(), paintedBackground, WCAGContrast.AA_FLOOR,
                )
                val layouts = mutableListOf<TextLayoutResult>()
                val body = compose.onNodeWithText(message.text, useUnmergedTree = true)
                body.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                val bodyForeground = layout.layoutInput.text.spanStyles.first().item.color
                assertEquals(expectedForeground.toComposeColor(), bodyForeground)
                val start = message.text.indexOf('#')
                val link = layout.layoutInput.text.getLinkAnnotations(start, message.text.length).single().item
                val linkForeground = accessibleForeground(
                    if (message.isFromSelf) frame.outgoingText else frame.hashtag,
                    paintedBackground, WCAGContrast.AA_FLOOR,
                )
                val renderedLinkForeground = requireNotNull(link.styles?.style).color
                assertEquals(linkForeground.toComposeColor(), renderedLinkForeground)
                val bounds = compose.onNodeWithTag("room-bubble:${message.id}", useUnmergedTree = true)
                    .fetchSemanticsNode().boundsInRoot
                val painted = bitmap.getPixel(bounds.center.x.toInt(), bounds.top.toInt() + 4)
                assertColorNear(background.toComposeColor().toArgb(), painted)
                assertColorNear(
                    frame.canvas.toComposeColor().toArgb(),
                    bitmap.getPixel(bounds.left.toInt() - 4, bounds.top.toInt() + 4),
                )
                val textBounds = body.fetchSemanticsNode().boundsInRoot
                val foregroundPixels = IntArray(textBounds.width.toInt() * textBounds.height.toInt())
                bitmap.getPixels(
                    foregroundPixels, 0, textBounds.width.toInt(), textBounds.left.toInt(), textBounds.top.toInt(),
                    textBounds.width.toInt(), textBounds.height.toInt(),
                )
                assertTrue("Mounted glyphs must paint the declared foreground", foregroundPixels.any {
                    colorsNear(expectedForeground.toComposeColor().toArgb(), it)
                })
                assertTrue(
                    "Body foreground must meet the existing floor on the painted bubble",
                    WCAGContrast.contrastRatio(bodyForeground.toThemeColor(), Color(painted).toThemeColor()) >= WCAGContrast.AA_FLOOR,
                )
                assertTrue(
                    "Hashtag foreground must meet the existing floor on the painted bubble",
                    WCAGContrast.contrastRatio(renderedLinkForeground.toThemeColor(), Color(painted).toThemeColor()) >= WCAGContrast.AA_FLOOR,
                )
            }
        } finally {
            bitmap.recycle()
        }
    }

    private fun colorsNear(expected: Int, actual: Int): Boolean =
        listOf(0, 8, 16, 24).all { shift -> kotlin.math.abs(((expected ushr shift) and 255) - ((actual ushr shift) and 255)) <= 2 }

    private fun assertColorNear(expected: Int, actual: Int) {
        assertTrue("Expected painted ${expected.toUInt().toString(16)}, got ${actual.toUInt().toString(16)}", colorsNear(expected, actual))
    }

    private fun showRoute(fixture: Fixture) {
        show { RoomConversationRoute(fixture.holder, fixture.links) }
        compose.waitUntil { fixture.holder.state.value.hasLoadedOnce }
    }

    private inner class Fixture(
        messages: List<RoomMessageDTO>,
        private val room: RemoteNodeSessionDTO = session,
        diagnostic: (String, Throwable) -> Unit = { operation, failure -> throw AssertionError(operation, failure) },
    ) :
        RoomConversationDependencies, ChatLinkEnvironment {
        var stored = messages
        val retries = mutableListOf<Pair<UUID, UUID>>()
        val joins = mutableListOf<Triple<ContactDTO, String?, Boolean>>()
        val external = mutableListOf<String>()
        val navigatedContacts = mutableListOf<ContactDTO>()
        val maps = mutableListOf<Pair<Double, Double>>()
        val hashtags = mutableListOf<String>()
        override val events = emptyFlow<RoomConversationEvent>()
        override val diagnostics = RoomConversationDiagnostics(diagnostic)
        override val data = object : RoomConversationDataSource {
            override suspend fun fetchMessages(session: RemoteNodeSessionDTO) = stored
            override suspend fun refreshSession(session: RemoteNodeSessionDTO) = room
            override suspend fun contactFor(session: RemoteNodeSessionDTO) = alice
            override suspend fun markAsRead(session: RemoteNodeSessionDTO) = Unit
            override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) = Unit
        }
        override val service = object : RoomConversationService {
            override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean): RemoteNodeSessionDTO {
                joins += Triple(contact, password, rememberPassword)
                return room.copy(isConnected = true)
            }
            override suspend fun reconnect(session: RemoteNodeSessionDTO) = error("Unexpected reconnect")
            override suspend fun post(session: RemoteNodeSessionDTO, text: String) = error("Unexpected post")
            override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID): RoomMessageDTO {
                retries += session.id to messageId
                val updated = stored.single { it.id == messageId }.copy(statusRawValue = MessageStatus.PENDING.rawValue)
                stored = stored.map { if (it.id == messageId) updated else it }
                return updated
            }
        }
        val holder = RoomConversationStateHolder(room, this, scope)
        private val router = ChatLinkRouter(this, scope, ChatListDiagnostics { operation, failure ->
            throw AssertionError(operation, failure)
        })
        val links = ConversationLinkActions(
            contacts = { listOf(alice) }, connectedDeviceName = { "Me" }, radioId = { radio },
            route = router::route, openExternal = { external += it }, navigateToContact = { navigatedContacts += it },
        )
        override var selectedTab = AppTab.CHATS
        override val currentRadioId = radio
        override val connectedDevicePublicKey = session.publicKey
        override val parser = MeshCoreLinkParser
        override fun navigateToMap(latitude: Double, longitude: Double) { maps += latitude to longitude }
        override fun navigateToContactDetail(contact: ContactDTO) { navigatedContacts += contact }
        override fun navigateToChannel(channel: ChannelDTO) = error("Unexpected channel navigation")
        override fun stageContactLink(link: ContactLinkResult) = error("Unexpected contact import")
        override fun stageChannelLink(link: ChannelLinkResult) = error("Unexpected channel import")
        override fun stageHashtag(fullName: String) { hashtags += fullName }
        override suspend fun fetchContact(radioId: RadioId, publicKey: Bytes) = alice.takeIf { it.publicKey == publicKey }
        override suspend fun fetchChannels(radioId: RadioId) = emptyList<ChannelDTO>()
    }
}
