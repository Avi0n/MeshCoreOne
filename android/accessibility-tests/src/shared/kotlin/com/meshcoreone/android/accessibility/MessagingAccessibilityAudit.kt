// AndroidOnly: WP-503 Share original mounted chat/room assertions across JVM and actual Android runners.
package com.meshcoreone.android.accessibility

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
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
import java.util.function.Supplier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows

internal class MessagingAccessibilityAudit(
    override val compose: ComposeTestRule,
    private val host: Supplier<ComponentActivity>,
    private val mount: (@Composable () -> Unit) -> Unit,
    private val surface: @Composable (@Composable () -> Unit) -> Unit,
) : NativeAccessibilityAssertions() {
    override val activity: ComponentActivity get() = host.get()

    override fun content(body: @Composable () -> Unit) = mount(body)

    @Composable
    override fun AuditSurface(body: @Composable () -> Unit) = surface(body)

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

    private fun roomContent(
        state: RoomConversationState,
        onLink: (String) -> Boolean = { error("This room case must not invoke a link") },
        retry: (UUID) -> Unit = {},
    ) {
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    RoomConversationContent(state = state, onDraftChanged = {}, onSend = {},
                        onRetry = retry, onLink = onLink, onReconnect = {}, onDismissError = {},
                        failureText = { "Fixture failure" })
                }
            }
        }
    }

    fun roomFollowUpRetainsAuthorContextWithoutAnnouncingTheWholeChat() {
        val first = roomMessage("cluster start")
        val followUp = roomMessage("follow up").copy(id = UUID(0, 4), timestamp = 101u)
        roomContent(RoomConversationState(room, listOf(first, followUp),
            RoomConversationStateHolder.rows(listOf(first, followUp)), hasLoadedOnce = true))
        assertNoWholeTimelineLiveRegion(first.text, followUp.text)
        val node = compose.onNode(messageNarrative("follow up", "Alice")).assertIsDisplayed().fetchSemanticsNode()
        assertTrue("The row must not absorb the other message", !narrative(node).contains(first.text))
    }

    fun roomOutgoingSentStatusIsExposedExactlyOnceOnItsMessage() {
        val message = roomMessage("sent body", self = true)
        roomContent(RoomConversationState(room, listOf(message),
            RoomConversationStateHolder.rows(listOf(message)), hasLoadedOnce = true))
        val label = host.get().getString(R.remoteNodesRoomMessageStatusDeliveredLabel)
        val node = compose.onNode(messageNarrative(message.text, label)).assertIsDisplayed().fetchSemanticsNode()
        assertEquals("Status should not be spoken twice", 1, Regex(Regex.escape(label)).findAll(narrative(node)).count())
    }

    fun roomClusterStartAnnouncesItsAuthorExactlyOnce() {
        val message = roomMessage("cluster body")
        roomContent(RoomConversationState(room, listOf(message),
            RoomConversationStateHolder.rows(listOf(message)), hasLoadedOnce = true))
        val node = compose.onNode(messageNarrative(message.text, "Alice")).assertIsDisplayed().fetchSemanticsNode()
        assertEquals(1, Regex("Alice").findAll(narrative(node)).count())
    }

    fun roomSentMessageHasNoRetryAction() {
        val message = roomMessage("sent without retry", self = true)
        var retried = false
        roomContent(RoomConversationState(room, listOf(message),
            RoomConversationStateHolder.rows(listOf(message)), hasLoadedOnce = true)) { retried = true }
        val retry = host.get().getString(C.chatsMessageStatusRetry)
        assertTrue(compose.onAllNodes(hasText(retry)).fetchSemanticsNodes().isEmpty())
        val customActions = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions))
            .fetchSemanticsNodes().flatMap { it.config[SemanticsActions.CustomActions] }
        assertTrue(customActions.none { it.label == retry })
        assertTrue(!retried)
    }

    fun roomFailedRetryHasANativeTargetAndInvokesTheCorrectMessageOnly() {
        val failed = roomMessage("failed body", self = true, status = MessageStatus.FAILED)
        var retried: UUID? = null
        roomContent(RoomConversationState(room, listOf(failed),
            RoomConversationStateHolder.rows(listOf(failed)), hasLoadedOnce = true)) { retried = it }
        val row = compose.onNode(messageNarrative(failed.text, host.get().getString(R.remoteNodesRoomMessageStatusFailedLabel)))
            .assertIsDisplayed().fetchSemanticsNode()
        val action = row.config[SemanticsActions.CustomActions].single { it.label == host.get().getString(C.chatsMessageActionRetry) }
        compose.runOnIdle { assertTrue("The source failed-only custom action must retry", action.action()) }
        assertEquals(failed.id, retried)
        retried = null
        val retry = host.get().getString(C.chatsMessageStatusRetry)
        val caption = compose.onNodeWithText(retry, useUnmergedTree = true).fetchSemanticsNode()
        val button = generateSequence(caption) { it.parent }.first { it.config.contains(SemanticsActions.OnClick) }
        val target = button.touchBoundsInRoot
        val density = host.get().resources.displayMetrics.density
        println("WP503_ROOM_RETRY_TARGET|bounds=$target|size=${button.size}|row=${row.boundsInRoot}")
        assertTrue("The real pointer Retry control must retain a 48dp effective target",
            target.width / density >= 48f && target.height / density >= 48f)
        assertTrue("Retry must not be clipped by its actual message row",
            target.left >= row.boundsInRoot.left && target.top >= row.boundsInRoot.top &&
                target.right <= row.boundsInRoot.right && target.bottom <= row.boundsInRoot.bottom)
        compose.onNode(SemanticsMatcher("Actual native Retry button") { it.id == button.id }, useUnmergedTree = true)
            .performTouchInput { click(center) }
        assertEquals(failed.id, retried)
        assertFont200Unclipped(compose.onNodeWithText(failed.text, useUnmergedTree = true))
    }

    fun roomHttpsMessageExposesANamedNonGestureLinkAction() {
        val message = roomMessage("see https://example.com")
        val opened = mutableListOf<String>()
        roomContent(RoomConversationState(room, listOf(message),
            RoomConversationStateHolder.rows(listOf(message)), hasLoadedOnce = true),
            onLink = { opened.add(it) })
        val label = C.chatsMessageActionOpenWebLink(host.get().resources, "example.com")
        val nodes = compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes()
        assertTrue("The mounted room body has no named open-link action",
            nodes.any { node -> node.config[SemanticsActions.CustomActions].any { it.label == label } })
        val action = nodes.flatMap { it.config[SemanticsActions.CustomActions] }.single { it.label == label }
        compose.runOnIdle { assertTrue("The native custom action must route the actual URL", action.action()) }
        assertEquals(listOf("https://example.com"), opened)
    }

    fun rememberPasswordSwitchHasPurposeAndCheckedStateOnOneNode() {
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

    fun directFailureRetryAndMixedDirectionTextHonorSystemFontAndTargets() {
        val message = MessageDTO(
            id = UUID(0, 5), radioId = radio, contactID = UUID(0, 6),
            text = "\u4e2d\u6587\u6d88\u606f \u05e9\u05dc\u05d5\u05dd ".repeat(4),
            timestamp = 100u, createdAt = Instant.ofEpochSecond(100), status = MessageStatus.FAILED,
        )
        var retried: UUID? = null
        val other = message.copy(id = UUID(0, 8), text = "other cached message", direction = MessageDirection.INCOMING)
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    ChatTimeline(timeline(message, other), callbacks { retried = it.id })
                }
            }
        }
        val retry = host.get().getString(C.chatsMessageStatusRetry)
        compose.onNodeWithText(retry).assertIsDisplayed().performClick()
        assertEquals(message.id, retried)
        assertVisibleClickTargetsAtLeast48Dp()
        assertFont200Unclipped(compose.onNodeWithText(message.text, useUnmergedTree = true))
        assertNoWholeTimelineLiveRegion(message.text, other.text)
    }

    fun auditDetectsLiveRegionOnTheActualTimelineInsteadOfOnlyTheTestSurface() {
        val first = auditMessage("first actual row", 20)
        val second = auditMessage("second actual row", 21)
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    ChatTimeline(timeline(first, second), callbacks {},
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                }
            }
        }
        val failure = assertThrows(AssertionError::class.java) {
            assertNoWholeTimelineLiveRegion(first.text, second.text)
        }
        assertTrue("Regression must reach the actual timeline ancestor", failure.message.orEmpty().contains("absorbs 2"))
    }

    fun auditAllowsASmallFailureOnlyLiveRegionWithoutAnnouncingOtherRows() {
        val first = auditMessage("first independent row", 22)
        val second = auditMessage("second independent row", 23)
        content {
            MeshCoreTheme(motionScale = 0f) {
                AuditSurface {
                    Column {
                        ChatTimeline(timeline(first, second), callbacks {}, modifier = Modifier.weight(1f))
                        Text("local send failure", Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }
            }
        }
        compose.onNodeWithText("local send failure").assertIsDisplayed()
        assertNoWholeTimelineLiveRegion(first.text, second.text)
    }

    fun renderedDirectAndChannelSpansClearTheirPaintedBubblesInAll38EffectiveFrames() {
        auditMessagingContrast(compose, ::content, { AuditSurface(it) },
            ::textLayout, ::assertFont200Unclipped, ::paintedBackground)
    }

    private fun auditMessage(body: String, id: Long) = MessageDTO(
        id = UUID(0, id), radioId = radio, text = body, timestamp = 100u,
        createdAt = Instant.ofEpochSecond(100), direction = MessageDirection.INCOMING,
    )

    private fun timeline(vararg messages: MessageDTO) = ChatTimelineState(
        messages = messages.toList(), rows = messages.map { TimelineRow.Message(it, true, true) },
        hasLoadedOnce = true, hasMoreMessages = false, initialAnchorConsumed = true,
    )

    private fun callbacks(retry: (MessageDTO) -> Unit) = ChatTimelineCallbacks({}, retry, {}, { _, _ -> }, {}, {})
}
