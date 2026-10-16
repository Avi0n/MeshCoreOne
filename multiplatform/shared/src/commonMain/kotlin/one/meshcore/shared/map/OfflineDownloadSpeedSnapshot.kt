package one.meshcore.shared.map

/**
 * Consecutive completed-byte snapshot used to derive a live download speed, matching iOS
 * `OfflineMapService` `byteSnapshots` / `downloadSpeeds`.
 */
data class OfflineDownloadSpeedSnapshot(val bytes: Long, val elapsedMs: Long, val speedBytesPerSec: Long?)
