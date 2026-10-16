package one.meshcore.shared.chat.pathdetail

import one.meshcore.shared.backup.MessageDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.chat.pathHops
import one.meshcore.shared.chat.pathString
import one.meshcore.shared.chat.pathStringForClipboard
import one.meshcore.shared.map.LatLng

/** Which row of the path detail a [PathHopRow] represents. */
sealed interface PathHopType {
    /** The originating node. */
    data object Sender : PathHopType

    /** An intermediate repeater, 1-based in path order. */
    data class Intermediate(val number: Int) : PathHopType

    /** The receiving node (the only row that carries an SNR). */
    data object Receiver : PathHopType
}

/**
 * One row of the message path detail: a node's resolved [nodeName] (null until resolved), its [nodeID]
 * (the hop hex, or the sender's id; null for the receiver), the [snr] carried only by the receiver row, and
 * its resolved [location] (null when the node's position is unknown) — feeds the path map view.
 */
data class PathHopRow(
    val type: PathHopType,
    val nodeName: String?,
    val nodeID: String?,
    val snr: Double?,
    val location: LatLng? = null,
    val matchKind: NodeNameMatchKind = NodeNameMatchKind.EXACT,
)

/**
 * Name and location resolution for one path-detail build. Location lookups default to unknown, and match
 * kinds default to exact, so a caller that only has names still gets sender, hop, and receiver rows.
 */
data class MessagePathResolution(
    val senderName: String?,
    val senderNodeID: String?,
    val receiverName: String?,
    val resolveRepeaterName: (ByteArray) -> String?,
    val senderLocation: LatLng? = null,
    val receiverLocation: LatLng? = null,
    val resolveRepeaterLocation: (ByteArray) -> LatLng? = { null },
    val resolveRepeaterMatchKind: (ByteArray) -> NodeNameMatchKind = { NodeNameMatchKind.EXACT },
    val senderMatchKind: NodeNameMatchKind = NodeNameMatchKind.EXACT,
)

/**
 * The derived data for the message path detail view: whether a path is [available] at all, the ordered
 * [rows] (sender, intermediate repeaters, receiver), and the copy strings. Node-name resolution is injected
 * because it depends on the live contact / discovered-repeater tables; the structural derivation — the hop
 * order, which row owns the SNR, and the hex path strings — is pure. Mirrors the iOS `MessagePathContent`.
 *
 * There is no "Direct" row: a path with zero intermediate hops simply renders sender + receiver.
 * ("Direct" elsewhere means the direct out-path routing mode, not a zero-hop path.)
 */
data class MessagePathDisplay(
    val available: Boolean,
    val rows: List<PathHopRow>,
    val hasIntermediateHops: Boolean,
    val pathString: String,
    val pathStringForClipboard: String,
) {
    /**
     * Intermediate-hop count from the message path, including hops too ambiguous to plot. A missing
     * `pathNodes` yields 0, which matches a genuine direct message and firmware that omits path data.
     */
    val hopCount: Int
        get() = rows.count { it.type is PathHopType.Intermediate }

    companion object {
        /**
         * Builds the path detail rows for [message]. A message with no path nodes is [available] `false`
         * (the empty state). [MessagePathResolution] supplies the caller's live names and locations.
         * The receiver row carries `message.snr`; sender and intermediate rows carry no SNR.
         */
        fun build(message: MessageDto, resolution: MessagePathResolution): MessagePathDisplay {
            if (message.pathNodes == null) {
                return MessagePathDisplay(
                    available = false,
                    rows = emptyList(),
                    hasIntermediateHops = false,
                    pathString = "",
                    pathStringForClipboard = "",
                )
            }

            val hops = message.pathHops
            val rows = buildList {
                add(
                    PathHopRow(
                        PathHopType.Sender,
                        nodeName = resolution.senderName,
                        nodeID = resolution.senderNodeID,
                        snr = null,
                        location = resolution.senderLocation,
                        matchKind = resolution.senderMatchKind,
                    ),
                )
                hops.forEachIndexed { index, hop ->
                    add(
                        PathHopRow(
                            type = PathHopType.Intermediate(index + 1),
                            nodeName = resolution.resolveRepeaterName(hop.raw),
                            nodeID = hop.hex,
                            snr = null,
                            location = resolution.resolveRepeaterLocation(hop.raw),
                            matchKind = resolution.resolveRepeaterMatchKind(hop.raw),
                        ),
                    )
                }
                add(
                    PathHopRow(
                        PathHopType.Receiver,
                        nodeName = resolution.receiverName,
                        nodeID = null,
                        snr = message.snr,
                        location = resolution.receiverLocation,
                    ),
                )
            }

            return MessagePathDisplay(
                available = true,
                rows = rows,
                hasIntermediateHops = hops.isNotEmpty(),
                pathString = message.pathString,
                pathStringForClipboard = message.pathStringForClipboard,
            )
        }
    }
}
