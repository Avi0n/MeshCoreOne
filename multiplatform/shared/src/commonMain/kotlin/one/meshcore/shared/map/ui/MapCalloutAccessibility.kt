package one.meshcore.shared.map.ui

/** iOS `DiscoveredNodeCalloutContent` headline: `"Discovered, {name}"`. */
internal fun discoveredCalloutHeadlineContentDescription(discoveredLabel: String, name: String): String =
    "$discoveredLabel, $name"
