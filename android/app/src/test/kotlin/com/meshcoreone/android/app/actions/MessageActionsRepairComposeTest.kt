// AndroidOnly: WP-309 Mounted public action route regressions using the existing App Robolectric harness.
// ReferenceInput: MC1/Views/Chats/Reactions/ReactionDetailsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/MessageActionAvailability.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.actions

import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings
import com.meshcoreone.android.core.l10n.generated.AppLocalizableStrings
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.app.navigation.messagePathLocation
import com.meshcoreone.android.feature.chats.actions.*
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
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
@Config(sdk = [31], qualifiers = "w360dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MessageActionsRepairComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val source = Source()
    private val actions = mutableListOf<MessageAction>()
    private lateinit var localized: android.content.Context
    private val now = Instant.parse("2026-10-22T12:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Before fun createHost() {
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
    }

    // AndroidOnly: WP-309 Real App path-input mapping and public route mounted endpoint regressions.
    // ReferenceInput: MC1/Views/Chats/Components/MessagePathContent.swift@db14559b39d32322b06477c6ae676112f583db50
    // ReferenceInput: MC1/Views/Chats/Components/PathHopRowView.swift@db14559b39d32322b06477c6ae676112f583db50
    // ReferenceInput: MC1Tests/ViewModels/MessagePathViewModelTests.swift@db14559b39d32322b06477c6ae676112f583db50
    @RunWith(RobolectricTestRunner::class)
    @Config(sdk = [31], qualifiers = "w360dp-h1000dp-mdpi")
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    class MessagePathEndpointComposeTest {
        @get:Rule val compose = createEmptyComposeRule()
        private lateinit var host: ActivityController<ComponentActivity>
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        private val revision = mutableStateOf(0)
        private lateinit var localized: android.content.Context
        private lateinit var source: EndpointSource
        private val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000003091"))
        private val instant = Instant.parse("2026-10-22T12:00:00Z")
        private val radioDevice = DeviceDTO(
            radioId = radio, publicKey = Bytes.of(0x0A, *IntArray(31)),
            nodeName = "Local radio without GPS", latitude = 0.0, longitude = 0.0,
        )

        @Before fun createHost() {
            host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
            host.get().actionBar?.hide()
            host.windowFocusChanged(true)
        }

        @After fun closeHost() {
            try { host.pause().stop().destroy() } finally { scope.cancel() }
        }

        private fun message(path: Bytes? = null, length: UByte = 0u) = MessageDTO(
            radioId = radio, channelIndex = 0u, text = "Endpoint target", timestamp = 700u,
            createdAt = instant, direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, pathLength = length, pathNodes = path,
            senderNodeName = "Remote sender", heardRepeats = 1, snr = 2.5,
        )

        private fun mount(
            target: MessageDTO,
            device: DeviceDTO? = radioDevice,
            contacts: List<ContactDTO> = emptyList(),
            locale: String = "en-US",
            fontScale: Float = 1f,
        ) {
            source = EndpointSource(target, contacts)
            val holder = MessageActionsStateHolder(source, scope)
            host.get().setContent {
                val configuration = Configuration(LocalConfiguration.current).apply {
                    setLocales(LocaleList(Locale.forLanguageTag(locale)))
                    this.fontScale = fontScale
                }
                localized = LocalContext.current.createConfigurationContext(configuration)
                CompositionLocalProvider(
                    LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalResources provides localized.resources,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    MeshCoreTheme {
                        val input = messagePathLocation(target.radioId, device, null, false)
                        MessageActionsRoute(
                            target, holder, input.localDevice, input.userLocation,
                            emptyList(), { error("Unexpected action $it") },
                            modifier = Modifier.testTag("endpoint-route-${revision.value}"),
                            clock = Clock.fixed(instant, ZoneOffset.UTC),
                            localDeviceName = input.localDeviceName,
                        )
                    }
                }
            }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(listOf("contacts", "discovered", "repeats"), source.reads)
                assertTrue(holder.state.value.isLoadingDetails)
            }
        }

        private fun text(id: Int) = localized.getString(id)
        private fun sender() = compose.onNodeWithText(text(AppChatsStrings.chatsPathHopSender))
        private fun receiver() = compose.onNodeWithText(text(AppChatsStrings.chatsPathReceiverLabel))
        private fun finishRead(repeats: List<MessageRepeatDTO> = emptyList()) {
            compose.runOnIdle { source.gate.complete(repeats) }
            compose.waitForIdle()
        }
        private fun assertNoExtraReads() = compose.runOnIdle {
            assertEquals(listOf("contacts", "discovered", "repeats"), source.reads)
        }
        private fun signal(snr: Double, quality: Int) = AppChatsStrings.chatsPathHopSignalQuality(
            localized.resources, text(quality),
            String.format(localized.resources.configuration.locales[0], "%.1f", snr),
        )

        @Test fun nilAndEmptyCanonicalZeroHopRetainEndpointsAndSelectedEchoSignalWithoutLocation() {
            for (path in listOf(null, Bytes.EMPTY)) {
                val target = message(path)
                val input = messagePathLocation(radio, radioDevice, null, false)
                assertFalse(radioDevice.hasLocation)
                assertNull(input.localDevice)
                assertNull(input.userLocation)
                assertEquals(radioDevice.nodeName, input.localDeviceName)
                val invalidGeometry = messagePathLocation(radio, radioDevice.copy(latitude = Double.NaN), null, false)
                assertNull(invalidGeometry.localDevice)
                assertEquals(radioDevice.nodeName, invalidGeometry.localDeviceName)
                mount(target)
                sender().assertDoesNotExist()
                receiver().assertDoesNotExist()
                val echo = MessageRepeatDTO(
                    messageID = target.id, receivedAt = instant.plusSeconds(1),
                    pathNodes = Bytes.of(0xA3), pathLength = 1u, snr = -4.5,
                )
                finishRead(listOf(echo))
                sender().performScrollTo().assertTextContains("Remote sender")
                receiver().performScrollTo().assertTextContains(requireNotNull(radioDevice.nodeName))
                    .assertTextContains(signal(2.5, AppChatsStrings.chatsSignalGood))
                compose.onNodeWithText(text(AppChatsStrings.chatsMessagePathFlood)).assertExists()
                compose.onNodeWithText("#2").performScrollTo().assertWidthIsAtLeast(48.dp)
                    .assertHeightIsAtLeast(48.dp).performClick()
                receiver().performScrollTo().assertTextContains(signal(-4.5, AppChatsStrings.chatsSignalFair))
                receiver().assertTextEquals(
                    requireNotNull(radioDevice.nodeName), text(AppChatsStrings.chatsPathReceiverLabel),
                    signal(-4.5, AppChatsStrings.chatsSignalFair),
                )
                compose.onNodeWithText("1. A3").performScrollTo().assertIsDisplayed()
                val orderedRows = compose.onAllNodes(
                    hasText(text(AppChatsStrings.chatsPathHopSender)) or
                        hasText("1. A3") or hasText(text(AppChatsStrings.chatsPathReceiverLabel)),
                ).fetchSemanticsNodes()
                assertEquals(3, orderedRows.size)
                assertTrue(orderedRows[0].config[SemanticsProperties.Text].any {
                    it.text == text(AppChatsStrings.chatsPathHopSender)
                })
                assertTrue(orderedRows[1].config[SemanticsProperties.Text].any { it.text == "1. A3" })
                assertTrue(orderedRows[2].config[SemanticsProperties.Text].any {
                    it.text == text(AppChatsStrings.chatsPathReceiverLabel)
                })
                compose.onNodeWithText(text(AppChatsStrings.chatsMessagePathFlood)).assertDoesNotExist()
                compose.runOnIdle { revision.value += 1 }
                receiver().performScrollTo().assertTextContains(signal(-4.5, AppChatsStrings.chatsSignalFair))
                assertNoExtraReads()
                compose.onNodeWithText(text(AppChatsStrings.chatsPathArrivalFirst)).performScrollTo().performClick()
                receiver().performScrollTo().assertTextContains(signal(2.5, AppChatsStrings.chatsSignalGood))
                assertNoExtraReads()
            }
        }

        @Test fun ambiguousDMSenderRetainsFirstByteAndPossibleMatchDisclosureAtDoubleFontInChinese() {
            val older = ContactDTO(
                radioId = radio, publicKey = Bytes.of(0x0A, 1, *IntArray(30)),
                name = "Older", typeRawValue = ContactType.CHAT.rawValue,
                lastAdvertTimestamp = 1u, lastHeardTimestamp = null,
            )
            val newer = older.copy(
                id = UUID.randomUUID(), publicKey = Bytes.of(0x0A, 2, *IntArray(30)),
                name = "\u53d1\u9001\u8005\u957f\u540d\u79f0\u7528\u4e8e\u9a8c\u8bc1\u6587\u5b57\u81ea\u52a8\u6362\u884c",
                lastAdvertTimestamp = 2u,
            )
            val target = message().copy(
                channelIndex = null, contactID = older.id, senderKeyPrefix = Bytes.of(0x0A),
                senderNodeName = null,
            )
            mount(target, contacts = listOf(older, newer), locale = "zh-Hans", fontScale = 2f)
            finishRead()
            sender().performScrollTo().assertTextContains(newer.name)
                .assertTextContains(AppChatsStrings.chatsPathHopNodeId(localized.resources, "0A"))
            val node = compose.onNodeWithText(newer.name, useUnmergedTree = true).performScrollTo()
            val layouts = mutableListOf<TextLayoutResult>()
            node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
            assertEquals(2f, layouts.single().layoutInput.density.fontScale, 0f)
            assertFalse(layouts.single().didOverflowHeight)
            assertFalse(layouts.single().didOverflowWidth)
            val possible = text(AppChatsStrings.chatsPathHopPossibleMatch)
            compose.onNodeWithText(possible).performScrollTo().assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp).performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithText(text(AppChatsStrings.chatsPathHopPossibleMatchExplanation)).assertExists()
            compose.onNodeWithText(text(AppLocalizableStrings.commonDone)).performClick()
            receiver().performScrollTo().assertTextContains(signal(2.5, AppChatsStrings.chatsSignalGood))
            compose.runOnIdle { revision.value += 1 }
            sender().performScrollTo().assertTextContains(newer.name)
            assertNoExtraReads()
        }

        @Test fun unknownAndOtherRadioUseSourceFallbackButTrueUnavailableSuppressesBothEndpoints() {
            val other = radioDevice.copy(radioId = RadioId(UUID.randomUUID()), nodeName = "Wrong radio")
            assertNull(messagePathLocation(radio, other, null, false).localDeviceName)
            val target = message().copy(channelIndex = null, senderNodeName = null, senderKeyPrefix = Bytes.EMPTY)
            mount(target, device = other)
            finishRead()
            sender().performScrollTo().assertTextContains(text(AppChatsStrings.chatsPathHopUnknown))
            receiver().performScrollTo().assertTextContains(text(AppChatsStrings.chatsPathReceiverYou))
            compose.onNodeWithText("Wrong radio").assertDoesNotExist()
            assertNoExtraReads()

            mount(message(Bytes.EMPTY, 1u))
            finishRead()
            sender().assertDoesNotExist()
            receiver().assertDoesNotExist()
            compose.onNodeWithText(text(AppChatsStrings.chatsPathUnavailableDescription)).performScrollTo().assertIsDisplayed()
            compose.onNodeWithText(text(AppChatsStrings.chatsMessagePathFlood)).assertDoesNotExist()
            assertNoExtraReads()
        }

        @Test fun outgoingEchoesUseLocalIdentityForSenderAndReceiverAndNeverCanonicalSignal() {
            val target = message().copy(direction = MessageDirection.OUTGOING, heardRepeats = 2, snr = 99.0)
            mount(target)
            val first = MessageRepeatDTO(
                messageID = target.id, receivedAt = instant, pathNodes = Bytes.EMPTY, pathLength = 0u, snr = 7.0,
            )
            val second = first.copy(
                id = UUID.randomUUID(), receivedAt = instant.plusSeconds(1),
                pathNodes = Bytes.of(0xA3), pathLength = 1u, snr = null,
            )
            finishRead(listOf(second, first))
            sender().performScrollTo().assertTextContains(requireNotNull(radioDevice.nodeName))
            receiver().performScrollTo().assertTextContains(requireNotNull(radioDevice.nodeName))
                .assertTextContains(signal(7.0, AppChatsStrings.chatsSignalExcellent))
            compose.onNodeWithText(text(AppChatsStrings.chatsPathArrivalFirst)).assertDoesNotExist()
            compose.onNodeWithText("#2").performScrollTo().performClick()
            receiver().performScrollTo().assertTextEquals(
                requireNotNull(radioDevice.nodeName), text(AppChatsStrings.chatsPathReceiverLabel),
            )
            sender().performScrollTo().assertTextContains(requireNotNull(radioDevice.nodeName))
            compose.onNodeWithText("1. A3").performScrollTo().assertIsDisplayed()
            compose.runOnIdle { revision.value += 1 }
            receiver().performScrollTo().assertTextEquals(
                requireNotNull(radioDevice.nodeName), text(AppChatsStrings.chatsPathReceiverLabel),
            )
            assertNoExtraReads()
        }

        private class EndpointSource(
            val target: MessageDTO,
            val savedContacts: List<ContactDTO>,
        ) : MessageActionsDataSource {
            val reads = mutableListOf<String>()
            val gate = CompletableDeferred<List<MessageRepeatDTO>>()
            override suspend fun contacts(radioId: RadioId): List<ContactDTO> {
                assertEquals(target.radioId, radioId)
                reads += "contacts"
                return savedContacts
            }
            override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> {
                assertEquals(target.radioId, radioId)
                reads += "discovered"
                return emptyList()
            }
            override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> {
                assertEquals(target.id, messageId)
                reads += "repeats"
                return gate.await()
            }
            override suspend fun reactions(messageId: UUID): List<ReactionDTO> = error("Unexpected reactions")
            override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = error("Unexpected block")
            override suspend fun blockContacts(contactIds: Set<UUID>): Unit = error("Unexpected block")
            override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = error("Unexpected mute")
        }
    }

    @After fun closeHost() {
        try { host.pause().stop().destroy() } finally { scope.cancel() }
    }

    private fun mount(locale: String = "en-US", outgoing: Boolean = false, fontScale: Float = 1f) {
        val holder = MessageActionsStateHolder(source, scope)
        host.get().setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(locale)))
                this.fontScale = fontScale
            }
            localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized,
                LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, fontScale),
            ) {
                MeshCoreTheme {
                    MessageActionsRoute(
                        source.message.copy(direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING),
                        holder, null, null, listOf("X"), actions::add, clock = clock,
                    )
                }
            }
        }
        compose.waitForIdle()
    }

    private fun text(id: Int) = localized.getString(id)
    private fun dialogText(id: Int) = compose.onNode(hasText(text(id)) and hasAnyAncestor(isDialog()))
    private fun clickAction(id: Int) {
        compose.onNodeWithText(text(id)).performScrollTo()
            .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
    }
    private fun openReactions() {
        compose.onNodeWithContentDescription("X, 1").performScrollTo().performClick()
        compose.onNode(isDialog()).assertExists()
    }

    @Test fun retainedIncomingActionsDispatchWithoutAnyTranslateEntryInEnglishAndChinese() {
        for (locale in listOf("en-US", "zh-Hans")) {
            mount(locale)
            compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionTranslate)).assertDoesNotExist()
            clickAction(AppChatsStrings.chatsMessageActionReply)
            clickAction(AppChatsStrings.chatsMessageActionSendDM)
            clickAction(AppChatsStrings.chatsMessageActionCopy)
            compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionSendAgain)).assertDoesNotExist()
            compose.onNode(hasText("X") and !hasContentDescription("X, 1")).performScrollTo().performClick()
            clickAction(AppChatsStrings.chatsMessageActionDelete)
            assertEquals(
                listOf(MessageAction.Reply, MessageAction.SendDirectMessage, MessageAction.Copy,
                    MessageAction.React("X"), MessageAction.Delete),
                actions.toList(),
            )
            assertFalse(actions.contains(MessageAction.Translate))
            assertEquals("Original \u4f60\u597d", source.message.text)
            actions.clear()
        }
    }

    @Test fun outgoingActionsKeepSendAgainAndCopyWithoutReplyOrTranslate() {
        mount(outgoing = true)
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionTranslate)).assertDoesNotExist()
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionReply)).assertDoesNotExist()
        compose.onNodeWithText(text(AppChatsStrings.chatsMessageActionSendDM)).assertDoesNotExist()
        clickAction(AppChatsStrings.chatsMessageActionCopy)
        clickAction(AppChatsStrings.chatsMessageActionSendAgain)
        assertEquals(listOf(MessageAction.Copy, MessageAction.SendAgain), actions)
    }

    @Test fun pendingThenEmptyShowsTitleOnlyAfterSuccessfulFetch() {
        for (locale in listOf("en-US", "zh-Hans")) {
            mount(locale)
            openReactions()
            dialogText(AppChatsStrings.reactionsEmptyStateTitle).assertDoesNotExist()
            compose.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo)).assertIsDisplayed()
            compose.runOnIdle { source.pending.last().complete(emptyList()) }
            dialogText(AppChatsStrings.reactionsEmptyStateTitle).assertIsDisplayed()
            dialogText(AppChatsStrings.reactionsEmptyStateDescription).assertIsDisplayed()
            dialogText(AppLocalizableStrings.commonDone).performClick()
        }
    }

    @Test fun failedReadOffersRealRetryAndThenLocalizedNamedSenderTime() {
        mount()
        openReactions()
        compose.runOnIdle { source.pending.single().completeExceptionally(IllegalStateException("private diagnostic")) }
        dialogText(AppLocalizableStrings.commonErrorFailedToLoad).assertIsDisplayed()
        dialogText(AppChatsStrings.reactionsEmptyStateTitle).assertDoesNotExist()
        compose.onNodeWithText("private diagnostic").assertDoesNotExist()
        dialogText(AppLocalizableStrings.commonTryAgain).performClick()
        compose.runOnIdle {
            assertEquals(2, source.pending.size)
            source.pending.last().complete(listOf(source.reaction(now.minusSeconds(86_400), "Yesterday sender")))
        }
        compose.onNodeWithText("Yesterday sender").assertIsDisplayed()
        compose.onNodeWithText("yesterday").assertIsDisplayed()
        dialogText(AppLocalizableStrings.commonErrorFailedToLoad).assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    @Test fun chineseNamedTimesAndLongSenderListRemainReachableAtDoubleFont() {
        mount("zh-Hans", fontScale = 2f)
        openReactions()
        compose.runOnIdle {
            source.pending.single().complete((0..30).map { index ->
                source.reaction(now.minusSeconds(86_400), "\u8282\u70b9 $index")
            })
        }
        compose.onNodeWithText("\u8282\u70b9 0").assertIsDisplayed()
        compose.onAllNodesWithText("\u6628\u5929").onFirst().assertIsDisplayed()
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        compose.onNodeWithText("\u8282\u70b9 30").assertIsDisplayed()
        dialogText(AppLocalizableStrings.commonDone).assertIsDisplayed().performClick()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertTrue(actions.isEmpty())
    }

    private class Source : MessageActionsDataSource {
        private val radio = RadioId(UUID.fromString("00000000-0000-0000-0000-000000000309"))
        val message = MessageDTO(
            id = UUID.fromString("00000000-0000-0000-0000-000000000310"),
            radioId = radio, contactID = null, channelIndex = 0u, text = "Original \u4f60\u597d",
            timestamp = 700u, createdAt = Instant.ofEpochSecond(700), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, textType = TextType.PLAIN,
            pathLength = 0u, pathNodes = Bytes.EMPTY, senderNodeName = "Remote",
            reactionSummary = "X:1",
        )
        val pending = mutableListOf<CompletableDeferred<List<ReactionDTO>>>()
        fun reaction(receivedAt: Instant, sender: String) = ReactionDTO(
            messageID = message.id, emoji = "X", senderName = sender, messageHash = "0123abcd",
            rawText = "original", radioId = radio, receivedAt = receivedAt,
        )
        override suspend fun reactions(messageId: UUID): List<ReactionDTO> {
            assertEquals(message.id, messageId)
            return CompletableDeferred<List<ReactionDTO>>().also { pending += it }.await()
        }
        override suspend fun contacts(radioId: RadioId): List<ContactDTO> = error("Unexpected details read")
        override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> = error("Unexpected details read")
        override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> = error("Unexpected details read")
        override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = error("Unexpected block")
        override suspend fun blockContacts(contactIds: Set<UUID>): Unit = error("Unexpected block")
        override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = error("Unexpected mute")
    }
}
