package one.meshcore.shared.connection

import one.meshcore.shared.ui.ScannedDevice

/**
 * BLE scan-list orchestration matching iOS `RSSIScanTracker`: skip unusable RSSI, smooth successive
 * samples, keep a name when a later packet omits it, and drop radios that stop advertising.
 */
class RssiScanTracker(private val nowMillis: () -> Long) {
    private val devicesById = linkedMapOf<String, ScannedDevice>()
    private val tiers = mutableMapOf<String, BleSignalTier>()
    private val lastSeenMillis = mutableMapOf<String, Long>()

    fun devices(): List<ScannedDevice> = devicesById.values.toList()

    fun signalTier(id: String): BleSignalTier? = tiers[id]

    fun ingest(discovery: ScannedDevice) {
        val rssi = discovery.rssi ?: return
        if (!rssiIsUsable(rssi)) return
        val previous = devicesById[discovery.id]
        val smoothed = smoothRssi(rssi, previous?.rssi)
        val name = discovery.name ?: previous?.name
        devicesById[discovery.id] = ScannedDevice(id = discovery.id, name = name, rssi = smoothed)
        tiers[discovery.id] = bleSignalTier(smoothed, tiers[discovery.id])
        lastSeenMillis[discovery.id] = nowMillis()
    }

    fun expireStale(asOfMillis: Long = nowMillis()) {
        val cutoff = asOfMillis - RSSI_STALE_WINDOW_MS
        val stale = lastSeenMillis.filter { it.value < cutoff }.keys.toList()
        for (id in stale) {
            lastSeenMillis.remove(id)
            devicesById.remove(id)
            tiers.remove(id)
        }
    }

    fun clear() {
        devicesById.clear()
        tiers.clear()
        lastSeenMillis.clear()
    }
}
