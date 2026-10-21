// AndroidOnly: WP-502 Production Rooms route/content with controlled service ports; not radio, Room database or golden evidence.
// ReferenceInput: MC1/Views/RemoteNodes/Rooms/RoomConversationView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/RemoteNodes/Rooms/RoomConversationViewModel.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Tests/ViewModels/RoomConversationViewModelOrderingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Services/Sources/MC1Services/Models/RoomMessage.swift@db14559b39d32322b06477c6ae676112f583db50
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
import androidx.compose.ui.graphics.toArgb
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
import com.meshcoreone.android.core.designsystem.readableGlyph
import com.meshcoreone.android.core.designsystem.toComposeColor
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings as Strings
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as Rooms
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.remote.RoomServerError
import com.meshcoreone.android.core.ui.UiErrorMapper
import com.meshcoreone.android.core.ui.uiString
import com.meshcoreone.android.feature.chats.composer.MessageLinkDispatcher
import com.meshcoreone.android.feature.chats.composer.ui.ConversationLinkActions
import com.meshcoreone.android.feature.chats.rooms.*
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
        compose.waitForIdle()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
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

    @Test fun liveOutOfOrderArrivalAppearsBetweenExistingRowsBeforeTheHeldReload() {
        val first = liveMessage(1, 100u)
        val second = liveMessage(2, 200u)
        val third = liveMessage(3, 300u)
        val arrival = liveMessage(4, 150u)
        assertLiveArrival(listOf(first, second, third), arrival, listOf(first, arrival, second, third))
    }

    @Test fun liveOlderArrivalAppearsBeforeTheExistingRowsBeforeTheHeldReload() {
        val first = liveMessage(1, 200u)
        val second = liveMessage(2, 300u)
        val arrival = liveMessage(3, 100u)
        assertLiveArrival(listOf(first, second), arrival, listOf(arrival, first, second))
    }

    @Test fun liveNewestArrivalAppearsAfterTheExistingRowsBeforeTheHeldReload() {
        val first = liveMessage(1, 100u)
        val second = liveMessage(2, 200u)
        val arrival = liveMessage(3, 300u)
        assertLiveArrival(listOf(first, second), arrival, listOf(first, second, arrival))
    }

    @Test fun liveEqualTimestampArrivalFollowsTheExistingIdentityBeforeTheHeldReload() {
        val first = liveMessage(1, 100u)
        val existing = liveMessage(2, 200u, "first")
        val arrival = liveMessage(3, 200u, "second")
        assertLiveArrival(listOf(first, existing), arrival, listOf(first, existing, arrival))
    }

    @Test fun liveDuplicateIdentityCannotReorderOrReplaceTheExistingRowBeforeTheHeldReload() {
        val first = liveMessage(1, 100u)
        val existing = liveMessage(2, 200u)
        val duplicate = liveMessage(2, 150u, existing.text)
        assertNotEquals(existing.deduplicationKey, duplicate.deduplicationKey)
        assertLiveArrival(listOf(first, existing), duplicate, listOf(first, existing))
    }

    @Test fun liveMidInsertionReconfiguresTheRetainedLaterRowsTimestampBeforeReload() {
        val locale = Locale.getDefault()
        val formatLocale = Locale.getDefault(Locale.Category.FORMAT)
        val displayLocale = Locale.getDefault(Locale.Category.DISPLAY)
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val prefix = Bytes.of(0xaa)
            val first = liveMessage(1, 100u, "first", prefix, "Alice")
            val later = liveMessage(2, 500u, "later", prefix, "Alice")
            val middle = liveMessage(3, 250u, "mid", prefix, "Alice")
            val expected = listOf(first, middle, later)
            val fixture = Fixture()
            fixture.stored = listOf(first, later)
            mountRoute(fixture)
            compose.waitUntil { fixture.holder.state.value.hasLoadedOnce && fixture.events.subscriptionCount.value == 1 }
            assertEquals(listOf(first, later), fixture.holder.state.value.rows.map { it.message })
            compose.onNode(hasSetTextAction()).performTextReplacement(body)
            compose.onNodeWithText("Jan 1, 00:01").assertIsDisplayed()
            compose.onNodeWithText("Jan 1, 00:08").assertIsDisplayed()
            assertEquals(listOf(true, true), fixture.holder.state.value.rows.map { it.showTimestamp })
            val held = CompletableDeferred<Unit>()
            compose.runOnIdle {
                fixture.fetchGate = held
                fixture.stored = expected
                assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, middle)))
            }
            compose.waitUntil { fixture.holder.state.value.messages == expected }
            fun assertReconfiguredRows() {
                assertEquals(expected, fixture.holder.state.value.messages)
                assertEquals(expected, fixture.holder.state.value.rows.map { it.message })
                assertEquals(listOf(true, false, false), fixture.holder.state.value.rows.map { it.showTimestamp })
                compose.onNodeWithText("Jan 1, 00:01").assertIsDisplayed()
                compose.onNodeWithText("Jan 1, 00:08").assertDoesNotExist()
                val tops = listOf("first", "mid", "later").map { text ->
                    compose.onNodeWithContentDescription("Alice: $text").assertIsDisplayed()
                        .fetchSemanticsNode().boundsInRoot.top
                }
                assertTrue(tops.zipWithNext().all { (a, b) -> a < b })
                compose.onNode(hasSetTextAction()).assertTextContains(body)
            }
            assertReconfiguredRows()
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            compose.waitUntil { fixture.loads.count { it == "fetch" } == 2 }
            assertTrue(fixture.holder.state.value.isLoading)
            assertReconfiguredRows()
            compose.runOnIdle { assertTrue(held.complete(Unit)) }
            compose.waitUntil { !fixture.holder.state.value.isLoading && fixture.loads.count { it == "changed" } == 2 }
            assertReconfiguredRows()
            assertEquals(
                List(2) { listOf("fetch", "read", "failed-seen", "notifications", "badge", "changed") }.flatten(),
                fixture.loads,
            )
            assertTrue(fixture.posts.isEmpty())
            assertTrue(fixture.joins.isEmpty())
            assertTrue(fixture.retries.isEmpty())
            assertTrue(fixture.reported.isEmpty())
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
            Locale.setDefault(Locale.Category.FORMAT, formatLocale)
            Locale.setDefault(Locale.Category.DISPLAY, displayLocale)
        }
    }

    @Test fun samePrefixBurstKeepsTheVisibleNameOnFirstAndMovesTheAvatarFlagToLastBeforeReload() {
        val prefix = Bytes.of(0xaa)
        val first = liveMessage(1, 100u, "msg", prefix, "Alice")
        val second = liveMessage(2, 160u, "msg", prefix, "Alice")
        val third = liveMessage(3, 220u, "msg", prefix, "Alice")
        val expected = listOf(first, second, third)
        val fixture = Fixture()
        fixture.stored = listOf(first)
        mountRoute(fixture)
        compose.waitUntil { fixture.holder.state.value.hasLoadedOnce && fixture.events.subscriptionCount.value == 1 }
        compose.onNode(hasSetTextAction()).performTextReplacement(body)
        val originalRow = fixture.holder.state.value.rows.single()
        assertEquals(first, originalRow.message)
        assertTrue(originalRow.showSenderName)
        assertTrue(originalRow.showAvatar)
        compose.onAllNodesWithText("Alice", useUnmergedTree = true).assertCountEquals(1)
        val load = listOf("fetch", "read", "failed-seen", "notifications", "badge", "changed")
        assertEquals(load, fixture.loads)
        val held = CompletableDeferred<Unit>()
        compose.runOnIdle {
            fixture.fetchGate = held
            fixture.stored = expected
            assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, second)))
            assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, third)))
        }
        compose.waitUntil { fixture.holder.state.value.messages == expected }
        fun assertBurstBookends() {
            val state = fixture.holder.state.value
            assertEquals(expected, state.messages)
            assertEquals(expected, state.rows.map { it.message })
            assertEquals(listOf(first.id), state.rows.filter { it.showSenderName }.map { it.message.id })
            assertEquals(listOf(third.id), state.rows.filter { it.showAvatar }.map { it.message.id })
            assertEquals(listOf(true, false, false), state.rows.map { it.showTimestamp })
            assertNotEquals(originalRow, state.rows.first())
            compose.onAllNodesWithText("Alice", useUnmergedTree = true).assertCountEquals(1)
            val nameBounds = compose.onNodeWithText("Alice", useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            compose.onAllNodesWithContentDescription("Alice: msg").assertCountEquals(3)
            val bubbles = expected.map { message ->
                compose.onNodeWithTag("room-bubble:${message.id}", useUnmergedTree = true).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
            }
            assertTrue(nameBounds.bottom <= bubbles.first().top)
            assertTrue(bubbles.zipWithNext().all { (a, b) -> a.top < b.top })
            compose.onNode(hasSetTextAction()).assertTextContains(body)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
            compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertDoesNotExist()
        }
        assertBurstBookends()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        compose.waitUntil { fixture.loads.count { it == "fetch" } == 2 }
        assertTrue(fixture.holder.state.value.isLoading)
        assertEquals(load + "fetch", fixture.loads)
        assertBurstBookends()
        compose.runOnIdle { assertTrue(held.complete(Unit)) }
        compose.waitUntil { !fixture.holder.state.value.isLoading && fixture.loads.count { it == "changed" } == 2 }
        assertBurstBookends()
        assertEquals(load + load, fixture.loads)
        assertEquals(1, fixture.events.subscriptionCount.value)
        assertTrue(fixture.posts.isEmpty())
        assertTrue(fixture.joins.isEmpty())
        assertTrue(fixture.retries.isEmpty())
        assertTrue(fixture.reported.isEmpty())
    }

    @Test fun differentPrefixesKeepSeparateVisibleNamesEvenWhenDisplayNamesMatchBeforeReload() {
        val first = liveMessage(1, 100u, "msg", Bytes.of(0xaa), "Alice")
        val second = liveMessage(2, 160u, "msg", Bytes.of(0xbb), "Alice")
        val expected = listOf(first, second)
        assertNotEquals(first.authorKeyPrefix, second.authorKeyPrefix)
        assertNotEquals(first.deduplicationKey, second.deduplicationKey)
        val fixture = Fixture()
        fixture.stored = listOf(first)
        mountRoute(fixture)
        compose.waitUntil { fixture.holder.state.value.hasLoadedOnce && fixture.events.subscriptionCount.value == 1 }
        compose.onNode(hasSetTextAction()).performTextReplacement(body)
        val originalRow = fixture.holder.state.value.rows.single()
        assertEquals(first, originalRow.message)
        assertTrue(originalRow.showSenderName)
        assertTrue(originalRow.showAvatar)
        compose.onAllNodesWithText("Alice", useUnmergedTree = true).assertCountEquals(1)
        val load = listOf("fetch", "read", "failed-seen", "notifications", "badge", "changed")
        assertEquals(load, fixture.loads)
        val held = CompletableDeferred<Unit>()
        compose.runOnIdle {
            fixture.fetchGate = held
            fixture.stored = expected
            assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, second)))
        }
        compose.waitUntil { fixture.holder.state.value.messages == expected }
        fun assertSeparateClusters() {
            val state = fixture.holder.state.value
            assertEquals(expected, state.messages)
            assertEquals(expected, state.rows.map { it.message })
            assertEquals(listOf(first.id, second.id), state.rows.filter { it.showSenderName }.map { it.message.id })
            assertEquals(listOf(first.id, second.id), state.rows.filter { it.showAvatar }.map { it.message.id })
            assertEquals(listOf(true, false), state.rows.map { it.showTimestamp })
            assertEquals(originalRow, state.rows.first())
            val names = compose.onAllNodesWithText("Alice", useUnmergedTree = true).assertCountEquals(2)
            val nameBounds = (0..1).map {
                names[it].assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            }.sortedBy { it.top }
            compose.onAllNodesWithContentDescription("Alice: msg").assertCountEquals(2)
            val bubbles = expected.map { message ->
                compose.onNodeWithTag("room-bubble:${message.id}", useUnmergedTree = true).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
            }
            assertTrue(nameBounds[0].bottom <= bubbles[0].top)
            assertTrue(bubbles[0].bottom <= nameBounds[1].top)
            assertTrue(nameBounds[1].bottom <= bubbles[1].top)
            compose.onNode(hasSetTextAction()).assertTextContains(body)
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
            compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertDoesNotExist()
        }
        assertSeparateClusters()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        compose.waitUntil { fixture.loads.count { it == "fetch" } == 2 }
        assertTrue(fixture.holder.state.value.isLoading)
        assertEquals(load + "fetch", fixture.loads)
        assertSeparateClusters()
        compose.runOnIdle { assertTrue(held.complete(Unit)) }
        compose.waitUntil { !fixture.holder.state.value.isLoading && fixture.loads.count { it == "changed" } == 2 }
        assertSeparateClusters()
        assertEquals(load + load, fixture.loads)
        assertEquals(1, fixture.events.subscriptionCount.value)
        assertTrue(fixture.posts.isEmpty())
        assertTrue(fixture.joins.isEmpty())
        assertTrue(fixture.retries.isEmpty())
        assertTrue(fixture.reported.isEmpty())
    }

    @Test fun selfOnlyMessagesNeverAcquireNamesOrAvatarFlagsBeforeReload() {
        val first = liveMessage(1, 100u, "msg", Bytes.of(0xaa), "Me").copy(isFromSelf = true)
        val second = liveMessage(2, 160u, "msg", Bytes.of(0xaa), "Me").copy(isFromSelf = true)
        val (before, after) = assertOriginalGroupingArrival(
            listOf(RoomMessageRow(first, true, false, false)),
            listOf(RoomMessageRow(first, true, false, false), RoomMessageRow(second, false, false, false)),
        )
        assertEquals(before.single(), after.first())
    }

    @Test fun singleFollowUpReconfiguresTheRetainedFirstRowWithoutChangingItsLogicalIdentity() {
        val first = liveMessage(1, 100u, "msg", Bytes.of(0xaa), "Alice")
        val second = liveMessage(2, 160u, "msg", Bytes.of(0xaa), "Alice")
        val (before, after) = assertOriginalGroupingArrival(
            listOf(RoomMessageRow(first, true, true, true)),
            listOf(RoomMessageRow(first, true, true, false), RoomMessageRow(second, false, false, true)),
        )
        assertEquals(first.id, before.single().message.id)
        assertEquals(before.single().message.id, after.first().message.id)
        assertEquals(before.single().message, after.first().message)
        assertTrue(before.single().showAvatar)
        assertFalse(after.first().showAvatar)
        assertTrue(after[1].showAvatar)
        assertNotEquals(before.single(), after.first())
    }

    @Test fun independent300SecondPairKeepsOneClusterAndOneVisibleTimestampBeforeReload() {
        val first = liveMessage(1, 1000u, "msg", Bytes.of(0xaa), "Alice")
        val second = liveMessage(2, 1300u, "msg", Bytes.of(0xaa), "Alice")
        assertOriginalGroupingArrival(
            listOf(RoomMessageRow(first, true, true, true)),
            listOf(RoomMessageRow(first, true, true, false), RoomMessageRow(second, false, false, true)),
        )
    }

    @Test fun independent301SecondPairSplitsBothBookendsAndVisibleTimestampsBeforeReload() {
        val first = liveMessage(1, 1000u, "msg", Bytes.of(0xaa), "Alice")
        val second = liveMessage(2, 1301u, "msg", Bytes.of(0xaa), "Alice")
        val (before, after) = assertOriginalGroupingArrival(
            listOf(RoomMessageRow(first, true, true, true)),
            listOf(RoomMessageRow(first, true, true, true), RoomMessageRow(second, true, true, true)),
        )
        assertEquals(before.single(), after.first())
    }

    @Test fun selfMessageBreaksTheIncomingSamePrefixClusterBeforeReload() {
        val first = liveMessage(1, 100u, "msg", Bytes.of(0xaa), "Alice")
        val self = liveMessage(2, 160u, "msg", Bytes.of(0xaa), "Alice").copy(isFromSelf = true)
        val third = liveMessage(3, 220u, "msg", Bytes.of(0xaa), "Alice")
        val (before, after) = assertOriginalGroupingArrival(
            listOf(RoomMessageRow(first, true, true, true)),
            listOf(
                RoomMessageRow(first, true, true, true),
                RoomMessageRow(self, false, false, false),
                RoomMessageRow(third, false, true, true),
            ),
        )
        assertEquals(before.single(), after.first())
    }

    private fun assertOriginalGroupingArrival(
        initialRows: List<RoomMessageRow>,
        expectedRows: List<RoomMessageRow>,
    ): Pair<List<RoomMessageRow>, List<RoomMessageRow>> {
        val locale = Locale.getDefault()
        val formatLocale = Locale.getDefault(Locale.Category.FORMAT)
        val displayLocale = Locale.getDefault(Locale.Category.DISPLAY)
        val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.US)
            TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
            val timestampLabels = mapOf(
                100u to "Jan 1, 00:01", 160u to "Jan 1, 00:02", 220u to "Jan 1, 00:03",
                1000u to "Jan 1, 00:16", 1300u to "Jan 1, 00:21", 1301u to "Jan 1, 00:21",
            )
            val fixture = Fixture()
            fixture.stored = initialRows.map { it.message }
            mountRoute(fixture)
            compose.waitUntil { fixture.holder.state.value.hasLoadedOnce && fixture.events.subscriptionCount.value == 1 }
            compose.onNode(hasSetTextAction()).performTextReplacement(body)
            fun assertVisibleRows(expected: List<RoomMessageRow>) {
                val state = fixture.holder.state.value
                assertEquals(expected.map { it.message }, state.messages)
                assertEquals(expected, state.rows)
                assertEquals(body, state.draft)
                assertTrue(state.hasLoadedOnce)
                assertNull(state.failure)
                val bubbles = expected.map { row ->
                    compose.onNodeWithTag("room-bubble:${row.message.id}", useUnmergedTree = true).assertIsDisplayed()
                        .fetchSemanticsNode().boundsInRoot
                }
                assertTrue(bubbles.zipWithNext().all { (a, b) -> a.bottom <= b.top })
                compose.onAllNodesWithText("msg", useUnmergedTree = true).assertCountEquals(expected.size)
                val labels = expected.map { row ->
                    if (row.message.isFromSelf) "msg, ${text(Rooms.remoteNodesRoomMessageStatusDeliveredLabel)}"
                    else "${row.message.authorDisplayName}: msg"
                }
                for ((label, count) in labels.groupingBy { it }.eachCount()) {
                    val nodes = compose.onAllNodesWithContentDescription(label).assertCountEquals(count)
                    for (index in 0 until count) nodes[index].assertIsDisplayed()
                }
                for (name in expected.map { it.message.authorDisplayName }.distinct()) {
                    val namedRows = expected.withIndex().filter { it.value.showSenderName && it.value.message.authorDisplayName == name }
                    val names = compose.onAllNodesWithText(name, useUnmergedTree = true).assertCountEquals(namedRows.size)
                    val bounds = namedRows.indices.map { names[it].assertIsDisplayed().fetchSemanticsNode().boundsInRoot }
                        .sortedBy { it.top }
                    for ((index, row) in namedRows.withIndex()) {
                        assertTrue(bounds[index].bottom <= bubbles[row.index].top)
                        if (row.index > 0) assertTrue(bubbles[row.index - 1].bottom <= bounds[index].top)
                    }
                    compose.onAllNodesWithContentDescription(name).assertCountEquals(0)
                }
                for (label in expected.map { timestampLabels.getValue(it.message.timestamp) }.distinct()) {
                    val count = expected.count { it.showTimestamp && timestampLabels.getValue(it.message.timestamp) == label }
                    val nodes = compose.onAllNodesWithText(label).assertCountEquals(count)
                    for (index in 0 until count) nodes[index].assertIsDisplayed()
                }
                compose.onNode(hasSetTextAction()).assertTextContains(body)
                compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertDoesNotExist()
                compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
            }
            assertVisibleRows(initialRows)
            val before = fixture.holder.state.value.rows
            val load = listOf("fetch", "read", "failed-seen", "notifications", "badge", "changed")
            assertEquals(load, fixture.loads)
            val expectedMessages = expectedRows.map { it.message }
            assertEquals(expectedMessages.size, expectedMessages.map { it.deduplicationKey }.distinct().size)
            val held = CompletableDeferred<Unit>()
            compose.runOnIdle {
                fixture.fetchGate = held
                fixture.stored = expectedMessages
                for (row in expectedRows.drop(initialRows.size)) {
                    assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, row.message)))
                }
            }
            compose.waitUntil { fixture.holder.state.value.messages == expectedMessages }
            assertFalse(fixture.holder.state.value.isLoading)
            assertEquals(load, fixture.loads)
            assertVisibleRows(expectedRows)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            compose.waitUntil { fixture.loads.count { it == "fetch" } == 2 }
            assertTrue(fixture.holder.state.value.isLoading)
            assertEquals(load + "fetch", fixture.loads)
            assertVisibleRows(expectedRows)
            compose.runOnIdle { assertTrue(held.complete(Unit)) }
            compose.waitUntil { !fixture.holder.state.value.isLoading && fixture.loads.count { it == "changed" } == 2 }
            assertVisibleRows(expectedRows)
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
            assertEquals(load + load, fixture.loads)
            assertEquals(1, fixture.events.subscriptionCount.value)
            assertTrue(fixture.posts.isEmpty())
            assertTrue(fixture.joins.isEmpty())
            assertTrue(fixture.retries.isEmpty())
            assertTrue(fixture.reported.isEmpty())
            return before to fixture.holder.state.value.rows
        } finally {
            TimeZone.setDefault(zone)
            Locale.setDefault(locale)
            Locale.setDefault(Locale.Category.FORMAT, formatLocale)
            Locale.setDefault(Locale.Category.DISPLAY, displayLocale)
        }
    }

    @Test fun originalIncomingFollowUpAnnouncesTheAuthorEvenWithoutAVisibleName() {
        val message = liveMessage(1, 100u, "follow up", Bytes.of(0xaa), "Alice")
        mountOriginalRoomBubble(RoomMessageRow(message, false, false, true))
        assertOriginalBubbleLabel(message, "Alice: follow up")
        compose.onNodeWithText("Alice", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun originalIncomingClusterStartAnnouncesTheAuthorOnlyOnce() {
        val message = liveMessage(1, 100u, "hello", Bytes.of(0xaa), "Alice")
        mountOriginalRoomBubble(RoomMessageRow(message, false, true, true))
        val label = "Alice: hello"
        val actual = assertOriginalBubbleLabel(message, label)
        assertEquals(1, Regex("Alice").findAll(actual).count())
        compose.onAllNodesWithText("Alice", useUnmergedTree = true).assertCountEquals(1)
    }

    @Test fun originalSentOutgoingAnnouncesItsStatusOnlyOnce() {
        val message = liveMessage(1, 100u, "hello", Bytes.of(0x42), "Me")
            .copy(isFromSelf = true, statusRawValue = MessageStatus.SENT.rawValue)
        mountOriginalRoomBubble(RoomMessageRow(message, false, false, false))
        val status = text(Rooms.remoteNodesRoomMessageStatusDeliveredLabel)
        val label = "hello, $status"
        val actual = assertOriginalBubbleLabel(message, label)
        assertEquals(1, Regex(Regex.escape(status)).findAll(actual).count())
        compose.onAllNodesWithContentDescription(status).assertCountEquals(0)
        compose.onNodeWithText(text(Chats.chatsMessageStatusSent), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test fun originalFailedOutgoingCustomRetryInvokesTheExactMessageCallback() {
        val message = liveMessage(1, 100u, "hello", Bytes.of(0x42), "Me")
            .copy(isFromSelf = true, statusRawValue = MessageStatus.FAILED.rawValue)
        val retried = mutableListOf<UUID>()
        mountOriginalRoomBubble(RoomMessageRow(message, false, false, false), onRetry = { retried += it })
        val label = "hello, ${text(Rooms.remoteNodesRoomMessageStatusFailedLabel)}"
        assertOriginalBubbleLabel(message, label)
        val actions = compose.onNodeWithContentDescription(label).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertEquals(listOf(text(Chats.chatsMessageActionRetry)), actions.map { it.label })
        assertTrue(retried.isEmpty())
        compose.runOnIdle { assertTrue(actions.single().action()) }
        assertEquals(listOf(message.id), retried)
        assertOriginalBubbleLabel(message, label)
        compose.onNode(hasSetTextAction()).assertTextContains(body)
    }

    @Test fun originalSentOutgoingHasNoCustomRetryEvenWithACallback() {
        val message = liveMessage(1, 100u, "hello", Bytes.of(0x42), "Me")
            .copy(isFromSelf = true, statusRawValue = MessageStatus.SENT.rawValue)
        val retried = mutableListOf<UUID>()
        mountOriginalRoomBubble(RoomMessageRow(message, false, false, false), onRetry = { retried += it })
        val label = "hello, ${text(Rooms.remoteNodesRoomMessageStatusDeliveredLabel)}"
        assertOriginalBubbleLabel(message, label)
        val actions = compose.onNodeWithContentDescription(label).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        assertTrue(actions.isEmpty())
        compose.onNodeWithText(text(Chats.chatsMessageStatusRetry), useUnmergedTree = true).assertDoesNotExist()
        assertTrue(retried.isEmpty())
    }

    @Test fun originalHttpsBodyExposesTheLocalizedHostActionAndDispatchesTheOriginalUrl() {
        val message = liveMessage(1, 100u, "see https://example.com", Bytes.of(0xaa), "Alice")
        val routed = mutableListOf<String>()
        val external = mutableListOf<String>()
        val dispatcher = MessageLinkDispatcher(
            contacts = { error("HTTPS action must not resolve contacts") },
            radioId = { error("HTTPS action must not resolve a radio") },
            connectedDeviceName = { error("HTTPS action must not resolve self") },
            onMention = { error("HTTPS action must not open a mention") },
            route = { routed += it; false },
            openExternal = { external += it },
        )
        mountOriginalRoomBubble(RoomMessageRow(message, false, true, true), onLink = dispatcher::dispatch)
        val label = "Alice: see https://example.com"
        assertOriginalBubbleLabel(message, label)
        val actions = compose.onNodeWithContentDescription(label).fetchSemanticsNode().config[SemanticsActions.CustomActions]
        val expectedName = Chats.chatsMessageActionOpenWebLink(host.get().resources, "example.com")
        assertEquals(listOf(expectedName), actions.map { it.label })
        assertTrue(routed.isEmpty())
        assertTrue(external.isEmpty())
        compose.runOnIdle { assertTrue(actions.single().action()) }
        assertEquals(listOf("https://example.com"), routed)
        assertEquals(listOf("https://example.com"), external)
        assertEquals("example.com", java.net.URI(external.single()).host)
        assertOriginalBubbleLabel(message, label)
        compose.onNode(hasSetTextAction()).assertTextContains(body)
    }

    private fun mountOriginalRoomBubble(
        row: RoomMessageRow,
        onRetry: (UUID) -> Unit = { error("Unexpected original bubble retry") },
        onLink: (String) -> Boolean = { error("Unexpected original bubble link") },
    ) = mount {
        RoomConversationContent(
            RoomConversationState(
                session, messages = listOf(row.message), rows = listOf(row), draft = body, hasLoadedOnce = true,
            ),
            onDraftChanged = { error("Unexpected original bubble draft mutation") },
            onSend = { error("Unexpected original bubble send") },
            onRetry = onRetry,
            onLink = onLink,
            onReconnect = { error("Unexpected original bubble authentication") },
            onDismissError = { error("Unexpected original bubble failure") },
            failureText = { throw AssertionError("Unexpected original bubble error", it) },
        )
    }

    private fun assertOriginalBubbleLabel(message: RoomMessageDTO, label: String): String {
        val parent = compose.onAllNodesWithContentDescription(label).assertCountEquals(1)[0].assertIsDisplayed()
        val descriptions = parent.fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf(label), descriptions)
        compose.onAllNodesWithContentDescription(message.authorDisplayName).assertCountEquals(0)
        compose.onNodeWithTag("room-bubble:${message.id}", useUnmergedTree = true).assertIsDisplayed()
        compose.onAllNodes(hasText(message.text) and !hasSetTextAction(), useUnmergedTree = true).assertCountEquals(1)[0]
            .assertIsDisplayed()
        compose.onNode(hasSetTextAction()).assertTextContains(body)
        compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertDoesNotExist()
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
        return descriptions.single()
    }

    @Test fun liveSamePrefixBurstMovesTheRenderedAvatarAndPreservesTheFirstBubbleGutter() {
        val first = liveMessage(1, 100u, "msg", Bytes.of(0xaa), "Alice")
        val second = liveMessage(2, 160u, "msg", Bytes.of(0xaa), "Alice")
        val third = liveMessage(3, 220u, "msg", Bytes.of(0xaa), "Alice")
        val expected = listOf(first, second, third)
        val fixture = Fixture()
        fixture.stored = listOf(first)
        mountRoute(fixture)
        compose.waitUntil { fixture.holder.state.value.hasLoadedOnce && fixture.events.subscriptionCount.value == 1 }
        compose.onNode(hasSetTextAction()).performTextReplacement(body)
        compose.onAllNodesWithText("A", useUnmergedTree = true).assertCountEquals(1)[0].assertIsDisplayed()
        val initialBubble = compose.onNodeWithTag("room-bubble:${first.id}", useUnmergedTree = true)
            .fetchSemanticsNode().boundsInRoot
        val initialAvatar = compose.onNodeWithTag("room-avatar:${first.id}", useUnmergedTree = true)
            .assertIsDisplayed().assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
            .fetchSemanticsNode().boundsInRoot
        assertEquals(initialBubble.bottom, initialAvatar.bottom, 0f)
        assertEquals(6f, initialBubble.left - initialAvatar.right, 0f)
        val load = listOf("fetch", "read", "failed-seen", "notifications", "badge", "changed")
        assertEquals(load, fixture.loads)
        val held = CompletableDeferred<Unit>()
        compose.runOnIdle {
            fixture.fetchGate = held
            fixture.stored = expected
            assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, second)))
            assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, third)))
        }
        compose.waitUntil { fixture.holder.state.value.messages == expected }
        fun assertRenderedBookends() {
            val state = fixture.holder.state.value
            assertEquals(expected, state.messages)
            assertEquals(
                listOf(
                    RoomMessageRow(first, true, true, false), RoomMessageRow(second, false, false, false),
                    RoomMessageRow(third, false, false, true),
                ),
                state.rows,
            )
            compose.onNodeWithTag("room-avatar:${first.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("room-avatar:${second.id}", useUnmergedTree = true).assertDoesNotExist()
            val avatar = compose.onNodeWithTag("room-avatar:${third.id}", useUnmergedTree = true)
                .assertIsDisplayed().assertWidthIsEqualTo(56.dp).assertHeightIsEqualTo(56.dp)
                .fetchSemanticsNode().boundsInRoot
            val bubbles = expected.map {
                compose.onNodeWithTag("room-bubble:${it.id}", useUnmergedTree = true).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
            }
            assertEquals(initialBubble.left, bubbles.first().left, 0f)
            assertTrue(bubbles.all { it.left == initialBubble.left })
            assertEquals(bubbles.last().bottom, avatar.bottom, 0f)
            assertEquals(6f, bubbles.last().left - avatar.right, 0f)
            val name = compose.onAllNodesWithText("Alice", useUnmergedTree = true).assertCountEquals(1)[0]
                .assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals(bubbles.first().left, name.left, 0f)
            compose.onAllNodesWithText("A", useUnmergedTree = true).assertCountEquals(1)[0].assertIsDisplayed()
            compose.onAllNodesWithContentDescription("Alice: msg").assertCountEquals(3)
            compose.onAllNodesWithContentDescription("Alice").assertCountEquals(0)
            compose.onNode(hasSetTextAction()).assertTextContains(body)
            assertNull(state.failure)
        }
        assertRenderedBookends()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        compose.waitUntil { fixture.loads.count { it == "fetch" } == 2 }
        assertTrue(fixture.holder.state.value.isLoading)
        assertEquals(load + "fetch", fixture.loads)
        assertRenderedBookends()
        compose.runOnIdle { assertTrue(held.complete(Unit)) }
        compose.waitUntil { !fixture.holder.state.value.isLoading && fixture.loads.count { it == "changed" } == 2 }
        assertRenderedBookends()
        assertEquals(load + load, fixture.loads)
        assertEquals(1, fixture.events.subscriptionCount.value)
        assertTrue(fixture.posts.isEmpty())
        assertTrue(fixture.joins.isEmpty())
        assertTrue(fixture.retries.isEmpty())
        assertTrue(fixture.reported.isEmpty())
    }

    @Test fun incomingAvatarFramesMatchSharedIdentityThemeAndScaledGutterAcrossCurrentWindows() {
        val first = liveMessage(1, 100u, "msg", Bytes.of(0xaa), "Alice")
        val second = liveMessage(2, 160u, "msg", Bytes.of(0xaa), "Alice")
        val self = liveMessage(3, 220u, "hello", Bytes.of(0x42), "Me").copy(isFromSelf = true)
        val messages = listOf(first, second, self)
        val rows = listOf(
            RoomMessageRow(first, false, true, false), RoomMessageRow(second, false, false, true),
            RoomMessageRow(self, false, false, false),
        )
        mount {
            RoomConversationContent(
                RoomConversationState(session, messages, rows, draft = body, hasLoadedOnce = true),
                onDraftChanged = { error("Unexpected avatar-frame draft mutation") },
                onSend = { error("Unexpected avatar-frame send") }, onRetry = { error("Unexpected avatar-frame retry") },
                onLink = { error("Unexpected avatar-frame link") }, onReconnect = { error("Unexpected avatar-frame auth") },
                onDismissError = { error("Unexpected avatar-frame failure") },
                failureText = { throw AssertionError("Unexpected avatar-frame error", it) },
            )
        }
        val inputs = CoverageMatrix.themeWindowFontCases
        CoverageMatrix.assertThemeWindowFontCoverage()
        assertEquals(57, inputs.size)
        val captures = ConversationScreenCases(compose, host.get(), setContent = { host.get().setContent(content = it) })
        val output = File(requireNotNull(System.getProperty("wp502ScreenshotDirectory")), "sdk31/room-avatars")
        for (case in inputs) {
            compose.runOnIdle { frame.value = case.copy(heightDp = 1000) }
            compose.waitForIdle()
            assertEquals(case.expectedScheme, effectiveScheme)
            val diameter = 28f * case.fontScale.coerceAtLeast(1f)
            val avatar = compose.onNodeWithTag("room-avatar:${second.id}", useUnmergedTree = true)
                .assertIsDisplayed().assertWidthIsEqualTo(diameter.dp).assertHeightIsEqualTo(diameter.dp)
                .fetchSemanticsNode().boundsInRoot
            compose.onNodeWithTag("room-avatar:${first.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithTag("room-avatar:${self.id}", useUnmergedTree = true).assertDoesNotExist()
            val bubbles = listOf(first, second).map {
                compose.onNodeWithTag("room-bubble:${it.id}", useUnmergedTree = true).assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot
            }
            val name = compose.onNodeWithText("Alice", useUnmergedTree = true).assertIsDisplayed()
                .fetchSemanticsNode().boundsInRoot
            if (case.rtl) {
                assertEquals(bubbles[0].right, bubbles[1].right, 0f)
                assertEquals(6f, avatar.left - bubbles[1].right, 0f)
                assertEquals(bubbles[0].right, name.right, 0f)
            } else {
                assertEquals(bubbles[0].left, bubbles[1].left, 0f)
                assertEquals(6f, bubbles[1].left - avatar.right, 0f)
                assertEquals(bubbles[0].left, name.left, 0f)
            }
            assertEquals(bubbles[1].bottom, avatar.bottom, 0f)
            val theme = requireNotNull(ThemeRegistry.theme(case.theme)).resolve(case.expectedScheme, false)
            val fill = theme.identityColor("Alice")
            val expectedGlyph = readableGlyph(theme.avatarGlyphColor(fill, false), fill).toComposeColor()
            val layouts = mutableListOf<TextLayoutResult>()
            compose.onNodeWithText("A", useUnmergedTree = true).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single()
            assertEquals(expectedGlyph, layout.layoutInput.style.color)
            assertEquals(case.fontScale, layout.layoutInput.density.fontScale, 0f)
            val glyph = layout.getBoundingBox(0)
            val textBounds = compose.onNodeWithText("A", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val glyphGeometry = "${case.id}: avatar=$avatar, size=${layout.size}, " +
                "constraints=${layout.layoutInput.constraints}, lines=${layout.lineCount}, " +
                "font=${layout.layoutInput.style.fontSize}, paragraph=${layout.multiParagraph.width}, " +
                "glyph=$glyph, text=$textBounds"
            assertEquals(glyphGeometry, 1, layout.lineCount)
            assertTrue(glyphGeometry, glyph.left >= 0f && glyph.right <= layout.size.width)
            assertTrue(glyphGeometry, glyph.top >= 0f && glyph.bottom <= layout.size.height)
            assertTrue(glyphGeometry, textBounds.left + glyph.left >= avatar.left &&
                textBounds.left + glyph.right <= avatar.right &&
                textBounds.top + glyph.top >= avatar.top && textBounds.top + glyph.bottom <= avatar.bottom)
            assertFalse(glyphGeometry, layout.didOverflowHeight)
            compose.onAllNodesWithContentDescription("Alice: msg").assertCountEquals(2)
            compose.onAllNodesWithContentDescription("Alice").assertCountEquals(0)
            compose.onNodeWithContentDescription("hello, ${text(Rooms.remoteNodesRoomMessageStatusDeliveredLabel)}")
                .assertIsDisplayed()
            compose.onNode(hasSetTextAction()).assertTextContains(body)
            captures.captureMountedScreen("room-avatar-${case.id}", output, tag = "wp502-rooms")
            val root = compose.onNodeWithTag("wp502-rooms").fetchSemanticsNode().boundsInRoot
            val bitmap = requireNotNull(android.graphics.BitmapFactory.decodeFile(File(output, "room-avatar-${case.id}.png").path))
            try {
                val x = (avatar.left - root.left + avatar.width * 0.15f).toInt()
                val y = (avatar.center.y - root.top).toInt()
                assertEquals("${case.id}: actual avatar fill differs from shared identity/theme", fill.toComposeColor().toArgb(), bitmap.getPixel(x, y))
            } finally {
                bitmap.recycle()
            }
        }
    }

    @Test fun selfRoomRowsNeverRenderAnIncomingAvatarOrReserveAnIncomingColumn() {
        val message = liveMessage(1, 100u, "hello", Bytes.of(0x42), "Me").copy(isFromSelf = true)
        mountOriginalRoomBubble(RoomMessageRow(message, false, false, false))
        for (fontScale in listOf(1f, 2f)) {
            compose.runOnIdle { frame.value = frame.value.copy(fontScale = fontScale) }
            assertOriginalBubbleLabel(message, "hello, ${text(Rooms.remoteNodesRoomMessageStatusDeliveredLabel)}")
            compose.onNodeWithTag("room-avatar:${message.id}", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("M", useUnmergedTree = true).assertDoesNotExist()
            compose.onNodeWithText("Me", useUnmergedTree = true).assertDoesNotExist()
            val bubble = compose.onNodeWithTag("room-bubble:${message.id}", useUnmergedTree = true)
                .fetchSemanticsNode().boundsInRoot
            val root = compose.onNodeWithTag("wp502-rooms").fetchSemanticsNode().boundsInRoot
            assertEquals(root.right - 16f, bubble.right, 0f)
        }
    }

    @Test fun initialRoomFetchFailureShowsTheTypedErrorThenRestoresHistoryOnTheNextLiveReload() =
        assertRoomLoadStageFailure("fetch")

    @Test fun roomReadFailureKeepsFetchedRowsThenRepeatsAllReadEffectsOnRecovery() =
        assertRoomLoadStageFailure("read")

    @Test fun roomFailedSendSeenFailureStopsLaterReadEffectsUntilRecovery() =
        assertRoomLoadStageFailure("failed-seen")

    @Test fun roomNotificationRemovalFailureKeepsRowsAndDefersBadgeAndChangeUntilRecovery() =
        assertRoomLoadStageFailure("notifications")

    @Test fun roomBadgeFailureKeepsRowsAndDefersConversationChangeUntilRecovery() =
        assertRoomLoadStageFailure("badge")

    @Test fun roomConversationChangeFailureRemainsVisibleUntilDismissalAndSuccessfulReload() =
        assertRoomLoadStageFailure("changed")

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun assertRoomLoadStageFailure(failingStage: String) {
        val stages = listOf("fetch", "read", "failed-seen", "notifications", "badge", "changed")
        assertTrue(failingStage in stages)
        val cached = liveMessage(1, 100u, "cached", Bytes.of(0xaa), "Alice")
        val arrival = liveMessage(2, 160u, "arrived", Bytes.of(0xaa), "Alice")
        val fixture = Fixture()
        val failure = RoomServerError.PermissionDenied()
        val calls = mutableListOf<String>()
        var failureStage: String? = failingStage
        var stored = listOf(cached)
        var gate = CompletableDeferred<Unit>()
        fun failIfSelected(stage: String) {
            if (failureStage == stage) throw failure
        }
        fun step(stage: String) {
            calls += stage
            failIfSelected(stage)
        }
        fun sessionStep(stage: String, requestedSession: RemoteNodeSessionDTO) {
            assertEquals(session, requestedSession)
            step(stage)
        }
        val dependencies = object : RoomConversationDependencies by fixture {
            override val data = object : RoomConversationDataSource by fixture.data {
                override suspend fun fetchMessages(session: RemoteNodeSessionDTO): List<RoomMessageDTO> {
                    assertEquals(this@RoomsComposeTest.session, session)
                    calls += "fetch"
                    gate.await()
                    failIfSelected("fetch")
                    return stored
                }
                override suspend fun markAsRead(session: RemoteNodeSessionDTO) = sessionStep("read", session)
                override suspend fun markFailedSendsSeen(session: RemoteNodeSessionDTO) = sessionStep("failed-seen", session)
                override suspend fun refreshSession(session: RemoteNodeSessionDTO): RemoteNodeSessionDTO? =
                    error("Load must not refresh authentication")
                override suspend fun contactFor(session: RemoteNodeSessionDTO): ContactDTO? =
                    error("Load must not look up authentication")
            }
            override suspend fun removeDeliveredNotifications(session: RemoteNodeSessionDTO) =
                sessionStep("notifications", session)
            override suspend fun updateBadgeCount() = step("badge")
            override fun conversationsChanged() = step("changed")
        }
        val clock = TestCoroutineScheduler()
        val holderScope = CoroutineScope(scope.coroutineContext + StandardTestDispatcher(clock))
        val holder = RoomConversationStateHolder(session, dependencies, holderScope)
        mount {
            RoomConversationRoute(
                holder, fixture.links, failureText = { uiString(UiErrorMapper().message(it)) },
            )
        }
        compose.runOnIdle { clock.runCurrent() }
        compose.waitUntil { calls == listOf("fetch") && fixture.events.subscriptionCount.value == 1 }
        assertTrue(holder.state.value.isLoading)
        assertFalse(holder.state.value.hasLoadedOnce)
        compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
        compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertDoesNotExist()
        compose.onNode(hasSetTextAction()).performTextReplacement(body)
        compose.runOnIdle { assertTrue(gate.complete(Unit)) }
        compose.waitUntil { holder.state.value.failure === failure }
        val failedCalls = stages.take(stages.indexOf(failingStage) + 1)
        assertEquals(failedCalls, calls)
        assertEquals(listOf("load room" to failure), fixture.reported)
        assertTrue(holder.state.value.hasLoadedOnce)
        assertFalse(holder.state.value.isLoading)
        val cachedBeforeRecovery = if (failingStage == "fetch") emptyList() else listOf(cached)
        fun assertVisibleMessages(expected: List<RoomMessageDTO>) {
            assertEquals(expected, holder.state.value.messages)
            assertEquals(expected, holder.state.value.rows.map { it.message })
            for (message in expected) {
                compose.onAllNodesWithContentDescription("${message.authorDisplayName}: ${message.text}")
                    .assertCountEquals(1)[0].assertIsDisplayed()
                compose.onNodeWithTag("room-bubble:${message.id}", useUnmergedTree = true).assertIsDisplayed()
            }
            if (expected.isEmpty()) compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertIsDisplayed()
            else compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertDoesNotExist()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
            compose.onNode(hasSetTextAction()).assertTextContains(body)
        }
        assertVisibleMessages(cachedBeforeRecovery)
        if (cachedBeforeRecovery.isEmpty()) {
            compose.onNodeWithTag("room-bubble:${cached.id}", useUnmergedTree = true).assertDoesNotExist()
        }
        compose.onNodeWithText(text(Strings.errorRoomServerPermissionDenied)).assertIsDisplayed().performClick()
        assertNull(holder.state.value.failure)
        assertEquals(failedCalls, calls)
        val recoveryGate = CompletableDeferred<Unit>()
        compose.runOnIdle {
            failureStage = null
            gate = recoveryGate
            stored = listOf(cached, arrival)
            assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, arrival)))
            clock.runCurrent()
        }
        val optimistic = cachedBeforeRecovery + arrival
        compose.waitUntil { holder.state.value.messages == optimistic }
        assertVisibleMessages(optimistic)
        assertEquals(failedCalls, calls)
        compose.runOnIdle {
            clock.advanceTimeBy(49)
            clock.runCurrent()
            assertEquals(failedCalls, calls)
            assertFalse(holder.state.value.isLoading)
            clock.advanceTimeBy(1)
            clock.runCurrent()
        }
        compose.waitUntil { calls == failedCalls + "fetch" }
        assertTrue(holder.state.value.isLoading)
        assertTrue(holder.state.value.hasLoadedOnce)
        assertVisibleMessages(optimistic)
        compose.onNodeWithText(text(Strings.errorRoomServerPermissionDenied)).assertDoesNotExist()
        compose.runOnIdle {
            assertTrue(recoveryGate.complete(Unit))
            clock.runCurrent()
        }
        compose.waitUntil { !holder.state.value.isLoading && calls == failedCalls + stages }
        assertVisibleMessages(listOf(cached, arrival))
        assertEquals(
            listOf(RoomMessageRow(cached, true, true, false), RoomMessageRow(arrival, false, false, true)),
            holder.state.value.rows,
        )
        assertNull(holder.state.value.failure)
        assertEquals(listOf("load room" to failure), fixture.reported)
        assertEquals(failedCalls + stages, calls)
        assertEquals(1, fixture.events.subscriptionCount.value)
        assertEquals(body, holder.state.value.draft)
        assertTrue(fixture.posts.isEmpty())
        assertTrue(fixture.joins.isEmpty())
        assertTrue(fixture.retries.isEmpty())
        assertFalse(holder.state.value.isSending)
        assertFalse(holder.state.value.isAuthenticating)
        val settled = holder.state.value
        compose.runOnIdle {
            holderScope.cancel()
            clock.runCurrent()
            assertEquals(0, fixture.events.subscriptionCount.value)
            assertTrue(fixture.events.tryEmit(RoomConversationEvent.MessageReceived(session.id, cached)))
            clock.advanceTimeBy(50)
            clock.runCurrent()
        }
        assertEquals(settled, holder.state.value)
        assertEquals(failedCalls + stages, calls)
    }

    private fun liveMessage(
        id: Long,
        timestamp: UInt,
        text: String = "msg $timestamp",
        prefix: Bytes = Bytes.of(0xab, 0xcd, 0xef, 0x01),
        author: String = "Author",
    ) = RoomMessageDTO(
        id = UUID(0, id), sessionID = session.id, authorKeyPrefix = prefix, authorName = author,
        text = text, timestamp = timestamp, createdAt = Instant.ofEpochSecond(id),
    )

    private fun assertLiveArrival(
        initial: List<RoomMessageDTO>,
        arrival: RoomMessageDTO,
        expected: List<RoomMessageDTO>,
    ) {
        assertEquals(initial.size, initial.map { it.deduplicationKey }.distinct().size)
        assertEquals(expected.size, expected.map { it.deduplicationKey }.distinct().size)
        val fixture = Fixture()
        fixture.stored = initial
        mountRoute(fixture)
        compose.waitUntil { fixture.holder.state.value.hasLoadedOnce && fixture.events.subscriptionCount.value == 1 }
        compose.onNode(hasSetTextAction()).performTextReplacement(body)
        val load = listOf("fetch", "read", "failed-seen", "notifications", "badge", "changed")
        assertEquals(load, fixture.loads)
        val held = CompletableDeferred<Unit>()
        compose.runOnIdle {
            fixture.fetchGate = held
            fixture.stored = expected
            val event = RoomConversationEvent.MessageReceived(session.id, arrival)
            assertTrue(fixture.events.tryEmit(event))
            assertTrue(fixture.events.tryEmit(event))
        }
        compose.waitUntil { fixture.holder.state.value.messages == expected }
        fun assertVisibleRows() {
            assertEquals(expected, fixture.holder.state.value.messages)
            val tops = expected.map { message ->
                compose.onNodeWithContentDescription("Author: ${message.text}").assertIsDisplayed()
                    .fetchSemanticsNode().boundsInRoot.top
            }
            assertTrue("Actual mounted room rows must remain chronological", tops.zipWithNext().all { (a, b) -> a < b })
            compose.onNode(hasSetTextAction()).assertTextContains(body)
            compose.onNodeWithText(text(Rooms.remoteNodesRoomNoMessagesYet)).assertDoesNotExist()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertDoesNotExist()
        }
        assertVisibleRows()
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        compose.waitUntil { fixture.loads.count { it == "fetch" } == 2 }
        assertTrue(fixture.holder.state.value.isLoading)
        assertEquals(load + "fetch", fixture.loads)
        assertVisibleRows()
        compose.runOnIdle { assertTrue(held.complete(Unit)) }
        compose.waitUntil { !fixture.holder.state.value.isLoading && fixture.loads.count { it == "changed" } == 2 }
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(50))
        assertEquals(load + load, fixture.loads)
        assertVisibleRows()
        assertEquals(1, fixture.events.subscriptionCount.value)
        assertTrue(fixture.posts.isEmpty())
        assertTrue(fixture.joins.isEmpty())
        assertTrue(fixture.retries.isEmpty())
        assertTrue(fixture.reported.isEmpty())
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
                compose.onNode(hasText(message.text) and !hasSetTextAction(), useUnmergedTree = true)
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
