package one.meshcore.shared.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.contact.ContactSyncResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomContactFrameAtomicityTest {
    @Test fun lateFrameFaultRollsBackInsertedAndUpdatedRows() = exercise(cancel = false, fault = FrameWriteProbeError())

    @Test fun lateFrameCallerCancellationRollsBackInsertedAndUpdatedRows() = exercise(cancel = true, fault = null)

    @Test fun liveBatchCommitsInsertionUpdateAndRepeatedKey() = exercise(cancel = false, fault = null)

    private fun exercise(cancel: Boolean, fault: Throwable?) {
        val fixture = RoomContactFrameAtomicityFixture(fault)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomContactFrameAtomicityFixture.TIMEOUT_MS) {
                    fixture.seed()
                    val before = fixture.rows.snapshot()
                    val unaffected = fixture.rows.survivors()
                    fixture.start()
                    if (cancel) fixture.cancelCaller()
                    val (result, thrown) = fixture.finish()
                    val expected = when {
                        fault != null -> {
                            assertNull(result)
                            assertTrue(thrown !is CancellationException)
                            assertTrue(generateSequence(thrown) { it.cause }.any { it === fault })
                            before
                        }
                        cancel -> {
                            assertNull(result)
                            assertTrue(thrown is CancellationException)
                            before
                        }
                        else -> {
                            assertNull(thrown)
                            assertEquals(
                                ContactSyncResult(
                                    RoomContactFrameAtomicityFixture.FRAME_COUNT,
                                    RoomContactFrameAtomicityFixture.REPEAT_TIME,
                                    true,
                                ),
                                result,
                            )
                            fixture.successfulSnapshot(before)
                        }
                    }
                    assertEquals(unaffected, fixture.rows.survivors())
                    assertEquals(
                        expected,
                        fixture.rows.snapshot(),
                        "LAST: contact batch did not preserve complete atomic state",
                    )
                }
            }
        } finally {
            fixture.close()
        }
    }

    private class FrameWriteProbeError : Error("Frame write probe")
}
