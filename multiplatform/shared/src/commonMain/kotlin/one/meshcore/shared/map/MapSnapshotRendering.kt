package one.meshcore.shared.map

/**
 * Platform map-thumbnail renderer. Returns PNG (or JPEG) bytes for the request, or null on failure.
 * Android implements this with MapLibre [org.maplibre.android.snapshotter.MapSnapshotter]; desktop has
 * no first-party MapLibre JVM binding and leaves the store on the coordinate-card fallback.
 */
fun interface MapSnapshotRendering {
    suspend fun render(request: MapSnapshotRequest): ByteArray?
}
