package one.meshcore.shared.tools.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.prefs.PreferencesStore
import one.meshcore.shared.trace.SavedTracePathStore
import one.meshcore.shared.trace.TraceResult
import one.meshcore.shared.ui.formatTemplate

/**
 * One failed run keeps its own message. A batch where every run failed is a count, because the last
 * message alone hides how many attempts went nowhere. Null when any run succeeded.
 */
internal fun traceRunFailureMessage(
    outcomes: List<TraceResult>,
    allFailedTemplate: String,
    failedLabel: String,
): String? {
    if (outcomes.any { it.success }) return null
    return if (outcomes.size > 1) {
        formatTemplate(allFailedTemplate, listOf(outcomes.size))
    } else {
        outcomes.lastOrNull()?.errorMessage ?: failedLabel
    }
}

/** A successful batch opens the list sheet only while the list, not the map, is showing. */
internal fun traceBatchShouldOpenList(outcomes: List<TraceResult>, mapMode: Boolean): Boolean =
    outcomes.any { it.success } && !mapMode

/**
 * The inline hop list hides once the results sheet is up for this same path. A stale result, or a
 * failed one, stays in the list.
 */
internal fun traceListShowsInlineResult(
    showingSheet: Boolean,
    resultSucceeded: Boolean,
    resultMatchesPath: Boolean,
): Boolean = !(showingSheet && resultSucceeded && resultMatchesPath)

internal suspend fun reloadTracePathScreenSaved(
    store: SavedTracePathStore?,
    radio: Uuid?,
    onLoaded: (List<SavedTracePathDto>) -> Unit,
    onFailed: () -> Unit,
) {
    currentCoroutineContext().ensureActive()
    if (store == null || radio == null) {
        onLoaded(emptyList())
        return
    }
    try {
        val paths = store.fetchSavedTracePaths(radio)
        currentCoroutineContext().ensureActive()
        onLoaded(paths)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        currentCoroutineContext().ensureActive()
        onLoaded(emptyList())
        onFailed()
    }
}

internal fun persistTracePathViewMode(scope: CoroutineScope, preferences: PreferencesStore?, enabled: Boolean) {
    scope.launch {
        preferences?.putString(
            PersistenceKeys.TRACE_PATH_VIEW_MODE,
            if (enabled) {
                PersistenceKeys.TRACE_PATH_VIEW_MODE_MAP
            } else {
                PersistenceKeys.TRACE_PATH_VIEW_MODE_LIST
            },
        )
    }
}
