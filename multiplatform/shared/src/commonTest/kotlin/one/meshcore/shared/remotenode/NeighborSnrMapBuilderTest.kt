package one.meshcore.shared.remotenode

import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.map.LineStyle
import one.meshcore.shared.map.MapFilterState
import one.meshcore.shared.map.PinStyle
import one.meshcore.shared.meshcore.Neighbour
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.remotenode.ui.NeighborPrefixResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class NeighborSnrMapBuilderTest {
    @Test
    fun exactLocatedMatchDrawsSnrLineAndBadge() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val prefix = ByteArray(6) { 0x42 }
        val contact = testContact(
            publicKey = prefix + ByteArray(26) { 0x00 },
            latitude = 37.1,
            longitude = -122.1,
        )
        val neighbor = Neighbour(publicKeyPrefix = prefix, secondsAgo = 30, snr = 9.0)

        val plot = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = listOf(neighbor),
            contacts = listOf(contact),
        )

        assertEquals(1, plot.lines.size)
        assertEquals(LineStyle.TRACE_GOOD, plot.lines[0].style)
        assertTrue(plot.points.any { it.pinStyle == PinStyle.REPEATER_RING_WHITE })
        assertTrue(plot.points.any { it.pinStyle == PinStyle.REPEATER })
        assertTrue(plot.points.any { it.pinStyle == PinStyle.BADGE })
        assertTrue(plot.points.filter { it.pinStyle != PinStyle.BADGE }.all { it.isClusterable })
        assertTrue(plot.points.filter { it.pinStyle == PinStyle.BADGE }.none { it.isClusterable })
        assertTrue(plot.unplottable.isEmpty())
        assertNotNull(plot.region)
    }

    @Test
    fun unresolvedNeighborIsUnplottable() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val neighbor = Neighbour(publicKeyPrefix = ByteArray(6) { 0x01 }, secondsAgo = 10, snr = 1.0)

        val plot = NeighborSnrMapBuilder.build(session, listOf(neighbor), contacts = emptyList())

        assertEquals(1, plot.unplottable.size)
        assertEquals(NeighborSnrMapBuilder.UnplottableReason.UNRESOLVED, plot.unplottable[0].reason)
        assertEquals(NodeNameMatchKind.UNRESOLVED, plot.unplottable[0].matchKind)
        assertTrue(plot.lines.isEmpty())
    }

    @Test
    fun aSixByteCollisionPlotsTheNearestNode() {
        // Six bytes is trusted outright, so the map places the closer of the two rather than dropping both.
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val prefix = ByteArray(6) { 0x11 }
        val far = testContact(publicKey = prefix + ByteArray(26) { 0x01 }, latitude = 40.0, longitude = -122.0)
        val near = testContact(publicKey = prefix + ByteArray(26) { 0x02 }, latitude = 37.1, longitude = -122.0)
        val neighbor = Neighbour(publicKeyPrefix = prefix, secondsAgo = 10, snr = 2.0)

        val plot = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = listOf(neighbor),
            contacts = listOf(far, near),
            options = NeighborSnrMapBuilder.Options(
                userLocation = UserLocation(latitude = 37.0, longitude = -122.0),
            ),
        )

        assertTrue(plot.unplottable.isEmpty())
        assertEquals(37.1, plot.points.first { it.pinStyle == PinStyle.REPEATER }.coordinate.latitude)
    }

    @Test
    fun aNeighborKnownOnlyFromAnAdvertIsPlottedFromItsDiscoveredRow() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val prefix = ByteArray(6) { 0x21 }
        val heard = DiscoveredNodeRecord(
            id = testUuid(70),
            radioID = testUuid(99),
            publicKey = prefix + ByteArray(26) { 0x07 },
            name = "Ridge Relay",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 0,
            latitude = 37.3,
            longitude = -122.3,
        )
        val neighbor = Neighbour(publicKeyPrefix = prefix, secondsAgo = 10, snr = 5.0)

        val plot = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = listOf(neighbor),
            contacts = emptyList(),
            options = NeighborSnrMapBuilder.Options(
                discovered = listOf(heard),
                filter = MapFilterState(showDiscovered = true),
            ),
        )

        assertTrue(plot.unplottable.isEmpty())
        assertEquals("Ridge Relay", plot.points.first { it.pinStyle == PinStyle.REPEATER }.label)
        assertEquals(1, plot.lines.size)
    }

    @Test
    fun aResolvedNeighborWithNoCoordinateIsUnplottable() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val prefix = ByteArray(6) { 0x31 }
        val contact = testContact(publicKey = prefix + ByteArray(26) { 0x03 }, name = "No Fix")
        val neighbor = Neighbour(publicKeyPrefix = prefix, secondsAgo = 10, snr = 2.0)

        val plot = NeighborSnrMapBuilder.build(session, listOf(neighbor), contacts = listOf(contact))

        assertEquals(1, plot.unplottable.size)
        assertEquals(NeighborSnrMapBuilder.UnplottableReason.NO_LOCATION, plot.unplottable[0].reason)
        assertEquals(NodeNameMatchKind.EXACT, plot.unplottable[0].matchKind)
        assertEquals("No Fix", plot.unplottable[0].displayName)
    }

    @Test
    fun favoritesOnlyKeepsTheSessionPinAndDropsANonFavoriteNeighbor() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val favoritePrefix = ByteArray(6) { 0x42 }
        val otherPrefix = ByteArray(6) { 0x43 }
        val favorite = testContact(
            publicKey = favoritePrefix + ByteArray(26) { 0x00 },
            name = "Fav",
            latitude = 37.1,
            longitude = -122.1,
            isFavorite = true,
        )
        val other = testContact(
            publicKey = otherPrefix + ByteArray(26) { 0x00 },
            name = "Other",
            latitude = 37.2,
            longitude = -121.9,
            isFavorite = false,
        )
        val plot = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = listOf(
                Neighbour(publicKeyPrefix = favoritePrefix, secondsAgo = 10, snr = 4.0),
                Neighbour(publicKeyPrefix = otherPrefix, secondsAgo = 10, snr = 3.0),
            ),
            contacts = listOf(favorite, other),
            options = NeighborSnrMapBuilder.Options(filter = MapFilterState(favoritesOnly = true)),
        )

        assertEquals(1, plot.points.count { it.pinStyle == PinStyle.REPEATER_RING_WHITE })
        assertEquals(1, plot.points.count { it.pinStyle == PinStyle.REPEATER })
        assertTrue(plot.points.any { it.pinStyle == PinStyle.REPEATER && it.label == "Fav" })
        assertEquals(1, plot.unplottable.size)
        assertEquals(NeighborSnrMapBuilder.UnplottableReason.UNRESOLVED, plot.unplottable[0].reason)
        assertTrue(plot.unplottable[0].neighbor.publicKeyPrefix.contentEquals(otherPrefix))
    }

    @Test
    fun discoveredOffDropsADiscoveredOnlyNeighborIntoUnplottable() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val prefix = ByteArray(6) { 0x21 }
        val heard = DiscoveredNodeRecord(
            id = testUuid(70),
            radioID = testUuid(99),
            publicKey = prefix + ByteArray(26) { 0x07 },
            name = "Heard",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 0,
            latitude = 37.1,
            longitude = -122.1,
        )
        val neighbor = Neighbour(publicKeyPrefix = prefix, secondsAgo = 10, snr = 5.0)

        val withDiscovered = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = listOf(neighbor),
            contacts = emptyList(),
            options = NeighborSnrMapBuilder.Options(
                discovered = listOf(heard),
                filter = MapFilterState(showDiscovered = true),
            ),
        )
        assertEquals(1, withDiscovered.points.count { it.pinStyle == PinStyle.REPEATER })

        val withoutDiscovered = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = listOf(neighbor),
            contacts = emptyList(),
            options = NeighborSnrMapBuilder.Options(
                discovered = listOf(heard),
                filter = MapFilterState(showDiscovered = false),
            ),
        )
        assertEquals(0, withoutDiscovered.points.count { it.pinStyle == PinStyle.REPEATER })
        assertEquals(1, withoutDiscovered.points.count { it.pinStyle == PinStyle.REPEATER_RING_WHITE })
        assertEquals(1, withoutDiscovered.unplottable.size)
    }

    @Test
    fun favoritesWithDiscoveredTrueStillDropsADiscoveredOnlyNeighbor() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val prefix = ByteArray(6) { 0x21 }
        val heard = DiscoveredNodeRecord(
            id = testUuid(70),
            radioID = testUuid(99),
            publicKey = prefix + ByteArray(26) { 0x07 },
            name = "Heard",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 0,
            latitude = 37.1,
            longitude = -122.1,
        )
        val plot = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = listOf(Neighbour(publicKeyPrefix = prefix, secondsAgo = 10, snr = 5.0)),
            contacts = emptyList(),
            options = NeighborSnrMapBuilder.Options(
                discovered = listOf(heard),
                filter = MapFilterState(favoritesOnly = true, showDiscovered = true),
            ),
        )

        assertEquals(1, plot.points.count { it.pinStyle == PinStyle.REPEATER_RING_WHITE })
        assertEquals(0, plot.points.count { it.pinStyle == PinStyle.REPEATER })
        assertEquals(1, plot.unplottable.size)
    }

    @Test
    fun typeGatesDoNotHideNeighborPins() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val prefix = ByteArray(6) { 0x42 }
        val contact = testContact(
            publicKey = prefix + ByteArray(26) { 0x00 },
            latitude = 37.1,
            longitude = -122.1,
        )
        val plot = NeighborSnrMapBuilder.build(
            session = session,
            neighbors = listOf(Neighbour(publicKeyPrefix = prefix, secondsAgo = 30, snr = 9.0)),
            contacts = listOf(contact),
            options = NeighborSnrMapBuilder.Options(filter = MapFilterState(showRepeater = false)),
        )

        assertEquals(1, plot.points.count { it.pinStyle == PinStyle.REPEATER })
        assertTrue(plot.unplottable.isEmpty())
    }

    @Test
    fun unresolvedTitleUsesClampedKeyBytes() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val neighbor = Neighbour(
            publicKeyPrefix = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()),
            secondsAgo = 10,
            snr = 1.0,
        )
        val two = NeighborSnrMapBuilder.build(
            session,
            listOf(neighbor),
            contacts = emptyList(),
            options = NeighborSnrMapBuilder.Options(keyDisplayByteCount = 2),
        )
        assertEquals("DEAD", two.unplottable.single().displayName)
        val three = NeighborSnrMapBuilder.build(
            session,
            listOf(neighbor),
            contacts = emptyList(),
            options = NeighborSnrMapBuilder.Options(keyDisplayByteCount = 3),
        )
        assertEquals("DEADBE", three.unplottable.single().displayName)
    }

    @Test
    fun collidingUnresolvedTitlesWidenToTheFullPrefix() {
        val session = testSession().copy(latitude = 37.0, longitude = -122.0)
        val first = Neighbour(
            publicKeyPrefix = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte(), 0x00, 0x01),
            secondsAgo = 10,
            snr = 1.0,
        )
        val second = Neighbour(
            publicKeyPrefix = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0x01, 0x02, 0x03, 0x04),
            secondsAgo = 10,
            snr = 1.0,
        )
        val distinct = Neighbour(
            publicKeyPrefix = byteArrayOf(
                0xAA.toByte(),
                0xBB.toByte(),
                0xCC.toByte(),
                0xDD.toByte(),
                0xEE.toByte(),
                0xFF.toByte(),
            ),
            secondsAgo = 10,
            snr = 1.0,
        )
        val plot = NeighborSnrMapBuilder.build(
            session,
            listOf(first, second, distinct),
            contacts = emptyList(),
            options = NeighborSnrMapBuilder.Options(
                keyDisplayByteCount = NeighborPrefixResolver.MIN_KEY_DISPLAY_BYTE_COUNT,
            ),
        )
        assertEquals(listOf("DEADBEEF0001", "DEAD01020304", "AABB"), plot.unplottable.map { it.displayName })
    }
}
