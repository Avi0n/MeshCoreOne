// AndroidOnly: WP-502 Complete frozen availability inputs through the real actions route; not device/golden acceptance.
// ReferenceInput: MC1Tests/Views/Chats/MessageActionAvailabilityTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/MessageActionsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.LocalMeshTheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as Chats
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.feature.chats.actions.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
@Config(sdk = [31], qualifiers = "en-rUS-w1080dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageActionAvailabilityComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var scope: CoroutineScope
    private lateinit var previousLocale: Locale
    private lateinit var previousZone: TimeZone
    private val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000900"))

    @Suppress("DEPRECATION")
    @Before fun createHost() {
        previousLocale = Locale.getDefault()
        previousZone = TimeZone.getDefault()
        Locale.setDefault(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
        val resources = host.get().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply { fontScale = 2f }, resources.displayMetrics)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    }

    @After fun closeHost() {
        try { host.windowFocusChanged(false); host.pause().stop().destroy() }
        finally {
            scope.cancel()
            Locale.setDefault(previousLocale)
            TimeZone.setDefault(previousZone)
        }
    }

    @Test fun floodNonEmptyPath() = verify(message(routeType = RouteType.FLOOD), Expected())
    @Test fun floodEmptyPath() = verify(message(pathNodes = Bytes.EMPTY, routeType = RouteType.FLOOD), Expected(path = false, details = false))
    @Test fun floodNilPath() = verify(message(pathNodes = null, routeType = RouteType.FLOOD), Expected(path = false, details = false))
    @Test fun directPath() = verify(message(routeType = RouteType.DIRECT), Expected(path = false, details = false, flood = false))
    @Test fun outgoingPath() = verify(message(direction = MessageDirection.OUTGOING, routeType = RouteType.FLOOD), Expected(path = false, details = false, outgoing = true))
    @Test fun channelOverridesDirectRoute() = verify(message(channelIndex = 0u, routeType = RouteType.DIRECT), Expected(named = true), resize = true)
    @Test fun incomingFloodWithoutExtras() = verify(message(routeType = RouteType.FLOOD), Expected())
    @Test fun incomingEmptyPathWithExtra() = verify(message(pathNodes = Bytes.EMPTY, routeType = RouteType.FLOOD, heardRepeats = 1), Expected(path = false))
    @Test fun incomingNilPathWithExtra() = verify(message(pathNodes = null, routeType = RouteType.FLOOD, heardRepeats = 1), Expected(path = false))
    @Test fun outgoingWithRepeats() = verify(message(direction = MessageDirection.OUTGOING, heardRepeats = 2), Expected(path = false, outgoing = true, repeats = true))
    @Test fun outgoingWithoutRepeats() = verify(message(direction = MessageDirection.OUTGOING), Expected(path = false, details = false, outgoing = true))
    @Test fun floodRouteClassification() = verify(message(routeType = RouteType.FLOOD), Expected())
    @Test fun tcFloodRouteClassification() = verify(message(routeType = RouteType.TC_FLOOD), Expected())
    @Test fun directRouteClassification() = verify(message(routeType = RouteType.DIRECT), Expected(path = false, details = false, flood = false))
    @Test fun tcDirectRouteClassification() = verify(message(routeType = RouteType.TC_DIRECT), Expected(path = false, details = false, flood = false))
    @Test fun unknownChannelRouteClassification() = verify(message(channelIndex = 0u), Expected(named = true))
    @Test fun unknownSentinelDirectClassification() = verify(message(pathLength = 0xFFu), Expected(path = false, details = false, flood = false))
    @Test fun unknownNonSentinelFloodClassification() = verify(message(pathLength = 2u), Expected())
    @Test fun directRouteIsDirect() = verify(message(routeType = RouteType.DIRECT), Expected(path = false, details = false, flood = false))
    @Test fun tcDirectRouteIsDirect() = verify(message(routeType = RouteType.TC_DIRECT), Expected(path = false, details = false, flood = false))
    @Test fun incomingNamedChannelCanSendDM() = verify(message(channelIndex = 0u), Expected(named = true))
    @Test fun outgoingChannelCannotSendDM() = verify(message(channelIndex = 0u, direction = MessageDirection.OUTGOING), Expected(path = false, details = false, outgoing = true))
    @Test fun incomingDMCannotSendDM() = verify(message(), Expected())
    @Test fun incomingPrefixOnlyChannelCannotSendDM() = verify(message(channelIndex = 0u, senderKeyPrefix = Bytes.of(0xAA)), Expected(), resize = true)
    @Test fun incomingPrefixOnlyChannelCannotBlock() = verify(message(channelIndex = 0u, senderKeyPrefix = Bytes.of(0xAA)), Expected())

    @Test fun everyEffectiveThemeRetainsOffersAndOneReadAcrossLoadingAndLoadedWidthChanges() {
        assertEquals(19, CoverageMatrix.themeFrames.size)
        assertEquals(10, CoverageMatrix.themeFrames.map { it.theme }.toSet().size)
        for (frame in CoverageMatrix.themeFrames) {
            verify(message(channelIndex = 0u), Expected(named = true), resize = true, frame = frame)
        }
    }

    @Test fun disposingTheOwnerDuringAHeldReadCannotPublishLateDetailsOrDispatchAnAction() =
        verify(message(channelIndex = 0u), Expected(named = true), resize = true, cancelWhileLoading = true)

    private data class Expected(
        val path: Boolean = true,
        val details: Boolean = true,
        val flood: Boolean = true,
        val outgoing: Boolean = false,
        val named: Boolean = false,
        val repeats: Boolean = false,
    )

    private fun message(
        contactID: UUID? = null,
        channelIndex: UByte? = null,
        pathLength: UByte = 2u,
        pathNodes: Bytes? = Bytes.of(0xA3, 0x7F),
        direction: MessageDirection = MessageDirection.INCOMING,
        routeType: RouteType? = null,
        senderKeyPrefix: Bytes? = null,
        heardRepeats: Long = 0,
    ) = MessageDTO(
        id = UUID.fromString("50200000-0000-0000-0000-000000000901"),
        radioId = radio, contactID = contactID, channelIndex = channelIndex,
        text = "Test", timestamp = 0u, createdAt = Instant.ofEpochSecond(1_700_000_000),
        direction = direction, status = MessageStatus.DELIVERED, textType = TextType.PLAIN,
        ackCode = null, pathLength = pathLength, snr = null, pathNodes = pathNodes,
        senderKeyPrefix = senderKeyPrefix,
        senderNodeName = if (senderKeyPrefix == null && channelIndex != null) "RemoteNode" else null,
        isRead = true, replyToID = null, roundTripTime = null, heardRepeats = heardRepeats,
        retryAttempt = 0, maxRetryAttempts = 0, routeType = routeType,
    )

    private fun verify(
        message: MessageDTO,
        expected: Expected,
        resize: Boolean = false,
        frame: ScreenCase = ScreenCase("availability"),
        cancelWhileLoading: Boolean = false,
    ) {
        val original = message.copy()
        val actual = MessageActionAvailability.forMessage(message)
        assertEquals(expected.path, actual.canViewPath)
        assertEquals(expected.details, actual.showsPathDetail)
        assertEquals(expected.flood, message.isFloodRouted)
        assertEquals(!expected.flood, message.isDirectRouted)
        assertEquals(!expected.outgoing, actual.canReply)
        assertEquals(expected.outgoing, actual.canSendAgain)
        assertEquals(expected.named, actual.canSendDirectMessage)
        assertEquals(expected.named, actual.canBlockSender)
        assertEquals(expected.repeats, actual.canShowRepeatDetails)
        assertTrue(actual.canCopy)
        assertTrue(actual.canDelete)

        val reads = mutableListOf<String>()
        val operations = mutableListOf<String>()
        val actions = mutableListOf<MessageAction>()
        val gate = CompletableDeferred<Unit>()
        var contactsCancelled = false
        val contacts = emptyList<ContactDTO>()
        val nodes = emptyList<DiscoveredNodeDTO>()
        val repeats = emptyList<MessageRepeatDTO>()
        fun forbidden(operation: String): Nothing {
            operations += operation
            throw AssertionError("Unexpected availability operation: $operation")
        }
        val source = object : MessageActionsDataSource {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                assertEquals(radio, radioId)
                reads += "contacts"
                try {
                    gate.await()
                } catch (cancelled: CancellationException) {
                    contactsCancelled = true
                    throw cancelled
                }
                return contacts
            }
            override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
                assertEquals(radio, radioId)
                reads += "discoveredNodes"
                return nodes
            }
            override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
                assertEquals(message.id, messageId)
                reads += "repeats"
                return repeats
            }
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> = forbidden("reactions")
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = forbidden("blockChannelSender")
            override suspend fun blockContacts(contactIds: Set<UUID>): Unit = forbidden("blockContacts")
            override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = forbidden("setMuted")
        }
        val holder = MessageActionsStateHolder(source, scope)
        val width = mutableStateOf(360)
        val direction = mutableStateOf(LayoutDirection.Ltr)
        val revision = mutableStateOf(0)
        val visible = mutableStateOf(true)
        var renderedRevision = -1
        host.get().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides direction.value) {
                MeshCoreTheme(
                    theme = requireNotNull(ThemeRegistry.theme(frame.theme)),
                    colorSchemePreference = frame.preference,
                    systemDark = frame.systemDark,
                    motionScale = 0f,
                ) {
                    val currentRevision = revision.value
                    val scheme = LocalMeshTheme.current.frame.colorScheme
                    SideEffect {
                        renderedRevision = currentRevision
                        assertEquals(frame.id, frame.expectedScheme, scheme)
                    }
                    Box(Modifier.width(width.value.dp).testTag("availability-window")) {
                        if (visible.value) {
                            MessageActionsRoute(
                                message, holder, null, null, emptyList(), actions::add,
                                modifier = Modifier.testTag("availability-route-$currentRevision"),
                                clock = Clock.fixed(Instant.ofEpochSecond(1_700_000_000), ZoneOffset.UTC),
                            )
                        }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertMountedProfile(host.get(), ScreenCase("availability", widthDp = 1080, heightDp = 1400, locale = "en-US", fontScale = 2f))
            assertEquals(if (expected.details) listOf("contacts") else emptyList<String>(), reads)
            assertEquals(expected.details, holder.state.value.isLoadingDetails)
            assertNull(holder.state.value.repeats)
            assertNull(holder.state.value.failure)
        }
        val progress = compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
        if (expected.details) progress.assertExists() else progress.assertDoesNotExist()
        compose.onNodeWithTag("availability-window").assertWidthIsEqualTo(360.dp)
        if (resize) {
            val loading = holder.state.value
            compose.runOnIdle {
                width.value = 720
                direction.value = LayoutDirection.Rtl
                revision.value++
            }
            compose.onNodeWithTag("availability-window").assertWidthIsEqualTo(720.dp)
            if (expected.details) progress.assertExists() else progress.assertDoesNotExist()
            compose.runOnIdle {
                assertSame(loading, holder.state.value)
                assertEquals(if (expected.details) listOf("contacts") else emptyList<String>(), reads)
                assertTrue(actions.isEmpty())
                assertTrue(operations.isEmpty())
            }
        }
        if (cancelWhileLoading) {
            assertTrue(expected.details)
            val loading = holder.state.value
            compose.runOnIdle {
                visible.value = false
                scope.cancel()
            }
            compose.onNodeWithTag("availability-route-${revision.value}").assertDoesNotExist()
            progress.assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(contactsCancelled)
                assertTrue(gate.complete(Unit))
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertSame(loading, holder.state.value)
                assertEquals(listOf("contacts"), reads)
                assertNull(holder.state.value.repeats)
                assertNull(holder.state.value.failure)
                assertEquals(original, message)
                assertTrue(actions.isEmpty())
                assertTrue(operations.isEmpty())
            }
            return
        }
        compose.runOnIdle { assertTrue(gate.complete(Unit)) }
        compose.waitForIdle()
        val readSequence = if (expected.details) listOf("contacts", "discoveredNodes", "repeats") else emptyList()
        compose.runOnIdle {
            assertEquals(readSequence, reads)
            assertFalse(holder.state.value.isLoadingDetails)
            assertNull(holder.state.value.failure)
            if (expected.details) {
                assertSame(repeats, holder.state.value.repeats)
                assertSame(contacts, holder.state.value.directory.contacts)
            } else {
                assertNull(holder.state.value.repeats)
            }
        }
        progress.assertDoesNotExist()
        val loaded = holder.state.value
        fun text(id: Int) = host.get().getString(id)
        fun offered(id: Int, visible: Boolean, click: Boolean = false) {
            val label = text(id)
            val node = compose.onNodeWithText(label)
            if (visible) {
                node.performScrollTo().assertIsDisplayed()
                assertMountedText(compose, node, label, 2f, direction.value, "complete availability original")
                if (click) node.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            } else node.assertDoesNotExist()
        }
        fun assertOffers(click: Boolean) {
            offered(Chats.chatsMessageActionReply, !expected.outgoing, click)
            offered(Chats.chatsMessageActionSendDM, expected.named, click)
            offered(Chats.chatsMessageActionCopy, true, click)
            offered(Chats.chatsMessageActionSendAgain, expected.outgoing, click)
            offered(Chats.chatsMessageActionPathDetails, expected.details)
            offered(Chats.chatsMessageActionBlockSender, expected.named)
            offered(Chats.chatsMessageActionTranslate, false)
            offered(Chats.chatsMessageActionDelete, true, click)
        }
        assertOffers(click = true)
        val dispatched = buildList {
            if (!expected.outgoing) add(MessageAction.Reply)
            if (expected.named) add(MessageAction.SendDirectMessage)
            add(MessageAction.Copy)
            if (expected.outgoing) add(MessageAction.SendAgain)
            add(MessageAction.Delete)
        }
        assertEquals(dispatched, actions)
        if (expected.named) {
            offered(Chats.chatsMessageActionBlockSender, true, click = true)
            compose.onNode(isDialog()).assertExists()
            val label = text(Chats.chatsBlockSenderCancel)
            val cancel = compose.onNodeWithText(label).assertIsDisplayed()
            assertMountedText(compose, cancel, label, 2f, direction.value, "cancel block without side effects")
            cancel.assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
        }
        compose.runOnIdle {
            revision.value++
            if (resize) {
                width.value = 360
                direction.value = LayoutDirection.Ltr
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("availability-window").assertWidthIsEqualTo(width.value.dp)
        assertOffers(click = false)
        compose.runOnIdle {
            assertEquals(if (resize) 2 else 1, renderedRevision)
            assertSame(loaded, holder.state.value)
            assertEquals(readSequence, reads)
            assertEquals(dispatched, actions)
            assertEquals(original, message)
            assertTrue(operations.isEmpty())
            assertFalse(contactsCancelled)
        }
    }
}
