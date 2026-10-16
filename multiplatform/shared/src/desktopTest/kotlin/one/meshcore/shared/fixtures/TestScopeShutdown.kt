package one.meshcore.shared.fixtures

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking

/** Joins background work before a synchronous fixture destroys the resources that work uses. */
internal fun CoroutineScope.cancelAndJoinBlocking() {
    val job = coroutineContext.job
    runBlocking { job.cancelAndJoin() }
}
