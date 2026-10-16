package one.meshcore.shared.contact

import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Discover row's hop chain, which the iOS row shows beside the hop count so two nodes advertising the
 * same name can be told apart by the route they arrived on.
 */
class DiscoveredNodeDisplayTest {
    @Test
    fun idPrefixWidensWithTheRadiosPathHashSize() {
        val node = node(
            outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
            outPath = ByteArray(0),
        ).copy(publicKey = byteArrayOf(0xA3.toByte(), 0x7F, 0x42, 0x11))

        assertEquals("A3", node.idPrefixHex(1))
        assertEquals("A37F", node.idPrefixHex(2))
        assertEquals("A37F42", node.idPrefixHex(3))
    }

    @Test
    fun pathNodesHexSplitsTheRouteAtTheEncodedHashWidth() {
        val node = node(
            outPathLength = PathEncoding.encodePathLen(hashSize = 2, hopCount = 3),
            outPath = byteArrayOf(0x1A, 0x2B, 0x3C, 0x4D, 0x5E, 0x6F),
        )

        assertEquals(2, node.pathHashSize)
        assertEquals(6, node.pathByteLength)
        assertEquals(listOf("1A2B", "3C4D", "5E6F"), node.pathNodesHex)
    }

    @Test
    fun pathNodesHexIsEmptyForAFloodRoutedNode() {
        val node = node(outPathLength = CONTACT_FLOOD_PATH_SENTINEL, outPath = byteArrayOf(0x1A, 0x2B))

        assertTrue(node.isFloodRouted)
        assertTrue(node.pathNodesHex.isEmpty())
    }

    @Test
    fun pathNodesHexStopsAtTheDeclaredByteLength() {
        val node = node(
            outPathLength = PathEncoding.encodePathLen(hashSize = 1, hopCount = 2),
            outPath = byteArrayOf(0x1A, 0x2B, 0x3C, 0x4D),
        )

        assertEquals(listOf("1A", "2B"), node.pathNodesHex)
    }

    @Test
    fun hopsRouteUsesBareCountAndShowsThePathIcon() {
        val node = node(
            outPathLength = PathEncoding.encodePathLen(hashSize = 1, hopCount = 2),
            outPath = byteArrayOf(0x1A, 0x2B),
        )

        assertEquals("2", node.routeKind.discoveryHopLabel())
        assertTrue(node.showsDiscoveryPathIcon)
    }

    @Test
    fun directAndFloodKeepLocalizedTokensAndHideThePathIcon() {
        val direct = node(
            outPathLength = PathEncoding.encodePathLen(hashSize = 1, hopCount = 0),
            outPath = ByteArray(0),
        )
        assertEquals(null, direct.routeKind.discoveryHopLabel())
        assertEquals(false, direct.showsDiscoveryPathIcon)

        val flood = node(outPathLength = CONTACT_FLOOD_PATH_SENTINEL, outPath = ByteArray(0))
        assertEquals(null, flood.routeKind.discoveryHopLabel())
        assertEquals(false, flood.showsDiscoveryPathIcon)
    }

    @Test
    fun shortPathsJoinWholeAndLongPathsElideTheMiddle() {
        val six = listOf("01", "02", "03", "04", "05", "06")
        assertEquals("01,02,03,04,05,06", formatDiscoveryPath(six))

        val seven = six + "07"
        assertEquals("01,02,03…05,06,07", formatDiscoveryPath(seven))
    }

    private fun node(outPathLength: Int, outPath: ByteArray): DiscoveredNodeRecord = DiscoveredNodeRecord(
        id = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("B0000000-0000-0000-0000-000000000001"),
        publicKey = ByteArray(32) { 0x11 },
        name = "Node",
        typeRawValue = ContactType.REPEATER.raw,
        lastHeardEpochSeconds = 0,
        outPathLength = outPathLength,
        outPath = outPath,
    )
}
