package one.meshcore.shared.chat.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.chat.reactions.ChannelReactionLookup
import one.meshcore.shared.chat.reactions.ReactionStore
import one.meshcore.shared.model.Uuid
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReactionDetailsOwnerInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementStoreReloadsTheOpenSheet() {
        val storeOwner = mutableStateOf<ReactionStore?>(store(FIRST_SENDER, FIRST_EMOJI, FIRST_REACTION_ID))

        compose.setContent {
            MaterialTheme {
                ReactionDetailsSheet(
                    messageId = MESSAGE_ID,
                    reactionStore = storeOwner.value,
                    onDismiss = {},
                )
            }
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(FIRST_SENDER).fetchSemanticsNodes().isNotEmpty()
        }

        compose.runOnIdle {
            storeOwner.value = store(REPLACEMENT_SENDER, REPLACEMENT_EMOJI, REPLACEMENT_REACTION_ID)
        }
        compose.waitUntil(timeoutMillis = LOAD_TIMEOUT_MS) {
            compose.onAllNodesWithText(REPLACEMENT_SENDER).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithText(FIRST_SENDER).assertDoesNotExist()
        compose.onNodeWithText(REPLACEMENT_SENDER).assertExists()
    }

    private fun store(sender: String, emoji: String, id: Uuid): ReactionStore = OwnerTestReactionStore(
        listOf(
            ReactionDto(
                id = id,
                messageID = MESSAGE_ID,
                emoji = emoji,
                senderName = sender,
                messageHash = "aabbccdd",
                rawText = ":aabbccdd:$emoji",
                receivedAt = 1.0,
                radioID = RADIO_ID,
            ),
        ),
    )

    private companion object {
        const val FIRST_SENDER = "Alice"
        const val REPLACEMENT_SENDER = "Bob"
        const val FIRST_EMOJI = "👍"
        const val REPLACEMENT_EMOJI = "❤️"
        const val LOAD_TIMEOUT_MS = 5_000L
        val MESSAGE_ID: Uuid = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
        val RADIO_ID: Uuid = Uuid.normalized("B0000000-0000-0000-0000-000000000001")
        val FIRST_REACTION_ID: Uuid = Uuid.normalized("C0000000-0000-0000-0000-000000000001")
        val REPLACEMENT_REACTION_ID: Uuid = Uuid.normalized("C0000000-0000-0000-0000-000000000002")
    }
}

private class OwnerTestReactionStore(private val reactions: List<ReactionDto>) : ReactionStore {
    override suspend fun fetchReactions(messageID: Uuid, limit: Int): List<ReactionDto> =
        reactions.filter { it.messageID == messageID }.take(limit)

    override suspend fun saveReaction(reaction: ReactionDto) = error("unused")

    override suspend fun reactionExists(messageID: Uuid, senderName: String, emoji: String): Boolean = error("unused")

    override suspend fun updateMessageReactionSummary(messageID: Uuid, summary: String?) = error("unused")

    override suspend fun findDirectMessageForReaction(
        radioID: Uuid,
        contactID: Uuid,
        messageHash: String,
        window: LongRange,
        limit: Int,
    ): MessageDto? = error("unused")

    override suspend fun findChannelMessageForReaction(
        lookup: ChannelReactionLookup,
        window: LongRange,
        limit: Int,
    ): MessageDto? = error("unused")

    override suspend fun fetchDirectMessageCandidates(
        radioID: Uuid,
        contactID: Uuid,
        window: LongRange,
        limit: Int,
    ): List<MessageDto> = error("unused")

    override suspend fun fetchChannelMessageCandidates(
        radioID: Uuid,
        channelIndex: Int,
        window: LongRange,
        limit: Int,
    ): List<MessageDto> = error("unused")

    override suspend fun fetchContact(id: Uuid): ContactDto? = error("unused")

    override suspend fun fetchChannel(radioID: Uuid, channelIndex: Int): ChannelDto? = error("unused")

    override suspend fun fetchMessage(id: Uuid): MessageDto? = error("unused")
}
