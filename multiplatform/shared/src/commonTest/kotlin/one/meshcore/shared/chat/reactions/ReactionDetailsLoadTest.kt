package one.meshcore.shared.chat.reactions

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ReactionDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A persist fault on reaction-details load must land an empty list, not leave `isLoading` true.
 * iOS `ReactionDetailsSheet.loadReactions` sets `isLoading = false` after catch.
 */
class ReactionDetailsLoadTest {
    @Test
    fun persistFaultReturnsEmptyList() = runTest {
        val rows = loadReactionDetails { throw IllegalStateException("db locked") }
        assertTrue(rows.isEmpty())
    }

    @Test
    fun successReturnsFetchedRows() = runTest {
        val row = ReactionDto(
            id = Uuid.normalized("00000000-0000-4000-8000-000000000001"),
            messageID = Uuid.normalized("00000000-0000-4000-8000-000000000002"),
            emoji = "👍",
            senderName = "Alice",
            messageHash = "aabbccdd",
            rawText = ":aabbccdd:👍",
            receivedAt = 1.0,
            radioID = Uuid.normalized("00000000-0000-4000-8000-000000000099"),
        )
        val rows = loadReactionDetails { listOf(row) }
        assertEquals(listOf(row.id), rows.map { it.id })
    }

    @Test
    fun cancellationPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val thrown = assertFailsWith<CancellationException> {
            loadReactionDetails { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun anErrorPropagates() = runTest {
        val fault = ReactionLoadProbeError()
        val thrown = assertFailsWith<ReactionLoadProbeError> {
            loadReactionDetails { throw fault }
        }
        assertSame(fault, thrown)
    }
}

private class ReactionLoadProbeError : Error("probe")
