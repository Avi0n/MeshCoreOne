package one.meshcore.shared.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch

/**
 * Bridges a platform foreground/background signal to the app-state lifecycle hooks, ported from the two
 * iOS `scenePhase` observers. The first foreground runs one-time launch [onActivate] (auto-reconnect,
 * migrations), then [onReady] (cold-launch deep links, mirroring `PendingExternalURL.markReady`), then
 * [onForeground] so first start matches iOS `handleReturnToForeground` after `initialize`. Every later
 * foreground runs [onForeground] only. [onBackground] runs on every background.
 *
 * Platform-neutral so the Android `LifecycleEventObserver` and the desktop window-focus watcher drive the
 * same logic; the first-vs-subsequent branch is unit-testable on the desktop JVM.
 *
 * [onForegroundChanged] reports the raw edge to anything that needs to know only whether the app is on
 * screen, such as the notification coordinator's banner suppression. It fires synchronously, ahead of the
 * suspending stage hooks, so work racing the transition reads the new state rather than the old one.
 */
class AppLifecycleController(
    private val scope: CoroutineScope,
    private val onActivate: suspend () -> Unit,
    private val onForeground: suspend () -> Unit,
    private val onBackground: () -> Unit,
    private val onReady: () -> Unit = {},
    private val onForegroundChanged: (Boolean) -> Unit = {},
) {
    private var activation: Deferred<Unit>? = null
    private var foregroundGeneration = 0L
    private var isForeground = false

    /** Call on each platform foreground (Android `ON_START`, desktop window gained focus). */
    fun onEnterForeground() {
        isForeground = true
        val generation = ++foregroundGeneration
        onForegroundChanged(true)
        val readiness = activation ?: scope.async(start = CoroutineStart.LAZY) {
            onActivate()
            onReady()
        }.also {
            activation = it
            it.start()
        }
        scope.launch {
            readiness.await()
            if (!isForeground || generation != foregroundGeneration) return@launch
            onForeground()
        }
    }

    /** Call on each platform background (Android `ON_STOP`, desktop window lost focus). */
    fun onEnterBackground() {
        isForeground = false
        foregroundGeneration++
        onForegroundChanged(false)
        onBackground()
    }
}
