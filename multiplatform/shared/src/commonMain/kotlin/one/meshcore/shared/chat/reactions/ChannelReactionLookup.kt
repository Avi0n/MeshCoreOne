package one.meshcore.shared.chat.reactions

import one.meshcore.shared.model.Uuid

/** The channel, parsed reaction, and local name used to find the message a channel reaction targets. */
data class ChannelReactionLookup(
    val radioID: Uuid,
    val channelIndex: Int,
    val parsed: ParsedReaction,
    val localNodeName: String?,
)
