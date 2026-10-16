package one.meshcore.shared.map

/**
 * Pin clustering: the parameters of the clustered pin source, its count-bubble sizing, and the split
 * between pins that merge into clusters and pins that always stand alone. Mirrors the iOS clustered
 * `MLNShapeSource` options and its clustered/fixed source pair.
 */
object MapClustering {
    /** Screen radius, in pixels, within which pins merge into one cluster. */
    const val CLUSTER_RADIUS_PX = 44

    /** Above this zoom level every pin renders on its own. */
    const val MAX_ZOOM_FOR_CLUSTERING = 14

    /** Extra zoom levels applied on top of a cluster's own expansion zoom when a tap drills into it. */
    const val EXPANSION_ZOOM_BOOST = 2.0

    /** Half the edge of the square hit rectangle a cluster tap queries, matching [CLUSTER_RADIUS_PX]. */
    const val CLUSTER_HIT_HALF_EDGE_PX = CLUSTER_RADIUS_PX / 2

    /** How many stops [CIRCLE_RADIUS_STEPS] publishes. The map style reads each one. */
    const val CIRCLE_RADIUS_STOP_COUNT = 4

    /** Cluster-circle radius per lowest point count that earns it; the stops of a step ramp. */
    val CIRCLE_RADIUS_STEPS: List<ClusterRadiusStep> = listOf(
        ClusterRadiusStep(pointCount = 0, radiusPx = 18f),
        ClusterRadiusStep(pointCount = 50, radiusPx = 24f),
        ClusterRadiusStep(pointCount = 100, radiusPx = 30f),
        ClusterRadiusStep(pointCount = 200, radiusPx = 38f),
    )

    /**
     * The zoom a cluster tap should move to, or null when the cluster cannot be expanded. The source
     * reports a negative expansion zoom for a cluster it cannot break apart, and moving there would
     * send the camera to a negative zoom level.
     */
    fun expansionZoom(clusterExpansionZoom: Int): Double? =
        if (clusterExpansionZoom < 0) null else clusterExpansionZoom + EXPANSION_ZOOM_BOOST
}

/** One stop of the cluster-circle radius ramp: [radiusPx] applies from [pointCount] upwards. */
data class ClusterRadiusStep(val pointCount: Int, val radiusPx: Float)

/** The two pin sources a map surface renders: pins that cluster, and pins that never do. */
data class MapPointPartition(val clusterable: List<MapPoint>, val fixed: List<MapPoint>)

/**
 * Splits pins by [MapPoint.isClusterable]. Node pins cluster when [clusteringEnabled] is true; a dropped
 * pin, a path hop, or any tool overlay marker stays exactly where it was placed, so toggling one of
 * them never re-clusters the rest. With clustering off, every pin is fixed.
 */
fun List<MapPoint>.partitionByClusterable(clusteringEnabled: Boolean = true): MapPointPartition {
    val (clusterable, fixed) = partition { clusteringEnabled && it.isClusterable }
    return MapPointPartition(clusterable = clusterable, fixed = fixed)
}
