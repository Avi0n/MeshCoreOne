package one.meshcore.shared.backup

import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.Inflater
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BackupZlibCompletionTest {
    @Test
    fun unfinishedRawDecodeIsRejected() {
        assertFailsWith<AppBackupError.InvalidFile> { decodeBackupFileBytes(unfinished(nowrap = true)) }
    }

    @Test
    fun unfinishedWrappedDecodeIsRejected() {
        assertFailsWith<AppBackupError.InvalidFile> { decodeBackupFileBytes(unfinished(nowrap = false)) }
    }

    @Test
    fun unfinishedRawDocumentIsRejected() {
        assertFailsWith<AppBackupError.InvalidFile> { readDocument(unfinished(nowrap = true)) }
    }

    @Test
    fun unfinishedWrappedDocumentIsRejected() {
        assertFailsWith<AppBackupError.InvalidFile> { readDocument(unfinished(nowrap = false)) }
    }

    @Test
    fun unfinishedRawParseIsRejected() {
        assertFailsWith<AppBackupError.InvalidFile> { parseBackupFile(unfinished(nowrap = true)) }
    }

    @Test
    fun unfinishedWrappedParseIsRejected() {
        assertFailsWith<AppBackupError.InvalidFile> { parseBackupFile(unfinished(nowrap = false)) }
    }

    @Test
    fun completedRawAndWrappedStreamsRetainExactJsonAndPreview() {
        val json = loadBackupFixture("legacy-minimal.json")
        val expected = previewPickedBackupJson(json)
        for (nowrap in listOf(true, false)) {
            val bytes = compressed(json.encodeToByteArray(), nowrap, complete = true)
            assertEquals(json, readDocument(bytes))
            assertEquals(expected, decodeBackupFileBytes(bytes).toImportPreview())
            assertEquals(expected, parseBackupFile(bytes).toImportPreview())
        }
    }

    @Test
    fun aZeroOutputFinalBlockCompletesAnExactBufferPayload() {
        val payload = ByteArray(EXACT_BUFFER_BYTES) { (it % DISTINCT_BYTE_VALUES).toByte() }
        val prefix = compressed(payload, nowrap = true, complete = false)
        val end = byteArrayOf(0x03, 0x00)
        val inflater = Inflater(true)
        try {
            inflater.setInput(prefix)
            val output = ByteArray(payload.size)
            assertEquals(payload.size, inflater.inflate(output))
            assertContentEquals(payload, output)
            assertEquals(0, inflater.inflate(output))
            assertFalse(inflater.finished())
            assertTrue(inflater.needsInput())
            inflater.setInput(end)
            assertEquals(0, inflater.inflate(output))
            assertTrue(inflater.finished())
        } finally {
            inflater.end()
        }
        assertContentEquals(payload, zlibDecompress(prefix + end, maxUncompressedBytes = payload.size))
    }

    @Test
    fun outputLimitKeepsItsClassificationForBothStreamFormats() {
        val payload = loadBackupFixture("legacy-minimal.json").encodeToByteArray()
        for (nowrap in listOf(true, false)) {
            for (complete in listOf(true, false)) {
                val error = assertFailsWith<AppBackupError.DecompressedTooLarge> {
                    backupFileBytesToJsonText(
                        compressed(payload, nowrap, complete),
                        maxUncompressedBytes = SMALL_OUTPUT_LIMIT,
                    )
                }
                assertEquals(SMALL_OUTPUT_LIMIT, error.maxBytes)
            }
        }
    }

    private fun unfinished(nowrap: Boolean): ByteArray {
        val payload = loadBackupFixture("legacy-minimal.json").encodeToByteArray()
        val bytes = compressed(payload, nowrap, complete = false)
        assertTrue(bytes.first() != '{'.code.toByte())
        val inflater = Inflater(nowrap)
        try {
            inflater.setInput(bytes)
            val output = ByteArray(payload.size + 1)
            val count = inflater.inflate(output)
            assertContentEquals(payload, output.copyOf(count))
            assertEquals(0, inflater.inflate(output))
            assertFalse(inflater.finished())
            assertTrue(inflater.needsInput())
        } finally {
            inflater.end()
        }
        return bytes
    }

    private fun compressed(payload: ByteArray, nowrap: Boolean, complete: Boolean): ByteArray {
        val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, nowrap)
        try {
            deflater.setInput(payload)
            if (complete) deflater.finish()
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(COMPRESSION_BUFFER_BYTES)
            do {
                val count = deflater.deflate(
                    buffer,
                    0,
                    buffer.size,
                    if (complete) Deflater.NO_FLUSH else Deflater.SYNC_FLUSH,
                )
                output.write(buffer, 0, count)
                if (complete && !deflater.finished()) assertTrue(count > 0)
            } while (if (complete) !deflater.finished() else count == buffer.size)
            assertTrue(deflater.needsInput())
            return output.toByteArray()
        } finally {
            deflater.end()
        }
    }

    private fun readDocument(bytes: ByteArray): String = bytes.inputStream().use { stream ->
        readBackupDocument(read = stream::read)
    }
}

private const val COMPRESSION_BUFFER_BYTES = 128
private const val EXACT_BUFFER_BYTES = 16 * 1024
private const val DISTINCT_BYTE_VALUES = 251
private const val SMALL_OUTPUT_LIMIT = 16
