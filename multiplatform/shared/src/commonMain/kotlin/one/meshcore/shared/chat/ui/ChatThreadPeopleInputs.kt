package one.meshcore.shared.chat.ui

import one.meshcore.shared.chat.reactions.ReactionSender
import one.meshcore.shared.chat.reactions.ReactionService
import one.meshcore.shared.chat.reactions.ReactionStore
import one.meshcore.shared.chat.reactions.RecentEmojisStore
import one.meshcore.shared.contact.BlockedChannelSenderService
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.model.Uuid

interface ChatThreadPeople {
    /** Contact service for block-sender matching; null when disconnected. */
    val contactService: () -> ContactService?

    /** Name-based channel sender block service; null until ServiceContainer wires it. */
    val blockedChannelSenderService: () -> BlockedChannelSenderService?

    /** Live radio partition key for block-sender persistence. */
    val radioId: () -> Uuid?

    /** Outgoing reaction service; null when disconnected. */
    val reactionService: () -> ReactionService?

    /** Reaction persistence for react send optimistic path. */
    val reactionStore: () -> ReactionStore?

    /** Wire-text sender for reactions (MessageService-backed in production). */
    val reactionSender: () -> ReactionSender?

    /** Recently used reaction emojis. App-lifetime; defaults stay in memory only. */
    val recentEmojisStore: RecentEmojisStore

    val frequentEmojisStore: RecentEmojisStore
}

/** Block-sender matching, reaction send, and the app-lifetime emoji stores. */
data class ChatThreadPeopleInputs(
    override val contactService: () -> ContactService? = { null },
    override val blockedChannelSenderService: () -> BlockedChannelSenderService? = { null },
    override val radioId: () -> Uuid? = { null },
    override val reactionService: () -> ReactionService? = { null },
    override val reactionStore: () -> ReactionStore? = { null },
    override val reactionSender: () -> ReactionSender? = { null },
    override val recentEmojisStore: RecentEmojisStore = RecentEmojisStore(),
    override val frequentEmojisStore: RecentEmojisStore = RecentEmojisStore(
        maxRecent = RecentEmojisStore.MAX_FREQUENT,
        prefsKey = RecentEmojisStore.FREQUENT_PREFS_KEY,
        emptyFallback = emptyList(),
    ),
) : ChatThreadPeople
