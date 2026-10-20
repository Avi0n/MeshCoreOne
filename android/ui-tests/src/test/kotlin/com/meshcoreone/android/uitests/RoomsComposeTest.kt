// AndroidOnly: WP-502 Production Rooms route/content with controlled service ports; not radio, Room database or golden evidence.
// ReferenceInput: MC1/Views/RemoteNodes/Rooms/RoomConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Room/RoomAuthenticationSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Tests/Views/Chats/Components/RoomMessageBubbleA11yLabelTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as Strings
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as Rooms
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.remote.RoomServerError
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.rooms.*
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
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
@Config(sdk = [31], qualifiers = "w1080dp-h1200dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RoomsComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val frame = mutableStateOf(ScreenCase("room-flow", heightDp = 1000, fontScale = 2f))
    private var effectiveScheme: ColorScheme? = null
    private val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000310"))
    private val session = RemoteNodeSessionDTO(
        id = UUID.fromString("50200000-0000-0000-0000-000000000311"),
        radioId = radio, publicKey = Bytes(ByteArray(32) { 0x42 }), name = "Room \u4e2d\u6587",
        role = RemoteNodeRole.ROOM_SERVER, isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE,
    )
    private val contact = ContactDTO(
        radioId = radio, publicKey = session.publicKey, name = session.name, lastHeardTimestamp = null,
    )
    private val body = "\u4e2d\u6587 \u05e9\u05dc\u05d5\u05dd"

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
    }

    @After fun close() {
        scope.cancel()
        host.pause().stop().destroy()
    }

    private fun text(id: Int): String = host.get().createConfigurationContext(
        Configuration(host.get().resources.configuration).apply {
            setLocales(LocaleList(Locale.forLanguageTag(frame.value.locale)))
        },
    ).getString(id)

    private fun mount(content: @Composable () -> Unit) {
        host.get().setContent {
            val case = frame.value
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(case.locale)))
            }
            val localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, case.fontScale),
                LocalLayoutDirection provides if (case.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCoreTheme(
                    theme = requireNotNull(ThemeRegistry.theme(case.theme)),
                    colorSchemePreference = case.preference, systemDark = case.systemDark, motionScale = 0f,
                ) {
                    val scheme = LocalMeshTheme.current.frame.colorScheme
                    SideEffect { effectiveScheme = scheme }
                    Box(Modifier.width(case.widthDp.dp).height(case.heightDp.dp).testTag("wp502-rooms")) { content() }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun mountRoute(fixture: Fixture) = mount {
        RoomConversationRoute(
            fixture.holder, fixture.links,
            failureText = { uiString(UiErrorMapper().message(it)) },
        )
    }

    private fun message(self: Boolean, status: MessageStatus = MessageStatus.DELIVERED) = RoomMessageDTO(
        id = UUID.fromString("50200000-0000-0000-0000-00000000031${if (self) 3 else 2}"),
        sessionID = session.id, authorKeyPrefix = Bytes.of(if (self) 0x42 else 0x0a),
        authorName = if (self) "Me" else "Alice", text = if (self) body else "Alice $body",
        timestamp = if (self) 1_704_067_201u else 1_704_067_200u,
        createdAt = Instant.ofEpochSecond(1_704_067_200), isFromSelf = self, statusRawValue = status.rawValue,
    )

    @Test fun loadingEmptyGuestMemberAdminAndDisconnectedUseTheProductionRoute() {
        val fixture = Fixture(session.copy(permissionLevel = RoomPermissionLevel.GUEST))
        val loading = CompletableDeferred<Unit>()
        fixture.fetchGate = loading
        mountRoute(fixture)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertDoesNotExist()
        compose.onNodeWithText(text(Rooms.remoteNodesRoomViewOnlyBanner)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.runOnIdle { loading.complete(Unit) }
        compose.waitUntil { fixture.holder.state.value.hasLoadedOnce }
        compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertIsDisplayed()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
        for (permission in listOf(RoomPermissionLevel.READ_WRITE, RoomPermissionLevel.ADMIN)) {
            fixture.recover(session.copy(permissionLevel = permission))
            val field = compose.onNode(hasSetTextAction())
            field.performClick().performTextReplacement(body)
            field.assertIsFocused().assertTextContains(body)
            for (width in CoverageMatrix.widths) {
                compose.runOnIdle { frame.value = frame.value.copy(widthDp = width) }
                compose.onNodeWithTag("wp502-rooms").assertWidthIsEqualTo(width.dp)
                field.assertIsFocused().assertTextContains(body)
                compose.onNodeWithText(text(Chats.chatsInputSendMessage)).assertIsEnabled()
            }
        }
        fixture.recover(session.copy(permissionLevel = RoomPermissionLevel.GUEST))
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(text(Rooms.remoteNodesRoomViewOnlyBanner)).assertIsDisplayed()
        fixture.recover(session.copy(isConnected = false))
        compose.onNodeWithText(text(Rooms.remoteNodesRoomViewOnlyBanner)).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsRoomTapToReconnect)).assertIsEnabled().assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        assertEquals(body, fixture.holder.state.value.draft)
        assertEquals(listOf("fetch", "read", "failed-seen", "notifications", "badge", "changed"), fixture.loads)
        assertTrue(fixture.posts.isEmpty())
        assertTrue(fixture.joins.isEmpty())
        assertTrue(fixture.retries.isEmpty())
        assertTrue(fixture.reported.isEmpty())
    }

    @Test fun failedPostRetainsDraftAndAccessibleRetryKeepsTheOriginalMessageIdentity() {
        val fixture = Fixture()
        val pending = message(self = true, status = MessageStatus.PENDING)
        val failure = RoomServerError.PermissionDenied()
        val held = CompletableDeferred<Unit>()
        fixture.postGate = held
        fixture.postFailure = failure
        mountRoute(fixture)
        compose.waitUntil { fixture.holder.state.value.hasLoadedOnce }
        compose.onNode(hasSetTextAction()).performTextReplacement(body)
        val send = compose.onNodeWithText(text(Chats.chatsInputSendMessage))
        send.performClick()
        compose.waitUntil { fixture.posts.size == 1 }
        send.assertIsNotEnabled().performClick()
        assertEquals(body, fixture.holder.state.value.draft)
        assertTrue(fixture.holder.state.value.messages.isEmpty())
        compose.runOnIdle { held.complete(Unit) }
        compose.waitUntil { fixture.holder.state.value.failure != null }
        assertSame(failure, fixture.holder.state.value.failure)
        assertEquals(listOf("send room message" to failure), fixture.reported)
        compose.onNodeWithText(text(Strings.errorRoomServerPermissionDenied)).assertIsDisplayed().performClick()
        assertNull(fixture.holder.state.value.failure)
        compose.onNode(hasSetTextAction()).assertTextContains(body)
        compose.runOnIdle { fixture.postFailure = null }
        send.assertIsEnabled().performClick()
        compose.waitUntil { fixture.holder.state.value.messages.size == 1 }
        assertEquals(listOf(session.id to body, session.id to body), fixture.posts)
        assertEquals("", fixture.holder.state.value.draft)
        assertEquals(pending, fixture.holder.state.value.messages.single())
        val failed = pending.copy(statusRawValue = MessageStatus.FAILED.rawValue)
        compose.runOnIdle {
            fixture.stored = listOf(failed)
            assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageChanged(session.id, failed.id)))
        }
        compose.waitUntil { fixture.holder.state.value.messages.single().status == MessageStatus.FAILED }
        val label = "$body, ${text(Rooms.remoteNodesRoomMessageStatusFailedLabel)}"
        val row = compose.onNodeWithContentDescription(label).assertIsDisplayed()
        val actions = row.fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(text(Chats.chatsMessageActionRetry)), actions.map { it.label })
        compose.runOnIdle { assertTrue(actions.single().action()) }
        compose.waitUntil { fixture.holder.state.value.messages.single().status == MessageStatus.PENDING }
        assertEquals(listOf(session.id to failed.id), fixture.retries)
        assertEquals(pending, fixture.holder.state.value.messages.single())
        compose.onNodeWithContentDescription("$body, ${text(Rooms.remoteNodesRoomMessageStatusSendingLabel)}")
            .assertIsDisplayed()
        assertEquals(2, fixture.posts.size)
        assertTrue(fixture.joins.isEmpty())
    }

    @Test fun reconnectLookupAndRejectedJoinKeepTheSheetAndExactRememberChoiceUntilSuccess() {
        val fixture = Fixture(session.copy(isConnected = false))
        val lookup = CompletableDeferred<Unit>()
        fixture.contactGate = lookup
        fixture.lookupContact = null
        mountRoute(fixture)
        compose.onNodeWithText(text(Chats.chatsRoomTapToReconnect)).performClick()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        compose.onNodeWithText(text(Chats.chatsRoomAuthNotFoundTitle)).assertDoesNotExist()
        compose.runOnIdle { lookup.complete(Unit) }
        compose.onNodeWithText(text(Chats.chatsRoomAuthNotFoundTitle)).assertIsDisplayed()
        compose.onNodeWithText(text(Chats.chatsRoomAuthNotFoundDescription)).assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsCommonCancel)).performClick()
        assertTrue(fixture.joins.isEmpty())
        compose.runOnIdle { fixture.lookupContact = contact }
        compose.onNodeWithText(text(Chats.chatsRoomTapToReconnect)).performClick()
        val remember = compose.onNodeWithText(text(Rooms.remoteNodesAuthRememberPassword))
        remember.assertIsOn().assertHeightIsAtLeast(48.dp).performClick().assertIsOff()
        compose.onNode(hasSetTextAction()).performTextInput("test-only-password")
        val held = CompletableDeferred<Unit>()
        val failure = RoomServerError.PermissionDenied()
        compose.runOnIdle { fixture.joinGate = held; fixture.joinFailure = failure }
        val join = compose.onNodeWithText(text(Rooms.remoteNodesAuthJoinRoom))
        join.assertHeightIsAtLeast(48.dp).performClick()
        compose.waitUntil { fixture.joins.size == 1 }
        join.assertIsNotEnabled().performClick()
        assertFalse(fixture.holder.state.value.session.isConnected)
        compose.runOnIdle { held.complete(Unit) }
        compose.waitUntil { fixture.holder.state.value.failure != null }
        assertSame(failure, fixture.holder.state.value.failure)
        assertEquals(listOf("authenticate room" to failure), fixture.reported)
        join.assertIsEnabled().assertIsDisplayed()
        remember.assertIsOff()
        compose.onNode(hasSetTextAction()).assertTextContains("test-only-password")
        compose.runOnIdle { fixture.joinFailure = null }
        join.performClick()
        compose.waitUntil { fixture.holder.state.value.session.isConnected }
        compose.onNodeWithText(text(Rooms.remoteNodesAuthJoinRoom)).assertDoesNotExist()
        compose.onNodeWithText(text(Chats.chatsRoomTapToReconnect)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        assertEquals(List(2) { Triple(contact, "test-only-password", false) }, fixture.joins)
        assertTrue(fixture.posts.isEmpty())
        assertTrue(fixture.retries.isEmpty())
    }

    @Test fun roomFramesCoverEffectiveSchemesWindowsFontsLocalesAndPermissionSurfaces() {
        val messages = listOf(message(false), message(true, MessageStatus.FAILED))
        val state = mutableStateOf(RoomConversationState(session))
        mount {
            RoomConversationContent(
                state.value, onDraftChanged = { error("Render-only input") }, onSend = { error("Render-only send") },
                onRetry = { error("Render-only retry") }, onLink = { error("Render-only link") },
                onReconnect = { error("Render-only authentication") }, onDismissError = { error("No fixture error") },
                failureText = { throw AssertionError("Unexpected fixture failure", it) },
            )
        }
        CoverageMatrix.assertThemeWindowFontCoverage()
        val inputs = CoverageMatrix.themeWindowFontCases.mapIndexed { index, case ->
            case.copy(id = "room-${case.id}", heightDp = 1000, locale = CoverageMatrix.locales[index % 12])
        }
        assertEquals(57, inputs.size)
        assertEquals(CoverageMatrix.locales.toSet(), inputs.map { it.locale }.toSet())
        fun surface(index: Int) = (index / 3 + index % 3) % 3
        assertEquals(
            CoverageMatrix.widths.flatMap { width -> (0..2).map { width to it } }.toSet(),
            inputs.mapIndexed { index, case -> case.widthDp to surface(index) }.toSet(),
        )
        val captures = ConversationScreenCases(compose, host.get(), setContent = { host.get().setContent(content = it) })
        val output = File(requireNotNull(System.getProperty("wp502ScreenshotDirectory")), "sdk31/rooms")
        for ((index, case) in inputs.withIndex()) {
            val room = when (surface(index)) {
                0 -> session.copy(isConnected = false)
                1 -> session.copy(permissionLevel = RoomPermissionLevel.GUEST)
                else -> session
            }
            compose.runOnIdle {
                frame.value = case
                state.value = RoomConversationState(
                    room, messages, RoomConversationStateHolder.rows(messages), draft = body, hasLoadedOnce = true,
                )
            }
            compose.waitForIdle()
            assertEquals(case.expectedScheme, effectiveScheme)
            compose.onNodeWithTag("wp502-rooms").assertWidthIsEqualTo(case.widthDp.dp).assertHeightIsEqualTo(1000.dp)
            compose.onNodeWithText(session.name).assertIsDisplayed()
            compose.onNodeWithContentDescription("Alice: ${messages.first().text}").assertIsDisplayed()
            compose.onNodeWithContentDescription("$body, ${text(Rooms.remoteNodesRoomMessageStatusFailedLabel)}")
                .assertIsDisplayed()
            when (surface(index)) {
                0 -> {
                    compose.onNodeWithText(text(Chats.chatsRoomTapToReconnect)).assertIsDisplayed().assertIsEnabled()
                    compose.onNode(hasSetTextAction()).assertDoesNotExist()
                }
                1 -> {
                    compose.onNodeWithText(text(Rooms.remoteNodesRoomViewOnlyBanner)).assertIsDisplayed()
                    compose.onNode(hasSetTextAction()).assertDoesNotExist()
                }
                else -> {
                    compose.onNode(hasSetTextAction()).assertIsDisplayed().assertTextContains(body)
                    compose.onNodeWithText(text(Chats.chatsInputSendMessage)).assertIsDisplayed().assertIsEnabled()
                }
            }
            for (message in messages) {
                val layouts = mutableListOf<TextLayoutResult>()
                compose.onNodeWithText(message.text, useUnmergedTree = true)
                    .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
                val layout = layouts.single()
                assertEquals(case.fontScale, layout.layoutInput.density.fontScale, 0f)
                assertFalse("${case.id}: room body clipped vertically", layout.didOverflowHeight)
                for (line in 0 until layout.lineCount) assertFalse(layout.isLineEllipsized(line))
                for ((offset, char) in message.text.withIndex()) {
                    if (char.isWhitespace() || Character.getType(char) == Character.FORMAT.toInt()) continue
                    val glyph = layout.getBoundingBox(offset)
                    assertTrue(
                        "${case.id}: visible room glyph $offset outside ${layout.size}: $glyph",
                        glyph.left >= 0f && glyph.top >= 0f &&
                            glyph.right <= layout.size.width && glyph.bottom <= layout.size.height,
                    )
                }
            }
            captures.captureMountedScreen(case.id, output, tag = "wp502-rooms")
        }
    }

    private inner class Fixture(room: RemoteNodeSessionDTO = session) : RoomConversationDependencies {
        var stored = emptyList<RoomMessageDTO>()
        var lookupContact: ContactDTO? = contact
        var fetchGate: CompletableDeferred<Unit>? = null
        var contactGate: CompletableDeferred<Unit>? = null
        var postGate: CompletableDeferred<Unit>? = null
        var joinGate: CompletableDeferred<Unit>? = null
        var postFailure: Exception? = null
        var joinFailure: Exception? = null
        val loads = mutableListOf<String>()
        val posts = mutableListOf<Pair<UUID, String>>()
        val joins = mutableListOf<Triple<ContactDTO, String?, Boolean>>()
        val retries = mutableListOf<Pair<UUID, UUID>>()
        val reported = mutableListOf<Pair<String, Throwable>>()
        override val events = MutableSharedFlow<RoomConversationEvent>(extraBufferCapacity = 8)
        override val data = object : RoomConversationDataSource {
            override suspend fun fetchMessages(session: RemoteNodeSessionDTO): List<RoomMessageDTO> {
                loads += "fetch"
                fetchGate?.await()
                return stored
            }
            override suspend fun refreshSession(session: RemoteNodeSessionDTO) = holder.state.value.session
            override suspend fun contactFor(session: RemoteNodeSessionDTO): ContactDTO? {
                contactGate?.await()
                return lookupContact
            }
            override suspend fun markAsRead(session: RemoteNodeSessionDTO) { loads += "read" }
            override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) { loads += "failed-seen" }
        }
        override val service = object : RoomConversationService {
            override suspend fun join(contact: ContactDTO, password: String?, rememberPassword: Boolean): RemoteNodeSessionDTO {
                joins += Triple(contact, password, rememberPassword)
                joinGate?.await()
                joinFailure?.let { throw it }
                return room.copy(isConnected = true, permissionLevel = RoomPermissionLevel.READ_WRITE)
            }
            override suspend fun reconnect(session: RemoteNodeSessionDTO) = error("Route uses authentication, not reconnect bypass")
            override suspend fun post(session: RemoteNodeSessionDTO, text: String): RoomMessageDTO {
                posts += session.id to text
                postGate?.await()
                postFailure?.let { throw it }
                return message(true, MessageStatus.PENDING).also { stored = listOf(it) }
            }
            override suspend fun retry(session: RemoteNodeSessionDTO, messageId: UUID): RoomMessageDTO {
                retries += session.id to messageId
                return stored.single { it.id == messageId }.copy(statusRawValue = MessageStatus.PENDING.rawValue)
                    .also { stored = listOf(it) }
            }
        }
        override val diagnostics = RoomConversationDiagnostics { operation, failure -> reported += operation to failure }
        override suspend fun removeDeliveredNotifications(session: RemoteNodeSessionDTO) { loads += "notifications" }
        override suspend fun updateBadgeCount() { loads += "badge" }
        override fun conversationsChanged() { loads += "changed" }
        val holder = RoomConversationStateHolder(room, this, scope)
        val links = ConversationLinkActions(
            contacts = { listOf(contact) }, connectedDeviceName = { "Me" }, radioId = { radio },
            route = { error("No fixture link") }, openExternal = { error("No external fixture link") },
            navigateToContact = { error("No fixture navigation") },
        )
        fun recover(updated: RemoteNodeSessionDTO) {
            compose.runOnIdle { assertTrue(events.tryEmit(RoomConversationEvent.ConnectionRecovered(session.id, updated))) }
            compose.waitUntil { holder.state.value.session == updated }
        }
    }
}
