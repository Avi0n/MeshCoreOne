package one.meshcore.shared.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.RoomContactMetadataWriterCase.HEARD
import one.meshcore.shared.persistence.RoomContactMetadataWriterCase.MENTIONS
import one.meshcore.shared.persistence.RoomContactMetadataWriterCase.UNREAD
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomContactMetadataWriterOwnershipTest {
    @Test fun canceledUnreadWriterPreservesFreshRows() = canceled(UNREAD)

    @Test fun canceledMentionWriterPreservesFreshRows() = canceled(MENTIONS)

    @Test fun canceledHeardWriterPreservesFreshRows() = canceled(HEARD)

    @Test fun activeUnreadWriterPreservesConcurrentMetadata() = active(UNREAD)

    @Test fun activeMentionWriterPreservesConcurrentMetadata() = active(MENTIONS)

    @Test fun activeHeardWriterPreservesConcurrentMetadataAndNewerRecency() = active(HEARD)

    @Test fun liveUnreadWriterOnlyClearsUnreadCount() = live(UNREAD)

    @Test fun liveMentionWriterOnlyClearsMentionCount() = live(MENTIONS)

    @Test fun liveHeardWriterOnlyAdvancesHeard() = live(HEARD)

    @Test fun missingUnreadTargetDoesNotInsertRows() = missing(UNREAD)

    @Test fun missingMentionTargetDoesNotInsertRows() = missing(MENTIONS)

    @Test fun missingHeardTargetReturnsFalseWithoutInsertingRows() = missing(HEARD)

    private fun canceled(kind: RoomContactMetadataWriterCase) = exercise(kind) {
        seed()
        val unaffected = rows.survivors()
        start()
        editCurrent()
        val expected = rows.snapshot()
        cancelOrigin()
        assertTrue(releaseAndJoin() is CancellationException)
        assertEquals(unaffected, rows.survivors())
        assertEquals(expected, rows.snapshot(), "LAST: canceled partial metadata writer committed stale rows")
    }

    private fun active(kind: RoomContactMetadataWriterCase) = exercise(kind) {
        seed()
        val unaffected = rows.survivors()
        start()
        editCurrent()
        val expected = expectedSnapshot()
        assertNull(releaseAndJoin())
        assertEquals(unaffected, rows.survivors())
        assertEquals(expected, rows.snapshot(), "LAST: active partial metadata writer replayed old fields")
    }

    private fun live(kind: RoomContactMetadataWriterCase) = exercise(kind) {
        seed()
        val unaffected = rows.survivors()
        val expected = expectedSnapshot()
        start()
        assertNull(releaseAndJoin())
        assertEquals(unaffected, rows.survivors())
        assertEquals(expected, rows.snapshot())
    }

    private fun missing(kind: RoomContactMetadataWriterCase) = exercise(kind) {
        seed()
        val before = rows.snapshot()
        missingTarget()
        assertEquals(before, rows.snapshot())
    }

    private fun exercise(
        kind: RoomContactMetadataWriterCase,
        block: suspend RoomContactMetadataWriterFixture.() -> Unit,
    ) {
        val fixture = RoomContactMetadataWriterFixture(kind)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomContactMetadataWriterFixture.TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
