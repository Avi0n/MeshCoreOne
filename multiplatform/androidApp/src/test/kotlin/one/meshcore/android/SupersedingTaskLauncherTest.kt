package one.meshcore.android

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class SupersedingTaskLauncherTest {
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aNewTaskCancelsAnOlderSuspendedTask() = runTest {
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val results = mutableListOf<String>()
        val launcher = SupersedingTaskLauncher(backgroundScope)

        launcher.launch {
            firstStarted.complete(Unit)
            releaseFirst.await()
            results += "old"
        }
        runCurrent()
        firstStarted.await()

        launcher.launch { results += "new" }
        runCurrent()
        releaseFirst.complete(Unit)
        runCurrent()

        assertEquals(listOf("new"), results)
    }
}
