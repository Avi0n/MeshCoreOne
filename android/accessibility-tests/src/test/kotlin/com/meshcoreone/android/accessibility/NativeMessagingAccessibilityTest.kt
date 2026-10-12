// AndroidOnly: WP-503 Mounted native chat/room semantics, effective targets, system font and actual span/surface contrast.
package com.meshcoreone.android.accessibility

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.core.designsystem.AppColorSchemePreference
import com.meshcoreone.android.core.designsystem.ColorScheme
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.designsystem.ThemeRegistry
import com.meshcoreone.android.core.designsystem.WCAGContrast
import com.meshcoreone.android.core.designsystem.toThemeColor
import com.meshcoreone.android.core.l10n.generated.AppChatsStrings as C
import com.meshcoreone.android.core.l10n.generated.AppRemoteNodesStrings as R
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RemoteNodeRole
import com.meshcoreone.android.core.model.RemoteNodeSessionDTO
import com.meshcoreone.android.core.model.RoomMessageDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.feature.chats.composer.MessageTextFormatter
import com.meshcoreone.android.feature.chats.composer.LinkColor
import com.meshcoreone.android.feature.chats.composer.ui.MessageBody
import com.meshcoreone.android.feature.chats.rooms.RoomAuthenticationSheet
import com.meshcoreone.android.feature.chats.rooms.RoomConversationContent
import com.meshcoreone.android.feature.chats.rooms.RoomConversationState
import com.meshcoreone.android.feature.chats.rooms.RoomConversationStateHolder
import com.meshcoreone.android.feature.chats.timeline.ChatTimelineState
import com.meshcoreone.android.feature.chats.timeline.TimelineRow
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimeline
import com.meshcoreone.android.feature.chats.timeline.ui.ChatTimelineCallbacks
import java.time.Instant
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31, 37], qualifiers = "en-rUS-w360dp-h900dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class NativeMessagingAccessibilityTest : NativeAccessibilityHarness() {
    private val radio = RadioId(UUID(0, 1))
    private val room = RemoteNodeSessionDTO(
        id = UUID(0, 2), radioId = radio, publicKey = Bytes(ByteArray(32) { 0x42 }),
        name = "Fixture room", role = RemoteNodeRole.ROOM_SERVER, isConnected = true,
    )
    private fun roomMessage(text: String, self: Boolean = false, status: MessageStatus = MessageStatus.SENT) =
        RoomMessageDTO(
            id = UUID(0, 3), sessionID = room.id, authorKeyPrefix = Bytes.of(0xAA),
            authorName = if (self) "Me" else "Alice", text = text, timestamp = 100u,
            createdAt = Instant.ofEpochSecond(100), isFromSelf = self, statusRawValue = status.rawValue,
        )

    private fun roomContent(state: RoomConversationState, retry: (UUID) -> Unit = {}) {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    RoomConversationContent(state, {}, {}, retry, {}, { }, { "Fixture failure" })
                }
            }
        }
    }

    @Test fun roomFollowUpRetainsAuthorContextWithoutAnnouncingTheWholeChat() {
        val first = roomMessage("cluster start")
        val followUp = roomMessage("follow up").copy(id = UUID(0, 4), timestamp = 101u)
        roomContent(RoomConversationState(room, listOf(first, followUp),
            RoomConversationStateHolder.rows(listOf(first, followUp)), hasLoadedOnce = true))
        val node = compose.onNode(messageNarrative("follow up", "Alice")).assertIsDisplayed().fetchSemanticsNode()
        assertTrue("The row must not absorb the other message", !narrative(node).contains(first.text))
        assertNoWholeScreenLiveRegion()
    }

    @Test fun roomOutgoingSentStatusIsExposedExactlyOnceOnItsMessage() {
        val message = roomMessage("sent body", self = true)
        roomContent(RoomConversationState(room, listOf(message),
            RoomConversationStateHolder.rows(listOf(message)), hasLoadedOnce = true))
        val label = host.get().getString(C.chatsMessageStatusSent)
        val node = compose.onNode(messageNarrative(message.text, label)).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("Status should not be spoken twice", 1, Regex(Regex.escape(label)).findAll(narrative(node)).count())
    }

    @Test fun roomClusterStartAnnouncesItsAuthorExactlyOnce() {
        val message = roomMessage("cluster body")
        roomContent(RoomConversationState(room, listOf(message),
            RoomConversationStateHolder.rows(listOf(message)), hasLoadedOnce = true))
        val node = compose.onNode(messageNarrative(message.text, "Alice")).assertIsDisplayed().fetchSemanticsNode()
        assertEquals(1, Regex("Alice").findAll(narrative(node)).count())
    }

    @Test fun roomSentMessageHasNoRetryAction() {
        val message = roomMessage("sent without retry", self = true)
        var retried = false
        roomContent(RoomConversationState(room, listOf(message),
            RoomConversationStateHolder.rows(listOf(message)), hasLoadedOnce = true)) { retried = true }
        val retry = host.get().getString(C.chatsMessageStatusRetry)
        assertTrue(compose.onAllNodes(hasText(retry)).fetchSemanticsNodes().isEmpty())
        val customActions = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.CustomActions))
            .fetchSemanticsNodes().flatMap { it.config[SemanticsProperties.CustomActions] }
        assertTrue(customActions.none { it.label == retry })
        assertTrue(!retried)
    }

    @Test fun roomFailedRetryHasANativeTargetAndInvokesTheCorrectMessageOnly() {
        val failed = roomMessage("failed body", self = true, status = MessageStatus.FAILED)
        var retried: UUID? = null
        roomContent(RoomConversationState(room, listOf(failed),
            RoomConversationStateHolder.rows(listOf(failed)), hasLoadedOnce = true)) { retried = it }
        val retry = host.get().getString(C.chatsMessageStatusRetry)
        compose.onNodeWithText(retry).assertIsDisplayed().performClick()
        assertEquals(failed.id, retried)
        assertVisibleClickTargetsAtLeast48Dp()
        assertFont200Unclipped(compose.onNodeWithText(failed.text, useUnmergedTree = true))
    }

    @Test fun roomHttpsMessageExposesANamedNonGestureLinkAction() {
        val message = roomMessage("see https://example.com")
        roomContent(RoomConversationState(room, listOf(message),
            RoomConversationStateHolder.rows(listOf(message)), hasLoadedOnce = true))
        val label = C.chatsMessageActionOpenWebLink(host.get().resources, "example.com")
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.CustomActions)).fetchSemanticsNodes()
        assertTrue("The mounted room body has no named open-link action",
            nodes.any { node -> node.config[SemanticsProperties.CustomActions].any { it.label == label } })
    }

    @Test fun rememberPasswordSwitchHasPurposeAndCheckedStateOnOneNode() {
        val contact = ContactDTO(
            radioId = radio, publicKey = room.publicKey, name = room.name, lastHeardTimestamp = null,
        )
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    RoomAuthenticationSheet(
                        RoomConversationState(room, authenticationContact = contact, authenticationLookupComplete = true),
                        {}, { _, _ -> },
                    )
                }
            }
        }
        val label = host.get().getString(R.remoteNodesAuthRememberPassword)
        compose.onNode(SemanticsMatcher("Label and checked state belong to one toggle") { node ->
            node.config.contains(SemanticsProperties.ToggleableState) && narrative(node).contains(label)
        }).assertIsDisplayed()
    }

    @Test fun directFailureRetryAndMixedDirectionTextHonorSystemFontAndTargets() {
        val message = MessageDTO(
            id = UUID(0, 5), radioId = radio, contactID = UUID(0, 6),
            text = "\u4e2d\u6587\u6d88\u606f \u05e9\u05dc\u05d5\u05dd ".repeat(4),
            timestamp = 100u, createdAt = Instant.ofEpochSecond(100), status = MessageStatus.FAILED,
        )
        var retried: UUID? = null
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    ChatTimeline(timeline(message), callbacks { retried = it.id })
                }
            }
        }
        val retry = host.get().getString(C.chatsMessageStatusRetry)
        compose.onNodeWithText(retry).assertIsDisplayed().performClick()
        assertEquals(message.id, retried)
        assertVisibleClickTargetsAtLeast48Dp()
        assertFont200Unclipped(compose.onNodeWithText(message.text, useUnmergedTree = true))
        assertNoWholeScreenLiveRegion()
    }

    @Test fun renderedDirectAndChannelSpansClearTheirPaintedBubblesInAll38EffectiveFrames() {
        val selected = mutableStateOf(ThemeRegistry.default)
        val preference = mutableStateOf(AppColorSchemePreference.LIGHT)
        val high = mutableStateOf(false)
        val outgoing = mutableStateOf(true)
        val text = "@[Alice] see https://example.com #ops"
        val message = mutableStateOf(MessageDTO(
            id = UUID(0, 7), radioId = radio, text = text, timestamp = 100u,
            createdAt = Instant.ofEpochSecond(100), status = MessageStatus.DELIVERED,
        ))
        var surface = Color.Unspecified
        content {
            MeshCoreTheme(selected.value, preference.value, highContrast = high.value, motionScale = 0f) {
                val color = if (outgoing.value) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
                SideEffect { surface = color }
                AuditSurface {
                    ChatTimeline(timeline(message.value), callbacks {}, messageBody = { value, sent ->
                        MessageBody(value.text, sent, "Alice", {})
                    })
                }
            }
        }
        val failures = mutableListOf<String>()
        var frames = 0
        var spans = 0
        for (theme in ThemeRegistry.allThemes) {
            for (scheme in ColorScheme.entries.filter { theme.preferredColorScheme == null || theme.preferredColorScheme == it }) {
                for (contrast in listOf(false, true)) {
                    frames++
                    for (sent in listOf(false, true)) {
                        compose.runOnIdle {
                            selected.value = theme
                            preference.value = if (scheme == ColorScheme.DARK) AppColorSchemePreference.DARK else AppColorSchemePreference.LIGHT
                            high.value = contrast
                            outgoing.value = sent
                            message.value = message.value.copy(
                                direction = if (sent) MessageDirection.OUTGOING else MessageDirection.INCOMING,
                                channelIndex = if (sent) null else 0u,
                                senderNodeName = if (sent) null else "Alice",
                            )
                        }
                        val formatted = MessageTextFormatter.format(text, sent, "Alice")
                        val normalized = formatted.text
                        val node = compose.onNode(
                            hasText(normalized) and SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult),
                            useUnmergedTree = true,
                        )
                        val layout = textLayout(node)
                        assertFont200Unclipped(node)
                        val background = paintedBackground(node, surface)
                        val annotated = layout.layoutInput.text
                        val emitted = annotated.spanStyles + annotated.getLinkAnnotations(0, annotated.length).mapNotNull { link ->
                            link.item.styles?.style?.let { androidx.compose.ui.text.AnnotatedString.Range(it, link.start, link.end) }
                        }
                        assertTrue("No emitted span colors were measured", emitted.isNotEmpty())
                        val boundaries = (listOf(0, annotated.length) + emitted.flatMap { listOf(it.start, it.end) }).distinct().sorted()
                        for ((start, end) in boundaries.zipWithNext()) {
                            val style = emitted.filter { it.start <= start && it.end >= end }
                                .fold(androidx.compose.ui.text.SpanStyle()) { combined, span -> combined.merge(span.item) }
                            assertTrue("Unresolved emitted foreground at $start-$end", style.color.isSpecified)
                            val fill = if (style.background.isSpecified) style.background.toThemeColor().compositeOver(background) else background
                            val ratio = WCAGContrast.contrastRatio(style.color.toThemeColor(), fill)
                            val identityToken = formatted.tokens.any {
                                it.start <= start && it.end >= end && it.color == LinkColor.MENTION_IDENTITY
                            }
                            val floor = if (contrast && identityToken) 7.0 else 4.5
                            val identity = "${theme.id.rawValue}:$scheme:hc=$contrast:outgoing=$sent:$start-$end"
                            println("WP503_CONTRAST|$identity|ratio=$ratio|minimum=$floor")
                            if (ratio < floor) failures += "$identity=$ratio<$floor"
                            spans++
                        }
                    }
                }
            }
        }
        assertEquals("Do not silently narrow the effective frame family", 38, frames)
        assertTrue("Zero span measurements is not acceptance", spans >= 76)
        assertTrue("Actual painted-feature contrast below 4.5:1: ${failures.joinToString()}", failures.isEmpty())
    }

    private fun timeline(message: MessageDTO) = ChatTimelineState(
        messages = listOf(message), rows = listOf(TimelineRow.Message(message, true, true)),
        hasLoadedOnce = true, hasMoreMessages = false, initialAnchorConsumed = true,
    )

    private fun callbacks(retry: (MessageDTO) -> Unit) = ChatTimelineCallbacks({}, retry, {}, { _, _ -> }, {}, {})
}
