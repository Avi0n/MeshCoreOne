package one.meshcore.shared.contact

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.persistence.toEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DiscoveredNodeStoreTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun upsertUpdatesByPublicKeyAndFilterWorks() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val key = ByteArray(32) { 0x11 }
        assertTrue(
            store.upsert(
                DiscoveredNodeRecord(
                    id = newDiscoveredNodeId(),
                    radioID = radio,
                    publicKey = key,
                    name = "Alpha",
                    typeRawValue = ContactType.CHAT.raw,
                    lastHeardEpochSeconds = 10,
                ),
            ),
        )
        assertFalse(
            store.upsert(
                DiscoveredNodeRecord(
                    id = newDiscoveredNodeId(),
                    radioID = radio,
                    publicKey = key,
                    name = "Alpha-2",
                    typeRawValue = ContactType.CHAT.raw,
                    lastHeardEpochSeconds = 20,
                ),
            ),
        )
        val listed = store.list(radio)
        assertEquals(1, listed.size)
        assertEquals("Alpha-2", listed.single().name)

        store.upsert(
            DiscoveredNodeRecord(
                id = newDiscoveredNodeId(),
                radioID = radio,
                publicKey = ByteArray(32) { 0x22 },
                name = "Rpt",
                typeRawValue = ContactType.REPEATER.raw,
                lastHeardEpochSeconds = 30,
            ),
        )
        val filtered = filterDiscoveredNodes(store.list(radio), DiscoverSegment.REPEATERS, "")
        assertEquals(1, filtered.size)
        assertEquals("Rpt", filtered.single().name)
    }

    @Test
    fun clearRemovesOnlyThatRadio() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val other = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
        store.upsert(
            DiscoveredNodeRecord(
                id = newDiscoveredNodeId(),
                radioID = radio,
                publicKey = ByteArray(32) { 1 },
                name = "A",
                typeRawValue = ContactType.CHAT.raw,
                lastHeardEpochSeconds = 1,
            ),
        )
        store.upsert(
            DiscoveredNodeRecord(
                id = newDiscoveredNodeId(),
                radioID = other,
                publicKey = ByteArray(32) { 2 },
                name = "B",
                typeRawValue = ContactType.CHAT.raw,
                lastHeardEpochSeconds = 1,
            ),
        )
        store.clear(radio)
        assertTrue(store.list(radio).isEmpty())
        assertEquals(1, store.list(other).size)
    }

    @Test
    fun removeDeletesOnlyTheMatchingRow() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val keyA = ByteArray(32) { 0xA1.toByte() }
        val keyB = ByteArray(32) { 0xB2.toByte() }
        store.upsert(
            DiscoveredNodeRecord(
                id = newDiscoveredNodeId(),
                radioID = radio,
                publicKey = keyA,
                name = "A",
                typeRawValue = ContactType.CHAT.raw,
                lastHeardEpochSeconds = 1,
            ),
        )
        store.upsert(
            DiscoveredNodeRecord(
                id = newDiscoveredNodeId(),
                radioID = radio,
                publicKey = keyB,
                name = "B",
                typeRawValue = ContactType.CHAT.raw,
                lastHeardEpochSeconds = 2,
            ),
        )

        store.remove(radio, keyA)

        val listed = store.list(radio)
        assertEquals(1, listed.size)
        assertEquals("B", listed.single().name)
    }

    @Test
    fun removeOfUnknownKeyIsANoOp() = runTest {
        val store = InMemoryDiscoveredNodeStore()
        val key = ByteArray(32) { 0x11 }
        store.upsert(
            DiscoveredNodeRecord(
                id = newDiscoveredNodeId(),
                radioID = radio,
                publicKey = key,
                name = "A",
                typeRawValue = ContactType.CHAT.raw,
                lastHeardEpochSeconds = 1,
            ),
        )

        store.remove(radio, ByteArray(32) { 0x99.toByte() })

        assertEquals(1, store.list(radio).size)
    }

    @Test
    fun filterMatchesByPublicKeyHexPrefix() {
        val node = DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = radio,
            publicKey = byteArrayOf(0xAB.toByte(), 0xCD.toByte()) + ByteArray(30),
            name = "Unrelated Name",
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 1,
        )

        val matchedUpper = filterDiscoveredNodes(listOf(node), DiscoverSegment.ALL, "ABCD")
        val matchedLower = filterDiscoveredNodes(listOf(node), DiscoverSegment.ALL, "abcd")
        val unmatched = filterDiscoveredNodes(listOf(node), DiscoverSegment.ALL, "FFFF")

        assertEquals(listOf(node), matchedUpper)
        assertEquals(listOf(node), matchedLower)
        assertTrue(unmatched.isEmpty())
    }

    /**
     * A search spans every node type; the segment applies only when the query is empty. Filtering by
     * segment first hides matching nodes the operator typed the name of.
     */
    @Test
    fun searchSpansAllTypesAndIgnoresTheSegment() {
        val chat = discoveredNode(name = "Bravo Chat", lastHeard = 1)
        val repeater = DiscoveredNodeRecord(
            id = newDiscoveredNodeId(),
            radioID = radio,
            publicKey = ByteArray(32) { 0x55 },
            name = "Bravo Repeater",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 2,
        )
        val nodes = listOf(chat, repeater)

        val searchedUnderRepeaters = filterDiscoveredNodes(nodes, DiscoverSegment.REPEATERS, "Bravo")
        val searchedUnderRooms = filterDiscoveredNodes(nodes, DiscoverSegment.ROOMS, "Bravo")
        val unsearchedUnderRepeaters = filterDiscoveredNodes(nodes, DiscoverSegment.REPEATERS, "")

        assertEquals(setOf("Bravo Chat", "Bravo Repeater"), searchedUnderRepeaters.map { it.name }.toSet())
        assertEquals(setOf("Bravo Chat", "Bravo Repeater"), searchedUnderRooms.map { it.name }.toSet())
        assertEquals(listOf("Bravo Repeater"), unsearchedUnderRepeaters.map { it.name })
    }

    /**
     * A flood-routed row surfaces the passively-heard inbound advert hops, so it reads "N hops" and sorts
     * with the routed rows instead of dropping to the bottom as unknown.
     */
    @Test
    fun floodRoutedRowFallsBackToTheInboundAdvertHopCount() {
        val heard = discoveredNode(name = "Heard", lastHeard = 1).copy(inboundHopCount = 2)
        val neverHeard = discoveredNode(name = "Unknown", lastHeard = 1)

        assertEquals(2, heard.displayedHopCount)
        assertEquals(ContactRouteKind.Hops(2), heard.routeKind)
        assertEquals(null, neverHeard.displayedHopCount)
        assertEquals(ContactRouteKind.Flood, neverHeard.routeKind)

        val sorted = sortDiscoveredNodes(listOf(neverHeard, heard), NodeSortOrder.HOPS, userLocation = null)
        assertEquals(listOf("Heard", "Unknown"), sorted.map { it.name })
    }

    @Test
    fun sortByNameIsCaseInsensitiveAscending() {
        val nodes = listOf(
            discoveredNode(name = "bravo", lastHeard = 1),
            discoveredNode(name = "Alpha", lastHeard = 2),
            discoveredNode(name = "charlie", lastHeard = 3),
        )
        val sorted = sortDiscoveredNodes(nodes, NodeSortOrder.NAME, userLocation = null)
        assertEquals(listOf("Alpha", "bravo", "charlie"), sorted.map { it.name })
    }

    @Test
    fun sortByLastHeardIsDescending() {
        val nodes = listOf(
            discoveredNode(name = "old", lastHeard = 10),
            discoveredNode(name = "new", lastHeard = 30),
            discoveredNode(name = "mid", lastHeard = 20),
        )
        val sorted = sortDiscoveredNodes(nodes, NodeSortOrder.LAST_HEARD, userLocation = null)
        assertEquals(listOf("new", "mid", "old"), sorted.map { it.name })
    }

    @Test
    fun sortByHopsPutsFloodRoutedLastAndFallsBackToName() {
        // Out-path-length byte, hash-size mode 0 (bits 7-6 = 00): the raw hop count is the byte value.
        val directOutPathLength = 0
        val twoHopOutPathLength = 2
        val direct = discoveredNode(name = "Direct", lastHeard = 1, outPathLength = directOutPathLength)
        val twoHop = discoveredNode(name = "TwoHop", lastHeard = 1, outPathLength = twoHopOutPathLength)
        val floodA = discoveredNode(name = "FloodB", lastHeard = 1)
        val floodB = discoveredNode(name = "FloodA", lastHeard = 1)
        val sorted = sortDiscoveredNodes(
            listOf(floodA, twoHop, floodB, direct),
            NodeSortOrder.HOPS,
            userLocation = null,
        )
        assertEquals(listOf("Direct", "TwoHop", "FloodA", "FloodB"), sorted.map { it.name })
    }

    @Test
    fun sortByDistanceFallsBackToLastHeardWithoutUserLocation() {
        val nodes = listOf(
            discoveredNode(name = "old", lastHeard = 10),
            discoveredNode(name = "new", lastHeard = 30),
        )
        val sorted = sortDiscoveredNodes(nodes, NodeSortOrder.DISTANCE, userLocation = null)
        assertEquals(listOf("new", "old"), sorted.map { it.name })
    }

    @Test
    fun sortByDistancePrefersLocatedThenNearestThenName() {
        val here = UserLocation(latitude = 0.0, longitude = 0.0)
        val near = discoveredNode(name = "Near", lastHeard = 1, latitude = 0.01, longitude = 0.0)
        val far = discoveredNode(name = "Far", lastHeard = 1, latitude = 1.0, longitude = 0.0)
        val unlocated = discoveredNode(name = "Unlocated", lastHeard = 1)
        val sorted = sortDiscoveredNodes(listOf(unlocated, far, near), NodeSortOrder.DISTANCE, userLocation = here)
        assertEquals(listOf("Near", "Far", "Unlocated"), sorted.map { it.name })
    }

    @Test
    fun byteArraysCompareByContent() {
        val record = DiscoveredNodeRecord(
            id = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
            radioID = radio,
            publicKey = ByteArray(32) { 0x11 },
            name = "Alpha",
            typeRawValue = ContactType.CHAT.raw,
            lastHeardEpochSeconds = 10,
            lastAdvertTimestamp = 20,
            latitude = 1.0,
            longitude = 2.0,
            outPathLength = 1,
            outPath = byteArrayOf(0x0A),
            inboundHopCount = 2,
            inboundHopAdvertTimestamp = 30,
        )
        val sameBytes = record.copy(
            publicKey = record.publicKey.copyOf(),
            outPath = record.outPath.copyOf(),
        )
        assertEquals(record, sameBytes)
        assertEquals(record.hashCode(), sameBytes.hashCode())
        assertEquals(record.toEntity(), sameBytes.toEntity())

        val otherKey = record.copy(publicKey = ByteArray(32) { 0x22 })
        assertNotEquals(record, otherKey)
        assertNotEquals(record.toEntity(), otherKey.toEntity())

        val otherPath = record.copy(outPath = byteArrayOf(0x0B))
        assertNotEquals(record, otherPath)
        assertNotEquals(record.toEntity(), otherPath.toEntity())
    }

    private fun discoveredNode(
        name: String,
        lastHeard: Long,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
        outPathLength: Int = CONTACT_FLOOD_PATH_SENTINEL,
    ): DiscoveredNodeRecord = DiscoveredNodeRecord(
        id = newDiscoveredNodeId(),
        radioID = radio,
        publicKey = ByteArray(32) { name.hashCode().toByte() },
        name = name,
        typeRawValue = ContactType.CHAT.raw,
        lastHeardEpochSeconds = lastHeard,
        latitude = latitude,
        longitude = longitude,
        outPathLength = outPathLength,
    )
}
