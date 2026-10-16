package one.meshcore.shared.chat

import one.meshcore.shared.model.Uuid

/** Conversation identities that currently have at least one outgoing failed send. */
data class FailedSendConversationKeys(
    val contactIDs: Set<Uuid> = emptySet(),
    val channelIDs: Set<Uuid> = emptySet(),
    val roomSessionIDs: Set<Uuid> = emptySet(),
) {
    val allIDs: Set<Uuid> get() = contactIDs + channelIDs + roomSessionIDs
}
