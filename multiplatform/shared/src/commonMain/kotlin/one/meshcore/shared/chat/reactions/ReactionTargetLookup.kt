package one.meshcore.shared.chat.reactions

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.Uuid

/**
 * Contact, channel, and message rows the reaction send and notification paths read. Split from
 * [ReactionStore] so the reaction seam and these lookups stay separate.
 */
interface ReactionTargetLookup {
    /** The contact row for [id], needed to address an outgoing DM reaction. */
    suspend fun fetchContact(id: Uuid): ContactDto?

    /** The channel row at ([radioID], [channelIndex]), needed for its mute state on a channel reaction. */
    suspend fun fetchChannel(radioID: Uuid, channelIndex: Int): ChannelDto?

    /** The message row for [id], needed to check whether a reaction's target was outgoing and to preview it. */
    suspend fun fetchMessage(id: Uuid): MessageDto?
}
