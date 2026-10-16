package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.RouteType

private const val PATH_HOP_COUNT_MASK = 0x3F
private const val PATH_SEPARATOR_DISPLAY = " → "
private const val PATH_SEPARATOR_CLIPBOARD = ","

/**
 * Pure derived accessors on the persisted message enums and DTO that the chat layer needs beyond the
 * Room entity and backup DTO. These mirror the Swift `Message`/`MessageDTO` computed properties. All
 * dates are epoch seconds (matching [MessageDto.timestamp] and [MessageDto.createdAt]); a caller that
 * needs an instant wraps them at the presentation layer.
 */

/** True while the message is still working toward the radio (optimistic states). */
val MessageStatus.isPending: Boolean
    get() = this == MessageStatus.PENDING || this == MessageStatus.SENDING

/** True only in the terminal failed state. */
val MessageStatus.hasFailed: Boolean
    get() = this == MessageStatus.FAILED

/** Whether this is an outgoing message. */
val MessageDto.isOutgoing: Boolean
    get() = direction == MessageDirection.OUTGOING

/** Whether the message is still pending delivery. */
val MessageDto.isPending: Boolean
    get() = status.isPending

/** Whether the message failed to send. */
val MessageDto.hasFailed: Boolean
    get() = status.hasFailed

/** Send time from the sender's device clock, as epoch seconds. */
val MessageDto.senderDateEpochSeconds: Double
    get() = timestamp.toDouble()

/**
 * Raw, uncorrected send time the sender stamped on the wire, as epoch seconds. Surfaces the literal
 * skewed value when the timestamp was corrected; otherwise equals [senderDateEpochSeconds].
 */
val MessageDto.wireSentDateEpochSeconds: Double
    get() = (senderTimestamp ?: timestamp).toDouble()

/**
 * Timestamp used for reaction-hash computation: the original sender timestamp when the stored one was
 * corrected, else the stored timestamp. Sender and receiver must agree on this value.
 */
val MessageDto.reactionTimestamp: Long
    get() = senderTimestamp ?: timestamp

/**
 * Whether the message was flood-routed (broadcast). Priority mirrors the Swift `Message.isFloodRouted`:
 * a channel message is always flood; otherwise a known [RouteType] decides; otherwise the encoded
 * `pathLength` is inspected against the flood sentinel. Note "Direct" here means the direct out-path
 * routing mode, not a zero-hop path.
 */
val MessageDto.isFloodRouted: Boolean
    get() {
        if (channelIndex != null) return true
        routeType?.let { return it == RouteType.FLOOD || it == RouteType.TC_FLOOD }
        return pathLength != ChatConstants.FLOOD_PATH_SENTINEL
    }

/** Whether the message was direct-routed (a pre-built path whose hops are consumed in transit). */
val MessageDto.isDirectRouted: Boolean
    get() = !isFloodRouted

/**
 * Hop count decoded from the lower 6 bits of `pathLength`, falling back to the masked raw byte for a
 * reserved hash mode. Mirrors the Swift `Message.hopCount`.
 */
val MessageDto.hopCount: Int
    get() = PathEncoding.decodePathLen(pathLength)?.hopCount ?: (pathLength and PATH_HOP_COUNT_MASK)

/** Bytes per hop hash (1, 2, or 3) from the `pathLength` hash mode, defaulting to 1. */
val MessageDto.pathHashSize: Int
    get() = PathEncoding.decodePathLen(pathLength)?.hashSize ?: 1

/** Bytes per hop hash when the path length byte encodes a known mode; null for reserved modes or no path. */
val MessageDto.pathHashSizeIfKnown: Int?
    get() = PathEncoding.decodePathLen(pathLength)?.hashSize

/** Each path hop's raw hash bytes plus uppercase hex; empty when the message carries no path nodes. */
val MessageDto.pathHops: List<one.meshcore.shared.chat.pathdetail.PathHop>
    get() = pathNodes?.let { PathEncoding.pathHops(it, pathHashSize) } ?: emptyList()

/** Path nodes as uppercase-hex strings, chunked by hash size. */
val MessageDto.pathNodesHex: List<String>
    get() = pathHops.map { it.hex }

/** Path as an arrow-separated display string, e.g. `A3 → 7F → 42`. */
val MessageDto.pathString: String
    get() = pathNodesHex.joinToString(separator = PATH_SEPARATOR_DISPLAY)

/** Path as a comma-separated string for the clipboard, e.g. `A3,7F,42`. */
val MessageDto.pathStringForClipboard: String
    get() = pathNodesHex.joinToString(separator = PATH_SEPARATOR_CLIPBOARD)
