package one.meshcore.shared.map.ui

import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.ui.formatTemplate

/**
 * Copy for the map contact sheet's outbound-path section. Flood-routed contacts show Routing / Flood;
 * a stored path shows Path Length and either "1 hop" or the plural hops template.
 */
internal fun mapOutboundPathCopy(
    isFloodRouted: Boolean,
    hopCount: Int,
    labels: MapOutboundPathLabels,
): MapOutboundPathCopy = if (isFloodRouted) {
    MapOutboundPathCopy(label = labels.routingLabel, value = labels.floodValue)
} else {
    val hops = if (hopCount == 1) {
        labels.hopSingular
    } else {
        formatTemplate(labels.hopsTemplate, listOf(hopCount))
    }
    MapOutboundPathCopy(label = labels.pathLengthLabel, value = hops)
}

/** Public key as uppercase hex with a space between bytes, matching iOS `uppercaseHexString(separator:)`. */
internal fun spacedUppercaseHex(bytes: ByteArray): String =
    bytes.toUppercaseHex().chunked(HEX_CHARS_PER_BYTE).joinToString(" ")

private const val HEX_CHARS_PER_BYTE = 2
