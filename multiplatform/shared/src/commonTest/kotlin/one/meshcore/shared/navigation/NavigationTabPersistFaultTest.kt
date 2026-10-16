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
 * Tab persist writes. A DataStore fault still accepts a later tab write.
 * Cancellation and a thrown error stop later writes and keep the thrown instance.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NavigationTabPersistFaultTest {
    @Test
    fun aCancelledWriteRethrowsTheSameExceptionAndStopsLaterWrites() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("stopped")
        val harness = writeHarness { release, writes ->
            writes.value += 1
            release.await()
            throw cancelled
        }
        val causes = mutableListOf<Throwable?>()
        harness.scope.coroutineContext[Job]!!.children.forEach { child ->
            child.invokeOnCompletion { causes += it }
        }
        harness.release.complete(Unit)
        harness.nav.selectTab(AppTab.MAP)

        assertSame(cancelled, causes.single())
        assertTrue(harness.thrown.isEmpty())
        assertEquals(1, harness.writes.value)
        assertNull(harness.backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertEquals(AppTab.MAP.index, harness.nav.selectedTab.value)
    }

    @Test
    fun aWriteErrorRethrowsTheSameErrorAndStopsLaterWrites() = runTest(UnconfinedTestDispatcher()) {
        val probe = TabWriteProbeError()
        val harness = writeHarness { release, writes ->
            writes.value += 1
            release.await()
            throw probe
        }
        harness.release.complete(Unit)
        harness.nav.selectTab(AppTab.MAP)

        assertSame(probe, harness.thrown.single())
        assertEquals(1, harness.writes.value)
        assertNull(harness.backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
        assertEquals(AppTab.MAP.index, harness.nav.selectedTab.value)
    }

    @Test
    fun aWriteExceptionStillPersistsLaterTabWrites() = runTest(UnconfinedTestDispatcher()) {
        val harness = writeHarness { _, writes ->
            writes.value += 1
            if (writes.value == 1) throw TabWriteStoreException("datastore down")
        }
        harness.nav.selectTab(AppTab.MAP)

        assertTrue(harness.thrown.isEmpty())
        assertEquals(2, harness.writes.value)
        assertEquals(AppTab.MAP.index, harness.backing.getInt(NavigationCoordinator.SELECTED_TAB_KEY))
    }
}

private class TabWriteProbeError : Error("probe")

private class TabWriteStoreException(message: String) : Exception(message)

private class WriteCount {
    var value: Int = 0
}

private class WriteHarness(
    val backing: InMemoryPreferencesStore,
    val release: CompletableDeferred<Unit>,
    val thrown: MutableList<Throwable>,
    val scope: CoroutineScope,
    val nav: NavigationCoordinator,
    val writes: WriteCount,
)

@OptIn(ExperimentalCoroutinesApi::class)
private fun TestScope.writeHarness(onWrite: suspend (CompletableDeferred<Unit>, WriteCount) -> Unit): WriteHarness {
    val backing = InMemoryPreferencesStore()
    val release = CompletableDeferred<Unit>()
    val writes = WriteCount()
    val prefs = object : PreferencesStore by backing {
        override suspend fun putInt(key: String, value: Int) {
            if (key == NavigationCoordinator.SELECTED_TAB_KEY) {
                onWrite(release, writes)
            }
            backing.putInt(key, value)
        }
    }
    val thrown = mutableListOf<Throwable>()
    val scope = CoroutineScope(
        SupervisorJob() + UnconfinedTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
            thrown += error
        },
    )
    return WriteHarness(backing, release, thrown, scope, NavigationCoordinator(scope, prefs), writes)
}
