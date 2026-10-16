package one.meshcore.shared.trace

import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.backup.TracePathRunDto

/**
 * Derived read models over a [SavedTracePathDto] and its runs. The backup DTO stores only raw fields, so
 * the run summaries (`runCount`, `successRate`, `recentRTTs`, ...) live here as extensions, mirroring the
 * computed properties on the Swift `SavedTracePathDTO`.
 */

/** A fully successful path (no runs yet) reports 100%. Mirrors Swift `successRate`'s empty-runs case. */
const val FULL_SUCCESS_RATE: Int = 100

/** Number of RTT samples kept for the recent-runs sparkline. Mirrors Swift `recentRTTs`'s `prefix(10)`. */
const val RECENT_RTT_LIMIT: Int = 10

/** Number of runs recorded for this path. */
val SavedTracePathDto.runCount: Int
    get() = runs.size

/** The most recent run date, or `null` when there are no runs. */
val SavedTracePathDto.lastRunDate: Double?
    get() = runs.maxOfOrNull { it.date }

/** Mean round-trip time in ms over successful runs, or `null` when none succeeded. */
val SavedTracePathDto.averageRoundTripMs: Int?
    get() {
        val successful = runs.filter { it.success }
        if (successful.isEmpty()) return null
        return meanRoundTripMs(successful.map { it.roundTripMs })
    }

/** Best (lowest) successful RTT in ms, or `null` when none succeeded. Mirrors Swift `bestRoundTrip`. */
val SavedTracePathDto.bestRoundTripMs: Int?
    get() = runs.filter { it.success }.minOfOrNull { it.roundTripMs }

/** Successful runs oldest-first for the detail chart. Mirrors Swift `successfulRuns`. */
val SavedTracePathDto.successfulRunsOldestFirst: List<TracePathRunDto>
    get() = runs.filter { it.success }.sortedBy { it.date }

/** All runs newest-first for the history list. Mirrors Swift `sortedRuns`. */
val SavedTracePathDto.runsNewestFirst: List<TracePathRunDto>
    get() = runs.sortedByDescending { it.date }

/** Success rate as a percentage (0-100); [FULL_SUCCESS_RATE] when there are no runs. */
val SavedTracePathDto.successRate: Int
    get() {
        if (runs.isEmpty()) return FULL_SUCCESS_RATE
        val successful = runs.count { it.success }
        return (successful * FULL_SUCCESS_RATE) / runs.size
    }

/** The last [RECENT_RTT_LIMIT] successful RTTs, oldest-first, for the sparkline. Mirrors Swift `recentRTTs`. */
val SavedTracePathDto.recentRTTs: List<Int>
    get() = runs.filter { it.success }
        .sortedByDescending { it.date }
        .take(RECENT_RTT_LIMIT)
        .reversed()
        .map { it.roundTripMs }

/** The stored full path as a byte list. Mirrors Swift `pathHashBytes`. */
val SavedTracePathDto.pathHashBytes: List<Byte>
    get() = pathBytes.toList()

/**
 * The second-most-recent successful run, used for the results comparison row, or `null` when fewer than
 * two successful runs exist. Mirrors Swift `TracePathViewModel.previousRun`.
 */
val SavedTracePathDto.previousSuccessfulRun: TracePathRunDto?
    get() {
        val successful = runs.filter { it.success }.sortedByDescending { it.date }
        return if (successful.size >= 2) successful[1] else null
    }

/**
 * The most recently-run saved path whose stored bytes exactly match [fullPathBytes], or `null` when none
 * match. Mirrors Swift `findMatchingSavedPath()` (exact `pathHashBytes == fullPathBytes`, newest run).
 */
fun findMatchingSavedPath(paths: List<SavedTracePathDto>, fullPathBytes: ByteArray): SavedTracePathDto? =
    paths.filter { it.pathBytes.contentEquals(fullPathBytes) }
        .maxByOrNull { it.lastRunDate ?: it.createdDate }

/**
 * The backup-merge dedupe key for a saved path: `(radioID, pathBytes, hashSize)`. Two paths with the
 * same key are the same path for merge purposes. Mirrors the Swift `batchInsertSavedTracePaths` key.
 */
fun savedPathDedupeKey(path: SavedTracePathDto): SavedPathDedupeKey =
    SavedPathDedupeKey(path.radioID.value, path.pathBytes.toList(), path.hashSize)

/** Value key for [savedPathDedupeKey]; [pathBytes] is a `List<Byte>` so structural equality holds. */
data class SavedPathDedupeKey(val radioID: String, val pathBytes: List<Byte>, val hashSize: Int)
