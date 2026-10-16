package one.meshcore.shared.room

import one.meshcore.shared.backup.RoomMessageDto
import one.meshcore.shared.model.MessageStatus

/**
 * Detail rows for the room long-press sheet (iOS `RoomMessageActionsSheet.details`). Pure so the
 * incoming vs outgoing row set stays unit-testable without Compose.
 */
object RoomMessageActionDetails {
    sealed interface Line {
        /** Wire send time (mesh timestamp, epoch seconds). */
        data class Sent(val epochSeconds: Double) : Line

        /** Local receive / create time (epoch seconds). */
        data class Received(val epochSeconds: Double) : Line

        data class Status(val status: MessageStatus) : Line

        data class RoundTrip(val milliseconds: Long) : Line
    }

    fun lines(message: RoomMessageDto): List<Line> {
        val status = MessageStatus.fromRaw(message.statusRawValue) ?: MessageStatus.PENDING
        val sent = Line.Sent(message.timestamp.toDouble())
        return if (message.isFromSelf) {
            buildList {
                add(sent)
                add(Line.Status(status))
                val rtt = message.roundTripTime
                if (rtt != null) add(Line.RoundTrip(rtt))
            }
        } else {
            listOf(
                sent,
                Line.Received(message.createdAt),
                Line.Status(status),
            )
        }
    }
}
