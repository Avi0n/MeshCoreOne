// AndroidOnly: WP-502 Mounted reaction badges -> production actions route/holder/dialog; not original-case, App reaction-send or full-sheet acceptance.
// ReferenceInput: MC1Tests/Views/Chats/Components/ReactionDetailsSelectionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/ReactionDetailsSheet.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/ReactionBadgesView.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1/Views/Chats/Reactions/Sections/ActionsEmojiSection.swift@db14559b39d32322b06477c6ae676112f583db50
// ReferenceInput: MC1Services/Sources/MC1Services/Models/Reaction.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.uitests

import android.content.pm.ApplicationInfo
import android.content.res.Configuration
import android.os.LocaleList
import android.provider.Settings
import android.view.View
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
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
import com.meshcoreone.android.core.designsystem.ThemeId
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.actions.*
import java.time.Instant
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.CancellationException
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
@Config(sdk = [31], qualifiers = "w360dp-h1400dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ReactionDetailsComposeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private lateinit var host: ActivityController<ComponentActivity>
    private lateinit var previousZone: TimeZone
    private lateinit var frame: ScreenCase
    private var effectiveScheme: ColorScheme? = null
    private val revision = mutableStateOf(0)
    private val fixtures = mutableListOf<Fixture>()
    private val dark = ScreenCase(
        "reactions-dark-cjk-rtl", theme = ThemeId.EMBER, expectedScheme = ColorScheme.DARK,
        locale = "zh-Hans", fontScale = 2f, rtl = true,
    )

    @Before fun createHost() {
        previousZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        host = Robolectric.buildActivity(ComponentActivity::class.java).setup().visible()
        host.get().actionBar?.hide()
        host.windowFocusChanged(true)
        Settings.Global.putFloat(host.get().contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() {
        try { host.pause().stop().destroy() }
        finally {
            fixtures.forEach { it.scope.cancel() }
            TimeZone.setDefault(previousZone)
        }
    }

    @Suppress("DEPRECATION")
    private fun mount(fixture: Fixture, case: ScreenCase = ScreenCase("reactions-light", locale = "en-US")) {
        fixtures += fixture
        frame = case
        val resources = host.get().resources
        resources.updateConfiguration(Configuration(resources.configuration).apply {
            fontScale = frame.fontScale
            setLayoutDirection(Locale.forLanguageTag(if (frame.rtl) "ar" else frame.locale))
        }, resources.displayMetrics)
        host.get().applicationInfo.flags = host.get().applicationInfo.flags or ApplicationInfo.FLAG_SUPPORTS_RTL
        host.get().window.decorView.layoutDirection = if (frame.rtl) View.LAYOUT_DIRECTION_RTL else View.LAYOUT_DIRECTION_LTR
        host.get().setContent {
            val configuration = Configuration(LocalConfiguration.current).apply {
                setLocales(LocaleList(Locale.forLanguageTag(frame.locale)))
            }
            val localized = LocalContext.current.createConfigurationContext(configuration)
            CompositionLocalProvider(
                LocalContext provides localized, LocalConfiguration provides configuration,
                LocalResources provides localized.resources,
                LocalDensity provides Density(LocalDensity.current.density, frame.fontScale),
                LocalLayoutDirection provides if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
            ) {
                MeshCoreTheme(
                    theme = requireNotNull(ThemeRegistry.theme(frame.theme)),
                    colorSchemePreference = frame.preference, systemDark = frame.systemDark, motionScale = 0f,
                ) {
                    val scheme = LocalMeshTheme.current.frame.colorScheme
                    SideEffect { effectiveScheme = scheme }
                    key(fixture) {
                        MessageActionsRoute(
                            fixture.message, fixture.holder, localDevice = null, userLocation = null,
                            recentEmojis = emptyList(), onAction = { fixture.forbidden("action:$it") },
                            modifier = Modifier.testTag("reaction-route-${revision.value}"),
                        )
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(frame.expectedScheme, effectiveScheme) }
    }

    private fun title() = if (frame.locale == "en-US") "Reactions" else "\u53cd\u5e94"
    private fun done() = if (frame.locale == "en-US") "Done" else "\u5b8c\u6210"
    private fun emptyCopy() = if (frame.locale == "en-US") "No one has reacted to this message yet" else "\u5c1a\u672a\u6709\u4eba\u5bf9\u6b64\u6d88\u606f\u505a\u51fa\u53cd\u5e94"
    private fun dialogText(text: String) = compose.onNode(hasText(text) and hasAnyAncestor(isDialog()))
    private fun tab(text: String) = compose.onNode(
        hasText(text) and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab),
    )

    private fun fullBounds(node: SemanticsNode): Rect {
        val position = node.positionInRoot
        return Rect(position.x, position.y, position.x + node.size.width, position.y + node.size.height)
    }

    private fun contains(outer: Rect, inner: Rect) =
        inner.left >= outer.left && inner.top >= outer.top && inner.right <= outer.right && inner.bottom <= outer.bottom

    private fun assertFullyVisible(node: SemanticsNode, expected: String) {
        val full = fullBounds(node)
        val visible = node.boundsInRoot
        val ancestors = generateSequence(node) { it.parent }.toList()
        val viewports = listOf(ancestors.last().boundsInRoot) + ancestors.drop(1).filter {
            it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) ||
                it.config.contains(SemanticsProperties.VerticalScrollAxisRange)
        }.map { it.boundsInRoot }
        val diagnostic = "$expected frame=$frame full=$full visible=$visible viewports=$viewports"
        assertTrue(diagnostic, full.width > 0f && full.height > 0f)
        assertTrue(diagnostic, contains(visible, full) && viewports.all { contains(it, full) })
    }

    private fun assertCopyLayout(node: SemanticsNodeInteraction, expected: String) {
        val semantics = node.assertIsDisplayed().fetchSemanticsNode()
        val bounds = semantics.boundsInRoot
        assertTrue(bounds.width > 0f && bounds.height > 0f)
        compose.runOnIdle { assertFullyVisible(semantics, expected) }
        val layouts = mutableListOf<TextLayoutResult>()
        node.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
        val layout = layouts.single()
        val diagnostic = "$expected frame=$frame node=$bounds size=${layout.size}" +
            " constraints=${layout.layoutInput.constraints}" +
            " paragraph=${layout.multiParagraph.width}x${layout.multiParagraph.height}"
        assertEquals(expected, layout.layoutInput.text.text)
        assertEquals(frame.fontScale, layout.layoutInput.density.fontScale, 0f)
        assertEquals(if (frame.rtl) LayoutDirection.Rtl else LayoutDirection.Ltr, layout.layoutInput.layoutDirection)
        assertFalse(diagnostic, layout.didOverflowWidth)
        assertFalse(diagnostic, layout.didOverflowHeight)
        for (line in 0 until layout.lineCount) assertFalse(diagnostic, layout.isLineEllipsized(line))
    }

    private fun clickVisibleTarget(node: SemanticsNodeInteraction, expected: String) {
        node.assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
        assertCopyLayout(node, expected)
        node.performClick()
    }

    private fun clickTab(label: String) {
        clickVisibleTarget(tab(label).performScrollTo(), label)
    }

    private fun openBadge(emoji: String, count: Int) {
        val badge = compose.onNodeWithContentDescription("$emoji, $count").performScrollTo()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        clickVisibleTarget(badge, if (count > 1) "$emoji $count" else emoji)
        compose.onNode(isDialog()).assertExists()
        assertCopyLayout(dialogText(title()), title())
    }

    private fun assertTabs(labels: List<String>, selected: String) {
        compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(labels.size)
        for (label in labels) {
            val node = tab(label).performScrollTo().assertHasClickAction().assertWidthIsAtLeast(48.dp).assertHeightIsAtLeast(48.dp)
                .assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, label == selected))
                .assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.ToggleableState))
            assertCopyLayout(node, label)
        }
        val observed = compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).fetchSemanticsNodes()
        compose.runOnIdle {
            val ordered = labels.map { label ->
                observed.single { node -> node.config[SemanticsProperties.Text].any { it.text == label } }
            }
            val scrollParents = ordered.map { node ->
                generateSequence(node.parent) { it.parent }.first { it.config.contains(SemanticsProperties.HorizontalScrollAxisRange) }
            }
            assertEquals(1, scrollParents.map { it.id }.distinct().size)
            val viewport = scrollParents.first().boundsInRoot
            val centers = ordered.map { fullBounds(it).center.x }
            assertTrue(
                "$labels frame=$frame same-viewport=$viewport tab centers=$centers",
                centers.zipWithNext().all { (left, right) -> if (frame.rtl) left > right else left < right },
            )
        }
    }

    private fun assertSenders(expected: List<String>) {
        for (name in expected) {
            val node = dialogText(name).performScrollTo()
            assertCopyLayout(node, name)
        }
        val observed = expected.map { dialogText(it).fetchSemanticsNode() }
        compose.runOnIdle {
            val scrollParents = observed.map { node ->
                generateSequence(node.parent) { it.parent }.first { it.config.contains(SemanticsProperties.VerticalScrollAxisRange) }
            }
            assertEquals(1, scrollParents.map { it.id }.distinct().size)
            val viewport = scrollParents.first().boundsInRoot
            val centers = observed.map { fullBounds(it).center.y }
            assertTrue("$expected frame=$frame same-viewport=$viewport sender centers=$centers", centers.zipWithNext().all { (top, bottom) -> top < bottom })
        }
        for (name in listOf("Laugh later", "\u7231\u4e3d\u4e1d", "Zulu", "\u05d0\u05d3\u05dd", "Beta", "Alpha", "Laugh first") - expected.toSet()) {
            dialogText(name).assertDoesNotExist()
        }
    }

    private fun finishDialog() {
        clickVisibleTarget(dialogText(done()), done())
        compose.onNode(isDialog()).assertDoesNotExist()
    }

    private fun assertBoundary(fixture: Fixture, reads: List<UUID>, failure: MessageActionsFailure? = null) {
        compose.runOnIdle {
            assertEquals(reads, fixture.reads)
            assertEquals(0, fixture.activeReads)
            assertTrue(fixture.forbiddenCalls.isEmpty())
            val state = fixture.holder.state.value
            assertEquals(failure, state.failure)
            assertFalse(state.isLoadingDetails)
            assertNull(state.repeats)
            assertEquals(MessagePathDirectory(), state.directory)
            assertNull(state.blockedSender)
            assertTrue(state.mutedContactIds.isEmpty())
        }
    }

    @Test fun preferredBadgeLoadsOrderedTabsAndTabSwitchUsesOriginalSenderRowsWithoutAnotherRead() {
        val fixture = Fixture("$THUMB:3,$HEART:2,$LAUGH:2")
        mount(fixture)
        openBadge(HEART, 2)
        compose.runOnIdle {
            assertEquals(listOf(MESSAGE_ID), fixture.reads)
            assertEquals(1, fixture.activeReads)
            assertTrue(fixture.attempts.single().complete(fixture.reactions))
        }
        assertTabs(listOf("$THUMB 3", "$HEART 2", "$LAUGH 2"), "$HEART 2")
        assertSenders(listOf("\u7231\u4e3d\u4e1d", "\u05d0\u05d3\u05dd"))
        compose.runOnIdle {
            val groups = fixture.holder.state.value.reactionGroups
            assertEquals(listOf(THUMB, HEART, LAUGH), groups.map { it.emoji })
            assertEquals(listOf(3, 2, 2), groups.map { it.count })
            assertEquals(listOf(fixture.reactions[2], fixture.reactions[4], fixture.reactions[5]), groups[0].reactions)
            assertEquals(listOf(fixture.reactions[1], fixture.reactions[3]), groups[1].reactions)
            assertEquals(listOf(fixture.reactions[0], fixture.reactions[6]), groups[2].reactions)
            assertSame(fixture.reactions[1], groups[1].reactions[0])
            assertEquals(HEART, fixture.holder.state.value.selectedEmoji)
            revision.value += 1
        }
        compose.onNodeWithTag("reaction-route-1").assertExists()
        assertTabs(listOf("$THUMB 3", "$HEART 2", "$LAUGH 2"), "$HEART 2")
        clickTab("$THUMB 3")
        assertTabs(listOf("$THUMB 3", "$HEART 2", "$LAUGH 2"), "$THUMB 3")
        assertSenders(listOf("Zulu", "Beta", "Alpha"))
        compose.runOnIdle { assertEquals(THUMB, fixture.holder.state.value.selectedEmoji) }
        finishDialog()
        assertBoundary(fixture, listOf(MESSAGE_ID))
    }

    @Test fun staleBadgeAndRealOverflowResolveToFirstAndDoneReopenReloadsOnlyTheExactMessage() {
        val fixture = Fixture("$THUMB:3,$HEART:2,$PARTY:1,$LAUGH:1")
        mount(fixture, dark)
        val available = listOf(fixture.reactions[2], fixture.reactions[1], fixture.reactions[4], fixture.reactions[3], fixture.reactions[5])
        openBadge(PARTY, 1)
        compose.runOnIdle { assertTrue(fixture.attempts.single().complete(available)) }
        assertTabs(listOf("$THUMB 3", "$HEART 2"), "$THUMB 3")
        assertSenders(listOf("Zulu", "Beta", "Alpha"))
        compose.runOnIdle { assertEquals(THUMB, fixture.holder.state.value.selectedEmoji) }
        clickTab("$HEART 2")
        assertTabs(listOf("$THUMB 3", "$HEART 2"), "$HEART 2")
        assertSenders(listOf("\u7231\u4e3d\u4e1d", "\u05d0\u05d3\u05dd"))
        finishDialog()
        assertBoundary(fixture, listOf(MESSAGE_ID))
        compose.runOnIdle { assertEquals(HEART, fixture.holder.state.value.selectedEmoji); revision.value += 1 }
        compose.onNodeWithTag("reaction-route-1").assertExists()
        clickVisibleTarget(compose.onNode(hasText("+1") and hasClickAction()).performScrollTo(), "+1")
        compose.onNode(isDialog()).assertExists()
        compose.runOnIdle {
            assertEquals(listOf(MESSAGE_ID, MESSAGE_ID), fixture.reads)
            assertEquals(1, fixture.activeReads)
            assertTrue(fixture.attempts[1].complete(available))
        }
        assertTabs(listOf("$THUMB 3", "$HEART 2"), "$THUMB 3")
        assertSenders(listOf("Zulu", "Beta", "Alpha"))
        compose.runOnIdle {
            assertEquals(listOf(THUMB, HEART), fixture.holder.state.value.reactionGroups.map { it.emoji })
            assertEquals(THUMB, fixture.holder.state.value.selectedEmoji)
        }
        finishDialog()
        assertBoundary(fixture, listOf(MESSAGE_ID, MESSAGE_ID))
    }

    @Test fun successfulEmptyReturnedGroupsShowLocalizedEmptyCopyOnlyAfterTheReadCompletes() {
        for (case in listOf(ScreenCase("reactions-empty", locale = "en-US"), dark)) {
            val fixture = Fixture("$THUMB:3")
            mount(fixture, case)
            openBadge(THUMB, 3)
            compose.runOnIdle {
                assertEquals(1, fixture.activeReads)
                assertTrue(fixture.attempts.single().complete(emptyList()))
            }
            compose.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)).assertCountEquals(0)
            assertCopyLayout(dialogText(emptyCopy()), emptyCopy())
            compose.runOnIdle {
                assertTrue(fixture.holder.state.value.reactionGroups.isEmpty())
                assertNull(fixture.holder.state.value.selectedEmoji)
                revision.value += 1
            }
            dialogText(emptyCopy()).assertIsDisplayed()
            finishDialog()
            assertBoundary(fixture, listOf(MESSAGE_ID))
        }
    }

    @Test fun diagnosticFailureAndOwnerCancellationDoNotBecomeSuccessfulReactionDataOrInvokeActions() {
        for (cancel in listOf(false, true)) {
            val fixture = Fixture("$THUMB:3")
            mount(fixture)
            openBadge(THUMB, 3)
            compose.runOnIdle {
                assertEquals(listOf(MESSAGE_ID), fixture.reads)
                assertEquals(1, fixture.activeReads)
                if (cancel) fixture.scope.cancel()
                else assertTrue(fixture.attempts.single().completeExceptionally(IllegalStateException("private reaction reason")))
            }
            compose.onNode(isDialog()).assertExists()
            dialogText("private reaction reason").assertDoesNotExist()
            compose.runOnIdle {
                assertTrue(fixture.holder.state.value.reactionGroups.isEmpty())
                assertNull(fixture.holder.state.value.selectedEmoji)
                assertEquals(if (cancel) 1 else 0, fixture.cancelledReads)
                assertEquals(if (cancel) 0 else 1, fixture.failedReads)
            }
            assertBoundary(fixture, listOf(MESSAGE_ID), if (cancel) null else MessageActionsFailure.ReactionsLoadFailed)
            finishDialog()
        }
    }

    private class Fixture(summary: String) : MessageActionsDataSource {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val reads = mutableListOf<UUID>()
        val forbiddenCalls = mutableListOf<String>()
        val attempts = mutableListOf<CompletableDeferred<List<ReactionDTO>>>()
        var activeReads = 0
        var failedReads = 0
        var cancelledReads = 0
        val message = MessageDTO(
            id = MESSAGE_ID, radioId = RADIO_ID, contactID = null, channelIndex = 0u,
            text = "Reaction target", timestamp = 700u, createdAt = Instant.ofEpochSecond(710),
            sortDate = Instant.ofEpochSecond(710), direction = MessageDirection.INCOMING,
            status = MessageStatus.DELIVERED, textType = TextType.PLAIN, ackCode = null,
            pathLength = 0u, snr = null, pathNodes = Bytes.EMPTY, senderKeyPrefix = null,
            senderNodeName = "Target parent", isRead = true, replyToID = null, roundTripTime = null,
            heardRepeats = 0, sendCount = 1, retryAttempt = 0, maxRetryAttempts = 0,
            deduplicationKey = null, linkPreviewURL = null, linkPreviewTitle = null,
            linkPreviewImageData = null, linkPreviewIconData = null, linkPreviewFetched = false,
            containsSelfMention = false, mentionSeen = false, failureSeen = false,
            timestampCorrected = false, senderTimestamp = null, reactionSummary = summary,
            routeType = null, regionScope = null, regionScopeMatches = SnapshotList.empty(),
        )
        val reactions = listOf(
            reaction("50200000-0000-0000-0000-000000000410", LAUGH, "Laugh later", 1400, "\uD83D\uDE02@[Target parent]\n0123abcd"),
            reaction("50200000-0000-0000-0000-000000000411", HEART, "\u7231\u4e3d\u4e1d", 1300, "\u2764\uFE0F@[Target parent]\n0123abcd"),
            reaction("50200000-0000-0000-0000-000000000412", THUMB, "Zulu", 1200, "\uD83D\uDC4D@[Target parent]\n0123abcd"),
            reaction("50200000-0000-0000-0000-000000000413", HEART, "\u05d0\u05d3\u05dd", 800, "\u2764\uFE0F@[Target parent]\n0123abcd"),
            reaction("50200000-0000-0000-0000-000000000414", THUMB, "Beta", 1100, "\uD83D\uDC4D@[Target parent]\n0123abcd"),
            reaction("50200000-0000-0000-0000-000000000415", THUMB, "Alpha", 900, "\uD83D\uDC4D@[Target parent]\n0123abcd"),
            reaction("50200000-0000-0000-0000-000000000416", LAUGH, "Laugh first", 1000, "\uD83D\uDE02@[Target parent]\n0123abcd"),
        )
        val holder = MessageActionsStateHolder(this, scope)

        private fun reaction(id: String, emoji: String, sender: String, received: Long, raw: String) = ReactionDTO(
            id = UUID.fromString(id), messageID = MESSAGE_ID, emoji = emoji, senderName = sender,
            messageHash = "0123abcd", rawText = raw, receivedAt = Instant.ofEpochSecond(received),
            channelIndex = 0u, contactID = null, radioId = RADIO_ID,
        )

        fun forbidden(port: String): Nothing {
            forbiddenCalls += port
            throw AssertionError("Forbidden reaction-details interaction: $port")
        }

        override suspend fun reactions(messageId: UUID): List<ReactionDTO> {
            reads += messageId
            if (messageId != MESSAGE_ID) forbidden("reactions.wrongMessage")
            val gate = CompletableDeferred<List<ReactionDTO>>()
            attempts += gate
            activeReads += 1
            return try { gate.await() }
            catch (cancelled: CancellationException) { cancelledReads += 1; throw cancelled }
            catch (failed: IllegalStateException) { failedReads += 1; throw failed }
            finally { activeReads -= 1 }
        }
        override suspend fun contacts(radioId: RadioId): List<ContactDTO> = forbidden("contacts")
        override suspend fun discoveredNodes(radioId: RadioId): List<DiscoveredNodeDTO> = forbidden("discoveredNodes")
        override suspend fun repeats(messageId: UUID): List<MessageRepeatDTO> = forbidden("repeats")
        override suspend fun blockChannelSender(radioId: RadioId, senderName: String): Unit = forbidden("blockChannelSender")
        override suspend fun blockContacts(contactIds: Set<UUID>): Unit = forbidden("blockContacts")
        override suspend fun setMuted(contactId: UUID, muted: Boolean): Unit = forbidden("setMuted")
    }

    companion object {
        private const val THUMB = "\uD83D\uDC4D"
        private const val HEART = "\u2764\uFE0F"
        private const val LAUGH = "\uD83D\uDE02"
        private const val PARTY = "\uD83C\uDF89"
        private val MESSAGE_ID = UUID.fromString("50200000-0000-0000-0000-000000000400")
        private val RADIO_ID = RadioId(UUID.fromString("50200000-0000-0000-0000-000000000401"))
    }
}
