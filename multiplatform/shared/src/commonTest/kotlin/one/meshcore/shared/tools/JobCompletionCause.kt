package one.meshcore.shared.tools

import kotlinx.coroutines.Job

/** Cause of an already-finished job. A job that is still running has not recorded one yet. */
internal fun Job.completionCause(): Throwable? {
    var cause: Throwable? = null
    invokeOnCompletion { cause = it }
    return cause
}
