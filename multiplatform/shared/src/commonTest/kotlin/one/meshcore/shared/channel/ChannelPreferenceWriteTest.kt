package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ChannelPreferenceWriteTest {
    @Test
    fun aCompletedWriteDoesNotRollBack() = runTest {
        var wrote = false
        var rolledBack = 0

        runChannelPreferenceWrite(
            write = { wrote = true },
            rollback = { rolledBack += 1 },
        )

        assertTrue(wrote)
        assertEquals(0, rolledBack)
    }

    @Test
    fun aStoreFaultRollsBack() = runTest {
        var rolledBack = 0

        runChannelPreferenceWrite(
            write = { error("store locked") },
            rollback = { rolledBack += 1 },
        )

        assertEquals(1, rolledBack)
    }

    @Test
    fun anErrorRollsBackAndPropagates() = runTest {
        val fault = ChannelPreferenceProbeFault()
        var rolledBack = 0

        val thrown = assertFailsWith<ChannelPreferenceProbeFault> {
            runChannelPreferenceWrite(
                write = { throw fault },
                rollback = { rolledBack += 1 },
            )
        }

        assertSame(fault, thrown)
        assertEquals(1, rolledBack)
    }

    @Test
    fun aCancelledWriteLeavesTheChangeAndRethrows() = runTest {
        val cancelled = CancellationException("left the channel")
        var rolledBack = 0

        val thrown = assertFailsWith<CancellationException> {
            runChannelPreferenceWrite(
                write = { throw cancelled },
                rollback = { rolledBack += 1 },
            )
        }

        assertSame(cancelled, thrown)
        assertEquals(0, rolledBack)
    }
}

private class ChannelPreferenceProbeFault : Error("probe")
