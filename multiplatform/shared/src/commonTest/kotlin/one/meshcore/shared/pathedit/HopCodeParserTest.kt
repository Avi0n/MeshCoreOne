package one.meshcore.shared.pathedit

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Direct unit tests for the pure `HopCodeParser` — it has no dedicated Swift test (it is exercised only
 * via `classifyCodes`/`addCodes`), and the spec calls for covering each of its five status branches plus
 * de-dup and capacity here, since it is the highest-value pure target.
 */
class HopCodeParserTest {
    // Resolver that recognizes any 1-byte prefix 0xAx by extending it to a full key.
    private fun resolveAx(hash: ByteArray): ResolvedNode? =
        if (hash.isNotEmpty() && (hash[0].toInt() and 0xF0) == 0xA0) {
            ResolvedNode(publicKey = PathEditFixtures.key(hash[0].toInt() and 0xFF), name = "Node")
        } else {
            null
        }

    private fun classify(input: String, existing: Set<List<Byte>> = emptySet(), capacity: Int? = 5) =
        HopCodeParser.classify(input, hashSize = 1, existingHashes = existing, remainingCapacity = capacity) {
            resolveAx(it)
        }

    @Test
    fun parseHexRequiresExactLength() {
        assertNull(HopCodeParser.parseHex("A1B2", hashSize = 1)) // too long for 1 byte
        assertNull(HopCodeParser.parseHex("A", hashSize = 1)) // odd length
        assertNull(HopCodeParser.parseHex("GG", hashSize = 1)) // non-hex
        val ok = HopCodeParser.parseHex("A1", hashSize = 1)
        assertTrue(ok != null && ok.size == 1 && ok[0] == 0xA1.toByte())
    }

    @Test
    fun willAddForValidResolvableCode() {
        val result = classify("A1")
        assertEquals(1, result.size)
        val status = assertIs<HopCodeStatus.WillAdd>(result[0].status)
        assertEquals("A1", result[0].code)
        assertTrue(result[0].willBeAdded)
        assertContentEquals(byteArrayOf(0xA1.toByte()), status.hop.hashBytes)
    }

    @Test
    fun invalidFormatForWrongLengthOrNonHex() {
        assertIs<HopCodeStatus.InvalidFormat>(classify("ZZ")[0].status)
    }

    @Test
    fun notFoundForValidHexWithNoNode() {
        assertIs<HopCodeStatus.NotFound>(classify("B1")[0].status)
    }

    @Test
    fun alreadyInPathWhenPrefixExists() {
        val existing = setOf(byteArrayOf(0xA1.toByte()).toList())
        assertIs<HopCodeStatus.AlreadyInPath>(classify("A1", existing = existing)[0].status)
    }

    @Test
    fun pathFullOnceCapacityExhausted() {
        // Capacity 1: first resolvable code adds, second becomes pathFull.
        val result = classify("A1, A2", capacity = 1)
        assertIs<HopCodeStatus.WillAdd>(result[0].status)
        assertIs<HopCodeStatus.PathFull>(result[1].status)
    }

    @Test
    fun deDupPreservesFirstOccurrenceAndUppercases() {
        // "a1" and "A1" collapse to one uppercased code; whitespace is trimmed.
        val result = classify(" a1 , A1 ")
        assertEquals(1, result.size)
        assertEquals("A1", result[0].code)
    }

    @Test
    fun runningPathSetGrowsAsCodesAdd() {
        // Three distinct resolvable codes all add, seeding the running path set as each is accepted.
        val result = classify("A1, A2, A3", capacity = null)
        assertEquals(3, result.size)
        assertTrue(result.all { it.status is HopCodeStatus.WillAdd })
    }

    @Test
    fun unlimitedCapacityNeverReportsPathFull() {
        val result = classify("A1, A2", capacity = null)
        assertTrue(result.all { it.status is HopCodeStatus.WillAdd })
    }

    @Test
    fun bucketHopCodesSplitsEveryStatus() {
        val key = byteArrayOf(1, 2, 3)
        val withKey = PathHop(byteArrayOf(0xA1.toByte()), publicKey = key, resolvedName = "Node")
        val withoutKey = PathHop(byteArrayOf(0xA2.toByte()))
        val bucket = bucketHopCodes(
            listOf(
                HopCodeClassification("A1", HopCodeStatus.WillAdd(withKey)),
                HopCodeClassification("A2", HopCodeStatus.WillAdd(withoutKey)),
                HopCodeClassification("B1", HopCodeStatus.NotFound),
                HopCodeClassification("C1", HopCodeStatus.AlreadyInPath),
                HopCodeClassification("ZZ", HopCodeStatus.InvalidFormat),
                HopCodeClassification("D1", HopCodeStatus.PathFull),
            ),
        )
        assertEquals(listOf("A1", "A2"), bucket.result.added)
        assertEquals(listOf("B1"), bucket.result.notFound)
        assertEquals(listOf("C1"), bucket.result.alreadyInPath)
        assertEquals(listOf("ZZ"), bucket.result.invalidFormat)
        assertEquals(listOf(withKey, withoutKey), bucket.hops)
        assertEquals(1, bucket.recordKeys.size)
        assertContentEquals(key, bucket.recordKeys[0])
    }
}
