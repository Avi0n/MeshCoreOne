package one.meshcore.shared.persistence

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.tools.InMemoryRxLogStore
import one.meshcore.shared.tools.MAX_RX_LOG_ENTRIES
import one.meshcore.shared.tools.RX_LOG_PRUNE_THRESHOLD
import one.meshcore.shared.tools.RxLogDecodeCache
import one.meshcore.shared.tools.RxLogEntry
import one.meshcore.shared.tools.RxLogStore
import one.meshcore.shared.tools.loadExistingRxLogEntriesGuarded

/**
 * Room-backed [RxLogStore]: the Tools → RX Log list survives process death and reloads on first use,
 * mirroring iOS `RxLogService.loadExistingEntries` (newest [keepCount] rows **per radio**).
 *
 * The live buffer stays an [InMemoryRxLogStore] so newest-first ordering and the per-radio
 * [MAX_RX_LOG_ENTRIES] cap keep exactly one implementation; this class adds the durable side. On disk
 * the same cap is enforced the way iOS `pruneRxLogEntries` does it — a partition is trimmed back to
 * [keepCount] only once it runs [pruneThreshold] rows past it, so a delete pass costs one pass per
 * hundred packets instead of one per packet while the table still stays bounded.
 *
 * Room upserts are visible to [RxLogCorrelator] only after they hit the table. iOS can delay
 * `modelContext.save()` because SwiftData fetches see unsaved inserts in the same context; delaying
 * the Room write would drop path correlation for the packet that just arrived.
 *
 * Decrypted text is never written: [redecrypt] re-derives it into the buffer from the stored payload
 * whenever decode material changes, so a reloaded row is as readable as a live one without plaintext
 * ever reaching disk.
 */
class RoomRxLogStore(
    private val dao: RxLogEntryDao,
    scope: CoroutineScope,
    private val keepCount: Int = MAX_RX_LOG_ENTRIES,
    private val pruneThreshold: Int = RX_LOG_PRUNE_THRESHOLD,
    private val decodeSnapshot: suspend () -> RxLogDecodeCache.DecodeSnapshot? = { null },
    private val logger: PersistentLogger? = null,
) : RxLogStore {
    private val buffer = InMemoryRxLogStore(maxEntries = keepCount)
    private val mutex = Mutex()

    /** Row counts per partition, seeded from disk on first touch (iOS `rxLogEntryCountsByDevice`). */
    private val countsByRadio = mutableMapOf<Uuid, Int>()
    private var hydrated = false

    override val snapshot: StateFlow<List<RxLogEntry>> = buffer.snapshot

    init {
        scope.launch { hydrate() }
    }

    /**
     * Loads the newest persisted page for each radio into the live buffer, once. Safe to call from
     * anywhere: later calls are no-ops, and an append that wins the race still lands in both the buffer
     * and the table. iOS `loadExistingEntries` pages by `radioID`; a global newest-N hydrate would drop
     * an older radio's rows when another radio filled the page.
     */
    suspend fun hydrate() {
        mutex.withLock { hydrateLocked() }
    }

    override suspend fun list(radioID: Uuid): List<RxLogEntry> = mutex.withLock {
        hydrateLocked()
        buffer.list(radioID)
    }

    override suspend fun clear(radioID: Uuid) = mutex.withLock {
        hydrateLocked()
        dao.deleteByRadio(radioID)
        countsByRadio[radioID] = 0
        buffer.clear(radioID)
    }

    override suspend fun append(entry: RxLogEntry) = mutex.withLock {
        hydrateLocked()
        // Seed from disk before inserting: the first DAO count already includes this row otherwise.
        val priorCount = radioCountLocked(entry.radioID)
        dao.upsert(entry.toEntity())
        countsByRadio[entry.radioID] = priorCount + 1
        pruneLocked(entry.radioID)
        buffer.append(entry)
    }

    override suspend fun redecrypt(decode: RxLogDecodeCache.DecodeSnapshot): List<RxLogEntry> = mutex.withLock {
        hydrateLocked()
        val changed = buffer.redecrypt(decode)
        // Only the attribution and region fields reach the table; the decrypted body stays in memory.
        changed.forEach { dao.upsert(it.toEntity()) }
        changed
    }

    private suspend fun hydrateLocked() {
        if (hydrated) return
        hydrated = true
        // Oldest first per radio, so the buffer's newest-first insert rebuilds the stored order under
        // the same cap the buffer already enforces. A Room fault leaves that radio empty so Tools →
        // RX Log stays up.
        val radioIds = loadExistingRxLogEntriesGuarded(
            logger = logger,
            load = { dao.listRadioIds() },
            empty = { emptyList() },
        )
        for (radioID in radioIds) {
            val rows = loadExistingRxLogEntriesGuarded(
                logger = logger,
                load = { dao.listNewestFirst(radioID, keepCount) },
                empty = { emptyList() },
            )
            rows.asReversed().forEach { buffer.append(it.toDomain()) }
        }
        // iOS decrypts the persisted page when the log opens. Secrets may already be in the
        // cache from Room or a finished sync, so a later material emit would miss these rows.
        decodeSnapshot()?.let { buffer.redecrypt(it) }
    }

    private suspend fun radioCountLocked(radioID: Uuid): Int =
        countsByRadio.getOrPut(radioID) { dao.countByRadio(radioID) }

    private suspend fun pruneLocked(radioID: Uuid) {
        val count = radioCountLocked(radioID)
        if (count <= keepCount + pruneThreshold) return
        val doomed = dao.oldestIdsByRadio(radioID, count - keepCount)
        if (doomed.isEmpty()) return
        dao.deleteByIds(doomed)
        countsByRadio[radioID] = count - doomed.size
    }

    data class Args(
        val database: MeshDatabase,
        val scope: CoroutineScope,
        val keepCount: Int = MAX_RX_LOG_ENTRIES,
        val pruneThreshold: Int = RX_LOG_PRUNE_THRESHOLD,
        val decodeSnapshot: suspend () -> RxLogDecodeCache.DecodeSnapshot? = { null },
        val logger: PersistentLogger? = null,
    )

    companion object {
        /** Builds a store from the process-lifetime [MeshDatabase] without exposing DAOs to app modules. */
        fun fromDatabase(args: Args): RoomRxLogStore = RoomRxLogStore(
            dao = args.database.rxLogEntryDao(),
            scope = args.scope,
            keepCount = args.keepCount,
            pruneThreshold = args.pruneThreshold,
            decodeSnapshot = args.decodeSnapshot,
            logger = args.logger,
        )
    }
}
