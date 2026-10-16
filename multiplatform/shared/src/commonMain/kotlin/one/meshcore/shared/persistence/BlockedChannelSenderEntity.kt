package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.backup.BlockedChannelSenderDto
import one.meshcore.shared.model.Uuid

/** Persistence mirror of the SwiftData `BlockedChannelSender` model. `radioID`-scoped. */
@Entity(tableName = "blocked_channel_sender")
data class BlockedChannelSenderEntity(
    @PrimaryKey val id: Uuid,
    val name: String,
    val radioID: Uuid,
    val dateBlocked: Double,
)

fun BlockedChannelSenderEntity.toDto(): BlockedChannelSenderDto =
    BlockedChannelSenderDto(id = id, name = name, radioID = radioID, dateBlocked = dateBlocked)

fun BlockedChannelSenderDto.toEntity(): BlockedChannelSenderEntity =
    BlockedChannelSenderEntity(id = id, name = name, radioID = radioID, dateBlocked = dateBlocked)
