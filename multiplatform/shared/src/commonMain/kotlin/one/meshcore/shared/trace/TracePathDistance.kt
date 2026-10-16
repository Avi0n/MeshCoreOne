package one.meshcore.shared.trace

import one.meshcore.shared.map.LatLng
import one.meshcore.shared.map.haversineMeters

/** Minimum number of hops (start + at least one other node) a distance figure needs. Mirrors iOS. */
private const val MIN_HOPS_FOR_DISTANCE = 2

/**
 * Total trace path distance in meters, or `null` when it cannot be computed. Kotlin port of iOS
 * `TracePathViewModel.totalPathDistance`: prefers the full path (including the local device's start/end
 * legs) when every hop has a location, and falls back to intermediate-repeaters-only when the device
 * itself lacks one (a repeater mid-path missing location still makes the whole figure unavailable).
 */
fun totalPathDistanceMeters(hops: List<TraceHop>): Double? {
    if (hops.size < MIN_HOPS_FOR_DISTANCE) return null

    distanceAlong(hops)?.let { return it }

    val intermediateOnly = hops.filter { !it.isStartNode && !it.isEndNode }
    return distanceAlong(intermediateOnly)
}

/** Summed leg-to-leg great-circle distance for [hops] in order, or `null` if any leg lacks a location. */
private fun distanceAlong(hops: List<TraceHop>): Double? {
    if (hops.size < MIN_HOPS_FOR_DISTANCE) return null
    val legs = hops.zipWithNext { current, next -> legMeters(current, next) }
    return if (legs.any { it == null }) null else legs.filterNotNull().sum()
}

/** Great-circle distance for one leg, or `null` when either end lacks a location. */
private fun legMeters(current: TraceHop, next: TraceHop): Double? {
    val from = current.locationOrNull() ?: return null
    val to = next.locationOrNull() ?: return null
    return haversineMeters(from, to)
}

private fun TraceHop.locationOrNull(): LatLng? = when {
    !hasLocation -> null
    else -> latitude?.let { lat -> longitude?.let { lon -> LatLng(lat, lon) } }
}

/**
 * Names (or hash display strings) of intermediate repeaters lacking a location, for the "why is this
 * unavailable" explanation. Mirrors iOS `repeatersWithoutLocation`.
 */
fun repeatersWithoutLocation(hops: List<TraceHop>): List<String> = hops
    .filter { !it.isStartNode && !it.isEndNode && !it.hasLocation }
    .map { it.resolvedName ?: it.hashDisplayString ?: "?" }

/**
 * Whether [totalPathDistanceMeters] used the intermediate-only fallback because the local device's
 * start/end nodes lack a location. Mirrors iOS `isDistanceUsingFallback`.
 */
fun isDistanceUsingDeviceLocationFallback(hops: List<TraceHop>): Boolean {
    if (totalPathDistanceMeters(hops) == null) return false
    val start = hops.firstOrNull() ?: return false
    val end = hops.lastOrNull() ?: return false
    return !start.hasLocation || !end.hasLocation
}
