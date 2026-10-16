package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.chat.ChatFixtures
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.SavedTracePathStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed delete still reports the delete message. Cancellation and an error leave the row.
 */
class TracePathDeleteFaultTest {
    private class DeleteProbeError : Error("probe")

    private val radio = ChatFixtures.RADIO

    @Test
    fun aDeleteFaultReportsTheDeleteMessage() = runTest {
        val backing = InMemorySavedTracePathStore()
        val created = backing.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val errors = mutableListOf<String>()
        val details = mutableListOf<SavedTracePathDto?>()

        confirmSavedTracePathDelete(
            request(
                store = store(backing) { _ -> error("db locked") },
                pathId = created.id,
                errors = errors,
                loaded = mutableListOf(),
                details = details,
            ),
        )

        assertEquals(listOf("delete failed"), errors)
        assertTrue(details.isEmpty())
        assertEquals("old", backing.fetchSavedTracePath(created.id)!!.name)
    }

    @Test
    fun aCancelledDeletePropagates() = runTest {
        val backing = InMemorySavedTracePathStore()
        val created = backing.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val errors = mutableListOf<String>()
        val cancelled = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> {
            confirmSavedTracePathDelete(
                request(
                    store = store(backing) { _ -> throw cancelled },
                    pathId = created.id,
                    errors = errors,
                    loaded = mutableListOf(),
                    details = mutableListOf(),
                ),
            )
        }

        assertSame(cancelled, thrown)
        assertTrue(errors.isEmpty())
        assertEquals("old", backing.fetchSavedTracePath(created.id)!!.name)
    }

    @Test
    fun aDeleteErrorPropagates() = runTest {
        val backing = InMemorySavedTracePathStore()
        val created = backing.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val errors = mutableListOf<String>()
        val fault = DeleteProbeError()

        val thrown = assertFailsWith<DeleteProbeError> {
            confirmSavedTracePathDelete(
                request(
                    store = store(backing) { _ -> throw fault },
                    pathId = created.id,
                    errors = errors,
                    loaded = mutableListOf(),
                    details = mutableListOf(),
                ),
            )
        }

        assertSame(fault, thrown)
        assertTrue(errors.isEmpty())
        assertEquals("old", backing.fetchSavedTracePath(created.id)!!.name)
    }

    @Test
    fun aSuccessfulDeleteClearsTheListAndDetail() = runTest {
        val store = InMemorySavedTracePathStore()
        val created = store.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val loaded = mutableListOf<List<SavedTracePathDto>>()
        val details = mutableListOf<SavedTracePathDto?>()

        confirmSavedTracePathDelete(
            request(
                store = store,
                pathId = created.id,
                errors = mutableListOf(),
                loaded = loaded,
                details = details,
            ),
        )

        assertTrue(loaded.single().isEmpty())
        assertNull(details.single())
        assertNull(store.fetchSavedTracePath(created.id))
    }

    @Test
    fun aReloadFaultStaysALoadFailure() = runTest {
        val backing = InMemorySavedTracePathStore()
        val created = backing.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val store = object : SavedTracePathStore by backing {
            override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> = error("db locked")
        }
        val errors = mutableListOf<String>()
        val loaded = mutableListOf<List<SavedTracePathDto>>()
        val details = mutableListOf<SavedTracePathDto?>()

        confirmSavedTracePathDelete(
            request(
                store = store,
                pathId = created.id,
                errors = errors,
                loaded = loaded,
                details = details,
            ),
        )

        assertEquals(listOf("load failed"), errors)
        assertEquals(2, loaded.size, "the reload reports an empty list, then the cover clears it again")
        assertTrue(loaded.all { it.isEmpty() })
        assertNull(details.single())
        assertNull(backing.fetchSavedTracePath(created.id))
    }

    private fun store(backing: InMemorySavedTracePathStore, delete: suspend (Uuid) -> Unit): SavedTracePathStore =
        object : SavedTracePathStore by backing {
            override suspend fun deleteSavedTracePath(id: Uuid) {
                delete(id)
            }
        }

    private fun request(
        store: SavedTracePathStore,
        pathId: Uuid,
        errors: MutableList<String>,
        loaded: MutableList<List<SavedTracePathDto>>,
        details: MutableList<SavedTracePathDto?>,
    ) = TracePathDeleteRequest(
        store = store,
        pathId = pathId,
        radioId = { radio },
        loadFailedMessage = "load failed",
        deleteFailedMessage = "delete failed",
        onSavedPaths = { loaded += it },
        onError = { errors += it },
        onDetailPath = { details += it },
    )
}
