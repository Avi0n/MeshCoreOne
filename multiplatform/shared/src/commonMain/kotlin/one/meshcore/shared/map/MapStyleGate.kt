package one.meshcore.shared.map

/**
 * Why a map style is unavailable, so the picker can disable it and explain the reason. Mirrors the two
 * cases the iOS `MapControlsToolbar.disabledReason(for:)` distinguishes.
 */
enum class MapStyleDisabledReason {
    /** The style needs a live network route and the device is offline. */
    REQUIRES_NETWORK,

    /** The style has no downloaded offline pack covering the current area. */
    NO_OFFLINE_COVERAGE,
}

/**
 * Pure gating for the map-style picker. A style stays enabled while the network is up; offline, it is
 * available only when it needs no network and a completed offline pack backs its layer. Mirrors iOS
 * `MapControlsToolbar.isDisabled(_:)` / `disabledReason(for:)`. The caller answers [hasOfflineCoverage]
 * for the visible viewport (`OfflineMapPackRegistry.hasCompletedPack`), so a pack downloaded for another
 * region cannot leave a style selectable over a blank map.
 */
object MapStyleGate {
    fun isDisabled(style: MapStyleSelection, isNetworkAvailable: Boolean, hasOfflineCoverage: Boolean): Boolean =
        !isNetworkAvailable && (style.requiresNetwork || !hasOfflineCoverage)

    fun disabledReason(
        style: MapStyleSelection,
        isNetworkAvailable: Boolean,
        hasOfflineCoverage: Boolean,
    ): MapStyleDisabledReason? = when {
        !isDisabled(style, isNetworkAvailable, hasOfflineCoverage) -> null
        style.requiresNetwork -> MapStyleDisabledReason.REQUIRES_NETWORK
        else -> MapStyleDisabledReason.NO_OFFLINE_COVERAGE
    }
}
