package one.meshcore.shared.map

fun MapFilterState.activeAdvancedDimensions(host: MapFilterHost): List<MapAdvancedFilterDimension> =
    if (!host.includesAdvanced) {
        emptyList()
    } else {
        MapAdvancedFilterDimension.entries.filter {
            when (it) {
                MapAdvancedFilterDimension.LAST_HEARD -> lastHeard.isActive
                MapAdvancedFilterDimension.HOPS -> hops.isActive
            }
        }
    }

fun MapFilterState.allowsAdvanced(heardAt: Long?, hops: Int?, now: Long): Boolean =
    lastHeard.contains(heardAt, now) && this.hops.contains(hops)

fun MapFilterState.clear(dimension: MapAdvancedFilterDimension): MapFilterState = when (dimension) {
    MapAdvancedFilterDimension.LAST_HEARD -> copy(lastHeard = MapLastHeardRange.ANY)
    MapAdvancedFilterDimension.HOPS -> copy(hops = MapHopRange.ANY)
}

fun MapFilterState.resetAdvanced(): MapFilterState = copy(lastHeard = MapLastHeardRange.ANY, hops = MapHopRange.ANY)
