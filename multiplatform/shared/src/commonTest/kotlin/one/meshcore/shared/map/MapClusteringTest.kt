package one.meshcore.shared.map

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pin clustering: the parameters pinned to the iOS source options, the clusterable/fixed split, and the
 * zoom a cluster tap drills to.
 */
class MapClusteringTest {
    private fun point(id: String, clusterable: Boolean) = MapPoint(
        id = id,
        coordinate = LatLng(37.33, -122.03),
        pinStyle = if (clusterable) PinStyle.CONTACT_CHAT else PinStyle.DROPPED_PIN,
        label = null,
        isClusterable = clusterable,
    )

    @Test
    fun clusterParametersMatchTheIosSourceOptions() {
        assertEquals(44, MapClustering.CLUSTER_RADIUS_PX)
        assertEquals(14, MapClustering.MAX_ZOOM_FOR_CLUSTERING)
        assertEquals(2.0, MapClustering.EXPANSION_ZOOM_BOOST)
    }

    @Test
    fun circleRadiusStepsMatchTheIosStops() {
        assertEquals(
            listOf(0 to 18f, 50 to 24f, 100 to 30f, 200 to 38f),
            MapClustering.CIRCLE_RADIUS_STEPS.map { it.pointCount to it.radiusPx },
        )
        assertEquals(MapClustering.CIRCLE_RADIUS_STOP_COUNT, MapClustering.CIRCLE_RADIUS_STEPS.size)
    }

    @Test
    fun clusterTapZoomsPastTheExpansionZoom() {
        assertEquals(9.0, MapClustering.expansionZoom(7))
    }

    @Test
    fun clusterThatCannotExpandReportsNoZoom() {
        assertNull(MapClustering.expansionZoom(-1))
    }

    @Test
    fun clusteringOffPutsEveryPinOnTheFixedSource() {
        val partition = listOf(
            point("contact-a", clusterable = true),
            point("dropped", clusterable = false),
        ).partitionByClusterable(clusteringEnabled = false)

        assertEquals(emptyList(), partition.clusterable.map { it.id })
        assertEquals(listOf("contact-a", "dropped"), partition.fixed.map { it.id })
    }

    @Test
    fun nodePinsClusterAndDroppedPinsDoNot() {
        val partition = listOf(
            point("contact-a", clusterable = true),
            point("dropped", clusterable = false),
            point("contact-b", clusterable = true),
        ).partitionByClusterable()

        assertEquals(listOf("contact-a", "contact-b"), partition.clusterable.map { it.id })
        assertEquals(listOf("dropped"), partition.fixed.map { it.id })
    }

    @Test
    fun contactPinsAreTheOnesThatCluster() {
        val contacts = MapNodesBuilder.pointsFromContacts(listOf(locatedContact()))
        assertEquals(contacts, contacts.partitionByClusterable().clusterable)
        assertEquals(emptyList(), contacts.partitionByClusterable().fixed)
    }

    private fun locatedContact(): ContactDto = ContactDto(
        id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
        publicKey = ByteArray(32) { 1 },
        name = "Node",
        typeRawValue = 1,
        flags = 0,
        outPathLength = 255,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 37.77,
        longitude = -122.42,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )
}
