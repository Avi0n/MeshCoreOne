package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.BaseColorSlot
import one.meshcore.shared.chat.EnvInputs
import one.meshcore.shared.chat.MessageBuildInputs
import one.meshcore.shared.chat.MessageFragmentBuilder
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.NodeNameResolution
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatReactionBubbleInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val selectedEmojis = mutableListOf<String>()
    private var detailsOpened = 0

    @Test
    fun incomingChannelReactionRendersAndRemainsInteractive() {
        showBubble(outgoing = false, channel = true, reaction = EMOJI)
        verifyReaction()
    }

    @Test
    fun outgoingDirectReactionRendersAndRemainsInteractive() {
        showBubble(outgoing = true, channel = false, reaction = EMOJI)
        verifyReaction()
    }

    @Test
    fun ordinaryMessageWithoutReactionsRenders() {
        showBubble(outgoing = false, channel = true, reaction = null)
        compose.onNodeWithText(BODY, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(EMOJI, useUnmergedTree = true).assertDoesNotExist()
        compose.runOnIdle {
            assertEquals(emptyList<String>(), selectedEmojis)
            assertEquals(0, detailsOpened)
        }
    }

    private fun verifyReaction() {
        compose.onNodeWithText(BODY, useUnmergedTree = true).assertIsDisplayed()
        val badge = compose.onNodeWithText(EMOJI, useUnmergedTree = true).assertIsDisplayed()
        badge.performTouchInput { click() }
        compose.runOnIdle {
            assertEquals(listOf(EMOJI), selectedEmojis)
            assertEquals(0, detailsOpened)
        }
        badge.performTouchInput { longClick() }
        compose.runOnIdle {
            assertEquals(listOf(EMOJI), selectedEmojis)
            assertEquals(1, detailsOpened)
        }
    }

    private fun showBubble(outgoing: Boolean, channel: Boolean, reaction: String?) {
        val message = MessageDto(
            id = MESSAGE_ID,
            radioID = RADIO_ID,
            text = BODY,
            timestamp = TIMESTAMP,
            createdAt = TIMESTAMP.toDouble(),
            direction = if (outgoing) MessageDirection.OUTGOING else MessageDirection.INCOMING,
            status = MessageStatus.SENT,
            textType = TextType.PLAIN,
            pathLength = 0,
            isRead = true,
            heardRepeats = 0,
            sendCount = 1,
            retryAttempt = 0,
            maxRetryAttempts = 0,
            linkPreviewFetched = false,
            containsSelfMention = false,
            mentionSeen = false,
            timestampCorrected = false,
            contactID = CONTACT_ID.takeUnless { channel },
            channelIndex = CHANNEL_INDEX.takeIf { channel },
            senderNodeName = SENDER,
            reactionSummary = reaction?.let { "$it:1" },
        )
        val inputs = MessageBuildInputs(
            messageID = MESSAGE_ID,
            baseColor = if (outgoing) BaseColorSlot.OUTGOING else BaseColorSlot.INCOMING,
            senderResolution = NodeNameResolution(SENDER, NodeNameMatchKind.EXACT),
            showTimestamp = false,
            showDirectionGap = false,
            showSenderName = false,
            showNewMessagesDivider = false,
        )
        val item = MessageFragmentBuilder.makeItem(message, inputs, EnvInputs.DEFAULT)
        compose.setContent {
            TimestampFormatting {
                MaterialTheme {
                    ChatMessageBubble(
                        item = item,
                        onRetry = {},
                        onTapReaction = selectedEmojis::add,
                        onOpenReactionDetails = { detailsOpened += 1 },
                    )
                }
            }
        }
    }

    private companion object {
        const val BODY = "Reaction bubble check"
        const val EMOJI = "👍"
        const val SENDER = "Synthetic sender"
        const val CHANNEL_INDEX = 1
        const val TIMESTAMP = 1_700_000_000L
        val MESSAGE_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val RADIO_ID: Uuid = Uuid.normalized("B0000000-0000-0000-0000-000000000001")
        val CONTACT_ID: Uuid = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
    }
}
