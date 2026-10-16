package one.meshcore.shared.map.ui

/** One outbound-path row: Flood routing, or hop count as Path Length. Mirrors iOS `ContactDetailSheet`. */
internal data class MapOutboundPathCopy(val label: String, val value: String)

/** Localized strings the outbound-path row chooses between. */
internal data class MapOutboundPathLabels(
    val routingLabel: String,
    val floodValue: String,
    val pathLengthLabel: String,
    val hopSingular: String,
    val hopsTemplate: String,
)
