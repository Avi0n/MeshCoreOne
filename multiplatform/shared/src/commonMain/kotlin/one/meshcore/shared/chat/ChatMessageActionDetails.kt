package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.model.RouteType

/**
 * Detail rows for the DM/channel long-press "Details" section (iOS `ActionsDetailsSection`
 * outgoing/incoming row builders). Pure so the incoming vs outgoing row set stays unit-testable
 * without Compose. Mirrors [one.meshcore.shared.room.RoomMessageActionDetails] for the room sheet.
 */
object ChatMessageActionDetails {
    sealed interface Line {
        /** Wire send time (mesh timestamp, epoch seconds); [adjusted] flags a clock-corrected value. */
        data class Sent(val epochSeconds: Double, val adjusted: Boolean) : Line

        /** Raw, uncorrected send time the sender stamped on the wire; shown only when [Sent.adjusted]. */
        data class OriginalSendTime(val epochSeconds: Double) : Line

        /** Local receive / create time (epoch seconds), incoming only. */
        data class Received(val epochSeconds: Double) : Line

        data class RoundTrip(val milliseconds: Long) : Line

        data class HeardRepeats(val count: Int) : Line

        data class Hops(val hopCount: Int, val isDirect: Boolean) : Line

        data class PathHash(val hashSizeBytes: Int) : Line

        /** Flood-routed region match; none means unresolved. */
        data class FloodRegion(val match: RegionMatchResult) : Line

        data class Snr(val decibels: Double) : Line
    }

    fun lines(message: MessageDto): List<Line> = if (message.isOutgoing) {
        outgoingLines(message)
    } else {
        incomingLines(message)
    }

    private fun outgoingLines(message: MessageDto): List<Line> = buildList {
        add(Line.Sent(message.senderDateEpochSeconds, adjusted = false))
        message.roundTripTime?.let { add(Line.RoundTrip(it)) }
        if (message.heardRepeats > 0) add(Line.HeardRepeats(message.heardRepeats))
    }

    private fun incomingLines(message: MessageDto): List<Line> = buildList {
        add(Line.Hops(message.hopCount, isDirect = message.isDirectRouted))
        message.pathHashSizeIfKnown?.let { add(Line.PathHash(it)) }
        if (message.routeType == RouteType.TC_FLOOD) {
            add(
                Line.FloodRegion(
                    RegionScopeSemantics.coalesce(message.regionScope, message.regionScopeMatches),
                ),
            )
        }
        add(Line.Sent(message.senderDateEpochSeconds, adjusted = message.timestampCorrected))
        if (message.timestampCorrected) add(Line.OriginalSendTime(message.wireSentDateEpochSeconds))
        add(Line.Received(message.createdAt))
        message.snr?.let { add(Line.Snr(it)) }
    }
}
