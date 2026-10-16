package one.meshcore.android

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/** Runs only the latest requested task, cancelling an older task that is still suspended. */
internal class SupersedingTaskLauncher(private val scope: CoroutineScope) {
    private val activeJob = AtomicReference<Job?>()

    fun launch(block: suspend () -> Unit) {
        val next = scope.launch(start = CoroutineStart.LAZY) { block() }
        activeJob.getAndSet(next)?.cancel()
        next.start()
    }
}
