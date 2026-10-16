package one.meshcore.shared.settings

/**
 * Location inclusion policy for advertisements. Raw values pinned to the Swift
 * `AdvertLocationPolicy` enum so Device-row and config import stay portable.
 */
enum class AdvertLocationPolicy(val rawValue: Int) {
    NONE(0),
    SHARE(1),
    PREFS(2),
    ;

    val isEnabled: Boolean
        get() = this != NONE

    companion object {
        fun fromRaw(raw: Int): AdvertLocationPolicy? = entries.firstOrNull { it.rawValue == raw }

        /**
         * The share-publicly toggle with no GPS context: enabled → [PREFS], disabled → [NONE]. Callers
         * that know the location settings should use [forSharePublicly] instead — a node fed by its own
         * GPS advertises a stale stored coordinate under [PREFS].
         */
        fun fromSharePublicly(enabled: Boolean): AdvertLocationPolicy = if (enabled) PREFS else NONE

        /**
         * The policy the Location screen writes. [SHARE] tells the radio to advertise its live GPS fix,
         * which is only right when the node's own GPS is the source; every other case advertises the
         * stored coordinate ([PREFS]).
         */
        fun forSharePublicly(
            share: Boolean,
            autoUpdateLocation: Boolean,
            deviceHasGps: Boolean,
            gpsSource: GpsSource,
        ): AdvertLocationPolicy {
            if (!share) return NONE
            if (autoUpdateLocation && deviceHasGps && gpsSource == GpsSource.DEVICE) return SHARE
            return PREFS
        }
    }
}
