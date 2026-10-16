package one.meshcore.shared.map

/**
 * Fixed geometry for the chat map thumbnail. Shared by the renderer (snapshot size) and the fragment
 * view (frame). Size is intentionally not part of [MapSnapshotRequest] so it never shards the cache.
 * Mirrors iOS `MapSnapshotLayout`.
 */
object MapSnapshotLayout {
    const val WIDTH_PX = 250
    const val HEIGHT_PX = 150
    const val CORNER_RADIUS_DP = 12
    const val ZOOM_LEVEL = 14.0

    /** Device-independent density multiplier for the GL snapshot buffer. */
    const val PIXEL_RATIO = 2f
}
