package one.meshcore.shared.settings.ui

internal data class RegionDiscoverOutcome(val regionsToSelect: List<String>?, val statusMessage: String?)

/**
 * A non-empty hit opens selection. The status line is read only when nothing new arrived,
 * so a hit does not ask for a message the screen will not show.
 */
internal suspend fun regionDiscoverOutcome(
    newRegions: List<String>,
    statusWhenEmpty: suspend () -> String?,
): RegionDiscoverOutcome {
    val regionsToSelect = newRegions.takeIf { it.isNotEmpty() }
    val statusMessage = if (regionsToSelect == null) statusWhenEmpty() else null
    return RegionDiscoverOutcome(regionsToSelect, statusMessage)
}
