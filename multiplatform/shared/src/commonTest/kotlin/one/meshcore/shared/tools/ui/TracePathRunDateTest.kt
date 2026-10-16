package one.meshcore.shared.tools.ui

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.trace.previousSuccessfulRun
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class TracePathRunDateTest {
    @Test
    fun subSecondRunsRetainTheirOrderForHistoryAndPreviousComparison() = runTest {
        val store = InMemorySavedTracePathStore()
        val result = TraceResult(emptyList(), 10, true, null, byteArrayOf(0x11), 1)
        val first = result.toRunDto(nowEpochMillis = { 1_250L })
        val second = result.toRunDto(nowEpochMillis = { 1_750L })
        val saved = store.createSavedTracePath(RADIO, "Saved", result.tracedPathBytes, 1, first)
        store.appendTracePathRun(saved.id, second)
        val reloaded = assertNotNull(store.fetchSavedTracePath(saved.id))
        assertEquals(1.25, first.date)
        assertEquals(1.75, second.date)
        assertEquals(listOf(second.id, first.id), reloaded.runs.sortedByDescending { it.date }.map { it.id })
        assertEquals(first.id, reloaded.previousSuccessfulRun?.id)
    }

    @Test
    fun savingCompletedBatchRunsKeepsItsExistingDateOffset() {
        val result = TraceResult(emptyList(), 10, true, null, byteArrayOf(0x11), 1)
        assertEquals(3.25, result.toRunDto(secondsOffset = 2, nowEpochMillis = { 1_250L }).date)
    }

    private companion object {
        val RADIO = Uuid("00000000-0000-0000-0000-000000000001")
    }
}
