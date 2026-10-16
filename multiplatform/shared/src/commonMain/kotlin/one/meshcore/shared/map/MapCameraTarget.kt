package one.meshcore.shared.map

/**
 * A camera framing request plus the counter that re-fires it, mirroring iOS `cameraRegion` and
 * `cameraRegionVersion`. Requesting the same region twice must move the camera twice, so a second
 * "Center all" after panning away, or re-opening a chat at the same coordinate, is not silently
 * dropped by a region-equality check.
 */
data class MapCameraTarget(val region: MapCameraRegion? = null, val version: Int = 0) {
    /** The next request for [region]; the bumped version makes the host apply it even when unchanged. */
    fun requesting(region: MapCameraRegion): MapCameraTarget = MapCameraTarget(region, version + 1)
}

/**
 * Whether a map host should apply [target] now. A changed [version] always re-applies; hosts whose
 * callers never version their target (they leave it at zero) keep the region-equality dedupe.
 *
 * A host must record the version even when the region turns out to be unusable, so an invalid target
 * cannot retry forever.
 */
fun shouldApplyCameraTarget(
    target: MapCameraRegion?,
    version: Int,
    lastTarget: MapCameraRegion?,
    lastVersion: Int,
): Boolean {
    if (target == null) return false
    if (version != lastVersion) return true
    return target != lastTarget
}
