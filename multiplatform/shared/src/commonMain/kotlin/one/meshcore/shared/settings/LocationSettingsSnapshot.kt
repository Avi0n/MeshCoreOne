package one.meshcore.shared.settings

/**
 * Read model for Settings → Location: stored coordinates and advert location policy from the Device row.
 * Map picker / auto-update GPS / device GPS toggles land with the map and platform GPS slices.
 */
data class LocationSettingsSnapshot(val latitude: Double, val longitude: Double, val advertLocationPolicyRaw: Int) {
    val hasLocation: Boolean
        get() = latitude != 0.0 || longitude != 0.0

    /**
     * Any non-zero policy byte shares a position, mirroring Swift `Device.sharesLocationPublicly`.
     * Node-config import passes an unmodeled byte through verbatim, so treating it as "not sharing"
     * would under-report a node that is broadcasting.
     */
    val shareLocationPublicly: Boolean
        get() = advertLocationPolicyRaw > AdvertLocationPolicy.NONE.rawValue
}
