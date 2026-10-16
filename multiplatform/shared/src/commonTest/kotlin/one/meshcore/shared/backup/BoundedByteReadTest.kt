package one.meshcore.shared.backup

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Document pickers call [readBackupDocument] and [readTextDocument]. The cap has to stop the
 * source before a later chunk is pulled, not after the whole stream is already in memory.
 */
class BoundedByteReadTest {
    @Test
    fun readBackupDocumentStopsBeforeTheNextChunkPastTheCap() {
        var calls = 0
        val error = assertFailsWith<AppBackupError.FileTooLarge> {
            readBackupDocument(
                read = { buffer ->
                    calls += 1
                    if (calls > 3) error("read past the cap")
                    buffer.fill(1)
                    CHUNK
                },
                maxBytes = CHUNK * 2,
                chunkSize = CHUNK,
            )
        }
        assertEquals(3, calls)
        assertEquals(CHUNK * 3, error.actualBytes)
        assertEquals(CHUNK * 2, error.maxBytes)
    }

    @Test
    fun readBackupDocumentReturnsJsonThatFits() {
        val payload = "{}".encodeToByteArray()
        var index = 0
        val text = readBackupDocument(
            read = { buffer ->
                if (index >= payload.size) {
                    -1
                } else {
                    buffer[0] = payload[index]
                    index += 1
                    1
                }
            },
            maxBytes = payload.size,
            chunkSize = 1,
        )
        assertEquals("{}", text)
        assertEquals(payload.size, index)
    }

    @Test
    fun readTextDocumentStopsOnTheSameCap() {
        var calls = 0
        assertFailsWith<AppBackupError.FileTooLarge> {
            readTextDocument(
                read = { buffer ->
                    calls += 1
                    buffer.fill('x'.code.toByte())
                    buffer.size
                },
                maxBytes = 4,
                chunkSize = CHUNK,
            )
        }
        assertEquals(1, calls)
    }
}

private const val CHUNK: Int = 8
