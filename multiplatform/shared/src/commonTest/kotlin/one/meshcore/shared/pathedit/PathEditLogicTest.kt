package one.meshcore.shared.pathedit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Mirrors the pure `PathManagementViewModel` statics: `normalizeHop`, `saveRejection`, and
 * `encodeEditablePath`, plus the load-time normalize -> saveRejection pipeline that
 * `initializeEditablePath` runs (ported as a pure walk since the VM is W9-S2).
 */
class PathEditLogicTest {
    // MARK: normalizeHop

    @Test
    fun normalizeHopWidensViaPublicKeyWhenTargetGrew() {
        val pk = PathEditFixtures.key(0xA1, 0xB2, 0xC3)
        val hop = PathHop(hashBytes = byteArrayOf(0xA1.toByte()), publicKey = pk, resolvedName = "H1")
        val normalized = PathEditLogic.normalizeHop(hop, targetHashSize = 3)
        assertContentEquals(byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()), normalized.hashBytes)
        assertContentEquals(pk, normalized.publicKey)
        assertEquals("H1", normalized.resolvedName)
    }

    @Test
    fun normalizeHopNarrowsViaPublicKeyWhenTargetShrank() {
        val pk = PathEditFixtures.key(0xA1, 0xB2, 0xC3)
        val hop = PathHop(hashBytes = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()), publicKey = pk)
        val normalized = PathEditLogic.normalizeHop(hop, targetHashSize = 1)
        assertContentEquals(byteArrayOf(0xA1.toByte()), normalized.hashBytes)
        assertContentEquals(pk, normalized.publicKey)
    }

    @Test
    fun normalizeHopNarrowsViaHashBytesWhenPublicKeyUnresolved() {
        val hop = PathHop(hashBytes = byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte()), publicKey = null)
        val normalized = PathEditLogic.normalizeHop(hop, targetHashSize = 1)
        assertContentEquals(byteArrayOf(0xA1.toByte()), normalized.hashBytes)
        assertNull(normalized.publicKey)
    }

    @Test
    fun normalizeHopLeavesNarrowerUnresolvedHopUnchanged() {
        val hop = PathHop(hashBytes = byteArrayOf(0xA1.toByte()), publicKey = null)
        val normalized = PathEditLogic.normalizeHop(hop, targetHashSize = 3)
        assertContentEquals(byteArrayOf(0xA1.toByte()), normalized.hashBytes)
        assertNull(normalized.publicKey)
    }

    @Test
    fun normalizeHopWidensViaPublicKeyEvenWhenStoredIsAlreadyAtTargetWidth() {
        val pk = PathEditFixtures.key(0xA1, 0xB2)
        val hop = PathHop(hashBytes = byteArrayOf(0xA1.toByte(), 0xB2.toByte()), publicKey = pk, resolvedName = "H1")
        val normalized = PathEditLogic.normalizeHop(hop, targetHashSize = 2)
        assertContentEquals(byteArrayOf(0xA1.toByte(), 0xB2.toByte()), normalized.hashBytes)
    }

    // MARK: saveRejection

    @Test
    fun saveRejectionFlagsHopMissingPublicKeyWhenTargetHashSizeGrew() {
        val hops = listOf(PathHop(hashBytes = byteArrayOf(0xA1.toByte()), publicKey = null))
        val rejection = PathEditLogic.saveRejection(hops, targetHashSize = 2, maxHopCount = 32)
        assertIs<PathSaveRejection.HopResizeRequired>(rejection)
    }

    @Test
    fun saveRejectionAllowsWideningWhenPublicKeyIsAvailable() {
        val pk = PathEditFixtures.key(0xA1, 0xB2)
        val hops = listOf(PathHop(hashBytes = byteArrayOf(0xA1.toByte()), publicKey = pk, resolvedName = "H1"))
        assertNull(PathEditLogic.saveRejection(hops, targetHashSize = 2, maxHopCount = 32))
    }

    @Test
    fun saveRejectionAllowsSameSizeEncodeEvenWithoutPublicKey() {
        val hops = listOf(PathHop(hashBytes = byteArrayOf(0xA1.toByte()), publicKey = null))
        assertNull(PathEditLogic.saveRejection(hops, targetHashSize = 1, maxHopCount = 63))
    }

    @Test
    fun saveRejectionFlagsHopCountOverBudget() {
        // 22 hops x 3 bytes = 66 > 64-byte budget -> maxHopCount for hashSize=3 is 21.
        val hops = (0 until 22).map { i ->
            val pk = PathEditFixtures.key(i)
            PathHop(hashBytes = pk.copyOfRange(0, 3), publicKey = pk, resolvedName = "H$i")
        }
        val rejection = PathEditLogic.saveRejection(hops, targetHashSize = 3, maxHopCount = 21)
        assertIs<PathSaveRejection.TooManyHops>(rejection)
        assertEquals(21, rejection.maxHopCount)
    }

    // MARK: encodeEditablePath

    @Test
    fun encodeEditablePathUsesFullPublicKeyWhenAvailable() {
        val pk1 = PathEditFixtures.key(0xA1, 0xB2)
        val pk2 = PathEditFixtures.key(0xC3, 0xD4)
        val hops = listOf(
            PathHop(hashBytes = byteArrayOf(0xA1.toByte()), publicKey = pk1, resolvedName = "H1"),
            PathHop(hashBytes = byteArrayOf(0xC3.toByte()), publicKey = pk2, resolvedName = "H2"),
        )
        val encoded = PathEditLogic.encodeEditablePath(hops, targetHashSize = 2)
        assertContentEquals(byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte()), encoded.path)
        assertEquals(0x42, encoded.length) // (2-1)<<6 | 2
    }

    @Test
    fun encodeEditablePathTruncatesPublicKeyToTargetHashSize() {
        val pk = PathEditFixtures.key(0xA1, 0xB2)
        val stored = byteArrayOf(0xA1.toByte(), 0xB2.toByte())
        val hops = listOf(PathHop(hashBytes = stored, publicKey = pk, resolvedName = "H1"))
        val encoded = PathEditLogic.encodeEditablePath(hops, targetHashSize = 1)
        assertContentEquals(byteArrayOf(0xA1.toByte()), encoded.path)
        assertEquals(0x01, encoded.length)
    }

    @Test
    fun encodeEditablePathFallsBackToHashBytesWhenPublicKeyIsNull() {
        val hops = listOf(
            PathHop(hashBytes = byteArrayOf(0xA1.toByte(), 0xB2.toByte()), publicKey = null),
            PathHop(hashBytes = byteArrayOf(0xC3.toByte(), 0xD4.toByte()), publicKey = null),
        )
        val encoded = PathEditLogic.encodeEditablePath(hops, targetHashSize = 2)
        assertContentEquals(byteArrayOf(0xA1.toByte(), 0xB2.toByte(), 0xC3.toByte(), 0xD4.toByte()), encoded.path)
        assertEquals(0x42, encoded.length)
    }

    // MARK: initializeEditablePath's pure normalize -> saveRejection pipeline

    @Test
    fun narrowerUnresolvedHopStaysShortSoSaveRejectionBlocksSave() {
        // Stored mode 0 (1B/hop), 1 hop, unresolvable. Device target hashSize=3.
        val storedHashSize = 1
        val storedPath = byteArrayOf(0xA1.toByte())
        val targetHashSize = 3
        val targetMaxHopCount = 21

        val hops = (0 until storedPath.size step storedHashSize).map { start ->
            val end = minOf(start + storedHashSize, storedPath.size)
            val bytes = storedPath.copyOfRange(start, end)
            PathEditLogic.normalizeHop(PathHop(hashBytes = bytes, publicKey = null), targetHashSize)
        }

        assertEquals(1, hops.size)
        assertContentEquals(byteArrayOf(0xA1.toByte()), hops[0].hashBytes)
        assertNull(hops[0].publicKey)

        val rejection = PathEditLogic.saveRejection(hops, targetHashSize, targetMaxHopCount)
        assertIs<PathSaveRejection.HopResizeRequired>(rejection)
    }

    @Test
    fun widerStoredHopNarrowsWhenResolutionFails() {
        // Stored mode 1 (2B/hop), unresolvable, device hashSize=1 -> truncate to 1 byte on load.
        val storedHashSize = 2
        val storedPath = byteArrayOf(0xA1.toByte(), 0xB2.toByte())
        val targetHashSize = 1

        val hops = (0 until storedPath.size step storedHashSize).map { start ->
            val end = minOf(start + storedHashSize, storedPath.size)
            val bytes = storedPath.copyOfRange(start, end)
            PathEditLogic.normalizeHop(PathHop(hashBytes = bytes, publicKey = null), targetHashSize)
        }

        assertEquals(1, hops.size)
        assertContentEquals(byteArrayOf(0xA1.toByte()), hops[0].hashBytes)
        assertNull(hops[0].publicKey)
        assertTrue(PathEditLogic.saveRejection(hops, targetHashSize, maxHopCount = 63) == null)
    }
}
