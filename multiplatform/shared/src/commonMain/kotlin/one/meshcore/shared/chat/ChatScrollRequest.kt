package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/** A conversation-bound notification intent; repeated taps have distinct request identities. */
data class ChatScrollRequest(val requestID: Uuid, val conversationID: String, val messageID: Uuid)
