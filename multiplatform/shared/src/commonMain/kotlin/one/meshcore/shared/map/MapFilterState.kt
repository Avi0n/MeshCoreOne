package one.meshcore.shared.map

import one.meshcore.shared.model.ContactType

/** Map surface that owns an independent filter preference. Mirrors iOS `MapFilterHost`. */
enum class MapFilterHost {
    MAIN_MAP,
    TRACE_PATH,
    NEIGHBOR_SNR,
    ;

    /** Type-axis toggles (chat / repeater / room) exist only on the main map. */
    val includesTypes: Boolean get() = this == MAIN_MAP
    val includesAdvanced: Boolean get() = this == MAIN_MAP
}

/**
 * Per-host pin filter. JSON keys match the Swift `MapFilterState` property names so an iOS backup
 * restores onto Android without a remap. Mirrors iOS `MapFilterState`.
 */
data class MapFilterState(
    val favoritesOnly: Boolean = false,
    val showDiscovered: Boolean = false,
    val showChat: Boolean = true,
    val showRepeater: Boolean = true,
    val showRoom: Boolean = true,
    val lastHeard: MapLastHeardRange = MapLastHeardRange.ANY,
    val hops: MapHopRange = MapHopRange.ANY,
) {
    /** Discovered layer for pin algebra; Favorites freezes the stored [showDiscovered] field. */
    val effectiveShowDiscovered: Boolean
        get() = !favoritesOnly && showDiscovered

    fun seedEquals(host: MapFilterHost): Boolean {
        val defaults = seed(host)
        return favoritesOnly == defaults.favoritesOnly &&
            showDiscovered == defaults.showDiscovered &&
            (!host.includesTypes || (showChat && showRepeater && showRoom)) &&
            (!host.includesAdvanced || (!lastHeard.isActive && !hops.isActive))
    }

    fun setFavoritesOnly(value: Boolean): MapFilterState = copy(favoritesOnly = value)

    /** No-op while Favorites is on. */
    fun setShowDiscovered(value: Boolean): MapFilterState = if (favoritesOnly) this else copy(showDiscovered = value)

    fun setShowChat(value: Boolean, host: MapFilterHost): MapFilterState = setType(value, host) { copy(showChat = it) }

    fun setShowRepeater(value: Boolean, host: MapFilterHost): MapFilterState =
        setType(value, host) { copy(showRepeater = it) }

    fun setShowRoom(value: Boolean, host: MapFilterHost): MapFilterState = setType(value, host) { copy(showRoom = it) }

    /** Main Map type-axis gate. Favorites bypasses type filtering. */
    fun allowsContactType(type: ContactType): Boolean {
        if (favoritesOnly) return true
        return when (type) {
            ContactType.CHAT -> showChat
            ContactType.REPEATER -> showRepeater
            ContactType.ROOM -> showRoom
        }
    }

    fun sanitized(host: MapFilterHost): MapFilterState {
        val advanced = if (host.includesAdvanced) this else resetAdvanced()
        if (!host.includesTypes || hasAtLeastOneEnabledType) return advanced
        return advanced.copy(showChat = true, showRepeater = true, showRoom = true)
    }

    fun toStorageString(): String = encodeMapFilter(this)

    private fun setType(value: Boolean, host: MapFilterHost, copyWith: (Boolean) -> MapFilterState): MapFilterState {
        if (favoritesOnly) return this
        if (!host.includesTypes) return this
        if (!value) {
            val probe = copyWith(false)
            if (!probe.hasAtLeastOneEnabledType) return this
        }
        return copyWith(value)
    }

    private val hasAtLeastOneEnabledType: Boolean
        get() = showChat || showRepeater || showRoom

    companion object {
        fun seed(host: MapFilterHost): MapFilterState = when (host) {
            MapFilterHost.TRACE_PATH -> MapFilterState(showDiscovered = true)
            MapFilterHost.MAIN_MAP, MapFilterHost.NEIGHBOR_SNR -> MapFilterState()
        }

        fun fromStorageString(raw: String): MapFilterState? {
            if (raw.isEmpty()) return null
            return decodeMapFilter(raw)
        }

        /**
         * Decode [raw] for [host], or the host seed when missing or corrupt. Does not write.
         * Mirrors iOS `MapFilterPreferences.state(fromRaw:host:)`.
         */
        fun fromRaw(raw: String?, host: MapFilterHost): MapFilterState =
            raw?.let(::fromStorageString)?.sanitized(host) ?: seed(host).sanitized(host)

        /**
         * Decode [raw] for the main map. A missing or corrupt string uses the host seed, then adopts
         * a legacy `showDiscoveredNodesOnMap` value when that key was ever written. Mirrors iOS
         * `MapFilterPreferences.resolveMigrating` for `.mainMap` without writing.
         */
        fun resolveMainMap(raw: String?, legacyShowDiscovered: Boolean?): MapFilterState {
            val decoded = raw?.let(::fromStorageString)?.sanitized(MapFilterHost.MAIN_MAP)
            if (decoded != null) return decoded
            val seed = seed(MapFilterHost.MAIN_MAP)
            return if (legacyShowDiscovered == true) seed.copy(showDiscovered = true) else seed
        }
    }
}
