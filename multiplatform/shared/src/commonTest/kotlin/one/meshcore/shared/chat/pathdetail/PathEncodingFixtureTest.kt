package one.meshcore.shared.chat.pathdetail

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import one.meshcore.shared.backup.loadBackupFixture
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.chat.hopCount
import one.meshcore.shared.chat.pathHashSize
import one.meshcore.shared.chat.pathHops
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.pathedit.PathEditLogic
import one.meshcore.shared.settings.ConfigImportDevice
import one.meshcore.shared.settings.ConfigSections
import one.meshcore.shared.settings.MeshCoreNodeConfig
import one.meshcore.shared.settings.buildContactConfig
import one.meshcore.shared.settings.planConfigImport
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import one.meshcore.shared.pathedit.PathHop as EditablePathHop

/**
 * The Kotlin side of the routing path-length codec freeze. The Swift `PathEncoding` is the source of
 * record and asserts the same vectors under `fixtures/path/`; a drift here means an Android-written
 * out-path byte would describe a different route width than the path bytes beside it.
 *
 * Fixtures resolve as a sibling of the backup fixtures dir (`mc1.fixturesDir` points at
 * `fixtures/backup`), reusing the existing loader.
 */
class PathEncodingFixtureTest {
    @Serializable
    private data class DecodeVector(
        val label: String,
        val encoded: Int,
        val hashSize: Int? = null,
        val hopCount: Int? = null,
        val byteLength: Int? = null,
    )

    @Serializable
    private data class EncodeVector(val label: String, val hashSize: Int, val hopCount: Int, val encoded: Int)

    @Serializable
    private data class Vectors(val decode: List<DecodeVector>, val encode: List<EncodeVector>)

    @Serializable
    private data class DecodeRow(
        val encoded: Int,
        val hashSize: Int? = null,
        val hopCount: Int? = null,
        val byteLength: Int? = null,
    )

    @Serializable
    private data class EncodeRow(val hashSize: Int, val hopCount: Int, val encoded: Int)

    @Serializable
    private data class PathLenTableFile(
        val maxPathHashMode: Int,
        val maxHopCount: Int,
        val maxPathBytes: Int,
        val decode: List<DecodeRow>,
        val encode: List<EncodeRow>,
    )

    private companion object {
        const val DECODE_BYTE_COUNT = 256
        const val ENCODE_SAMPLE_COUNT = 27
        const val ROUTING_PUBLIC_KEY = "1111111111111111111111111111111111111111111111111111111111111111"

        val json = Json { ignoreUnknownKeys = true }

        fun vectors(): Vectors = json.decodeFromString(loadBackupFixture("../path/path-len-vectors.json"))

        fun pathLenTable(): PathLenTableFile = json.decodeFromString(loadBackupFixture("../path/path-len-table.json"))
    }

    @Test
    fun everyDecodeVectorMatches() {
        for (vector in vectors().decode) {
            val decoded = PathEncoding.decodePathLen(vector.encoded)
            if (vector.hashSize == null) {
                assertNull(decoded, "reserved mode must not decode: '${vector.label}'")
                continue
            }
            assertNotNull(decoded, "decode failed for '${vector.label}'")
            assertEquals(vector.hashSize, decoded.hashSize, vector.label)
            assertEquals(vector.hopCount, decoded.hopCount, vector.label)
            assertEquals(vector.byteLength, decoded.byteLength, vector.label)
        }
    }

    @Test
    fun everyEncodeVectorMatches() {
        for (vector in vectors().encode) {
            assertEquals(
                vector.encoded,
                PathEncoding.encodePathLen(hashSize = vector.hashSize, hopCount = vector.hopCount),
                "encode drift for '${vector.label}'",
            )
        }
    }

    @Test
    fun exhaustiveTableMatchesSwift() {
        val table = pathLenTable()
        assertEquals(PathEncoding.MAX_PATH_HASH_MODE, table.maxPathHashMode)
        assertEquals(PathEncoding.MAX_HOP_COUNT, table.maxHopCount)
        assertEquals(PathEncoding.MAX_PATH_BYTES, table.maxPathBytes)
        assertEquals(DECODE_BYTE_COUNT, table.decode.size)
        assertEquals(ENCODE_SAMPLE_COUNT, table.encode.size)
        assertEquals(DECODE_BYTE_COUNT, PATH_LEN_HASH_SIZE.size)
        for (row in table.decode) {
            val decoded = PathEncoding.decodePathLen(row.encoded)
            assertEquals(row.hashSize ?: 0, PATH_LEN_HASH_SIZE[row.encoded], "hash size ${row.encoded}")
            if (row.hashSize == null) {
                assertNull(decoded, "reserved byte ${row.encoded}")
                assertEquals(3, PathEncoding.pathHashMode(row.encoded))
                continue
            }
            assertNotNull(decoded, "decode failed for ${row.encoded}")
            assertEquals(row.hashSize, decoded.hashSize)
            assertEquals(row.hopCount, decoded.hopCount)
            assertEquals(row.byteLength, decoded.byteLength)
            assertEquals(row.hopCount, PATH_LEN_HOP_COUNT[row.encoded])
            assertEquals(row.byteLength, PATH_LEN_BYTE_LENGTH[row.encoded])
            assertEquals(row.hashSize - 1, PathEncoding.pathHashMode(row.encoded))
            assertEquals(row.encoded, PathEncoding.encodePathLen(row.hashSize, requireNotNull(row.hopCount)))
        }
        for (row in table.encode) {
            assertEquals(
                row.encoded,
                PathEncoding.encodePathLen(hashSize = row.hashSize, hopCount = row.hopCount),
                "encode drift hashSize=${row.hashSize} hopCount=${row.hopCount}",
            )
        }
    }

    @Test
    fun restoredMessageKeepsTheRawLengthAndShowsTheTable() {
        val row = pathLenTable().decode.first { it.encoded == 0x43 }
        val hashSize = requireNotNull(row.hashSize)
        val hopCount = requireNotNull(row.hopCount)
        val byteLength = requireNotNull(row.byteLength)
        val storedNodes = ByteArray(byteLength) { index -> index.toByte() }
        val restored = ChatFixtures.message(direction = MessageDirection.INCOMING).copy(
            pathLength = row.encoded,
            pathNodes = storedNodes,
        )

        assertEquals(row.encoded, restored.pathLength)
        assertContentEquals(storedNodes, restored.pathNodes)
        assertEquals(hopCount, restored.hopCount)
        assertEquals(hashSize, restored.pathHashSize)
        assertEquals(hopCount, restored.pathHops.size)
    }

    @Test
    fun outgoingPathEditWritesTheTableLength() {
        val row = pathLenTable().encode.first { it.hashSize == 2 && it.hopCount == 2 }
        val hops = List(row.hopCount) { index ->
            EditablePathHop(hashBytes = byteArrayOf(index.toByte(), (index + 1).toByte()))
        }
        val encoded = PathEditLogic.encodeEditablePath(hops, targetHashSize = row.hashSize)

        assertEquals(row.encoded, encoded.length)
        assertEquals(row.hopCount * row.hashSize, encoded.path.size)
    }

    @Test
    fun configurationImportAndExportUseTheTable() {
        val imported = pathLenTable().encode.first { it.hashSize == 2 && it.hopCount == 2 }
        val pathBytes = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())
        val config = MeshCoreNodeConfig(
            contacts = listOf(
                MeshCoreNodeConfig.ContactConfig(
                    type = 1u,
                    name = "Bob",
                    publicKey = ROUTING_PUBLIC_KEY,
                    flags = 0u,
                    latitude = "1.0",
                    longitude = "2.0",
                    lastAdvert = 5u,
                    lastModified = 100u,
                    outPath = "aabbccdd",
                    pathHashMode = (imported.hashSize - 1).toUByte(),
                ),
            ),
        )
        val record = planConfigImport(
            config,
            ConfigSections(contacts = true),
            ConfigImportDevice(
                maxChannels = 8,
                maxContacts = 100,
                maxTxPower = 22,
                existingChannels = emptyList(),
                existingContacts = emptyMap(),
            ),
        ).contactRecords.single()
        assertEquals(imported.encoded, record.outPathLength.toInt())
        assertContentEquals(pathBytes, record.outPath)

        val exportedRow = pathLenTable().decode.first { it.encoded == imported.encoded }
        val stored = MeshContact(
            id = ROUTING_PUBLIC_KEY,
            publicKey = ByteArray(32) { 0x11 },
            typeRawValue = 1,
            flags = 0,
            outPathLength = imported.encoded,
            outPath = pathBytes,
            advertisedName = "Bob",
            lastAdvertisement = 5L,
            latitude = 1.0,
            longitude = 2.0,
            lastModified = 100L,
        )
        val exported = buildContactConfig(stored)
        assertEquals(imported.encoded, stored.outPathLength)
        assertEquals((requireNotNull(exportedRow.hashSize) - 1).toUByte(), exported.pathHashMode)
        assertEquals("aabbccdd", exported.outPath)

        val flood = buildContactConfig(stored.copy(outPathLength = 0xFF, outPath = ByteArray(0)))
        assertNull(flood.pathHashMode)
        assertNull(flood.outPath)

        val reserved = buildContactConfig(stored.copy(outPathLength = 192, outPath = ByteArray(0)))
        assertEquals(3u, reserved.pathHashMode)
    }
}
