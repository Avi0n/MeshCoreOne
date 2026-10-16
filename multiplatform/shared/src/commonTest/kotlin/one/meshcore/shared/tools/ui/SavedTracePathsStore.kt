package one.meshcore.shared.tools.ui

import one.meshcore.shared.backup.SavedTracePathDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.trace.InMemorySavedTracePathStore
import one.meshcore.shared.trace.SavedTracePathStore

/** A saved-trace store whose path list comes from the caller, so a reload test can vary only that fetch. */
internal fun savedTracePathsStore(fetch: suspend (Uuid) -> List<SavedTracePathDto>): SavedTracePathStore {
    val backing = InMemorySavedTracePathStore()
    return object : SavedTracePathStore by backing {
        override suspend fun fetchSavedTracePaths(radioID: Uuid): List<SavedTracePathDto> = fetch(radioID)
    }
}
