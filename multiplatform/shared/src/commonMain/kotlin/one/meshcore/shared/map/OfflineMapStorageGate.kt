package one.meshcore.shared.map

/**
 * Free-space and aggregate storage helpers for Offline Maps settings.
 * Mirrors iOS `RegionPickerSheet.exceedsAvailableSpace` and storage section totals.
 */
object OfflineMapStorageGate {
    /**
     * True when remaining free space is under the 100 MB floor (iOS `minimumDiskSpaceBytes`),
     * or when the estimate is known and larger than remaining free space. Unknown capacity
     * never blocks. A known-but-tiny volume is blocked even when the estimate is small.
     */
    fun exceedsAvailableSpace(estimatedBytes: Long?, availableBytes: Long?): Boolean {
        if (availableBytes == null) return false
        if (availableBytes < MINIMUM_OFFLINE_MAP_FREE_BYTES) return true
        if (estimatedBytes == null) return false
        return estimatedBytes > availableBytes
    }

    /** iOS `OfflineMapService.minimumDiskSpaceBytes`. */
    const val MINIMUM_OFFLINE_MAP_FREE_BYTES = 100_000_000L

    /** Sum of completed-resource sizes reported on pack rows (MapLibre progress). */
    fun totalStorageBytes(packs: List<OfflineMapPack>): Long = packs.sumOf { it.sizeBytes.coerceAtLeast(0L) }

    /**
     * Whether the region picker's Download action is enabled. Mirrors the iOS `RegionPickerSheet`
     * `.disabled(regionName.isEmpty || exceedsAvailableSpace || !isNetworkAvailable || selectionBounds == nil)`:
     * a name, a selection, room on disk, and a live network are all required, since tile fetch needs the network.
     */
    fun canDownload(
        hasRegionName: Boolean,
        hasSelection: Boolean,
        exceedsAvailableSpace: Boolean,
        isNetworkAvailable: Boolean,
    ): Boolean = hasRegionName && hasSelection && !exceedsAvailableSpace && isNetworkAvailable
}
