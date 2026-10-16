package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TraceResult
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed saved-path run record still returns false. Cancellation and an error leave the record.
 */
class TracePathRecordRunsFaultTest {
    private class RecordRunProbeError : Error("probe")

    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val path = byteArrayOf(1, 2)

    @Test
    fun aCancelledRecordRunPropagates() = runTest {
        val cancelled = CancellationException("stopped")
        val (store, savedPaths) = recordingStore { _, _ -> throw cancelled }

        val thrown = assertFailsWith<CancellationException> {
            recordRunsOnMatchingSavedPath(
                store = store,
                savedPaths = savedPaths,
                pathBytes = path,
                outcomes = listOf(failedResult()),
            )
        }

        assertSame(cancelled, thrown)
        assertTrue(runs(store, savedPaths).isEmpty())
    }

    @Test
    fun aRecordRunErrorPropagates() = runTest {
        val fault = RecordRunProbeError()
        val (store, savedPaths) = recordingStore { _, _ -> throw fault }

        val thrown = assertFailsWith<RecordRunProbeError> {
            recordRunsOnMatchingSavedPath(
                store = store,
                savedPaths = savedPaths,
                pathBytes = path,
                outcomes = listOf(failedResult()),
            )
        }

        assertSame(fault, thrown)
        assertTrue(runs(store, savedPaths).isEmpty())
    }

    private suspend fun recordingStore(
        append: suspend (Uuid, TracePathRunDto) -> Unit,
    ): Pair<SavedTracePathStore, List<SavedTracePathDto>> {
        val backing = InMemorySavedTracePathStore()
        backing.createSavedTracePath(radio, "path", path, 1, initialRun = null)
        val store = object : SavedTracePathStore by backing {
            override suspend fun appendTracePathRun(pathID: Uuid, run: TracePathRunDto) {
                append(pathID, run)
            }
        }
        return store to backing.fetchSavedTracePaths(radio)
    }

    private suspend fun runs(store: SavedTracePathStore, savedPaths: List<SavedTracePathDto>) =
        store.fetchSavedTracePath(savedPaths.single().id)!!.runs

    private fun failedResult() = TraceResult(
        hops = emptyList(),
        durationMs = 0,
        success = false,
        errorMessage = "fail",
        tracedPathBytes = path,
        hashSize = 1,
    )
}
