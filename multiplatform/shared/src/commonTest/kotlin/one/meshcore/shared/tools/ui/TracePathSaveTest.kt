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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TracePathSaveTest {
    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000001")

    @Test
    fun cancelledSaveDoesNotBecomeAFailedSaveResult() = runTest {
        val cancelled = CancellationException("save cancelled")
        val thrown = assertFailsWith<CancellationException> {
            runSavedTraceStore(false) { throw cancelled }
        }

        assertSame(cancelled, thrown)
    }

    @Test
    fun cancelledCapabilityReadStopsTraceLoading() = runTest {
        val cancelled = CancellationException("capabilities cancelled")
        val thrown = assertFailsWith<CancellationException> {
            readTraceDeviceHashMode { throw cancelled }
        }

        assertSame(cancelled, thrown)
    }

    private fun result(success: Boolean, durationMs: Int, path: ByteArray = byteArrayOf(1, 2)): TraceResult =
        TraceResult(
            hops = emptyList(),
            durationMs = durationMs,
            success = success,
            errorMessage = if (success) null else "fail",
            tracedPathBytes = path,
            hashSize = 1,
        )

    @Test
    fun batchSavePersistsEveryCompletedRun() = runTest {
        val store = InMemorySavedTracePathStore()
        val batch = listOf(result(false, 0), result(true, 100), result(true, 120))
        val ok = persistCompletedTracePath(
            store = store,
            path = CompletedTracePath(
                radioID = radio,
                name = "path",
                hashSize = 1,
                batchResults = batch,
                result = batch.last(),
            ),
        )
        assertTrue(ok)
        val saved = store.fetchSavedTracePaths(radio).single()
        assertEquals(3, saved.runs.size, "all three completed batch runs are persisted, not just one")
    }

    @Test
    fun batchRunsGetDistinctOrderedTimestamps() = runTest {
        val store = InMemorySavedTracePathStore()
        val batch = listOf(result(true, 10), result(true, 20), result(true, 30))

        persistCompletedTracePath(store, CompletedTracePath(radio, "path", 1, batch, batch.last()))

        // Sharing one timestamp makes run history, the sparkline and the "vs previous" row key off a
        // tie, so an arbitrary run shows as the most recent.
        val dates = store.fetchSavedTracePaths(radio).single().runs.map { it.date }
        assertEquals(dates.size, dates.toSet().size, "every batch run carries its own timestamp")
        assertEquals(dates.sorted(), dates.sorted(), "timestamps are orderable")
    }

    @Test
    fun batchSaveWithNoSuccessSavesNothing() = runTest {
        val store = InMemorySavedTracePathStore()
        val batch = listOf(result(false, 0), result(false, 0))
        val ok = persistCompletedTracePath(store, CompletedTracePath(radio, "path", 1, batch, batch.last()))
        assertFalse(ok)
        assertTrue(store.fetchSavedTracePaths(radio).isEmpty())
    }

    @Test
    fun singleSavePersistsOneSuccessfulRun() = runTest {
        val store = InMemorySavedTracePathStore()
        val ok = persistCompletedTracePath(
            store = store,
            path = CompletedTracePath(
                radioID = radio,
                name = "path",
                hashSize = 1,
                batchResults = emptyList(),
                result = result(true, 90),
            ),
        )
        assertTrue(ok)
        assertEquals(1, store.fetchSavedTracePaths(radio).single().runs.size)
    }

    @Test
    fun singleSaveWithoutSuccessSavesNothing() = runTest {
        val store = InMemorySavedTracePathStore()
        val ok = persistCompletedTracePath(store, CompletedTracePath(radio, "path", 1, emptyList(), result(false, 0)))
        assertFalse(ok)
        assertTrue(store.fetchSavedTracePaths(radio).isEmpty())
    }

    @Test
    fun failedRunsAreRecordedOnTheMatchingSavedPath() = runTest {
        val store = InMemorySavedTracePathStore()
        val path = byteArrayOf(1, 2)
        val saved = store.createSavedTracePath(radio, "path", path, 1, initialRun = null)
        val recorded = recordRunsOnMatchingSavedPath(
            store = store,
            savedPaths = store.fetchSavedTracePaths(radio),
            pathBytes = path,
            outcomes = listOf(result(false, 0)),
        )

        assertTrue(recorded)
        val runs = store.fetchSavedTracePath(saved.id)!!.runs
        assertEquals(1, runs.size, "a timeout run is recorded, so the success rate can drop below 100%")
        assertFalse(runs.single().success)
    }

    @Test
    fun mixedBatchRecordsEveryRunOnTheMatchingSavedPath() = runTest {
        val store = InMemorySavedTracePathStore()
        val path = byteArrayOf(1, 2)
        val saved = store.createSavedTracePath(radio, "path", path, 1, initialRun = null)
        recordRunsOnMatchingSavedPath(
            store = store,
            savedPaths = store.fetchSavedTracePaths(radio),
            pathBytes = path,
            outcomes = listOf(result(true, 100), result(false, 0), result(true, 120)),
        )

        val runs = store.fetchSavedTracePath(saved.id)!!.runs
        assertEquals(3, runs.size)
        assertEquals(1, runs.count { !it.success })
    }

    @Test
    fun persistFaultOnRecordRunsDoesNotThrow() = runTest {
        val backing = InMemorySavedTracePathStore()
        val path = byteArrayOf(1, 2)
        val saved = backing.createSavedTracePath(radio, "path", path, 1, initialRun = null)
        val store = object : SavedTracePathStore by backing {
            override suspend fun appendTracePathRun(pathID: Uuid, run: TracePathRunDto) {
                error("room down")
            }
        }

        val recorded = recordRunsOnMatchingSavedPath(
            store = store,
            savedPaths = backing.fetchSavedTracePaths(radio),
            pathBytes = path,
            outcomes = listOf(result(false, 0)),
        )

        assertFalse(recorded)
        assertTrue(backing.fetchSavedTracePath(saved.id)!!.runs.isEmpty())
    }

    @Test
    fun persistFaultOnSavedPathListReturnsEmpty() = runTest {
        val store = object : SavedTracePathStore by InMemorySavedTracePathStore() {
            override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> {
                error("room down")
            }
        }

        var loaded: List<SavedTracePathDto>? = null
        var failed = false
        reloadTracePathScreenSaved(
            store,
            radio,
            onLoaded = { loaded = it },
            onFailed = { failed = true },
        )

        assertEquals(emptyList(), loaded)
        assertTrue(failed)
    }

    @Test
    fun persistFaultOnSavedPathDetailReturnsNull() = runTest {
        val store = object : SavedTracePathStore by InMemorySavedTracePathStore() {
            override suspend fun fetchSavedTracePath(id: Uuid): SavedTracePathDto? {
                error("room down")
            }
        }

        assertEquals(null, loadSavedTracePath(store, radio))
    }

    @Test
    fun runsGoNowhereWhenNoSavedPathMatches() = runTest {
        val store = InMemorySavedTracePathStore()
        val saved = store.createSavedTracePath(radio, "path", byteArrayOf(9, 9), 1, initialRun = null)
        val recorded = recordRunsOnMatchingSavedPath(
            store = store,
            savedPaths = store.fetchSavedTracePaths(radio),
            pathBytes = byteArrayOf(1, 2),
            outcomes = listOf(result(false, 0)),
        )

        assertFalse(recorded)
        assertTrue(store.fetchSavedTracePath(saved.id)!!.runs.isEmpty())
    }

    @Test
    fun saveIsBlockedWithoutASuccessfulResultForTheCurrentPath() {
        val path = byteArrayOf(1, 2)
        assertFalse(
            canSaveTracePath(path, result = null, batchResults = emptyList(), batchEnabled = false),
            "nothing to save before a trace runs",
        )
        assertFalse(
            canSaveTracePath(path, result = result(false, 0, path), batchResults = emptyList(), batchEnabled = false),
            "a failed trace records no path",
        )
        assertFalse(
            canSaveTracePath(
                currentPathBytes = byteArrayOf(3, 4),
                result = result(true, 90, path),
                batchResults = emptyList(),
                batchEnabled = false,
            ),
            "the path changed since the run, so the result no longer describes it",
        )
        assertTrue(canSaveTracePath(path, result(true, 90, path), emptyList(), batchEnabled = false))
    }

    @Test
    fun batchSaveIsGatedOnTheFirstSuccessfulRunsPath() {
        val path = byteArrayOf(1, 2)
        val batch = listOf(result(false, 0, path), result(true, 100, path))
        assertTrue(canSaveTracePath(path, batch.last(), batch, batchEnabled = true))
        assertFalse(
            canSaveTracePath(path, batch.first(), listOf(result(false, 0, path)), batchEnabled = true),
            "an all-failed batch has no traced path to save",
        )
        assertFalse(canSaveTracePath(byteArrayOf(3, 4), batch.last(), batch, batchEnabled = true))
    }

    @Test
    fun saveIsWithheldWhenThePathIsAlreadySaved() = runTest {
        val store = InMemorySavedTracePathStore()
        val path = byteArrayOf(1, 2)
        store.createSavedTracePath(radio, "path", path, 1, initialRun = null)
        val savedPaths = store.fetchSavedTracePaths(radio)

        assertFalse(
            canSaveTracePath(path, result(true, 90, path), emptyList(), batchEnabled = false, savedPaths = savedPaths),
            "saving again would split this path's history and success rate across two rows",
        )
        assertTrue(
            canSaveTracePath(
                currentPathBytes = byteArrayOf(7, 8),
                result = result(true, 90, byteArrayOf(7, 8)),
                batchResults = emptyList(),
                batchEnabled = false,
                savedPaths = savedPaths,
            ),
            "a path with no saved row is still offered",
        )
    }

    @Test
    fun hopHexStringsMatchesIosStrideIncludingPartialLastHop() {
        assertEquals(emptyList(), hopHexStrings(byteArrayOf(), hashSize = 1))
        assertEquals(emptyList(), hopHexStrings(byteArrayOf(1, 2), hashSize = 0))
        assertEquals(listOf("0A", "0B", "0C"), hopHexStrings(byteArrayOf(0x0A, 0x0B, 0x0C), hashSize = 1))
        assertEquals(listOf("0A0B", "0C"), hopHexStrings(byteArrayOf(0x0A, 0x0B, 0x0C), hashSize = 2))
    }
}
