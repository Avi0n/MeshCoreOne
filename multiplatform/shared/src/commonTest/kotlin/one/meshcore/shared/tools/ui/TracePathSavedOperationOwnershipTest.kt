package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.SavedTracePathStore
import kotlin.test.Test
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class TracePathSavedOperationOwnershipTest {
    @Test
    fun cancelledRenameCannotPublishAnOrdinaryStoreError() = runTest {
        val release = CompletableDeferred<Unit>()
        val errors = mutableListOf<String>()
        val backing = InMemorySavedTracePathStore()
        val store = object : SavedTracePathStore by backing {
            override suspend fun updateSavedTracePathName(id: Uuid, name: String) {
                withContext(NonCancellable) { release.await() }
                error("retired rename failed")
            }
        }
        val job = backgroundScope.launch {
            renameSavedTracePath(
                TracePathRenameRequest(
                    store, PATH_ID, "New", { RADIO }, null, "Load failed", "Rename failed",
                    onSavedPaths = {}, onError = { errors += it }, onDetailPath = {},
                ),
            )
        }
        runCurrent()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun cancelledDeleteCannotPublishAnOrdinaryStoreError() = runTest {
        val release = CompletableDeferred<Unit>()
        val errors = mutableListOf<String>()
        val backing = InMemorySavedTracePathStore()
        val store = object : SavedTracePathStore by backing {
            override suspend fun deleteSavedTracePath(id: Uuid) {
                withContext(NonCancellable) { release.await() }
                error("retired delete failed")
            }
        }
        val job = backgroundScope.launch {
            confirmSavedTracePathDelete(
                TracePathDeleteRequest(
                    store,
                    PATH_ID,
                    { RADIO },
                    "Load failed",
                    "Delete failed",
                    onSavedPaths = {},
                    onError = { errors += it },
                    onDetailPath = {},
                ),
            )
        }
        runCurrent()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun cancelledReloadCannotPublishItsFallbackRowsOrError() = runTest {
        val release = CompletableDeferred<Unit>()
        val publications = mutableListOf<String>()
        val backing = InMemorySavedTracePathStore()
        val store = object : SavedTracePathStore by backing {
            override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> {
                withContext(NonCancellable) { release.await() }
                error("retired reload failed")
            }
        }
        val job = backgroundScope.launch {
            reloadTracePathScreenSaved(
                store,
                RADIO,
                onLoaded = { publications += "rows" },
                onFailed = { publications += "error" },
            )
        }
        runCurrent()
        job.cancel()
        release.complete(Unit)
        job.join()
        assertTrue(publications.isEmpty())
    }

    private companion object {
        val RADIO = Uuid("00000000-0000-0000-0000-000000000001")
        val PATH_ID = Uuid("00000000-0000-0000-0000-000000000002")
    }
}
