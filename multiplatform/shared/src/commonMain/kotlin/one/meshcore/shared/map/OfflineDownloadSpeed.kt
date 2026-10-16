package one.meshcore.shared.map

/**
 * Updates the speed from a new completed-byte count. A sample shorter than half a second keeps the
 * previous speed; unchanged bytes zero it, matching iOS `loadPacks`.
 */
fun nextOfflineDownloadSpeed(
    previous: OfflineDownloadSpeedSnapshot?,
    currentBytes: Long,
    currentElapsedMs: Long,
    minElapsedMs: Long = OFFLINE_DOWNLOAD_SPEED_MIN_ELAPSED_MS,
): OfflineDownloadSpeedSnapshot {
    if (previous == null) {
        return OfflineDownloadSpeedSnapshot(currentBytes, currentElapsedMs, speedBytesPerSec = null)
    }
    val elapsedMs = currentElapsedMs - previous.elapsedMs
    val speed = when {
        elapsedMs > minElapsedMs && currentBytes > previous.bytes ->
            ((currentBytes - previous.bytes) * MILLIS_PER_SECOND) / elapsedMs
        currentBytes == previous.bytes -> 0L
        else -> previous.speedBytesPerSec
    }
    return OfflineDownloadSpeedSnapshot(currentBytes, currentElapsedMs, speed)
}

/** iOS only shows speed while the pack is active and the rate is positive. */
fun displayedOfflineDownloadSpeed(completed: Boolean, paused: Boolean, speedBytesPerSec: Long?): Long? =
    speedBytesPerSec?.takeIf { !completed && !paused && it > 0 }

/** Region name and layer on an offline-pack row, comma-joined. */
fun offlinePackHeadline(displayName: String, layerLabel: String) = "$displayName, $layerLabel"

/** Storage Used is the offline db file length (iOS `MLNOfflineStorage.databaseURL`), not a pack sum. */
fun offlineMapStorageUsedBytes(databaseFileLength: Long?): Long = databaseFileLength?.coerceAtLeast(0L) ?: 0L

private const val OFFLINE_DOWNLOAD_SPEED_MIN_ELAPSED_MS = 500L
private const val MILLIS_PER_SECOND = 1_000L
