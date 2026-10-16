package one.meshcore.shared.persistence

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.RoomContactFrameMergeCase.INCREMENTAL
import one.meshcore.shared.persistence.RoomContactFrameMergeCase.SINGLE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomContactFrameMergeTest {
    @Test fun singleFramePreservesNewerAvatarAndConfirmedFavorite() = exercise(SINGLE, edited = true)

    @Test fun singleFrameOrdinaryPolicy() = exercise(SINGLE, edited = false)

    @Test fun incrementalFirstWriterPreservesNewerAvatarAndConfirmedFavorite() = exercise(INCREMENTAL, edited = true)

    @Test fun incrementalFirstWriterOrdinaryPolicy() = exercise(INCREMENTAL, edited = false)

    @Test fun incrementalReplayPreservesNewerAvatarAndConfirmedFavorite() =
        exercise(INCREMENTAL, edited = true, ordinal = RoomContactFrameMergeFixture.SECOND_WRITER)

    @Test fun incrementalReplayOrSinglePassOrdinaryPolicy() =
        exercise(INCREMENTAL, edited = false, ordinal = RoomContactFrameMergeFixture.SECOND_WRITER)

    private fun exercise(
        kind: RoomContactFrameMergeCase,
        edited: Boolean,
        ordinal: Int = RoomContactFrameMergeFixture.FIRST_WRITER,
    ) {
        val fixture = RoomContactFrameMergeFixture(kind, ordinal)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomContactFrameMergeFixture.TIMEOUT_MS) {
                    fixture.seed()
                    val unaffected = fixture.rows.survivors()
                    val gated = fixture.start()
                    if (ordinal == RoomContactFrameMergeFixture.FIRST_WRITER) assertTrue(gated)
                    if (edited) fixture.editCurrent()
                    val expected = fixture.expectedSnapshot(edited)
                    fixture.finish(edited)
                    assertEquals(unaffected, fixture.rows.survivors())
                    assertEquals(expected, fixture.rows.snapshot(), "LAST: frame writer replayed older local metadata")
                }
            }
        } finally {
            fixture.close()
        }
    }
}
