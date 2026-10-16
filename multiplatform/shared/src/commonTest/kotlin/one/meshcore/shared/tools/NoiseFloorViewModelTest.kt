package one.meshcore.shared.tools

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NoiseFloorViewModelTest {
    @Test
    fun hiddenPollingStopsAndReturnRetainsOriginAndHistory() = runTest {
        val f = Fixture(this)
        val mount = f.model.shown(messages)
        runCurrent()
        f.model.hidden(mount)
        advanceTimeBy(60_000)
        runCurrent()
        assertEquals(1, f.calls)
        assertFalse(f.model.state.value.isPolling)
        f.model.shown(messages)
        runCurrent()
        assertEquals(listOf(0L, 60_000L), f.model.state.value.readings.map { it.timestampEpochMs })
        assertEquals(0L, f.model.state.value.chartStartEpochMs)
    }

    @Test
    fun overlappingMountsSharePollerAndOldDisposalDoesNotStopIt() = runTest {
        val f = Fixture(this)
        val old = f.model.shown(messages)
        runCurrent()
        val replacement = f.model.shown(messages)
        runCurrent()
        assertEquals(1, f.calls)
        f.model.hidden(old)
        advanceTimeBy(NOISE_FLOOR_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(2, f.calls)
        f.model.hidden(replacement)
        assertFalse(f.model.state.value.isPolling)
    }

    @Test
    fun newRadioClearsHistoryButTransientDisconnectRetainsIt() = runTest {
        val f = Fixture(this)
        val first = f.model.shown(messages)
        runCurrent()
        f.model.hidden(first)
        f.radio = null
        val disconnected = f.model.shown(messages)
        runCurrent()
        assertEquals(1, f.model.state.value.readings.size)
        assertEquals(messages.disconnected, f.model.state.value.errorMessage)
        f.model.hidden(disconnected)
        f.radio = Uuid.random()
        advanceTimeBy(10_000)
        f.model.shown(messages)
        runCurrent()
        assertEquals(listOf(10_000L), f.model.state.value.readings.map { it.timestampEpochMs })
        assertEquals(10_000L, f.model.state.value.chartStartEpochMs)
        assertNull(f.model.state.value.errorMessage)
    }

    @Test
    fun replacementRejectsNonCooperativeSuccessAndOldFinalizer() = runTest { rejectReplacement(false) }

    @Test
    fun replacementRejectsNonCooperativeFailureAndOldFinalizer() = runTest { rejectReplacement(true) }

    private suspend fun TestScope.rejectReplacement(fail: Boolean) {
        val f = Fixture(this)
        val old = f.model.shown(messages)
        runCurrent()
        val late = CompletableDeferred<Unit>()
        f.fetch = {
            withContext(NonCancellable) { late.await() }
            if (fail) error("retired session") else reading(123, -70)
        }
        advanceTimeBy(NOISE_FLOOR_POLL_INTERVAL_MS)
        runCurrent()
        f.owner = MutableSharedFlow()
        f.fetch = { reading(testScheduler.currentTime, -105) }
        val replacement = f.model.shown(messages)
        runCurrent()
        f.model.hidden(old)
        late.complete(Unit)
        runCurrent()
        assertEquals(listOf(-101, -105), f.model.state.value.readings.map { it.noiseFloor })
        assertNull(f.model.state.value.errorMessage)
        assertTrue(f.model.state.value.isPolling)
        f.model.hidden(replacement)
    }

    @Test
    fun firstFetchLatencyDoesNotMoveOrigin() = runTest {
        val f = Fixture(this)
        val pending = CompletableDeferred<NoiseFloorReading>()
        f.fetch = { pending.await() }
        f.model.shown(messages)
        runCurrent()
        advanceTimeBy(5000)
        pending.complete(reading(testScheduler.currentTime))
        runCurrent()
        assertEquals(0L, f.model.state.value.chartStartEpochMs)
        assertEquals(5000L, f.model.state.value.readings.single().timestampEpochMs)
    }

    @Test
    fun hiddenPendingFetchCannotPublishAfterReturn() = runTest {
        val f = Fixture(this)
        val late = CompletableDeferred<Unit>()
        f.fetch = {
            withContext(NonCancellable) { late.await() }
            reading(0, -70)
        }
        val old = f.model.shown(messages)
        runCurrent()
        f.model.hidden(old)
        f.fetch = { reading(testScheduler.currentTime, -105) }
        f.model.shown(messages)
        runCurrent()
        late.complete(Unit)
        runCurrent()
        assertEquals(-105, f.model.state.value.readings.single().noiseFloor)
        assertTrue(f.model.state.value.isPolling)
    }

    @Test
    fun providerReplacementRejectsFailureWithoutRemount() = runTest {
        val f = Fixture(this)
        val pending = CompletableDeferred<Unit>()
        f.fetch = {
            pending.await()
            error("old connection")
        }
        f.model.shown(messages)
        runCurrent()
        f.owner = MutableSharedFlow()
        f.fetch = { reading(testScheduler.currentTime) }
        pending.complete(Unit)
        runCurrent()
        assertNull(f.model.state.value.errorMessage)
        advanceTimeBy(NOISE_FLOOR_POLL_INTERVAL_MS)
        runCurrent()
        assertEquals(1, f.model.state.value.readings.size)
    }

    @Test
    fun failureRetainsSamplesAndLocalizedMessageCanChange() = runTest {
        val f = Fixture(this)
        f.model.shown(messages)
        runCurrent()
        f.fetch = { error("radio refused") }
        advanceTimeBy(NOISE_FLOOR_POLL_INTERVAL_MS)
        runCurrent()
        f.model.updateMessages(NoiseFloorViewModel.Messages("offline", "translated failure"))
        assertEquals(1, f.model.state.value.readings.size)
        assertEquals("translated failure", f.model.state.value.errorMessage)
    }

    @Test
    fun closeStopsPollingPermanently() = runTest {
        val f = Fixture(this)
        f.model.shown(messages)
        runCurrent()
        f.model.close()
        f.model.shown(messages)
        advanceTimeBy(10_000)
        runCurrent()
        assertEquals(1, f.calls)
        assertFalse(f.model.state.value.isPolling)
    }

    private class Fixture(scope: TestScope) {
        var radio: Uuid? = Uuid.random()
        var owner = MutableSharedFlow<MeshEvent>()
        var calls = 0
        var fetch: suspend () -> NoiseFloorReading? = {
            calls++
            reading(scope.testScheduler.currentTime)
        }
        val model = NoiseFloorViewModel(
            scope.backgroundScope,
            NoiseFloorViewModel.Dependencies({ fetch }, { owner }, { radio }),
            { scope.testScheduler.currentTime },
        )
    }

    private companion object {
        val messages = NoiseFloorViewModel.Messages("disconnected", "failed")
        fun reading(time: Long, noise: Int = -101) = NoiseFloorReading(Uuid.random(), time, noise, -70, 5.0)
    }
}
