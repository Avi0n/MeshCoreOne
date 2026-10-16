package one.meshcore.shared.persistence

import androidx.room.Entity
import androidx.room.PrimaryKey
import one.meshcore.shared.model.Uuid

/** Persistence mirror of the SwiftData `MessageRepeat` model, keyed to its parent by [messageID]. */
@Entity(tableName = "message_repeat")
data class MessageRepeatEntity(
    @PrimaryKey val id: Uuid,
    val messageID: Uuid,
    val receivedAt: Double,
    val pathNodes: ByteArray,
    val pathLength: Int = 0,
    val snr: Double? = null,
    val rssi: Int? = null,
    val rxLogEntryID: Uuid? = null,
)
