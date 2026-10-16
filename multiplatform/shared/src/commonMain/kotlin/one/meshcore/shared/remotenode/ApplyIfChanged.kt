package one.meshcore.shared.remotenode

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Sends [command] only when [current] is present and different from [original].
 * Commits only after [succeeded] returns true. A null current counts as unchanged.
 */
internal suspend fun <T> applyIfChanged(
    current: T?,
    original: T?,
    command: (T) -> String,
    succeeded: suspend (String) -> Boolean,
    commit: (T) -> Unit,
): Boolean {
    currentCoroutineContext().ensureActive()
    val value = current
    return when {
        value == null || value == original -> true
        else -> {
            val applied = succeeded(command(value))
            currentCoroutineContext().ensureActive()
            if (applied) commit(value)
            applied
        }
    }
}
