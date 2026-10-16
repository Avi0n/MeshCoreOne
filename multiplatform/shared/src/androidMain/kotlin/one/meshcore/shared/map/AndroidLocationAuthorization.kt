package one.meshcore.shared.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

internal fun Context.locationAuthorizationStatus(hasAsked: Boolean = false): LocationAuthorizationStatus =
    locationAuthorizationStatus(
        fineGranted = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION),
        coarseGranted = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION),
        hasAsked = hasAsked,
    )

private fun Context.hasPermission(permission: String): Boolean =
    checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

/**
 * Maps the two location grants to an authorization status. Either precision authorizes, mirroring the
 * iOS rule that any when-in-use authorization counts: an approximate-only grant still drives the map
 * dot, distance sort, and region suggestion, just coarser.
 *
 * An ungranted state is [LocationAuthorizationStatus.DENIED] after this install has already shown the
 * prompt. Before that it is [LocationAuthorizationStatus.NOT_DETERMINED], matching iOS authorization.
 */
internal fun locationAuthorizationStatus(
    fineGranted: Boolean,
    coarseGranted: Boolean,
    hasAsked: Boolean = false,
): LocationAuthorizationStatus = if (fineGranted || coarseGranted) {
    LocationAuthorizationStatus.AUTHORIZED_WHEN_IN_USE
} else if (hasAsked) {
    LocationAuthorizationStatus.DENIED
} else {
    LocationAuthorizationStatus.NOT_DETERMINED
}
