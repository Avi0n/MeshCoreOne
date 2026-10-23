// AndroidOnly: WP-502 Exact unique-prefix name assertion through production actions loading/repeat-row rendering; no primary-case or device acceptance.
// ReferenceInput: MC1Tests/Views/Chats/Components/RepeatRowViewTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Components/RepeatRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Components/RepeatDetailsContent.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Utilities/RepeaterResolver.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-502 Additional exact zero-extra canonical arrival through the real details consumer; no primary-case or device acceptance.
// ReferenceInput: MC1Tests/Views/Chats/Components/MessagePathArrivalTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Components/MessagePathArrival.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Components/FallbackMatchIndicatorView.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.res.Configuration
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.feature.chats.actions.*
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
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
import org.robolectric.shadows.ShadowDialog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "en-rUS-w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessagePathComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var scope: CoroutineScope
    private lateinit var previousLocale: Locale
    private lateinit var previousZone: TimeZone

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
        assertEquals(2f, resources.configuration.fontScale, 0f)
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

    @Test fun uniqueShortPrefixRetainsRidgeInTheRealRepeatRowAcrossRecomposition() {
        val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000700"))
        val ridge = ContactDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000701"),
            radioId = radio, publicKey = Bytes.of(0x3F, 0x01, *IntArray(30)), name = "Ridge",
            typeRawValue = ContactType.REPEATER.rawValue, flags = 0u, outPathLength = 0u,
            outPath = Bytes.EMPTY, lastAdvertTimestamp = 10u, latitude = 0.0, longitude = 0.0,
            lastModified = 0u, lastHeardTimestamp = null, nickname = null,
            isBlocked = false, isMuted = false, isFavorite = false, lastMessageDate = null, unreadCount = 0,
        )
        val message = MessageDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000702"),
            radioId = radio, contactID = ridge.id, text = "Repeat target", timestamp = 1_700_000_000u,
            createdAt = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, pathLength = 0u, pathNodes = Bytes.EMPTY, heardRepeats = 1,
        )
        val repeat = MessageRepeatDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000703"),
            messageID = message.id, receivedAt = Instant.ofEpochSecond(1_700_000_001),
            pathNodes = Bytes.of(0x3F), pathLength = 1u, snr = 7.0, rssi = -80L,
        )
        val contacts = listOf(ridge)
        val repeats = listOf(repeat)
        val gate = CompletableDeferred<List<MessageRepeatDTO>>()
        val reads = mutableListOf<String>()
        val forbiddenCalls = mutableListOf<String>()
        fun forbidden(operation: String): Nothing {
            forbiddenCalls += operation
            throw AssertionError("Forbidden unique repeat-row interaction: $operation")
        }
        val source = object : MessageActionsDataSource {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                assertEquals(radio, radioId)
                reads += "contacts"
                return contacts
            }
            override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
                assertEquals(radio, radioId)
                reads += "discoveredNodes"
                return emptyList()
            }
            override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
                assertEquals(message.id, messageId)
                reads += "repeats"
                return gate.await()
            }
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> = forbidden("reactions")
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = forbidden("blockChannelSender")
            override suspend fun blockContacts(contactIds: Set<UUID>): Unit = forbidden("blockContacts")
            override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = forbidden("setMuted")
        }
        val holder = MessageActionsStateHolder(source, scope)
        val revision = mutableStateOf(0)
        host.get().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MeshCoreTheme(
                    colorSchemePreference = AppColorSchemePreference.LIGHT,
                    systemDark = false, highContrast = false, motionScale = 0f,
                ) {
                    MessageActionsRoute(
                        message, holder, localDevice = null, userLocation = null, recentEmojis = emptyList(),
                        onAction = { forbidden("action:$it") }, modifier = Modifier.testTag("repeat-route-${revision.value}"),
                    )
                }
            }
        }
        compose.runOnIdle {
            assertMountedProfile(host.get(), ScreenCase("unique-repeat-row", heightDp = 1400, locale = "en-US", fontScale = 2f))
            assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
            assertTrue(holder.state.value.isLoadingDetails)
            assertNull(holder.state.value.repeats)
            assertNull(holder.state.value.failure)
        }
        compose.onNodeWithText("3F  Ridge").assertDoesNotExist()
        compose.runOnIdle { assertTrue(gate.complete(repeats)) }

        fun assertOriginalAndRow() {
            val row = compose.onNodeWithContentDescription("Ridge. Excellent. SNR 7.0 dB. RSSI -80 dBm")
                .performScrollTo().assertIsDisplayed()
            for (text in listOf("3F  Ridge", "1 Hop", "Excellent", "SNR 7.0 dB", "RSSI -80 dBm")) {
                assertMountedText(compose, row, text, 2f, LayoutDirection.Ltr, "exact unique original repeat row")
            }
            compose.onNodeWithText("Valley", substring = true).assertDoesNotExist()
            compose.onNodeWithText("<unknown repeater>", substring = true).assertDoesNotExist()
            compose.runOnIdle {
                val state = holder.state.value
                assertFalse(state.isLoadingDetails)
                assertNull(state.failure)
                assertSame(contacts, state.directory.contacts)
                assertSame(ridge, state.directory.repeaters.single())
                assertTrue(state.directory.discoveredRepeaters.isEmpty())
                assertSame(repeats, state.repeats)
                val observed = requireNotNull(state.repeats).single()
                assertSame(repeat, observed)
                assertEquals(Bytes.of(0x3F), observed.repeaterHash)
                val resolution = PathNodeResolver.repeatResolution(
                    observed.repeaterHash, state.directory.repeaters, state.directory.discoveredRepeaters, null,
                    host.get().getString(AppChatsStrings.chatsRepeatsUnknownRepeater),
                )
                assertEquals("Ridge", resolution.displayName)
                assertEquals(NodeNameMatchKind.EXACT, resolution.matchKind)
                assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
                assertTrue(forbiddenCalls.isEmpty())
                assertNull(state.blockedSender)
                assertTrue(state.mutedContactIds.isEmpty())
                assertTrue(state.reactionGroups.isEmpty())
            }
        }
        assertOriginalAndRow()
        val retained = compose.runOnIdle { holder.state.value }
        compose.runOnIdle { revision.value++ }
        compose.onNodeWithTag("repeat-route-1").assertExists()
        compose.onNodeWithTag("repeat-route-0").assertDoesNotExist()
        assertOriginalAndRow()
        compose.runOnIdle { assertSame(retained, holder.state.value) }
    }

    @Test fun incomingWithoutExtrasRetainsItsSingleFirstArrivalAndRealCanonicalHopAcrossRecomposition() {
        val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000704"))
        val message = MessageDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000705"),
            radioId = radio, contactID = null, channelIndex = 0u, text = "flood", timestamp = 1_700_000_000u,
            createdAt = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, textType = TextType.PLAIN, ackCode = null,
            pathLength = 1u, snr = 8.5, pathNodes = Bytes.of(0xAA), senderKeyPrefix = null,
            senderNodeName = "Alice", isRead = true, replyToID = null, roundTripTime = null,
            heardRepeats = 0, retryAttempt = 0, maxRetryAttempts = 0,
        )
        val contacts = emptyList<ContactDTO>()
        val repeats = emptyList<MessageRepeatDTO>()
        val gate = CompletableDeferred<List<MessageRepeatDTO>>()
        val reads = mutableListOf<String>()
        val forbiddenCalls = mutableListOf<String>()
        fun forbidden(operation: String): Nothing {
            forbiddenCalls += operation
            throw AssertionError("Forbidden zero-extra canonical interaction: $operation")
        }
        val source = object : MessageActionsDataSource {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                assertEquals(radio, radioId)
                reads += "contacts"
                return contacts
            }
            override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
                assertEquals(radio, radioId)
                reads += "discoveredNodes"
                return emptyList()
            }
            override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
                assertEquals(message.id, messageId)
                reads += "repeats"
                return gate.await()
            }
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> = forbidden("reactions")
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = forbidden("blockChannelSender")
            override suspend fun blockContacts(contactIds: Set<UUID>): Unit = forbidden("blockContacts")
            override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = forbidden("setMuted")
        }
        val holder = MessageActionsStateHolder(source, scope)
        val revision = mutableStateOf(0)
        host.get().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MeshCoreTheme(
                    colorSchemePreference = AppColorSchemePreference.LIGHT,
                    systemDark = false, highContrast = false, motionScale = 0f,
                ) {
                    MessageActionsRoute(
                        message, holder, localDevice = null, userLocation = null, recentEmojis = emptyList(),
                        onAction = { forbidden("action:$it") }, modifier = Modifier.testTag("single-first-route-${revision.value}"),
                    )
                }
            }
        }
        compose.runOnIdle {
            assertMountedProfile(host.get(), ScreenCase("single-first-arrival", heightDp = 1400, locale = "en-US", fontScale = 2f))
            assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
            assertTrue(holder.state.value.isLoadingDetails)
            assertNull(holder.state.value.repeats)
            assertNull(holder.state.value.failure)
        }
        compose.onNodeWithText("1. AA").assertDoesNotExist()
        compose.runOnIdle { assertTrue(gate.complete(repeats)) }

        fun assertOriginalAndCanonicalHop() {
            val tag = "single-first-route-${revision.value}"
            for (text in listOf("Path Details", "1. AA", "<unknown>")) {
                val node = compose.onNode(hasText(text) and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true)
                    .performScrollTo().assertIsDisplayed()
                assertMountedText(compose, node, text, 2f, LayoutDirection.Ltr, "original zero-extra canonical arrival")
            }
            compose.onAllNodes(
                SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab), useUnmergedTree = true,
            ).assertCountEquals(0)
            compose.onNodeWithText("First").assertDoesNotExist()
            compose.onNodeWithText("#2").assertDoesNotExist()
            compose.onNodeWithText("Repeat Details").assertDoesNotExist()
            compose.runOnIdle {
                val state = holder.state.value
                assertFalse(state.isLoadingDetails)
                assertNull(state.failure)
                assertSame(contacts, state.directory.contacts)
                assertTrue(state.directory.repeaters.isEmpty())
                assertTrue(state.directory.discoveredRepeaters.isEmpty())
                assertSame(repeats, state.repeats)
                val arrivals = MessagePathArrivals.assemble(message, requireNotNull(state.repeats))
                assertEquals(1, arrivals.size)
                val first = arrivals.single()
                assertTrue(first.isFirst)
                assertEquals(message.id, first.id)
                assertEquals(Bytes.of(0xAA), first.pathNodes)
                assertEquals(1L, MessagePathArrivals.arrivalCount(message))
                assertEquals(1.toUByte(), first.pathLength)
                assertEquals(message.createdAt, first.receivedAt)
                assertEquals(8.5, first.snr)
                assertNull(first.rssi)
                assertFalse(first.isZeroHop)
                assertFalse(first.isPathUnavailable)
                assertEquals(listOf("AA"), first.pathHops.map { it.hex })
                assertEquals("AA", first.pathStringForClipboard)
                assertEquals(message.id, MessagePathArrivals.resolvedSelection(null, arrivals))
                assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
                assertTrue(forbiddenCalls.isEmpty())
                assertNull(state.blockedSender)
                assertTrue(state.mutedContactIds.isEmpty())
                assertTrue(state.reactionGroups.isEmpty())
            }
        }
        assertOriginalAndCanonicalHop()
        val retained = compose.runOnIdle { holder.state.value }
        compose.runOnIdle { revision.value++ }
        compose.onNodeWithTag("single-first-route-1").assertExists()
        compose.onNodeWithTag("single-first-route-0").assertDoesNotExist()
        assertOriginalAndCanonicalHop()
        compose.runOnIdle { assertSame(retained, holder.state.value) }
    }

    @Test fun nilPathFirstArrivalRemainsZeroHopWhenSelectingItsEchoAndRecomposing() {
        val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000713"))
        val message = MessageDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000714"),
            radioId = radio, contactID = null, channelIndex = 0u, text = "flood", timestamp = 1_700_000_000u,
            createdAt = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, textType = TextType.PLAIN, ackCode = null,
            pathLength = 0u, snr = 8.5, pathNodes = null, senderKeyPrefix = null,
            senderNodeName = "Alice", isRead = true, replyToID = null, roundTripTime = null,
            heardRepeats = 1, retryAttempt = 0, maxRetryAttempts = 0,
        )
        val echo = MessageRepeatDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000715"),
            messageID = message.id, receivedAt = Instant.ofEpochSecond(1_700_000_001),
            pathNodes = Bytes.of(0xAA), pathLength = 1u, snr = 7.0, rssi = -80L, rxLogEntryID = null,
        )
        val repeats = listOf(echo)
        val gate = CompletableDeferred<List<MessageRepeatDTO>>()
        val reads = mutableListOf<String>()
        val forbiddenCalls = mutableListOf<String>()
        fun forbidden(operation: String): Nothing {
            forbiddenCalls += operation
            throw AssertionError("Forbidden zero-hop arrival interaction: $operation")
        }
        val source = object : MessageActionsDataSource {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                assertEquals(radio, radioId)
                reads += "contacts"
                return emptyList()
            }
            override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
                assertEquals(radio, radioId)
                reads += "discoveredNodes"
                return emptyList()
            }
            override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
                assertEquals(message.id, messageId)
                reads += "repeats"
                return gate.await()
            }
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> = forbidden("reactions")
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = forbidden("blockChannelSender")
            override suspend fun blockContacts(contactIds: Set<UUID>): Unit = forbidden("blockContacts")
            override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = forbidden("setMuted")
        }
        val holder = MessageActionsStateHolder(source, scope)
        val revision = mutableStateOf(0)
        host.get().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MeshCoreTheme(
                    colorSchemePreference = AppColorSchemePreference.LIGHT,
                    systemDark = false, highContrast = false, motionScale = 0f,
                ) {
                    MessageActionsRoute(
                        message, holder, localDevice = null, userLocation = null, recentEmojis = emptyList(),
                        onAction = { forbidden("action:$it") }, modifier = Modifier.testTag("zero-hop-route-${revision.value}"),
                    )
                }
            }
        }
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        fun tab(label: String) = compose.onNode(hasText(label) and tabRole, useUnmergedTree = true)
        fun text(copy: String) = compose.onNode(
            hasText(copy) and hasAnyAncestor(hasTestTag("zero-hop-route-${revision.value}")), useUnmergedTree = true,
        )
        compose.runOnIdle {
            assertMountedProfile(host.get(), ScreenCase("nil-first-with-echo", heightDp = 1400, locale = "en-US", fontScale = 2f))
            assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
            assertTrue(holder.state.value.isLoadingDetails)
            assertNull(holder.state.value.repeats)
            assertNull(holder.state.value.failure)
        }
        compose.onAllNodes(tabRole, useUnmergedTree = true).assertCountEquals(0)
        text("Flood").assertDoesNotExist()
        text("1. AA").assertDoesNotExist()
        compose.runOnIdle { assertTrue(gate.complete(repeats)) }

        fun assertOriginalAndState() {
            compose.runOnIdle {
                val state = holder.state.value
                assertFalse(state.isLoadingDetails)
                assertNull(state.failure)
                assertSame(repeats, state.repeats)
                assertSame(echo, requireNotNull(state.repeats).single())
                assertNull(message.pathNodes)
                val arrivals = MessagePathArrivals.assemble(message, requireNotNull(state.repeats))
                assertEquals(2, arrivals.size)
                val first = arrivals.first()
                assertTrue(first.isFirst)
                assertEquals(Bytes.EMPTY, first.pathNodes)
                assertTrue(first.isZeroHop)
                assertFalse(first.isPathUnavailable)
                assertEquals(message.id, first.id)
                assertEquals(0.toUByte(), first.pathLength)
                assertTrue(first.pathHops.isEmpty())
                assertEquals(8.5, first.snr)
                assertNull(first.rssi)
                assertEquals(message.createdAt, first.receivedAt)
                assertEquals(echo.id, arrivals[1].id)
                assertFalse(arrivals[1].isFirst)
                assertFalse(arrivals[1].isZeroHop)
                assertFalse(arrivals[1].isPathUnavailable)
                assertEquals(listOf("AA"), arrivals[1].pathHops.map { it.hex })
                assertEquals(2L, MessagePathArrivals.arrivalCount(message))
                assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
                assertTrue(state.directory.contacts.isEmpty())
                assertTrue(state.directory.repeaters.isEmpty())
                assertTrue(state.directory.discoveredRepeaters.isEmpty())
                assertNull(state.blockedSender)
                assertTrue(state.mutedContactIds.isEmpty())
                assertTrue(state.reactionGroups.isEmpty())
                assertTrue(forbiddenCalls.isEmpty())
            }
            compose.onAllNodes(tabRole, useUnmergedTree = true).assertCountEquals(2)
            for (label in listOf("First", "#2")) {
                val node = tab(label).performScrollTo().assertIsDisplayed().assertHasClickAction()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                assertMountedText(compose, node, label, 2f, LayoutDirection.Ltr, "real arrival selector")
            }
            text("Path data is not available for this message").assertDoesNotExist()
            val repeatRow = text("AA  <unknown repeater>").performScrollTo().assertIsDisplayed()
            assertMountedText(compose, repeatRow, "AA  <unknown repeater>", 2f, LayoutDirection.Ltr, "original one-AA echo")
        }
        fun assertSelected(first: Boolean) {
            tab("First").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, first))
            tab("#2").assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, !first))
            if (first) {
                val flood = text("Flood").performScrollTo().assertIsDisplayed()
                assertMountedText(compose, flood, "Flood", 2f, LayoutDirection.Ltr, "canonical nil-path zero-hop consumer")
                text("1. AA").assertDoesNotExist()
            } else {
                text("Flood").assertDoesNotExist()
                for (copy in listOf("1. AA", "<unknown>")) {
                    val node = text(copy).performScrollTo().assertIsDisplayed()
                    assertMountedText(compose, node, copy, 2f, LayoutDirection.Ltr, "selected AA echo hop")
                }
            }
        }
        assertOriginalAndState()
        assertSelected(first = true)
        val retained = compose.runOnIdle { holder.state.value }
        tab("#2").performScrollTo().performClick()
        assertSelected(first = false)
        assertOriginalAndState()
        compose.runOnIdle { revision.value++ }
        compose.onNodeWithTag("zero-hop-route-1").assertExists()
        compose.onNodeWithTag("zero-hop-route-0").assertDoesNotExist()
        assertSelected(first = false)
        assertOriginalAndState()
        tab("First").performScrollTo().performClick()
        assertSelected(first = true)
        assertOriginalAndState()
        compose.runOnIdle { assertSame(retained, holder.state.value) }
    }

    @Test fun incomingExtrasSelectTheirOriginalTimeOrderRatherThanRepositoryOrderAcrossRecomposition() {
        val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000716"))
        val createdAt = Instant.ofEpochSecond(1_700_000_000)
        val message = MessageDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000717"),
            radioId = radio, contactID = null, channelIndex = 0u, text = "flood", timestamp = 1_700_000_000u,
            createdAt = createdAt, direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, textType = TextType.PLAIN, ackCode = null,
            pathLength = 1u, snr = 8.5, pathNodes = Bytes.of(0xAA), senderKeyPrefix = null,
            senderNodeName = "Alice", isRead = true, replyToID = null, roundTripTime = null,
            heardRepeats = 2, retryAttempt = 0, maxRetryAttempts = 0,
        )
        val extraB = MessageRepeatDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000718"),
            messageID = message.id, receivedAt = createdAt.plusSeconds(5),
            pathNodes = Bytes.of(0xBB), pathLength = 1u, snr = 7.0, rssi = -80L, rxLogEntryID = null,
        )
        val extraC = MessageRepeatDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000719"),
            messageID = message.id, receivedAt = createdAt.plusSeconds(1),
            pathNodes = Bytes.of(0xCC), pathLength = 1u, snr = 7.0, rssi = -80L, rxLogEntryID = null,
        )
        val repeats = listOf(extraB, extraC)
        val gate = CompletableDeferred<List<MessageRepeatDTO>>()
        val reads = mutableListOf<String>()
        val forbiddenCalls = mutableListOf<String>()
        fun forbidden(operation: String): Nothing {
            forbiddenCalls += operation
            throw AssertionError("Forbidden original arrival-order interaction: $operation")
        }
        val source = object : MessageActionsDataSource {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                assertEquals(radio, radioId)
                reads += "contacts"
                return emptyList()
            }
            override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
                assertEquals(radio, radioId)
                reads += "discoveredNodes"
                return emptyList()
            }
            override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
                assertEquals(message.id, messageId)
                reads += "repeats"
                return gate.await()
            }
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> = forbidden("reactions")
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = forbidden("blockChannelSender")
            override suspend fun blockContacts(contactIds: Set<UUID>): Unit = forbidden("blockContacts")
            override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = forbidden("setMuted")
        }
        val holder = MessageActionsStateHolder(source, scope)
        val revision = mutableStateOf(0)
        host.get().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MeshCoreTheme(
                    colorSchemePreference = AppColorSchemePreference.LIGHT,
                    systemDark = false, highContrast = false, motionScale = 0f,
                ) {
                    MessageActionsRoute(
                        message, holder, localDevice = null, userLocation = null, recentEmojis = emptyList(),
                        onAction = { forbidden("action:$it") }, modifier = Modifier.testTag("ordered-arrivals-${revision.value}"),
                    )
                }
            }
        }
        val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        fun tab(label: String) = compose.onNode(hasText(label) and tabRole, useUnmergedTree = true)
        fun text(copy: String) = compose.onNode(
            hasText(copy) and hasAnyAncestor(hasTestTag("ordered-arrivals-${revision.value}")), useUnmergedTree = true,
        )
        compose.runOnIdle {
            assertMountedProfile(host.get(), ScreenCase("original-ordered-extras", heightDp = 1400, locale = "en-US", fontScale = 2f))
            assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
            assertTrue(holder.state.value.isLoadingDetails)
            assertNull(holder.state.value.repeats)
            assertNull(holder.state.value.failure)
        }
        compose.onAllNodes(tabRole, useUnmergedTree = true).assertCountEquals(0)
        for (hop in listOf("AA", "BB", "CC")) text("1. $hop").assertDoesNotExist()
        compose.runOnIdle { assertTrue(gate.complete(repeats)) }

        fun assertOriginalAndState() {
            compose.runOnIdle {
                val state = holder.state.value
                assertFalse(state.isLoadingDetails)
                assertNull(state.failure)
                assertSame(repeats, state.repeats)
                assertSame(extraB, requireNotNull(state.repeats)[0])
                assertSame(extraC, requireNotNull(state.repeats)[1])
                val arrivals = MessagePathArrivals.assemble(message, requireNotNull(state.repeats))
                assertEquals(3, arrivals.size)
                assertTrue(arrivals[0].isFirst)
                assertEquals(message.id, arrivals[0].id)
                assertEquals(Bytes.of(0xCC), arrivals[1].pathNodes)
                assertEquals(Bytes.of(0xBB), arrivals[2].pathNodes)
                assertFalse(arrivals[1].isFirst)
                assertEquals(3L, MessagePathArrivals.arrivalCount(message))
                assertEquals(listOf(message.id, extraC.id, extraB.id), arrivals.map { it.id })
                assertEquals(listOf(createdAt, createdAt.plusSeconds(1), createdAt.plusSeconds(5)), arrivals.map { it.receivedAt })
                assertEquals(listOf(Bytes.of(0xAA), Bytes.of(0xCC), Bytes.of(0xBB)), arrivals.map { it.pathNodes })
                assertEquals(listOf(true, false, false), arrivals.map { it.isFirst })
                assertEquals(listOf(1.toUByte(), 1.toUByte(), 1.toUByte()), arrivals.map { it.pathLength })
                assertEquals(listOf(8.5, 7.0, 7.0), arrivals.map { it.snr })
                assertEquals(listOf(null, -80L, -80L), arrivals.map { it.rssi })
                assertTrue(arrivals.none { it.isZeroHop || it.isPathUnavailable })
                assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
                assertTrue(state.directory.contacts.isEmpty())
                assertTrue(state.directory.repeaters.isEmpty())
                assertTrue(state.directory.discoveredRepeaters.isEmpty())
                assertNull(state.blockedSender)
                assertTrue(state.mutedContactIds.isEmpty())
                assertTrue(state.reactionGroups.isEmpty())
                assertTrue(forbiddenCalls.isEmpty())
            }
            val tabs = compose.onAllNodes(tabRole, useUnmergedTree = true).assertCountEquals(3).fetchSemanticsNodes()
            assertEquals(listOf("First", "#2", "#3"), tabs.map { it.config[SemanticsProperties.Text].single().text })
            for (label in listOf("First", "#2", "#3")) {
                val node = tab(label).performScrollTo().assertIsDisplayed().assertHasClickAction()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                assertMountedText(compose, node, label, 2f, LayoutDirection.Ltr, "original time-ordered arrival selector")
            }
            text("Path data is not available for this message").assertDoesNotExist()
            text("Flood").assertDoesNotExist()
            for (hop in listOf("BB", "CC")) {
                val row = text("$hop  <unknown repeater>").performScrollTo().assertIsDisplayed()
                assertMountedText(compose, row, "$hop  <unknown repeater>", 2f, LayoutDirection.Ltr, "unchanged original repeat record")
            }
        }
        fun assertSelected(label: String, hop: String) {
            for (candidate in listOf("First", "#2", "#3")) {
                tab(candidate).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, candidate == label))
            }
            val node = text("1. $hop").performScrollTo().assertIsDisplayed()
            assertMountedText(compose, node, "1. $hop", 2f, LayoutDirection.Ltr, "actual selected original arrival hop")
            for (other in listOf("AA", "CC", "BB").filter { it != hop }) text("1. $other").assertDoesNotExist()
            assertOriginalAndState()
        }
        assertSelected("First", "AA")
        val retained = compose.runOnIdle { holder.state.value }
        tab("#2").performScrollTo().performClick()
        assertSelected("#2", "CC")
        tab("#3").performScrollTo().performClick()
        assertSelected("#3", "BB")
        compose.runOnIdle { revision.value++ }
        compose.onNodeWithTag("ordered-arrivals-1").assertExists()
        compose.onNodeWithTag("ordered-arrivals-0").assertDoesNotExist()
        assertSelected("#3", "BB")
        tab("#2").performScrollTo().performClick()
        assertSelected("#2", "CC")
        tab("First").performScrollTo().performClick()
        assertSelected("First", "AA")
        compose.runOnIdle { assertSame(retained, holder.state.value) }
    }

    @Test fun repeatFallbackHasIndependentAccessibleExplanationWithoutChangingExactOrUnresolvedRows() {
        val radio = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000706"))
        val ridge = ContactDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000707"),
            radioId = radio, publicKey = Bytes.of(0x3F, 0x01, *IntArray(30)), name = "Ridge",
            typeRawValue = ContactType.REPEATER.rawValue, flags = 0u, outPathLength = 0u,
            outPath = Bytes.EMPTY, lastAdvertTimestamp = 10u, latitude = 0.0, longitude = 0.0,
            lastModified = 0u, lastHeardTimestamp = null, nickname = null,
            isBlocked = false, isMuted = false, isFavorite = false, lastMessageDate = null, unreadCount = 0,
        )
        val valley = ridge.copy(
            id = UUID.fromString("50200000-0000-0000-0000-000000000708"),
            publicKey = Bytes.of(0x3F, 0x02, *IntArray(30)), name = "Valley",
        )
        val contacts = listOf(ridge, valley)
        val message = MessageDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000709"),
            radioId = radio, contactID = ridge.id, text = "Repeat uncertainty", timestamp = 1_700_000_000u,
            createdAt = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, pathLength = 0u, pathNodes = Bytes.EMPTY, heardRepeats = 3,
        )
        val fallback = MessageRepeatDTO(
            id = UUID.fromString("50200000-0000-0000-0000-000000000710"),
            messageID = message.id, receivedAt = Instant.ofEpochSecond(1_700_000_001),
            pathNodes = Bytes.of(0x3F), pathLength = 1u, snr = 7.0, rssi = -80L,
        )
        val exact = fallback.copy(
            id = UUID.fromString("50200000-0000-0000-0000-000000000711"),
            receivedAt = Instant.ofEpochSecond(1_700_000_002), pathNodes = Bytes.of(0x3F, 0x01), pathLength = 0x41u,
        )
        val unresolved = fallback.copy(
            id = UUID.fromString("50200000-0000-0000-0000-000000000712"),
            receivedAt = Instant.ofEpochSecond(1_700_000_003), pathNodes = Bytes.of(0xAA),
        )
        val repeats = listOf(fallback, exact, unresolved)
        val gate = CompletableDeferred<List<MessageRepeatDTO>>()
        val reads = mutableListOf<String>()
        val forbiddenCalls = mutableListOf<String>()
        fun forbidden(operation: String): Nothing {
            forbiddenCalls += operation
            throw AssertionError("Forbidden repeat uncertainty interaction: $operation")
        }
        val source = object : MessageActionsDataSource {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                assertEquals(radio, radioId)
                reads += "contacts"
                return contacts
            }
            override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
                assertEquals(radio, radioId)
                reads += "discoveredNodes"
                return emptyList()
            }
            override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
                assertEquals(message.id, messageId)
                reads += "repeats"
                return gate.await()
            }
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> = forbidden("reactions")
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = forbidden("blockChannelSender")
            override suspend fun blockContacts(contactIds: Set<UUID>): Unit = forbidden("blockContacts")
            override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = forbidden("setMuted")
        }
        val holder = MessageActionsStateHolder(source, scope)
        val revision = mutableStateOf(0)
        host.get().setContent {
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                MeshCoreTheme(
                    colorSchemePreference = AppColorSchemePreference.LIGHT,
                    systemDark = false, highContrast = false, motionScale = 0f,
                ) {
                    MessageActionsRoute(
                        message, holder, localDevice = null, userLocation = null, recentEmojis = emptyList(),
                        onAction = { forbidden("action:$it") }, modifier = Modifier.testTag("uncertainty-route-${revision.value}"),
                    )
                }
            }
        }
        val label = "Possible match, matched by short prefix"
        val title = "Possible Match"
        val explanation = "Multiple nodes share this prefix. The displayed name may not be correct."
        compose.runOnIdle {
            assertMountedProfile(host.get(), ScreenCase("repeat-uncertainty", heightDp = 1400, locale = "en-US", fontScale = 2f))
            assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
            assertTrue(holder.state.value.isLoadingDetails)
            assertNull(holder.state.value.repeats)
            assertNull(holder.state.value.failure)
        }
        compose.onNodeWithContentDescription(label).assertDoesNotExist()
        compose.runOnIdle { assertTrue(gate.complete(repeats)) }

        fun row(hashAndName: String, name: String) = compose.onNode(
            hasContentDescription("$name. Excellent. SNR 7.0 dB. RSSI -80 dBm") and
                hasAnyDescendant(hasText(hashAndName)),
            useUnmergedTree = true,
        )
        fun indicator() = compose.onNodeWithContentDescription(label)
        fun assertRowsAndState() {
            for ((text, name) in listOf("3F  Ridge" to "Ridge", "3F01  Ridge" to "Ridge", "AA  <unknown repeater>" to "<unknown repeater>")) {
                val observed = row(text, name).performScrollTo().assertIsDisplayed().assertHasNoClickAction()
                for (copy in listOf(text, "1 Hop", "Excellent", "SNR 7.0 dB", "RSSI -80 dBm")) {
                    assertMountedText(compose, observed, copy, 2f, LayoutDirection.Ltr, "real repeat uncertainty row")
                }
            }
            compose.onAllNodesWithContentDescription(label).assertCountEquals(1)
            val button = indicator().performScrollTo().assertIsDisplayed().assertHasClickAction()
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, explanation))
            assertMountedText(compose, button, title, 2f, LayoutDirection.Ltr, "independent repeat uncertainty control")
            val fallbackRowId = row("3F  Ridge", "Ridge").fetchSemanticsNode().id
            val buttonNode = button.fetchSemanticsNode()
            assertNotEquals(fallbackRowId, buttonNode.id)
            assertTrue(generateSequence(buttonNode.parent) { it.parent }.any { it.id == fallbackRowId })
            for ((text, name) in listOf("3F01  Ridge" to "Ridge", "AA  <unknown repeater>" to "<unknown repeater>")) {
                row(text, name).assert(hasAnyDescendant(hasContentDescription(label)).not())
            }
            compose.runOnIdle {
                val state = holder.state.value
                assertFalse(state.isLoadingDetails)
                assertNull(state.failure)
                assertSame(contacts, state.directory.contacts)
                assertSame(repeats, state.repeats)
                val kinds = repeats.map { repeat ->
                    PathNodeResolver.repeatResolution(
                        repeat.repeaterHash, state.directory.repeaters, state.directory.discoveredRepeaters, null,
                        host.get().getString(AppChatsStrings.chatsRepeatsUnknownRepeater),
                    ).matchKind
                }
                assertEquals(listOf(NodeNameMatchKind.FALLBACK, NodeNameMatchKind.EXACT, NodeNameMatchKind.UNRESOLVED), kinds)
                assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
                assertTrue(forbiddenCalls.isEmpty())
                assertNull(state.blockedSender)
                assertTrue(state.mutedContactIds.isEmpty())
                assertTrue(state.reactionGroups.isEmpty())
            }
        }
        fun assertExplanation() {
            compose.onNode(isDialog()).assertExists()
            for (copy in listOf(title, explanation, "Done")) {
                val node = compose.onNode(hasText(copy) and hasAnyAncestor(isDialog()), useUnmergedTree = true)
                assertMountedText(compose, node, copy, 2f, LayoutDirection.Ltr, "localized repeat uncertainty explanation")
            }
            compose.onNode(hasText("Done") and hasClickAction() and hasAnyAncestor(isDialog()))
                .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        }
        assertRowsAndState()
        val retained = compose.runOnIdle { holder.state.value }
        indicator().performTouchInput { click() }
        assertExplanation()
        compose.runOnIdle { revision.value++ }
        compose.onNodeWithTag("uncertainty-route-1").assertExists()
        compose.onNodeWithTag("uncertainty-route-0").assertDoesNotExist()
        assertExplanation()
        compose.onNode(hasText("Done") and hasClickAction() and hasAnyAncestor(isDialog())).performTouchInput { click() }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertRowsAndState()
        indicator().performSemanticsAction(SemanticsActions.OnClick) { assertTrue(it()) }
        assertExplanation()
        compose.runOnIdle {
            val dialog = requireNotNull(ShadowDialog.getLatestDialog())
            assertTrue(dialog.isShowing)
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
            dialog.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        }
        compose.onNode(isDialog()).assertDoesNotExist()
        assertRowsAndState()
        compose.runOnIdle { assertSame(retained, holder.state.value) }
    }

    @Test fun outgoingOriginalTwoEchoesHaveNoCanonicalArrivalAndSelectAndCopyTheirOwnPaths() {
        val message = originalOutgoing(721, heardRepeats = 2)
        val repeats = listOf(
            originalEcho(message, 722, Bytes.of(0x11)),
            originalEcho(message, 723, Bytes.of(0x22), receivedAt = Instant.ofEpochSecond(1_700_000_002)),
        )
        val mounted = OriginalArrivalMount(message, repeats)
        mounted.assertLoading()
        mounted.finishRead()
        fun assertOriginal() {
            val arrivals = mounted.assertLoaded()
            assertEquals(2, arrivals.size)
            assertTrue(arrivals.all { !it.isFirst })
            assertEquals(2L, MessagePathArrivals.arrivalCount(message))
            assertEquals(listOf(repeats[0].id, repeats[1].id), arrivals.map { it.id })
            assertTrue(arrivals.none { it.id == message.id })
            assertNull(message.pathNodes)
        }
        fun assertSelected(label: String, path: String) {
            assertOriginal()
            mounted.assertTabs(listOf("#1", "#2"), label)
            mounted.assertText("1. $path")
            mounted.text("1. ${if (path == "11") "22" else "11"}").assertDoesNotExist()
            mounted.copyPath(path)
            assertOriginal()
        }
        assertSelected("#1", "11")
        mounted.tab("#2").performScrollTo().performClick()
        assertSelected("#2", "22")
        mounted.recompose()
        assertSelected("#2", "22")
        mounted.tab("#1").performScrollTo().performClick()
        assertSelected("#1", "11")
    }

    @Test fun outgoingOriginalIdenticalEchoesRemainThreeSelectableObservationsWithoutInventedDifferences() {
        val message = originalOutgoing(724, heardRepeats = 3)
        val shared = Bytes.of(0x42)
        val repeats = listOf(
            originalEcho(message, 725, shared),
            originalEcho(message, 726, shared),
            originalEcho(message, 727, shared),
        )
        assertEquals(3, repeats.map { it.id }.toSet().size)
        assertEquals(listOf(repeats[0], repeats[0], repeats[0]), repeats.map { it.copy(id = repeats[0].id) })
        val mounted = OriginalArrivalMount(message, repeats)
        mounted.assertLoading()
        mounted.finishRead()
        fun assertOriginal() {
            val arrivals = mounted.assertLoaded()
            assertEquals(3, arrivals.size)
            assertEquals(setOf(shared), arrivals.map { it.pathNodes }.toSet())
            assertEquals(repeats.map { it.id }.toSet(), arrivals.map { it.id }.toSet())
            assertTrue(arrivals.none { it.isFirst || it.id == message.id })
            assertEquals(3L, MessagePathArrivals.arrivalCount(message))
            compose.onAllNodes(
                hasText("42  <unknown repeater>") and
                    hasAnyAncestor(hasTestTag(mounted.routeTag)),
                useUnmergedTree = true,
            ).assertCountEquals(3)
        }
        fun assertSelected(label: String) {
            assertOriginal()
            mounted.assertTabs(listOf("#1", "#2", "#3"), label)
            mounted.assertText("1. 42")
            mounted.copyPath("42")
            assertOriginal()
        }
        assertSelected("#1")
        mounted.tab("#2").performScrollTo().performClick()
        assertSelected("#2")
        mounted.tab("#3").performScrollTo().performClick()
        assertSelected("#3")
        mounted.recompose()
        assertSelected("#3")
        mounted.tab("#1").performScrollTo().performClick()
        assertSelected("#1")
    }

    @Test fun outgoingOriginalNilMessagePathUsesTheTwoHopEchoForRealDetailsAndClipboard() {
        val message = originalOutgoing(728, heardRepeats = 1)
        assertNull(message.pathNodes)
        val echo = originalEcho(message, 729, Bytes.of(0xA3, 0x7F), pathLength = 2u)
        val mounted = OriginalArrivalMount(message, listOf(echo))
        mounted.assertLoading()
        mounted.finishRead()
        fun assertOriginalAndConsumer() {
            assertNull(message.pathNodes)
            val arrivals = mounted.assertLoaded()
            val arrival = requireNotNull(arrivals.firstOrNull())
            assertFalse(arrival.isPathUnavailable)
            assertEquals(listOf("A3", "7F"), arrival.pathHops.map { it.hex })
            assertEquals("A3,7F", arrival.pathStringForClipboard)
            assertEquals(1, arrivals.size)
            assertEquals(echo.id, arrival.id)
            assertFalse(arrival.isFirst)
            mounted.assertTabs(emptyList(), selected = null)
            mounted.assertText("1. A3")
            mounted.assertText("2. 7F")
            mounted.assertText("A3 \u2192 7F")
            mounted.text("1. 7F").assertDoesNotExist()
            mounted.text("2. A3").assertDoesNotExist()
            mounted.copyPath("A3,7F")
            mounted.assertLoaded()
        }
        assertOriginalAndConsumer()
        mounted.recompose()
        assertOriginalAndConsumer()
    }

    @Test fun incomingOriginalNonNilEmptyPathStaysZeroHopWithoutForcingAnUnavailableDisclosureOrRead() {
        val message = MessageDTO(
            id = UUID(502, 730), radioId = RadioId(UUID(502, 720)),
            contactID = null, channelIndex = 0u, text = "flood", timestamp = 1_700_000_000u,
            createdAt = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, textType = TextType.PLAIN, ackCode = null,
            pathLength = 0u, snr = 8.5, pathNodes = Bytes.EMPTY, senderKeyPrefix = null,
            senderNodeName = "Alice", isRead = true, replyToID = null, roundTripTime = null,
            heardRepeats = 0, retryAttempt = 0, maxRetryAttempts = 0,
        )
        val mounted = OriginalArrivalMount(message, emptyList())
        fun assertOriginalAndConsumer() {
            assertNotNull(message.pathNodes)
            assertEquals(Bytes.EMPTY, message.pathNodes)
            val arrivals = MessagePathArrivals.assemble(message, emptyList())
            val arrival = requireNotNull(arrivals.firstOrNull())
            assertTrue(arrival.isZeroHop)
            assertFalse(arrival.isPathUnavailable)
            assertTrue(arrival.pathHops.isEmpty())
            assertEquals(1, arrivals.size)
            assertEquals(message.id, arrival.id)
            assertTrue(arrival.isFirst)
            assertEquals(1L, MessagePathArrivals.arrivalCount(message))
            assertFalse(MessageActionAvailability.forMessage(message).showsPathDetail)
            mounted.assertNoRead()
            mounted.assertTabs(emptyList(), selected = null)
            mounted.assertText("flood")
            mounted.assertText("0 hops")
            mounted.assertText("SNR 8.5 dB (Excellent)")
            mounted.text(host.get().getString(AppChatsStrings.chatsMessageActionPathDetails)).assertDoesNotExist()
            mounted.text(host.get().getString(AppChatsStrings.chatsMessageActionRepeatDetails)).assertDoesNotExist()
            mounted.text("Flood").assertDoesNotExist()
            mounted.text("1. AA").assertDoesNotExist()
            mounted.assertNoPathCopy()
        }
        assertOriginalAndConsumer()
        mounted.recompose()
        assertOriginalAndConsumer()
    }

    private fun originalOutgoing(id: Long, heardRepeats: Long) = MessageDTO(
        id = UUID(502, id), radioId = RadioId(UUID(502, 720)),
        contactID = null, channelIndex = 0u, text = "out", timestamp = 1_700_000_000u,
        createdAt = Instant.ofEpochSecond(1_700_000_000), direction = MessageDirection.OUTGOING,
        status = MessageStatus.SENT, textType = TextType.PLAIN, ackCode = null,
        pathLength = 0u, snr = null, pathNodes = null, senderKeyPrefix = null,
        senderNodeName = null, isRead = true, replyToID = null, roundTripTime = null,
        heardRepeats = heardRepeats, retryAttempt = 0, maxRetryAttempts = 0,
    )

    private fun originalEcho(
        message: MessageDTO,
        id: Long,
        pathNodes: Bytes,
        pathLength: UByte = 1u,
        receivedAt: Instant = Instant.ofEpochSecond(1_700_000_001),
    ) = MessageRepeatDTO(
        id = UUID(502, id), messageID = message.id, receivedAt = receivedAt,
        pathNodes = pathNodes, pathLength = pathLength, snr = 7.0, rssi = -80L, rxLogEntryID = null,
    )

    private inner class OriginalArrivalMount(
        private val message: MessageDTO,
        private val repeats: List<MessageRepeatDTO>,
    ) {
        private val reads = mutableListOf<String>()
        private val forbiddenCalls = mutableListOf<String>()
        private val gate = CompletableDeferred<List<MessageRepeatDTO>>()
        private val revision = mutableStateOf(0)
        private val tabRole = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        private val source = object : MessageActionsDataSource {
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                assertEquals(message.radioId, radioId)
                reads += "contacts"
                return emptyList()
            }
            override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
                assertEquals(message.radioId, radioId)
                reads += "discoveredNodes"
                return emptyList()
            }
            override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
                assertEquals(message.id, messageId)
                reads += "repeats"
                return gate.await()
            }
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> = forbidden("reactions")
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = forbidden("blockChannelSender")
            override suspend fun blockContacts(contactIds: Set<UUID>): Unit = forbidden("blockContacts")
            override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = forbidden("setMuted")
        }
        private val holder = MessageActionsStateHolder(source, scope)
        private val initialState = holder.state.value
        private lateinit var loadedState: MessageActionsState
        private val clipboard = requireNotNull(host.get().getSystemService(android.content.ClipboardManager::class.java))
        val routeTag: String get() = "original-remainder-${revision.value}"

        init {
            host.get().setContent {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    MeshCoreTheme(
                        colorSchemePreference = AppColorSchemePreference.LIGHT,
                        systemDark = false, highContrast = false, motionScale = 0f,
                    ) {
                        MessageActionsRoute(
                            message, holder, localDevice = null, userLocation = null, recentEmojis = emptyList(),
                            onAction = { forbidden("action:$it") }, modifier = Modifier.testTag(routeTag),
                        )
                    }
                }
            }
            compose.runOnIdle {
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Original", "untouched original clipboard"))
                assertMountedProfile(host.get(), ScreenCase("original-arrival-remainder", heightDp = 1400, locale = "en-US", fontScale = 2f))
            }
        }

        private fun forbidden(operation: String): Nothing {
            forbiddenCalls += operation
            throw AssertionError("Forbidden original remainder interaction: $operation")
        }

        fun text(copy: String) = compose.onNode(
            hasText(copy) and hasAnyAncestor(hasTestTag(routeTag)), useUnmergedTree = true,
        )

        fun tab(label: String) = compose.onNode(hasText(label) and tabRole, useUnmergedTree = true)

        fun assertText(copy: String) {
            val node = text(copy).performScrollTo().assertIsDisplayed()
            assertMountedText(compose, node, copy, 2f, LayoutDirection.Ltr, "original arrival remainder")
        }

        fun assertLoading() {
            compose.runOnIdle {
                assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
                assertTrue(holder.state.value.isLoadingDetails)
                assertNull(holder.state.value.repeats)
                assertNull(holder.state.value.failure)
                assertFalse(gate.isCompleted)
                assertTrue(forbiddenCalls.isEmpty())
            }
            compose.onAllNodes(tabRole, useUnmergedTree = true).assertCountEquals(0)
            for (hop in listOf("1. 11", "1. 22", "1. 42", "1. A3", "2. 7F")) text(hop).assertDoesNotExist()
            assertNoPathCopy()
        }

        fun finishRead() {
            compose.runOnIdle { assertTrue(gate.complete(repeats)) }
            compose.runOnIdle {
                assertFalse(holder.state.value.isLoadingDetails)
                assertNull(holder.state.value.failure)
                loadedState = holder.state.value
            }
        }

        fun assertLoaded(): List<MessagePathArrival> = compose.runOnIdle {
            val state = holder.state.value
            assertSame(loadedState, state)
            assertFalse(state.isLoadingDetails)
            assertNull(state.failure)
            assertSame(repeats, state.repeats)
            for (index in repeats.indices) assertSame(repeats[index], requireNotNull(state.repeats)[index])
            assertEquals(listOf("contacts", "discoveredNodes", "repeats"), reads)
            assertTrue(state.directory.contacts.isEmpty())
            assertTrue(state.directory.repeaters.isEmpty())
            assertTrue(state.directory.discoveredRepeaters.isEmpty())
            assertNull(state.blockedSender)
            assertTrue(state.mutedContactIds.isEmpty())
            assertTrue(state.reactionGroups.isEmpty())
            assertTrue(forbiddenCalls.isEmpty())
            val arrivals = MessagePathArrivals.assemble(message, requireNotNull(state.repeats))
            for (echo in repeats) {
                val arrival = arrivals.single { it.id == echo.id }
                assertEquals(echo.pathNodes, arrival.pathNodes)
                assertEquals(echo.pathLength, arrival.pathLength)
                assertEquals(echo.receivedAt, arrival.receivedAt)
                assertEquals(echo.snr, arrival.snr)
                assertEquals(echo.rssi, arrival.rssi)
                assertFalse(arrival.isFirst)
                assertFalse(arrival.isPathUnavailable)
            }
            arrivals
        }

        fun assertTabs(labels: List<String>, selected: String?) {
            val nodes = compose.onAllNodes(tabRole, useUnmergedTree = true)
                .assertCountEquals(labels.size).fetchSemanticsNodes()
            assertEquals(labels, nodes.map { it.config[SemanticsProperties.Text].single().text })
            for (label in labels) {
                val node = tab(label).performScrollTo().assertIsDisplayed().assertHasClickAction()
                    .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, label == selected))
                assertMountedText(compose, node, label, 2f, LayoutDirection.Ltr, "original remainder selector")
            }
            tab("First").assertDoesNotExist()
            text("Path data is not available for this message").assertDoesNotExist()
        }

        fun copyPath(expected: String) {
            val copyLabel = host.get().getString(AppChatsStrings.chatsPathCopyAccessibility)
            val copyHint = host.get().getString(AppChatsStrings.chatsPathCopyHint)
            val button = compose.onNodeWithContentDescription(copyLabel).performScrollTo()
                .assertIsDisplayed().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher("original localized path copy hint") {
                    it.config[SemanticsActions.OnClick].label == copyHint
                })
            assertMountedText(
                compose, button, host.get().getString(AppChatsStrings.chatsPathCopyButton),
                2f, LayoutDirection.Ltr, "real original path-copy button",
            )
            button.performClick()
            compose.runOnIdle {
                assertEquals(expected, requireNotNull(clipboard.primaryClip).getItemAt(0).text.toString())
                assertNotEquals(message.text, expected)
                assertFalse(expected.contains("\u2192"))
                assertTrue(forbiddenCalls.isEmpty())
            }
        }

        fun assertNoPathCopy() {
            compose.onNodeWithContentDescription(host.get().getString(AppChatsStrings.chatsPathCopyAccessibility)).assertDoesNotExist()
            compose.runOnIdle {
                assertEquals("untouched original clipboard", requireNotNull(clipboard.primaryClip).getItemAt(0).text.toString())
            }
        }

        fun assertNoRead() {
            compose.runOnIdle {
                assertSame(initialState, holder.state.value)
                assertFalse(holder.state.value.isLoadingDetails)
                assertNull(holder.state.value.repeats)
                assertNull(holder.state.value.failure)
                assertTrue(reads.isEmpty())
                assertFalse(gate.isCompleted)
                assertTrue(forbiddenCalls.isEmpty())
            }
        }

        fun recompose() {
            val oldTag = routeTag
            compose.runOnIdle { revision.value++ }
            compose.onNodeWithTag(routeTag).assertExists()
            compose.onNodeWithTag(oldTag).assertDoesNotExist()
        }
    }
}
