package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * One live subscription to [ContactService.events]. Rebinding the same instance is a no-op.
 * A new service cancels the previous job, and a null service clears it. The channel is opened
 * before the collector starts so a push during bind is not missed.
 */
internal class ContactServiceEventBinding(private val scope: CoroutineScope) {
    private var observed: ContactService? = null
    private var job: Job? = null
    private var isClosed = false

    suspend fun bind(service: ContactService?, onEvent: suspend (ContactServiceEvent) -> Unit) {
        if (isClosed) return
        if (service === observed) return
        job?.cancel()
        observed = service
        if (service == null) return
        val events = service.events()
        job = scope.launch {
            for (event in events) onEvent(event)
        }.also { collector ->
            // The subscription is acquired eagerly, even if a cancelled scope never starts the body.
            collector.invokeOnCompletion { events.cancel() }
        }
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        job?.cancel()
        job = null
        observed = null
    }
}
