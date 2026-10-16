package one.meshcore.shared.chat

import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.model.Uuid

/** The channel packet a reaction hook consumes or leaves as a chat row. */
data class ChannelReactionWire(
    val text: String,
    val channelIndex: Int,
    val senderNodeName: String?,
    val selfNodeName: String,
    val receiveTimeEpochSeconds: Double,
    val radioID: Uuid,
)

/** A saved channel message the notifier may surface. */
data class ChannelMessageNotice(
    val channel: ResolvedChannel,
    val channelIndex: Int,
    val radioID: Uuid,
    val senderNodeName: String?,
    val text: String,
    val messageID: Uuid,
    val hasSelfMention: Boolean,
)

/** A saved room message the notifier may surface. */
data class RoomMessageNotice(
    val roomName: String,
    val sessionID: Uuid,
    val senderName: String?,
    val text: String,
    val messageID: Uuid,
    val notificationLevel: NotificationLevel,
)
