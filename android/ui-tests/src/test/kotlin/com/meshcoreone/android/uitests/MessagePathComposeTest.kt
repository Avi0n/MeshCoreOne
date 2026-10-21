// AndroidOnly: WP-502 Exact unique-prefix name assertion through production actions loading/repeat-row rendering; no primary-case or device acceptance.
// ReferenceInput: MC1Tests/Views/Chats/Components/RepeatRowViewTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Components/RepeatRowView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Components/RepeatDetailsContent.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Utilities/RepeaterResolver.swift@db14559b39d32322b06477c6ae676112f583db50
// AndroidOnly: WP-502 Additional exact zero-extra canonical arrival through the real details consumer; no primary-case or device acceptance.
// ReferenceInput: MC1Tests/Views/Chats/Components/MessagePathArrivalTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Components/MessagePathArrival.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.res.Configuration
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.LayoutDirection
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
}
