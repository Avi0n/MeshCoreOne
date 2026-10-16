package one.meshcore.shared.model

/**
 * A channel's flood-scope preference. Mirrors Swift `ChannelFloodScope`. Orthogonal to the
 * device-level default: [Inherit] applies the device default at send time, [AllRegions] overrides it
 * to broadcast everywhere, [Region] pins one named region.
 */
sealed interface ChannelFloodScope {
    data object Inherit : ChannelFloodScope

    data object AllRegions : ChannelFloodScope

    data class Region(val name: String) : ChannelFloodScope
}

/**
 * Bridges the two-field on-disk representation (`floodScopeModeRawValue` + `regionScope`) and the
 * [ChannelFloodScope] enum. Mode raw values are pinned strings matching Swift so a rename cannot
 * change the persisted/backup format. Invalid combinations are not representable: `specific` mode
 * with a null/empty region recomposes to [ChannelFloodScope.Inherit], exactly as Swift does.
 */
object ChannelFloodScopeStorage {
    const val MODE_INHERIT = "inherit"
    const val MODE_ALL_REGIONS = "allRegions"
    const val MODE_SPECIFIC = "specific"

    fun recompose(modeRawValue: String, regionName: String?): ChannelFloodScope = when (modeRawValue) {
        MODE_ALL_REGIONS -> ChannelFloodScope.AllRegions
        MODE_SPECIFIC ->
            regionName
                ?.takeIf { it.isNotEmpty() }
                ?.let { ChannelFloodScope.Region(it) }
                ?: ChannelFloodScope.Inherit
        else -> ChannelFloodScope.Inherit
    }

    fun decompose(scope: ChannelFloodScope): Pair<String, String?> = when (scope) {
        ChannelFloodScope.Inherit -> MODE_INHERIT to null
        ChannelFloodScope.AllRegions -> MODE_ALL_REGIONS to null
        is ChannelFloodScope.Region -> MODE_SPECIFIC to scope.name
    }
}
