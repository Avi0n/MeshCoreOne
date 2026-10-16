package one.meshcore.shared.trace

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.model.Uuid
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Persistence seam for saved trace paths and their runs. Mirrors the Swift `TracePathPersisting`
 * protocol the trace view model calls. Unit tests drive [InMemorySavedTracePathStore]; production
 * Tools UI uses [one.meshcore.shared.persistence.RoomSavedTracePathStore] so lists survive process death.
 */
interface SavedTracePathStore {
    /** A radio's saved paths, newest `createdDate` first. */
    suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto>

    /** A single saved path by id, or `null` when absent. */
    suspend fun fetchSavedTracePath(id: Uuid): SavedTracePathDto?

    /** Creates a saved path (minting id and createdDate), optionally seeding it with an initial run. */
    suspend fun createSavedTracePath(
        radioID: Uuid,
        name: String,
        pathBytes: ByteArray,
        hashSize: Int,
        initialRun: TracePathRunDto?,
    ): SavedTracePathDto

    /** Renames a saved path; a no-op if the id is absent. */
    suspend fun updateSavedTracePathName(id: Uuid, name: String)

    /** Deletes a saved path and its runs (cascade); a no-op if absent. */
    suspend fun deleteSavedTracePath(id: Uuid)

    /** Appends a run to a saved path; a no-op if the path id is absent. */
    suspend fun appendTracePathRun(pathID: Uuid, run: TracePathRunDto)
}

/**
 * In-memory [SavedTracePathStore] behind a [Mutex]. The reference for the newest-first ordering, cascade
 * delete, and run-append semantics the Room adapter must reproduce, and the test backing. [newId] and
 * [now] are injectable so tests can pin ids and timestamps.
 */
@OptIn(ExperimentalTime::class)
class InMemorySavedTracePathStore(
    private val newId: () -> Uuid = Uuid::random,
    private val now: () -> Double = { Clock.System.now().epochSeconds.toDouble() },
) : SavedTracePathStore {
    private val mutex = Mutex()
    private val paths = mutableListOf<SavedTracePathDto>()

    override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> = mutex.withLock {
        paths.filter { it.radioID == radioID }.sortedByDescending { it.createdDate }
    }

    override suspend fun fetchSavedTracePath(id: Uuid): SavedTracePathDto? = mutex.withLock {
        paths.firstOrNull { it.id == id }
    }

    override suspend fun createSavedTracePath(
        radioID: Uuid,
        name: String,
        pathBytes: ByteArray,
        hashSize: Int,
        initialRun: TracePathRunDto?,
    ): SavedTracePathDto = mutex.withLock {
        val dto = SavedTracePathDto(
            id = newId(),
            radioID = radioID,
            name = name,
            pathBytes = pathBytes,
            hashSize = hashSize,
            createdDate = now(),
            runs = initialRun?.let { listOf(it) } ?: emptyList(),
        )
        paths += dto
        dto
    }

    override suspend fun updateSavedTracePathName(id: Uuid, name: String) = mutex.withLock {
        val index = paths.indexOfFirst { it.id == id }
        if (index >= 0) paths[index] = paths[index].copy(name = name)
        Unit
    }

    override suspend fun deleteSavedTracePath(id: Uuid) = mutex.withLock {
        paths.removeAll { it.id == id }
        Unit
    }

    override suspend fun appendTracePathRun(pathID: Uuid, run: TracePathRunDto) = mutex.withLock {
        val index = paths.indexOfFirst { it.id == pathID }
        if (index >= 0) paths[index] = paths[index].copy(runs = paths[index].runs + run)
        Unit
    }
}
