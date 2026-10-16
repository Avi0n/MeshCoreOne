package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.Uuid

/** One observed route by which a message reached this radio. */
class MessagePathArrival internal constructor(facts: PathArrivalFacts) {
    val id: Uuid = facts.id
    val pathNodes: ByteArray = facts.pathNodes
    val pathLength: Int = facts.pathLength
    val snr: Double? = facts.snr
    val rssi: Int? = facts.rssi
    val receivedAt: Double = facts.receivedAt
    val isFirst: Boolean = facts.isFirst

    val hopCount: Int
        get() = PathEncoding.decodePathLen(pathLength)?.hopCount ?: (pathLength and PathEncoding.MAX_HOP_COUNT)
}

/** Builds the selectable arrival list from a message and its recorded extra paths. */
object MessagePathArrivals {
    fun assemble(message: MessageDto, repeats: List<MessageRepeatDto>): List<MessagePathArrival> {
        val extras = repeats.sortedBy { it.receivedAt }.map { repeat ->
            MessagePathArrival(
                PathArrivalFacts(
                    id = repeat.id,
                    pathNodes = repeat.pathNodes,
                    pathLength = repeat.pathLength,
                    snr = repeat.snr,
                    rssi = repeat.rssi,
                    receivedAt = repeat.receivedAt,
                    isFirst = false,
                ),
            )
        }
        if (message.direction == MessageDirection.OUTGOING) return extras
        val first = MessagePathArrival(
            PathArrivalFacts(
                id = message.id,
                pathNodes = message.pathNodes ?: byteArrayOf(),
                pathLength = message.pathLength,
                snr = message.snr,
                rssi = null,
                receivedAt = message.createdAt,
                isFirst = true,
            ),
        )
        return listOf(first) + extras
    }

    fun arrivalCount(message: MessageDto): Int =
        message.heardRepeats + if (message.direction == MessageDirection.OUTGOING) 0 else 1

    fun resolvedSelection(preferred: Uuid?, arrivals: List<MessagePathArrival>): Uuid? =
        arrivals.firstOrNull { it.id == preferred }?.id ?: arrivals.firstOrNull()?.id
}

internal data class PathArrivalFacts(
    val id: Uuid,
    val pathNodes: ByteArray,
    val pathLength: Int,
    val snr: Double?,
    val rssi: Int?,
    val receivedAt: Double,
    val isFirst: Boolean,
)
