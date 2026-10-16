package one.meshcore.shared.persistence

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.reactions.ReactionTargetLookup
import one.meshcore.shared.model.Uuid

/** Room reads for the contact, channel, and message rows a reaction addresses. */
internal class RoomReactionTargets(
    private val contactDao: ContactDao,
    private val channelDao: ChannelDao,
    private val messageDao: MessageDao,
) : ReactionTargetLookup {
    override suspend fun fetchContact(id: Uuid): ContactDto? = contactDao.getById(id)?.toDto()

    override suspend fun fetchChannel(radioID: Uuid, channelIndex: Int): ChannelDto? =
        channelDao.getByRadioAndIndex(radioID, channelIndex)?.toDto()

    override suspend fun fetchMessage(id: Uuid): MessageDto? = messageDao.getById(id)?.toDto()
}
