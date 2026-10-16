package one.meshcore.shared.map

/**
 * Whether the map should treat the device as offline. [hasInternet] is the
 * `NET_CAPABILITY_INTERNET` bit, or null when there is no active network.
 * Mirrors iOS `OfflineMapService` / `NWPathMonitor` `status != .satisfied`.
 */
fun isNetworkOfflineFromInternetCapability(hasInternet: Boolean?): Boolean = hasInternet != true
