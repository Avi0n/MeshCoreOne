package one.meshcore.shared.persistence

import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.SavedTracePathStore
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Room-backed [SavedTracePathStore]. Reproduces [one.meshcore.shared.trace.InMemorySavedTracePathStore]
 * ordering (newest createdDate first), cascade delete of runs, and run-append against the real
 * `saved_trace_path` / `trace_path_run` tables so Tools → Trace Path survives process death.
 *
 * Prefer [fromDatabase] from app modules so callers never touch Room DAOs (RoomDatabase is not
 * exported on the androidApp classpath).
 */
@OptIn(ExperimentalTime::class)
class RoomSavedTracePathStore(
    private val pathDao: SavedTracePathDao,
    private val runDao: TracePathRunDao,
    private val newId: () -> Uuid = Uuid::random,
    private val now: () -> Double = { Clock.System.now().epochSeconds.toDouble() },
) : SavedTracePathStore {
    companion object {
        /** Builds a store from the process-lifetime [MeshDatabase] without exposing DAOs to app modules. */
        fun fromDatabase(
            database: MeshDatabase,
            newId: () -> Uuid = Uuid::random,
            now: () -> Double = { Clock.System.now().epochSeconds.toDouble() },
        ): RoomSavedTracePathStore = RoomSavedTracePathStore(
            pathDao = database.savedTracePathDao(),
            runDao = database.tracePathRunDao(),
            newId = newId,
            now = now,
        )
    }
    override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> =
        pathDao.listByRadio(radioID).map { it.toDtoWithRuns() }

    override suspend fun fetchSavedTracePath(id: Uuid): SavedTracePathDto? = pathDao.getById(id)?.toDtoWithRuns()

    override suspend fun createSavedTracePath(
        radioID: Uuid,
        name: String,
        pathBytes: ByteArray,
        hashSize: Int,
        initialRun: TracePathRunDto?,
    ): SavedTracePathDto {
        val pathId = newId()
        val createdDate = now()
        pathDao.upsert(
            SavedTracePathEntity(
                id = pathId,
                radioID = radioID,
                name = name,
                pathBytes = pathBytes,
                hashSize = hashSize,
                createdDate = createdDate,
            ),
        )
        val runs = if (initialRun != null) {
            runDao.upsert(initialRun.toEntity(pathId))
            listOf(initialRun)
        } else {
            emptyList()
        }
        return SavedTracePathDto(
            id = pathId,
            radioID = radioID,
            name = name,
            pathBytes = pathBytes,
            hashSize = hashSize,
            createdDate = createdDate,
            runs = runs,
        )
    }

    override suspend fun updateSavedTracePathName(id: Uuid, name: String) {
        if (pathDao.getById(id) == null) return
        pathDao.updateName(id, name)
    }

    override suspend fun deleteSavedTracePath(id: Uuid) {
        runDao.deleteByPath(id)
        pathDao.deleteById(id)
    }

    override suspend fun appendTracePathRun(pathID: Uuid, run: TracePathRunDto) {
        if (pathDao.getById(pathID) == null) return
        runDao.upsert(run.toEntity(pathID))
    }

    private suspend fun SavedTracePathEntity.toDtoWithRuns(): SavedTracePathDto {
        val runs = runDao.listByPath(id).map { it.toDto() }
        return toDto(runs)
    }
}

private fun TracePathRunDto.toEntity(savedPathID: Uuid): TracePathRunEntity = TracePathRunEntity(
    id = id,
    savedPathID = savedPathID,
    date = date,
    success = success,
    roundTripMs = roundTripMs,
    hopsSNR = hopsSNR,
)
