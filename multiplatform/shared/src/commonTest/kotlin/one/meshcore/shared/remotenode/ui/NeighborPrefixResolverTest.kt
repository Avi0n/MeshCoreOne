package one.meshcore.shared.remotenode.ui

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.NodeNameMatchKind
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NeighborPrefixResolverTest {
    @Test
    fun exactUniqueMatch() {
        val key = ByteArray(32) { it.toByte() }
        val contact = contact(publicKey = key, name = "Hilltop")
        val resolution = NeighborPrefixResolver.resolve(key.copyOf(6), listOf(contact))
        assertEquals("Hilltop", resolution.displayName)
        assertEquals(NodeNameMatchKind.EXACT, resolution.matchKind)
    }

    @Test
    fun ambiguousShortPrefixIsFallback() {
        val prefix = ByteArray(2) { 0xAB.toByte() }
        val a = contact(publicKey = prefix + ByteArray(30) { 1 }, name = "Alpha")
        val b = contact(publicKey = prefix + ByteArray(30) { 2 }, name = "Bravo")
        val resolution = NeighborPrefixResolver.resolve(prefix, listOf(a, b))
        assertEquals(NodeNameMatchKind.FALLBACK, resolution.matchKind)
    }

    @Test
    fun aSixByteCollisionIsStillTrusted() {
        // Six bytes is the width at which iOS stops hedging; the hedge is for the 1-3 byte path hops.
        val prefix = ByteArray(6) { 0xAB.toByte() }
        val a = contact(publicKey = prefix + ByteArray(26) { 1 }, name = "Alpha")
        val b = contact(publicKey = prefix + ByteArray(26) { 2 }, name = "Bravo")
        assertEquals(NodeNameMatchKind.EXACT, NeighborPrefixResolver.resolve(prefix, listOf(a, b)).matchKind)
    }

    @Test
    fun unresolvedFallsBackToHex() {
        val prefix = ByteArray(6) { 0xCD.toByte() }
        val resolution = NeighborPrefixResolver.resolve(prefix, emptyList())
        assertEquals(NodeNameMatchKind.UNRESOLVED, resolution.matchKind)
        assertEquals("CDCD", resolution.displayName)
        assertEquals(
            "Unknown",
            NeighborPrefixResolver.neighborRowDisplayName(resolution, unknownLabel = "Unknown"),
        )
    }

    @Test
    fun neighborRowKeepsAResolvedName() {
        val key = ByteArray(32) { it.toByte() }
        val contact = contact(publicKey = key, name = "Hilltop")
        val resolution = NeighborPrefixResolver.resolve(key.copyOf(6), listOf(contact))
        assertEquals(
            "Hilltop",
            NeighborPrefixResolver.neighborRowDisplayName(resolution, unknownLabel = "Unknown"),
        )
    }

    @Test
    fun aNodeKnownOnlyFromAnAdvertResolvesToItsName() {
        val key = ByteArray(32) { 0x51 }
        val heard = discovered(publicKey = key, name = "Ridge Relay")
        val resolution = NeighborPrefixResolver.resolve(key.copyOf(6), emptyList(), listOf(heard))
        assertEquals("Ridge Relay", resolution.displayName)
        assertEquals(NodeNameMatchKind.EXACT, resolution.matchKind)
    }

    @Test
    fun aContactWinsOverADiscoveredRowCarryingTheSamePrefix() {
        val key = ByteArray(32) { 0x62 }
        val saved = contact(publicKey = key, name = "Saved")
        val heard = discovered(publicKey = key, name = "Heard")
        assertEquals("Saved", NeighborPrefixResolver.resolve(key.copyOf(6), listOf(saved), listOf(heard)).displayName)
    }

    @Test
    fun aShortPrefixHeldByBothSourcesIsFallback() {
        // The contact book alone cannot claim the prefix is unambiguous when an advert also carries it.
        val prefix = ByteArray(2) { 0x73 }
        val saved = contact(publicKey = prefix + ByteArray(30) { 1 }, name = "Saved")
        val heard = discovered(publicKey = prefix + ByteArray(30) { 2 }, name = "Heard")
        val resolution = NeighborPrefixResolver.resolve(prefix, listOf(saved), listOf(heard))
        assertEquals("Saved", resolution.displayName)
        assertEquals(NodeNameMatchKind.FALLBACK, resolution.matchKind)
    }

    @Test
    fun aCollidingPrefixPicksTheNearestNode() {
        val prefix = ByteArray(2) { 0x84.toByte() }
        val far = contact(
            publicKey = prefix + ByteArray(30) { 1 },
            name = "Far",
            place = ContactPlace(latitude = 40.0, longitude = -122.0),
        )
        val near = contact(
            publicKey = prefix + ByteArray(30) { 2 },
            name = "Near",
            place = ContactPlace(latitude = 37.1, longitude = -122.0),
        )
        val resolution = NeighborPrefixResolver.resolve(
            prefix,
            listOf(far, near),
            userLocation = UserLocation(latitude = 37.0, longitude = -122.0),
        )
        assertEquals("Near", resolution.displayName)
    }

    @Test
    fun withoutAUserLocationTheFreshestAdvertWins() {
        val prefix = ByteArray(2) { 0x95.toByte() }
        val stale = contact(
            publicKey = prefix + ByteArray(30) { 1 },
            name = "Stale",
            place = ContactPlace(lastAdvertTimestamp = 100),
        )
        val fresh = contact(
            publicKey = prefix + ByteArray(30) { 2 },
            name = "Fresh",
            place = ContactPlace(lastAdvertTimestamp = 900),
        )
        assertEquals("Fresh", NeighborPrefixResolver.resolve(prefix, listOf(stale, fresh)).displayName)
    }

    @Test
    fun equalAdvertsPreferAPhoneClockHeardOverAStaleRadioModified() {
        val prefix = ByteArray(2) { 0xD9.toByte() }
        val radioNewer = contact(
            publicKey = prefix + ByteArray(30) { 1 },
            name = "RadioNewer",
            place = ContactPlace(lastAdvertTimestamp = 50, lastModified = 400, lastHeardTimestamp = 100),
        )
        val heardNewer = contact(
            publicKey = prefix + ByteArray(30) { 2 },
            name = "HeardNewer",
            place = ContactPlace(lastAdvertTimestamp = 50, lastModified = 100, lastHeardTimestamp = 400),
        )
        assertEquals("HeardNewer", NeighborPrefixResolver.resolve(prefix, listOf(radioNewer, heardNewer)).displayName)
    }

    @Test
    fun aLocatedNodeOutranksOneWithNoCoordinate() {
        val prefix = ByteArray(2) { 0xA6.toByte() }
        val unlocated = contact(
            publicKey = prefix + ByteArray(30) { 1 },
            name = "Unlocated",
            place = ContactPlace(lastAdvertTimestamp = 900),
        )
        val located = contact(
            publicKey = prefix + ByteArray(30) { 2 },
            name = "Located",
            place = ContactPlace(latitude = 60.0, longitude = 20.0),
        )
        val resolution = NeighborPrefixResolver.resolve(
            prefix,
            listOf(unlocated, located),
            userLocation = UserLocation(latitude = 37.0, longitude = -122.0),
        )
        assertEquals("Located", resolution.displayName)
    }

    @Test
    fun resolveLocatedCarriesTheDiscoveredRowsCoordinate() {
        val key = ByteArray(32) { 0xB7.toByte() }
        val heard = discovered(publicKey = key, name = "Ridge", latitude = 37.5, longitude = -122.5)
        val located = NeighborPrefixResolver.resolveLocated(key.copyOf(6), emptyList(), listOf(heard))
        assertEquals(37.5, located?.latitude)
        assertEquals(-122.5, located?.longitude)
    }

    @Test
    fun resolveLocatedLeavesAnUnlocatedMatchWithoutACoordinate() {
        val key = ByteArray(32) { 0xC8.toByte() }
        val located = NeighborPrefixResolver.resolveLocated(key.copyOf(6), listOf(contact(key, "Hilltop")))
        assertNull(located?.latitude)
        assertNull(located?.longitude)
    }

    @Test
    fun keyDisplayByteCountClampsHashSizeToTwoOrThree() {
        assertEquals(2, NeighborPrefixResolver.keyDisplayByteCount(null))
        assertEquals(2, NeighborPrefixResolver.keyDisplayByteCount(1))
        assertEquals(2, NeighborPrefixResolver.keyDisplayByteCount(2))
        assertEquals(3, NeighborPrefixResolver.keyDisplayByteCount(3))
        assertEquals(3, NeighborPrefixResolver.keyDisplayByteCount(8))
    }

    private data class ContactPlace(
        val latitude: Double = 0.0,
        val longitude: Double = 0.0,
        val lastAdvertTimestamp: Long = 0,
        val lastModified: Long = 0,
        val lastHeardTimestamp: Long? = null,
    )

    private fun contact(publicKey: ByteArray, name: String, place: ContactPlace = ContactPlace()): ContactDto =
        ContactDto(
            id = Uuid.normalized("C0000000-0000-0000-0000-000000000001"),
            radioID = Uuid.normalized("R0000000-0000-0000-0000-000000000001"),
            publicKey = publicKey,
            name = name,
            typeRawValue = 1,
            flags = 0,
            outPathLength = 255,
            outPath = ByteArray(0),
            lastAdvertTimestamp = place.lastAdvertTimestamp,
            latitude = place.latitude,
            longitude = place.longitude,
            lastModified = place.lastModified,
            lastHeardTimestamp = place.lastHeardTimestamp,
            isBlocked = false,
            isMuted = false,
            isFavorite = false,
            unreadCount = 0,
        )

    private fun discovered(
        publicKey: ByteArray,
        name: String,
        latitude: Double = 0.0,
        longitude: Double = 0.0,
    ): DiscoveredNodeRecord = DiscoveredNodeRecord(
        id = Uuid.normalized("D0000000-0000-0000-0000-000000000001"),
        radioID = Uuid.normalized("R0000000-0000-0000-0000-000000000001"),
        publicKey = publicKey,
        name = name,
        typeRawValue = 1,
        lastHeardEpochSeconds = 0,
        latitude = latitude,
        longitude = longitude,
    )
}
