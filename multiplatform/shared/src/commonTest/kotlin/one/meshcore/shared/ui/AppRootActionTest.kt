package one.meshcore.shared.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertSame

@OptIn(ExperimentalCoroutinesApi::class)
class AppRootActionTest {
    @Test
    fun cancelledDisconnectKeepsTheActionCancelled() = runTest {
        val cancelled = CancellationException("disconnect cancelled")
        var cause: Throwable? = null
        launchDisconnectAction { throw cancelled }
        val child = coroutineContext[Job]!!.children.single()
        child.invokeOnCompletion { cause = it }

        runCurrent()

        assertSame(cancelled, cause)
    }
}
