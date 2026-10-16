package one.meshcore.shared.tools

import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.MapCameraRegion
import one.meshcore.shared.map.boundingRegion
import one.meshcore.shared.map.userLocateCameraRegion

fun LineOfSightViewModel.zoomToShowBothPoints() {
    val a = pointA?.coordinate ?: return
    val b = pointB?.coordinate ?: return
    listOf(a, b).boundingRegion()?.let { setCameraRegion(it) }
}

fun LineOfSightViewModel.consumeCameraTarget(version: Int = cameraTargetVersion) {
    if (version != cameraTargetVersion) return
    cameraTarget = null
}

/** Frames a tight region on [coordinate] (iOS LOS locate, no radio fallback). */
fun LineOfSightViewModel.frameOnUser(coordinate: LatLng) {
    setCameraRegion(userLocateCameraRegion(coordinate))
}

internal fun LineOfSightViewModel.setCameraRegion(region: MapCameraRegion) {
    cameraRegion = region
    cameraTarget = region
    cameraTargetVersion++
    bump()
}

fun LineOfSightViewModel.acceptUserCameraRegion(region: MapCameraRegion) {
    cameraRegion = region
    hasUserAdjustedCamera = true
    bump()
}

internal fun LineOfSightViewModel.applyInitialRepeaterCameraFitIfNeeded() {
    if (hasUserAdjustedCamera || cameraTargetVersion > 0) {
        hasAppliedInitialCameraFit = true
        return
    }
    if (hasAppliedInitialCameraFit || repeatersWithLocation.isEmpty()) return
    hasAppliedInitialCameraFit = true
    centerOnAllRepeaters()
}

fun LineOfSightViewModel.restoreCameraAfterMapRecreation() {
    cameraRegion?.let { setCameraRegion(it) }
}
