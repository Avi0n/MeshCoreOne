package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/**
 * Uniquely identifies a `(radio, conversation)` pair so a single [ChatCoordinator] instance can be shared
 * across every UI surface bound to that conversation (split view, sheet dismissal, navigation transitions).
 * [ChatCoordinatorRegistry] keys its map on this value. Mirrors the Swift `ChatConversationID`; draft
 * persistence derives its storage key from the same pair.
 */
data class ChatConversationID(val radioID: Uuid, val conversation: ConversationKey) {
    sealed interface ConversationKey {
        /** A direct chat, keyed on the contact UUID. */
        data class Dm(val contactID: Uuid) : ConversationKey

        /** A channel chat, keyed on the firmware slot index. */
        data class Channel(val channelIndex: Int) : ConversationKey
    }

    companion object {
        fun dm(radioID: Uuid, contactID: Uuid): ChatConversationID =
            ChatConversationID(radioID, ConversationKey.Dm(contactID))

        fun channel(radioID: Uuid, channelIndex: Int): ChatConversationID =
            ChatConversationID(radioID, ConversationKey.Channel(channelIndex))
    }
}
