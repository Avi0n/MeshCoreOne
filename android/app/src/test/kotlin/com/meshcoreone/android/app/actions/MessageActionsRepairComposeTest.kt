// AndroidOnly: WP-309 Mounted public action route regressions using the existing App Robolectric harness.
// ReferenceInput: MC1/Views/Chats/Reactions/ReactionDetailsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/MessageActionAvailability.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.actions

import android.content.res.Configuration
import android.content.ClipData
import android.content.ClipboardManager
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
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

        @Test fun selectedPathCopyWritesPlatformClipboardWithoutDispatchingMessageCopyOrLeavingDetails() {
            for (locale in listOf("en-US", "zh-Hans")) {
                val target = message(Bytes.of(0x0A, 0xFF), 2u)
                mount(target, locale = locale, fontScale = 2f)
                val echo = MessageRepeatDTO(
                    messageID = target.id, receivedAt = instant.plusSeconds(1),
                    pathNodes = Bytes.of(0xA3, 0x7F), pathLength = 2u, snr = -4.5,
                )
                finishRead(listOf(echo))
                val clipboard = requireNotNull(host.get().getSystemService(ClipboardManager::class.java))
                compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("Existing", target.text)) }
                fun copy(expected: String) {
                    val control = compose.onNodeWithContentDescription(text(AppChatsStrings.chatsPathCopyAccessibility))
                    control.performScrollTo().assertIsDisplayed().assertHasClickAction()
                        .assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                        .assertTextContains(text(AppChatsStrings.chatsPathCopyButton))
                        .assert(SemanticsMatcher("localized copy hint") {
                            it.config[SemanticsActions.OnClick].label == text(AppChatsStrings.chatsPathCopyHint)
                        }).performClick()
                    compose.runOnIdle {
                        val actual = requireNotNull(clipboard.primaryClip).getItemAt(0).text.toString()
                        assertEquals(expected, actual)
                        assertNotEquals(target.text, actual)
                        assertFalse(actual.contains("\u2192"))
                    }
                    receiver().performScrollTo().assertExists()
                    assertNoExtraReads()
                }
                copy("0A,FF")
                compose.onNodeWithText("#2").performScrollTo().performClick()
                compose.onNodeWithText("A3 \u2192 7F").performScrollTo().assertIsDisplayed()
                copy("A3,7F")
                compose.runOnIdle { revision.value += 1 }
                receiver().performScrollTo().assertTextContains(signal(-4.5, AppChatsStrings.chatsSignalFair))
                copy("A3,7F")
                compose.onNodeWithText(text(AppChatsStrings.chatsPathArrivalFirst)).performScrollTo().performClick()
                copy("0A,FF")
            }
        }

        @Test fun zeroHopAndUnavailableNeverOfferOrWritePathCopy() {
            val clipboard = requireNotNull(host.get().getSystemService(ClipboardManager::class.java))
            for (target in listOf(message(null), message(Bytes.EMPTY), message(Bytes.EMPTY, 1u))) {
                mount(target)
                compose.runOnIdle { clipboard.setPrimaryClip(ClipData.newPlainText("Existing", "untouched")) }
                finishRead()
                compose.onNodeWithContentDescription(text(AppChatsStrings.chatsPathCopyAccessibility)).assertDoesNotExist()
                compose.onNodeWithText(text(AppChatsStrings.chatsPathCopyButton)).assertDoesNotExist()
                compose.runOnIdle { revision.value += 1 }
                compose.onNodeWithContentDescription(text(AppChatsStrings.chatsPathCopyAccessibility)).assertDoesNotExist()
                compose.runOnIdle {
                    assertEquals("untouched", requireNotNull(clipboard.primaryClip).getItemAt(0).text.toString())
                }
                assertNoExtraReads()
            }
        }

        @Test
        @Config(sdk = [31, 33])
        fun original12400mBannerUsesResourceRoadUnitsAndExposesIncompleteWithoutDuplicateHops() {
            val previousDefault = Locale.getDefault()
            try {
                for ((locale, unit, range) in listOf(
                    Triple("en-AU", "km", 12.0..13.0),
                    Triple("en-US", "mi", 7.0..8.0),
                    Triple("en-GB", "mi", 7.0..8.0),
                    Triple("fr-FR", "km", 12.0..13.0),
                )) {
                    Locale.setDefault(if (locale == "en-US") Locale.FRANCE else Locale.US)
                    val distance = mutableStateOf<Double?>(12_400.0)
                    val incomplete = mutableStateOf(true)
                    host.get().setContent {
                        val configuration = Configuration(LocalConfiguration.current).apply {
                            setLocales(LocaleList(Locale.forLanguageTag(locale)))
                            fontScale = 2f
                        }
                        localized = LocalContext.current.createConfigurationContext(configuration)
                        CompositionLocalProvider(
                            LocalContext provides localized, LocalConfiguration provides configuration,
                            LocalResources provides localized.resources,
                            LocalDensity provides Density(LocalDensity.current.density, 2f),
                        ) {
                            MeshCoreTheme { MessagePathDistanceBanner(distance.value, incomplete.value) }
                        }
                    }
                    compose.waitForIdle()
                    val distanceNode = compose.onNode(hasText(unit, substring = true)).assertIsDisplayed()
                    val formatted = distanceNode.fetchSemanticsNode().config[SemanticsProperties.Text].single().text
                    val number = java.text.NumberFormat.getInstance(Locale.forLanguageTag(locale)).parse(formatted).toDouble()
                    val case = "sdk=${android.os.Build.VERSION.SDK_INT}, resources=$locale, " +
                        "ambient=${Locale.getDefault()}, formatted=<$formatted>, parsed=$number"
                    assertTrue("$case: expected magnitude in $range $unit for 12400m", number in range)
                    assertFalse("$case: must not display unconverted raw meters", formatted.contains("12400"))
                    val layouts = mutableListOf<TextLayoutResult>()
                    distanceNode.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                    val layout = layouts.single()
                    val measured = "$case: size=${layout.size}, constraints=${layout.layoutInput.constraints}, " +
                        "paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}, " +
                        "lines=${layout.lineCount}, fontScale=${layout.layoutInput.density.fontScale}"
                    assertEquals("$measured: expected 200% font", 2f, layout.layoutInput.density.fontScale, 0f)
                    assertFalse("$measured: distance must not overflow height", layout.didOverflowHeight)
                    // Simple-string semantics recreates a max-width paragraph but keeps wrap-content size.
                    // These fixtures are start-aligned LTR: line/glyph coordinates are the paint coordinates.
                    assertEquals(measured, formatted, layout.layoutInput.text.text)
                    assertEquals(measured, LayoutDirection.Ltr, layout.layoutInput.layoutDirection)
                    assertTrue("$measured: start-aligned text required for direct glyph bounds",
                        layout.layoutInput.style.textAlign in listOf(TextAlign.Start, TextAlign.Unspecified))
                    val semantics = distanceNode.fetchSemanticsNode()
                    val origin = semantics.positionInRoot
                    val full = Rect(origin.x, origin.y,
                        origin.x + semantics.size.width, origin.y + semantics.size.height)
                    val ancestors = generateSequence(semantics) { it.parent }.toList()
                    val visibleBounds = ancestors.map { it.boundsInRoot }
                    fun contains(outer: Rect, inner: Rect) =
                        inner.left >= outer.left && inner.top >= outer.top &&
                            inner.right <= outer.right && inner.bottom <= outer.bottom
                    val boundsMessage = "$measured: full=$full, visible/ancestors=$visibleBounds"
                    assertTrue("$boundsMessage: full node must fit all visible ancestor bounds",
                        full.width > 0f && full.height > 0f && visibleBounds.all { contains(it, full) })
                    assertEquals(boundsMessage, semantics.size.width, layout.size.width)
                    assertEquals(boundsMessage, semantics.size.height, layout.size.height)
                    assertTrue("$boundsMessage: nonempty complete line layout required", layout.lineCount > 0)
                    assertEquals("$boundsMessage: last line must include the entire distance",
                        formatted.length, layout.getLineEnd(layout.lineCount - 1))
                    for (line in 0 until layout.lineCount) {
                        assertFalse("$boundsMessage: line $line must not ellipsize", layout.isLineEllipsized(line))
                        val paintedLine = Rect(
                            origin.x + layout.getLineLeft(line), origin.y + layout.getLineTop(line),
                            origin.x + layout.getLineRight(line), origin.y + layout.getLineBottom(line),
                        )
                        assertTrue("$boundsMessage: painted line $line=$paintedLine must not clip",
                            contains(full, paintedLine) && visibleBounds.all { contains(it, paintedLine) })
                    }
                    for (offset in formatted.indices) {
                        val glyph = layout.getBoundingBox(offset).translate(origin)
                        assertTrue("$boundsMessage: glyph $offset=$glyph must not clip",
                            contains(full, glyph) && visibleBounds.all { contains(it, glyph) })
                    }
                    // Only distance and localized incomplete label: the source preview excludes hop count.
                    assertEquals(2, compose.onAllNodes(
                        SemanticsMatcher.keyIsDefined(SemanticsProperties.Text),
                    ).fetchSemanticsNodes().size)
                    val disclosure = compose.onNodeWithContentDescription(text(AppChatsStrings.chatsPathDistanceIncomplete))
                    disclosure.assertIsDisplayed().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                        .assert(SemanticsMatcher("localized distance explanation hint") {
                            it.config[SemanticsActions.OnClick].label == text(AppChatsStrings.chatsPathDistanceIncompleteExplanation)
                        }).performClick()
                    compose.onNode(isDialog()).assertExists()
                    compose.onNodeWithText(text(AppChatsStrings.chatsPathDistanceIncompleteTitle)).assertIsDisplayed()
                    compose.onNodeWithText(text(AppChatsStrings.chatsPathDistanceIncompleteExplanation)).assertIsDisplayed()
                    compose.onNodeWithText(text(AppLocalizableStrings.commonDone)).assertWidthIsAtLeast(48.dp)
                        .assertHeightIsAtLeast(48.dp).performClick()
                    compose.onNode(isDialog()).assertDoesNotExist()
                    if (locale == "fr-FR") {
                        compose.runOnIdle { distance.value = 1_500.0 }
                        compose.onNode(hasText("km", substring = true) and hasText("1,5", substring = true))
                            .assertIsDisplayed()
                    }
                    if (android.os.Build.VERSION.SDK_INT <= 32 && locale != "fr-FR") {
                        val (meters, expected) = when (locale) {
                            "en-AU" -> 950.0 to "950 m"
                            "en-US" -> 100.0 to "330 ft"
                            else -> 100.0 to "110 yd"
                        }
                        compose.runOnIdle { distance.value = meters }
                        compose.onNodeWithText(expected).assertIsDisplayed()
                    }
                    compose.runOnIdle { distance.value = null }
                    compose.onNode(hasText(unit, substring = true)).assertDoesNotExist()
                    disclosure.assertExists()
                    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(1)
                    compose.runOnIdle { incomplete.value = false }
                    disclosure.assertDoesNotExist()
                    compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).assertCountEquals(0)
                }
            } finally {
                Locale.setDefault(previousDefault)
            }
        }

        @Test fun incompleteUnlocatedPathOffersRealExplanationWithoutInventingDistanceOrMapSuccess() {
            mount(message(Bytes.of(0xA3), 1u), locale = "zh-Hans", fontScale = 2f)
            finishRead()
            compose.onNodeWithContentDescription(text(AppChatsStrings.chatsPathAccessibilityViewOnMap))
                .performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            compose.onNodeWithText(text(AppChatsStrings.chatsPathUnplaceableTitle)).performScrollTo().assertIsDisplayed()
            val disclosure = compose.onNodeWithContentDescription(text(AppChatsStrings.chatsPathDistanceIncomplete))
            disclosure.performScrollTo().assertIsDisplayed().assertWidthIsAtLeast(48.dp)
                .assertHeightIsAtLeast(48.dp).performClick()
            compose.onNode(isDialog()).assertExists()
            compose.onNodeWithText(text(AppChatsStrings.chatsPathDistanceIncompleteExplanation)).assertIsDisplayed()
            compose.onNodeWithText(text(AppLocalizableStrings.commonDone)).performClick()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.runOnIdle { revision.value += 1 }
            disclosure.performScrollTo().assertIsDisplayed()
            compose.onNode(SemanticsMatcher("no invented numeric map distance") {
                it.config.getOrNull(SemanticsProperties.Text)?.any { value ->
                    value.text.contains(Regex("\\d.*(?:km|mi| m|公里|米)"))
                } == true
            }).assertDoesNotExist()
            compose.onNode(hasText(text(AppChatsStrings.chatsMessageActionDetails)) and hasClickAction())
                .performScrollTo().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp).performClick()
            receiver().performScrollTo().assertTextContains(signal(2.5, AppChatsStrings.chatsSignalGood))
            compose.onNodeWithContentDescription(text(AppChatsStrings.chatsPathCopyAccessibility)).performScrollTo().assertExists()
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
