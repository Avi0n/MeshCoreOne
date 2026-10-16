package one.meshcore.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `.mc1backup` transport — Apple-compatible raw DEFLATE, fixture golden, and JSON fallback.
 */
class BackupZlibTest {
    @Test
    fun compressIsNotPlainJson() {
        val json = """{"v":1}""".encodeToByteArray()
        val compressed = zlibCompress(json)
        assertTrue(compressed.isNotEmpty())
        assertTrue(compressed[0] != '{'.code.toByte(), "compressed body must not be raw JSON")
    }

    @Test
    fun roundTripFullEnvelopeFixture() {
        val json = loadBackupFixture("full-envelope.json")
        val compressed = zlibCompress(json.encodeToByteArray())
        val plain = zlibDecompress(compressed)
        assertEquals(json, plain.decodeToString())
        val envelope = decodeBackupEnvelope(plain.decodeToString())
        assertTrue(envelope.devices.isNotEmpty())
    }

    @Test
    fun decodeBackupFileBytesAcceptsCompressedAndRawJson() {
        val json = loadBackupFixture("legacy-minimal.json")
        val compressed = zlibCompress(json.encodeToByteArray())
        val fromCompressed = decodeBackupFileBytes(compressed)
        val fromRaw = decodeBackupFileBytes(json.encodeToByteArray())
        assertEquals(fromRaw.exportDate, fromCompressed.exportDate)
        assertEquals(fromRaw.devices.size, fromCompressed.devices.size)
    }

    @Test
    fun backupFileBytesToJsonTextRoundTrips() {
        val json = loadBackupFixture("unicode-edge.json")
        val compressed = zlibCompress(json.encodeToByteArray())
        assertEquals(json, backupFileBytesToJsonText(compressed))
        assertEquals(json, backupFileBytesToJsonText(json.encodeToByteArray()))
    }

    @Test
    fun decompressRespectsMaxUncompressedCap() {
        val payload = ByteArray(2_000) { 'a'.code.toByte() }
        val compressed = zlibCompress(payload)
        assertFailsWith<BackupZlibException> {
            zlibDecompress(compressed, maxUncompressedBytes = 100)
        }
        val error = assertFailsWith<AppBackupError.DecompressedTooLarge> {
            backupFileBytesToJsonText(compressed, maxUncompressedBytes = 100)
        }
        assertEquals(100, error.maxBytes)
    }

    @Test
    fun unrecognizedFormatRejected() {
        val garbage = byteArrayOf(0x00, 0x01, 0x02, 0x03, 0x04)
        assertFailsWith<AppBackupError.InvalidFile> {
            backupFileBytesToJsonText(garbage)
        }
        assertFailsWith<AppBackupError.InvalidFile> {
            decodeBackupFileBytes(garbage)
        }
        assertFailsWith<AppBackupError.InvalidFile> {
            backupFileBytesToJsonText(byteArrayOf())
        }
    }

    @Test
    fun goldenMc1BackupFixtureDecodes() {
        // Swift-produced golden (NSData.compressed .zlib = raw DEFLATE).
        val bytes = loadBackupFixtureBytes("full-envelope.mc1backup")
        assertTrue(bytes[0] != '{'.code.toByte())
        val envelope = decodeBackupFileBytes(bytes)
        assertTrue(envelope.devices.isNotEmpty())
        val json = loadBackupFixture("full-envelope.json")
        assertEquals(json, backupFileBytesToJsonText(bytes))
    }

    @Test
    fun kotlinExportAcceptedBySameDecoderAsSwiftGolden() {
        val json = loadBackupFixture("full-envelope.json")
        val kotlinBytes = zlibCompress(json.encodeToByteArray())
        assertEquals(json, backupFileBytesToJsonText(kotlinBytes))
        val swiftBytes = loadBackupFixtureBytes("full-envelope.mc1backup")
        // Both must yield identical JSON payload (compression bytes may differ by level/window).
        assertEquals(
            backupFileBytesToJsonText(swiftBytes),
            backupFileBytesToJsonText(kotlinBytes),
        )
    }
}
