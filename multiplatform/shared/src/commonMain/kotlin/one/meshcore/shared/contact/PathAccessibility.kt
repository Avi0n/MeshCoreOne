package one.meshcore.shared.contact

import one.meshcore.shared.ui.formatTemplate

/**
 * Screen-reader label for the contact-detail Route row. Matches iOS `pathAccessibilityLabel`: flood
 * and zero-hop use dedicated strings; a populated path uses the Route: prefix template over the
 * named-or-hex hop display (not the visible "Route" headline plus footer).
 */
internal fun pathAccessibilityLabel(
    isFloodRouted: Boolean,
    pathHopCount: Int,
    pathDisplay: String,
    copy: PathAccessibilityCopy,
): String = when {
    isFloodRouted -> copy.floodLabel
    pathHopCount == 0 -> copy.directLabel
    else -> formatTemplate(copy.prefixTemplate, listOf(pathDisplay))
}
