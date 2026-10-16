package one.meshcore.shared.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TracePathBuilder
import one.meshcore.shared.trace.TraceResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TracePathOwnershipTest {
    @Test
    fun runningBeforeInitialHydrationStillRecordsExistingSavedHistory() = runTest {
        val store = InMemorySavedTracePathStore()
        val saved = store.createSavedTracePath(RADIO, "Saved", PATH, HASH_SIZE, null)
        val model = model(store) { _, _, _ -> success() }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        assertTrue(model.state.value.savedPaths.isEmpty())
        model.run()
        runCurrent()
        assertEquals(1, store.fetchSavedTracePath(saved.id)?.runs?.size)
    }

    @Test
    fun resetDuringPreflightLookupPreventsSendAndHistory() = runTest {
        val store = InMemorySavedTracePathStore()
        val saved = store.createSavedTracePath(RADIO, "Saved", PATH, HASH_SIZE, null)
        val release = CompletableDeferred<Unit>()
        val paused = object : SavedTracePathStore by store {
            override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> {
                withContext(NonCancellable) { release.await() }
                return store.fetchSavedTracePaths(radioID)
            }
        }
        var calls = 0
        val model = model(paused) { _, _, _ ->
            calls++
            success()
        }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.run()
        runCurrent()
        model.reset()
        release.complete(Unit)
        runCurrent()
        assertEquals(0, calls)
        assertTrue(store.fetchSavedTracePath(saved.id)?.runs?.isEmpty() == true)
        assertNull(model.state.value.result)
        assertFalse(model.state.value.isRunning)
    }

    @Test
    fun canceledSameRadioSavedLoadCannotPublishItsLaterOrdinaryError() = runTest {
        val store = InMemorySavedTracePathStore()
        val saved = store.createSavedTracePath(RADIO, "Saved", PATH, HASH_SIZE, null)
        val release = CompletableDeferred<Unit>()
        var fetches = 0
        val paused = object : SavedTracePathStore by store {
            override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> {
                if (++fetches == 1) {
                    withContext(NonCancellable) { release.await() }
                    error("retired read failed")
                }
                return store.fetchSavedTracePaths(radioID)
            }
        }
        val model = model(paused) { _, _, _ -> success() }
        model.load()
        runCurrent()
        model.load()
        runCurrent()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(saved.id), model.state.value.savedPaths.map { it.id })
        assertNull(model.state.value.errorMessage)
    }

    @Test
    fun aSavedLoadCompletingAfterResetCannotRestoreOldRows() = runTest {
        val store = InMemorySavedTracePathStore()
        store.createSavedTracePath(RADIO, "Saved", PATH, HASH_SIZE, null)
        val release = CompletableDeferred<Unit>()
        val paused = object : SavedTracePathStore by store {
            override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> {
                withContext(NonCancellable) { release.await() }
                return store.fetchSavedTracePaths(radioID)
            }
        }
        val model = model(paused) { _, _, _ -> success() }
        val old = backgroundScope.launch { model.reloadSaved() }
        runCurrent()
        model.reset()
        release.complete(Unit)
        old.join()
        assertTrue(model.state.value.savedPaths.isEmpty())
    }

    @Test
    fun savedLookupFaultStillAllowsTraceWithoutInventingHistory() = runTest {
        val backing = InMemorySavedTracePathStore()
        val store = object : SavedTracePathStore by backing {
            override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> = error("lookup failed")
        }
        var calls = 0
        val model = model(store) { _, _, _ ->
            calls++
            success()
        }
        model.builder.loadSavedPath(PATH, HASH_SIZE)
        model.run()
        runCurrent()
        assertEquals(1, calls)
        assertTrue(model.state.value.result?.success == true)
        assertFalse(model.state.value.isRunning)
    }

    private fun TestScope.model(store: SavedTracePathStore, runner: suspend (ByteArray, Int, Int) -> TraceResult) =
        TracePathViewModel(
            backgroundScope,
            TracePathViewModel.Dependencies(
                runner = { runner },
                builder = TracePathBuilder.Dependencies(contactService = { null }, radioId = { RADIO }),
                deviceHashMode = { null },
                deviceLocation = { null },
                savedPathStore = store,
                messages = { TracePathViewModel.Messages("Empty", "Failed", "%d failed", "Load failed") },
            ),
        )
    private fun success() = TraceResult(emptyList(), 10, true, null, PATH, HASH_SIZE)

    private companion object {
        val RADIO = Uuid("00000000-0000-0000-0000-000000000001")
        val PATH = byteArrayOf(0x11)
        const val HASH_SIZE = 1
    }
}
