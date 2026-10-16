package one.meshcore.shared.lifecycle

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** Tracks fire-and-forget work for one owner and rejects launches after close. */
internal class OwnedJobs(private val scope: CoroutineScope) {
    private val jobs = mutableSetOf<Job>()

    var isClosed = false
        private set

    fun launch(block: suspend CoroutineScope.() -> Unit): Job? {
        if (isClosed) return null
        val job = scope.launch(start = CoroutineStart.LAZY, block = block)
        jobs += job
        job.invokeOnCompletion { jobs -= job }
        job.start()
        return job
    }

    fun close() {
        if (isClosed) return
        isClosed = true
        jobs.toList().forEach { it.cancel() }
        jobs.clear()
    }
}
