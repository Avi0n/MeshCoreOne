package one.meshcore.shared.chat.pathdetail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.MessageRepeatDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A persist fault on repeat-details load must land an empty list, not leave `repeats == null`.
 * iOS `HeardRepeatsService.refreshRepeats` returns `[]` after `Failed to fetch repeats`.
 */
class RepeatDetailsLoadTest {
    @Test
    fun persistFaultReturnsEmptyList() = runTest {
        val rows = loadRepeatDetails { throw IllegalStateException("db locked") }
        assertTrue(rows.isEmpty())
    }

    @Test
    fun successReturnsFetchedRows() = runTest {
        val row = MessageRepeatDto(
            id = Uuid.normalized("00000000-0000-4000-8000-000000000001"),
            messageID = Uuid.normalized("00000000-0000-4000-8000-000000000002"),
            receivedAt = 1.0,
            pathNodes = ByteArray(0),
        )
        val rows = loadRepeatDetails { listOf(row) }
        assertEquals(listOf(row.id), rows.map { it.id })
    }

    @Test
    fun cancellationPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            loadRepeatDetails { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun anErrorPropagates() = runTest {
        val fault = RepeatLoadProbeError()
        val thrown = assertFailsWith<RepeatLoadProbeError> {
            loadRepeatDetails { throw fault }
        }
        assertSame(fault, thrown)
    }
}

private class RepeatLoadProbeError : Error("probe")
