package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** A superseded section cannot clear its replacement's loading state. */
internal class RemoteSectionRequest(private val scope: CoroutineScope) {
    var job: Job? = null
        private set
    private var generation = 0L

    fun launch(begin: () -> Unit, onCancel: () -> Unit, onFailure: (Exception) -> Unit, read: suspend () -> Unit) {
        if (!scope.isActive) return
        val token = ++generation
        job?.cancel()
        begin()
        job = scope.launch(start = CoroutineStart.LAZY) {
            runStatusSectionRead(
                onCancel = { if (scope.isActive && generation == token) onCancel() },
                onFailure = { if (scope.isActive && generation == token) onFailure(it) },
            ) {
                currentCoroutineContext().ensureActive()
                read()
                currentCoroutineContext().ensureActive()
            }
        }
        job?.start()
    }
}

internal fun remoteSectionNeedsLoad(expanded: Boolean, loaded: Boolean, loading: Boolean): Boolean =
    expanded && !loaded && !loading
