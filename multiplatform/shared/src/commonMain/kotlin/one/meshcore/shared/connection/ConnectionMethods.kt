package one.meshcore.shared.connection

import one.meshcore.shared.backup.ConnectionMethod

/**
 * Merges freshly-observed reachability descriptors into a device's existing ones, replacing by
 * transport type — the exact semantics of Swift `ConnectionManager.createDevice`. This is the "how to
 * reach the radio" axis, kept strictly separate from "which radio" (the persisted `lastConnectedDeviceID`).
 *
 * For each incoming method, every existing method of the *same* transport type is dropped and the new
 * one appended; methods of *other* types are untouched. So a radio reachable both over BLE and WiFi
 * keeps one descriptor of each, and reconnecting over WiFi refreshes only the WiFi host/port. Collapsing
 * the two axes — or wholesale-replacing the list on a partial connect — is the bug that once made macOS
 * BLE radios invisible to auto-reconnect.
 */
fun mergeConnectionMethods(
    existing: List<ConnectionMethod>,
    incoming: List<ConnectionMethod>,
): List<ConnectionMethod> {
    val merged = existing.toMutableList()
    for (method in incoming) {
        merged.removeAll { it.sameTransportType(method) }
        merged += method
    }
    return merged
}

private fun ConnectionMethod.sameTransportType(other: ConnectionMethod): Boolean = when (this) {
    is ConnectionMethod.WiFi -> other is ConnectionMethod.WiFi
    is ConnectionMethod.Bluetooth -> other is ConnectionMethod.Bluetooth
}
