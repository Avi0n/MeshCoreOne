package one.meshcore.shared.remotenode

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.runReportingException

/** A retired caller cannot adopt a sender's late result or turn its failure into UI state. */
internal suspend fun <T> checkedSettingsCommand(send: suspend () -> T): T {
    val context = currentCoroutineContext()
    return runReportingException(
        onFailure = { error ->
            context.ensureActive()
            throw error
        },
    ) {
        context.ensureActive()
        send().also { context.ensureActive() }
    }
}
