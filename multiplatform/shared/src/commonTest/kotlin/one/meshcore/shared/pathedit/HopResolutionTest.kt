package one.meshcore.shared.pathedit

import one.meshcore.shared.map.LatLng
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Mirrors the iOS `resolveHashToPublicKey`/`resolveHashToName` rules: unique-match wins, zero contact
 * matches fall through to discovered, and contact ambiguity never falls through. Also covers the
 * `createPathHop` + `normalizeHop` load pipeline the VM's `initializeEditablePath` runs.
 */
class HopResolutionTest {
    @Test
    fun resolvePublicKeyReturnsNullWhenMultipleContactsSharePrefix() {
        val contacts = listOf(
            PathEditFixtures.contact(name = "A", publicKey = PathEditFixtures.key(0xA1, 0x00)),
            PathEditFixtures.contact(name = "B", publicKey = PathEditFixtures.key(0xA1, 0x01)),
        )
        assertNull(HopResolution.resolvePublicKey(byteArrayOf(0xA1.toByte()), contacts, emptyList()))
    }

    @Test
    fun resolvePublicKeyFallsThroughToDiscoveredWhenContactsHaveNoMatch() {
        val fullKey = PathEditFixtures.key(0xDD, 0xEE)
        val resolved = HopResolution.resolvePublicKey(
            byteArrayOf(0xDD.toByte()),
            contacts = emptyList(),
            discovered = listOf(PathEditFixtures.discovered(publicKey = fullKey)),
        )
        assertContentEquals(fullKey, resolved)
    }

    @Test
    fun resolvePublicKeyIgnoresDiscoveredWhenContactsAreAmbiguous() {
        val contacts = listOf(
            PathEditFixtures.contact(name = "A", publicKey = PathEditFixtures.key(0xA1, 0x00)),
            PathEditFixtures.contact(name = "B", publicKey = PathEditFixtures.key(0xA1, 0x01)),
        )
        val discovered = listOf(PathEditFixtures.discovered(publicKey = PathEditFixtures.key(0xA1, 0x02)))
        assertNull(HopResolution.resolvePublicKey(byteArrayOf(0xA1.toByte()), contacts, discovered))
    }

    @Test
    fun resolveNameReturnsDisplayNameOfUniqueMatch() {
        val contacts = listOf(PathEditFixtures.contact(name = "Hop1", publicKey = PathEditFixtures.key(0xA1)))
        assertEquals("Hop1", HopResolution.resolveName(byteArrayOf(0xA1.toByte()), contacts, emptyList()))
    }

    @Test
    fun resolveNameFallsThroughToDiscovered() {
        val discovered = listOf(PathEditFixtures.discovered(name = "Disc1", publicKey = PathEditFixtures.key(0xBB)))
        assertEquals("Disc1", HopResolution.resolveName(byteArrayOf(0xBB.toByte()), emptyList(), discovered))
    }

    @Test
    fun createPathHopResolvesPublicKeyAndNameForUniqueContact() {
        val fullKey = PathEditFixtures.key(0xA1, 0xB2, 0xC3)
        val contacts = listOf(PathEditFixtures.contact(name = "Hop1", publicKey = fullKey))
        val hop = HopResolution.createPathHop(byteArrayOf(0xA1.toByte()), contacts, emptyList())
        assertContentEquals(fullKey, hop.publicKey)
        assertEquals("Hop1", hop.resolvedName)
    }

    @Test
    fun createPathHopThenNormalizeReslicesResolvedHopToDeviceHashSize() {
        // Stored 2-byte hop resolves to a known contact; device hashSize=1 -> hashBytes collapse to key.prefix(1).
        val fullKey = PathEditFixtures.key(0xA1, 0xB2, 0xC3)
        val contacts = listOf(PathEditFixtures.contact(name = "Hop1", publicKey = fullKey))
        val hop = HopResolution.createPathHop(byteArrayOf(0xA1.toByte(), 0xB2.toByte()), contacts, emptyList())
        val normalized = PathEditLogic.normalizeHop(hop, targetHashSize = 1)
        assertContentEquals(fullKey, normalized.publicKey)
        assertContentEquals(byteArrayOf(0xA1.toByte()), normalized.hashBytes)
    }

    @Test
    fun resolveLocationUsesADiscoveredOnlyNodesPosition() {
        val key = PathEditFixtures.key(0xC7)
        val discovered = listOf(PathEditFixtures.discovered(publicKey = key, location = LatLng(52.5, 13.4)))

        val located = HopResolution.resolveLocation(byteArrayOf(0xC7.toByte()), emptyList(), discovered)

        // A hop heard only through an advert used to resolve a name but never a pin, which sent the trace
        // distance readout to its fallback and left the node off the map.
        assertEquals(52.5, located?.latitude)
        assertEquals(13.4, located?.longitude)
    }

    @Test
    fun resolveLocationIgnoresADiscoveredNodeWithNoFix() {
        val key = PathEditFixtures.key(0xC8)
        val discovered = listOf(PathEditFixtures.discovered(publicKey = key))

        // (0,0) is the firmware's "no fix", not a position in the Atlantic.
        assertNull(HopResolution.resolveLocation(byteArrayOf(0xC8.toByte()), emptyList(), discovered))
    }
}
