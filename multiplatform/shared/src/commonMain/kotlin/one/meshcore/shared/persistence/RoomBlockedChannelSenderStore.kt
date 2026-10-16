package one.meshcore.shared.persistence

import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.contact.BlockedChannelSenderStore
import one.meshcore.shared.model.Uuid

/**
 * Room-backed [BlockedChannelSenderStore]: `(radioID, name)` upsert keeps the existing id and updates
 * [BlockedChannelSenderDto.dateBlocked]; list is newest-block-first.
 */
class RoomBlockedChannelSenderStore(private val dao: BlockedChannelSenderDao) : BlockedChannelSenderStore {
    override suspend fun save(sender: BlockedChannelSenderDto) {
        val existing = dao.listByRadio(sender.radioID).firstOrNull { it.name == sender.name }
        val id = existing?.id ?: sender.id
        dao.upsert(
            BlockedChannelSenderEntity(
                id = id,
                name = sender.name,
                radioID = sender.radioID,
                dateBlocked = sender.dateBlocked,
            ),
        )
    }

    override suspend fun delete(radioID: Uuid, name: String) {
        dao.delete(radioID, name)
    }

    override suspend fun list(radioID: Uuid): List<BlockedChannelSenderDto> = dao.listByRadio(radioID)
        .sortedByDescending { it.dateBlocked }
        .map {
            BlockedChannelSenderDto(
                id = it.id,
                name = it.name,
                radioID = it.radioID,
                dateBlocked = it.dateBlocked,
            )
        }
}
