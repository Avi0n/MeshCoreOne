package one.meshcore.shared.navigation

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PreferencesStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tab restore reads. A DataStore fault still starts later tab writes.
 * Cancellation and a thrown error skip those writes and keep the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationTabRestoreFaultTest {
    @Test
    fun aCancelledReadRethrowsTheSameExceptionAndSkipsLaterWrites() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("stopped")
        val backing = InMemoryPreferencesStore()
        val release = CompletableDeferred<Unit>()
        val prefs = object : PreferencesStore by backing {
            override suspend fun getInt(key: String): Int? {
                if (key == NavigationCoordinator.SELECTED_TAB_KEY) {
                    release.await()
                    throw cancelled
                }
                return backing.getInt(key)
            }
        }
        val thrown = mutableListOf<Throwable>()
        val scope = restoreScope(thrown)
        val nav = NavigationCoordinator(scope, prefs)
        val causes = mutableListOf<Throwable?>()
        scope.coroutineContext[Job]!!.children.forEach { child ->
            child.invokeOnCompletion { causes += it }
        }
        release.complete(Unit)
        nav.selectTab(AppTab.MAP)

        assertSame(cancelled, causes.single())
        assertTrue(thrown.isEmpty())
        assertNull(backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
    }

    @Test
    fun aReadErrorRethrowsTheSameErrorAndSkipsLaterWrites() = runTest(UnconfinedTestDispatcher()) {
        val probe = TabRestoreProbeError()
        val backing = InMemoryPreferencesStore()
        val release = CompletableDeferred<Unit>()
        val prefs = object : PreferencesStore by backing {
            override suspend fun getInt(key: String): Int? {
                if (key == NavigationCoordinator.SELECTED_TAB_KEY) {
                    release.await()
                    throw probe
                }
                return backing.getInt(key)
            }
        }
        val thrown = mutableListOf<Throwable>()
        val nav = NavigationCoordinator(restoreScope(thrown), prefs)
        release.complete(Unit)
        nav.selectTab(AppTab.MAP)

        assertSame(probe, thrown.single())
        assertNull(backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertEquals(AppTab.MAP.index, nav.selectedTab.value)
    }

    @Test
    fun aReadExceptionStillPersistsLaterTabWrites() = runTest(UnconfinedTestDispatcher()) {
        val backing = InMemoryPreferencesStore(
            mapOf(NavigationCoordinator.SELECTED_TAB_KEY to AppTab.TOOLS.index),
        )
        val prefs = object : PreferencesStore by backing {
            override suspend fun getInt(key: String): Int? {
                if (key == NavigationCoordinator.SELECTED_TAB_KEY) throw TabRestoreReadException("datastore down")
                return backing.getInt(key)
            }
        }
        val thrown = mutableListOf<Throwable>()
        val nav = NavigationCoordinator(restoreScope(thrown), prefs)

        assertEquals(AppTab.CHATS.index, nav.selectedTab.value)
        nav.selectTab(AppTab.MAP)
        assertEquals(AppTab.MAP.index, backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertTrue(thrown.isEmpty())
    }
}

private class TabRestoreProbeError : Error("probe")

private class TabRestoreReadException(message: String) : Exception(message)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.restoreScope(thrown: MutableList<Throwable> = mutableListOf()): CoroutineScope = CoroutineScope(
    SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
        thrown += error
    },
)
