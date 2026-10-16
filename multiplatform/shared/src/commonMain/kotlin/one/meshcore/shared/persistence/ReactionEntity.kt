package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.Uuid

/** Persistence mirror of the SwiftData `Reaction` model. `radioID` is the data-partition key. */
@Entity(tableName = "reaction")
data class ReactionEntity(
    @PrimaryKey val id: Uuid,
    val messageID: Uuid,
    val emoji: String,
    val senderName: String,
    val messageHash: String,
    val rawText: String,
    val receivedAt: Double,
    val radioID: Uuid,
    val channelIndex: Int? = null,
    val contactID: Uuid? = null,
)
