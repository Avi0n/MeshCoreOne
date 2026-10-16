package one.meshcore.shared.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scene-lifecycle bridge: the first foreground runs activation, ready, then the same reconcile
 * iOS runs after `initialize`; later foregrounds run the reconcile only; every background runs the
 * background hook.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AppLifecycleControllerTest {
    @Test
    fun `the first foreground activates, marks ready, and reconciles`() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<String>()
        val controller = AppLifecycleController(
            scope = backgroundScope,
            onActivate = { events += "activate" },
            onForeground = { events += "foreground" },
            onBackground = { events += "background" },
            onReady = { events += "ready" },
        )

        controller.onEnterForeground()
        controller.onEnterBackground()
        controller.onEnterForeground()
        controller.onEnterBackground()

        assertEquals(
            listOf("activate", "ready", "foreground", "background", "foreground", "background"),
            events,
        )
    }

    @Test
    fun `every edge reports the on-screen state ahead of its hook`() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<String>()
        val controller = AppLifecycleController(
            scope = backgroundScope,
            onActivate = { events += "activate" },
            onForeground = { events += "foreground" },
            onBackground = { events += "background" },
            onForegroundChanged = { events += "onScreen=$it" },
        )

        controller.onEnterForeground()
        controller.onEnterBackground()
        controller.onEnterForeground()

        assertEquals(
            listOf(
                "onScreen=true",
                "activate",
                "foreground",
                "onScreen=false",
                "background",
                "onScreen=true",
                "foreground",
            ),
            events,
        )
    }

    @Test
    fun `only the live foreground edge continues after activation`() = runTest {
        val activationStarted = CompletableDeferred<Unit>()
        val finishActivation = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val controller = AppLifecycleController(
            scope = backgroundScope,
            onActivate = {
                events += "activate"
                activationStarted.complete(Unit)
                finishActivation.await()
            },
            onForeground = { events += "foreground" },
            onBackground = { events += "background" },
            onReady = { events += "ready" },
        )

        controller.onEnterForeground()
        runCurrent()
        activationStarted.await()
        controller.onEnterBackground()
        controller.onEnterForeground()
        finishActivation.complete(Unit)
        runCurrent()

        assertEquals(listOf("activate", "background", "ready", "foreground"), events)
    }

    @Test
    fun `activation finishing in the background does not run the foreground hook`() = runTest {
        val finishActivation = CompletableDeferred<Unit>()
        val events = mutableListOf<String>()
        val controller = AppLifecycleController(
            scope = backgroundScope,
            onActivate = {
                events += "activate"
                finishActivation.await()
            },
            onForeground = { events += "foreground" },
            onBackground = { events += "background" },
            onReady = { events += "ready" },
        )

        controller.onEnterForeground()
        runCurrent()
        controller.onEnterBackground()
        finishActivation.complete(Unit)
        runCurrent()

        assertEquals(listOf("activate", "background", "ready"), events)
    }
}
