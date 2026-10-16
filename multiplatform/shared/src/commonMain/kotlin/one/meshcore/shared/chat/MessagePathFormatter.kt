package one.meshcore.shared.chat

import one.meshcore.shared.backup.MessageDto

/** Comma-separated hop hex chain longer than this collapses to a first/last preview. */
private const val MAX_INLINE_HOP_NODES = 6

/** Hop count shown at each edge of a collapsed hop chain. */
private const val TRUNCATED_EDGE_HOP_COUNT = 3

/** Ellipsis joining the two edges of a collapsed hop chain. */
private const val TRUNCATION_ELLIPSIS = "…"

/**
 * Formats a message's routing path for the compact inline label under a message bubble (e.g. "Direct",
 * "Flood", "A3,7F,42", or "A3,7F,42…B2,C1,D0"). Kotlin port of the Swift `MessagePathFormatter`; string
 * lookups are injected so this stays a pure function callable from the off-thread item builder.
 */
object MessagePathFormatter {
    fun format(message: MessageDto, directLabel: String, floodLabel: String): String {
        // Destination marker: a single 0xFF byte indicates a direct message with no intermediate hops.
        val rawNodes = message.pathNodes
        val isDestinationMarkerOnly = rawNodes != null && rawNodes.size == 1 && rawNodes[0] == DESTINATION_MARKER
        val nodes = message.pathNodesHex
        return when {
            message.isDirectRouted || isDestinationMarkerOnly -> directLabel
            nodes.isEmpty() -> floodLabel
            nodes.size > MAX_INLINE_HOP_NODES -> {
                val first = nodes.take(TRUNCATED_EDGE_HOP_COUNT).joinToString(separator = ",")
                val last = nodes.takeLast(TRUNCATED_EDGE_HOP_COUNT).joinToString(separator = ",")
                "$first$TRUNCATION_ELLIPSIS$last"
            }
            else -> nodes.joinToString(separator = ",")
        }
    }

    private const val DESTINATION_MARKER: Byte = 0xFF.toByte()
}
