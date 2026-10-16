package one.meshcore.shared.tools.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.trace.findMatchingSavedPath
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** The completed trace run set to persist: batch runs when non-empty, otherwise the single [result]. */
internal data class CompletedTracePath(
    val radioID: Uuid,
    val name: String,
    val hashSize: Int,
    val batchResults: List<TraceResult>,
    val result: TraceResult?,
)

/**
 * Whether the current path can be saved: there is a successful result, the path has not changed since
 * that result was traced (in batch mode the first successful run defines the traced path), and no saved
 * path already holds these bytes — a second row would split the path's runs and success rate in two.
 * Mirrors the Swift `canSavePath` plus the `isRunningSavedPath` gate that hides the save row.
 */
internal fun canSaveTracePath(
    currentPathBytes: ByteArray,
    result: TraceResult?,
    batchResults: List<TraceResult>,
    batchEnabled: Boolean,
    savedPaths: List<SavedTracePathDto> = emptyList(),
): Boolean {
    val reference = if (batchEnabled) {
        batchResults.firstOrNull { it.success }
    } else {
        result?.takeIf { it.success }
    }
    if (currentPathBytes.isEmpty() || !currentPathBytes.contentEquals(reference?.tracedPathBytes)) return false
    return findMatchingSavedPath(savedPaths, currentPathBytes) == null
}

/**
 * Appends every completed run — timeouts and send failures included — to the saved path matching the
 * traced [pathBytes], so a saved path's success rate counts the failures too. Mirrors the Swift
 * `recordFailedRun` plus the success append in `handleTraceResponse`, both of which write to the
 * `activeSavedPath` matched before the run rather than to a per-result lookup. Returns false when no
 * saved path matches, and when a persist fault keeps the recorded runs from landing.
 */
internal suspend fun recordRunsOnMatchingSavedPath(
    store: SavedTracePathStore,
    savedPaths: List<SavedTracePathDto>,
    pathBytes: ByteArray,
    outcomes: List<TraceResult>,
): Boolean {
    val match = findMatchingSavedPath(savedPaths, pathBytes) ?: return false
    return runExceptionFallback({ false }) {
        outcomes.forEachIndexed { index, outcome ->
            store.appendTracePathRun(match.id, outcome.toRunDto(secondsOffset = index))
        }
        outcomes.isNotEmpty()
    }
}

internal suspend fun loadSavedTracePath(store: SavedTracePathStore, id: Uuid): SavedTracePathDto? =
    runSavedTraceStore(null) { store.fetchSavedTracePath(id) }

/**
 * Runs one saved-trace-path store operation. A store fault returns [fallback] for the screen's failure policy.
 * Cancellation and an Error propagate.
 */
internal suspend fun <T> runSavedTraceStore(fallback: T, read: suspend () -> T): T =
    runExceptionFallback({ fallback }, read)

/**
 * Persists a saved trace path from a completed run set, mirroring the Swift `savePath`. In batch mode
 * ([CompletedTracePath.batchResults] non-empty) the first successful result seeds the path and every
 * remaining completed result is appended as a run, so batch history survives; single mode saves the
 * one successful [CompletedTracePath.result]. Returns false when nothing successful is available to save.
 */
internal suspend fun persistCompletedTracePath(
    store: SavedTracePathStore,
    path: CompletedTracePath,
    isCurrentOwner: () -> Boolean = { true },
): Boolean {
    currentCoroutineContext().ensureActive()
    if (!isCurrentOwner()) return false
    return if (path.batchResults.isNotEmpty()) {
        persistCompletedTraceBatch(store, path, isCurrentOwner)
    } else {
        persistCompletedTraceSingle(store, path)
    }
}

private suspend fun persistCompletedTraceBatch(
    store: SavedTracePathStore,
    path: CompletedTracePath,
    isCurrentOwner: () -> Boolean,
): Boolean {
    val firstSuccess = path.batchResults.firstOrNull { it.success } ?: return false
    val saved = store.createSavedTracePath(
        radioID = path.radioID,
        name = path.name,
        pathBytes = firstSuccess.tracedPathBytes,
        hashSize = path.hashSize,
        initialRun = firstSuccess.toRunDto(),
    )
    path.batchResults.forEachIndexed { index, outcome ->
        currentCoroutineContext().ensureActive()
        check(isCurrentOwner())
        if (outcome !== firstSuccess) store.appendTracePathRun(saved.id, outcome.toRunDto(secondsOffset = index))
    }
    return true
}

private suspend fun persistCompletedTraceSingle(store: SavedTracePathStore, path: CompletedTracePath): Boolean {
    val single = path.result?.takeIf { it.success } ?: return false
    store.createSavedTracePath(
        radioID = path.radioID,
        name = path.name,
        pathBytes = single.tracedPathBytes,
        hashSize = path.hashSize,
        initialRun = single.toRunDto(),
    )
    return true
}

@OptIn(kotlin.time.ExperimentalTime::class)
internal fun TraceResult.toRunDto(
    secondsOffset: Int = 0,
    nowEpochMillis: () -> Long = { kotlin.time.Clock.System.now().toEpochMilliseconds() },
): TracePathRunDto = TracePathRunDto(
    id = Uuid.random(),
    // Fractional dates keep sub-second runs ordered; offsets separate runs saved together.
    date = nowEpochMillis().toDouble() / MILLIS_PER_SECOND + secondsOffset,
    success = success,
    roundTripMs = durationMs,
    // Intermediate hops only. The end node repeats the last intermediate's SNR, and `hopsSNR` crosses the
    // backup envelope: one extra sample shifts every per-hop index when the Swift importer reads it.
    hopsSNR = hops.mapNotNull { hop -> hop.snr.takeUnless { hop.isStartNode || hop.isEndNode } },
)

private const val MILLIS_PER_SECOND = 1000.0
