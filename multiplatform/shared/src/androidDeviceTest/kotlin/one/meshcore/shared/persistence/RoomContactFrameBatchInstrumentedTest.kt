package one.meshcore.shared.persistence

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestName
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoomContactFrameBatchInstrumentedTest {
    @get:Rule val testName = TestName()

    @Test fun lateFrameFaultRollsBackInsertedAndUpdatedRows() = exercise(cancel = false, fault = FrameWriteProbeError())

    @Test fun lateFrameCallerCancellationRollsBackInsertedAndUpdatedRows() = exercise(cancel = true, fault = null)

    @Test fun liveBatchCommitsInsertionUpdateAndRepeatedKey() = exercise(cancel = false, fault = null)

    private fun exercise(cancel: Boolean, fault: Throwable?) {
        val fixture = RoomContactFrameBatchNativeFixture(fault)
        try {
            runBlocking {
                withTimeout(RoomContactFrameBatchNativeFixture.TIMEOUT_MS) {
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
                                RoomContactFrameBatchNativeFixture.FRAME_COUNT,
                                result,
                            )
                            fixture.successfulSnapshot(before)
                        }
                    }
                    val survivorsEqual = unaffected == fixture.rows.survivors()
                    assertTrue(survivorsEqual)
                    val after = fixture.rows.snapshot()
                    fixture.record(testName.methodName, expected == after, survivorsEqual, true)
                    assertEquals(
                        "LAST: contact batch did not preserve complete atomic state",
                        expected,
                        after,
                    )
                }
            }
        } finally {
            fixture.close(testName.methodName)
        }
    }

    private class FrameWriteProbeError : Error("Frame write probe")
}
