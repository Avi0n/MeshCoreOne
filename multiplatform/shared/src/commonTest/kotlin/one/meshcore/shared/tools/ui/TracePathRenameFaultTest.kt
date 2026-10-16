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
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed rename still reports the rename message. Cancellation and an error leave the rename.
 */
class TracePathRenameFaultTest {
    private class RenameProbeError : Error("probe")

    private val radio = ChatFixtures.RADIO

    @Test
    fun aRenameFaultReportsTheRenameMessage() = runTest {
        val backing = InMemorySavedTracePathStore()
        val created = backing.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val errors = mutableListOf<String>()
        val loaded = mutableListOf<List<SavedTracePathDto>>()
        val details = mutableListOf<SavedTracePathDto?>()

        renameSavedTracePath(
            request(
                store = store(backing) { _, _ -> error("db locked") },
                pathId = created.id,
                errors = errors,
                loaded = loaded,
                details = details,
            ),
        )

        assertEquals(listOf("rename failed"), errors)
        assertTrue(loaded.isEmpty())
        assertTrue(details.isEmpty())
        assertEquals("old", backing.fetchSavedTracePath(created.id)!!.name)
    }

    @Test
    fun aCancelledRenamePropagates() = runTest {
        val backing = InMemorySavedTracePathStore()
        val created = backing.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val errors = mutableListOf<String>()
        val cancelled = CancellationException("stopped")

        val thrown = assertFailsWith<CancellationException> {
            renameSavedTracePath(
                request(
                    store = store(backing) { _, _ -> throw cancelled },
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
    fun aRenameErrorPropagates() = runTest {
        val backing = InMemorySavedTracePathStore()
        val created = backing.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val errors = mutableListOf<String>()
        val fault = RenameProbeError()

        val thrown = assertFailsWith<RenameProbeError> {
            renameSavedTracePath(
                request(
                    store = store(backing) { _, _ -> throw fault },
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
    fun aSuccessfulRenameReloadsTheListAndOpenDetail() = runTest {
        val store = InMemorySavedTracePathStore()
        val created = store.createSavedTracePath(radio, "old", byteArrayOf(1, 2), 1, initialRun = null)
        val loaded = mutableListOf<List<SavedTracePathDto>>()
        val details = mutableListOf<SavedTracePathDto?>()

        renameSavedTracePath(
            request(
                store = store,
                pathId = created.id,
                errors = mutableListOf(),
                loaded = loaded,
                details = details,
            ),
        )

        assertEquals("ridge", loaded.single().single().name)
        assertEquals("ridge", details.single()!!.name)
        assertEquals("ridge", store.fetchSavedTracePath(created.id)!!.name)
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

        renameSavedTracePath(
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
        assertEquals("ridge", details.single()!!.name)
        assertEquals("ridge", backing.fetchSavedTracePath(created.id)!!.name)
    }

    private fun store(
        backing: InMemorySavedTracePathStore,
        update: suspend (Uuid, String) -> Unit,
    ): SavedTracePathStore = object : SavedTracePathStore by backing {
        override suspend fun updateSavedTracePathName(id: Uuid, name: String) {
            update(id, name)
        }
    }

    private fun request(
        store: SavedTracePathStore,
        pathId: Uuid,
        errors: MutableList<String>,
        loaded: MutableList<List<SavedTracePathDto>>,
        details: MutableList<SavedTracePathDto?>,
    ) = TracePathRenameRequest(
        store = store,
        pathId = pathId,
        name = "ridge",
        radioId = { radio },
        detailPathId = pathId,
        loadFailedMessage = "load failed",
        renameFailedMessage = "rename failed",
        onSavedPaths = { loaded += it },
        onError = { errors += it },
        onDetailPath = { details += it },
    )
}
