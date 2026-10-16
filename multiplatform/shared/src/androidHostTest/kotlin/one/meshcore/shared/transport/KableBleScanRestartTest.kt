package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.ui.BleAvailability
import one.meshcore.shared.ui.ScannedDevice
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * A Kable scan Flow that dies with a non-cancellation error must not leave the picker showing
 * [BleAvailability.READY] while nothing is scanning. iOS CoreBluetooth scan does not fail this way;
 * Android must restart while the sheet still holds the request.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class KableBleScanRestartTest {
    private class FakeAdapterObservation : AutoCloseable {
        override fun close() = Unit
    }

    @Test
    fun aFailedScanRestartsWhileTheRequestIsHeld() = runTest {
        var startCount = 0
        var attempts = 0
        val controller = KableBleScanController(
            scope = this,
            scanPermissionGranted = { true },
            adapterEnabled = { true },
            observeAdapterState = { _ -> FakeAdapterObservation() },
            options = KableBleScanController.Options(
                onStartingScan = { startCount++ },
                collectAdvertisements = {
                    attempts++
                    if (attempts >= 2) awaitCancellation()
                    error("scanner died")
                },
                scanRestartDelay = 1.seconds,
            ),
        )

        controller.startScan()
        runCurrent()
        assertEquals(1, startCount)
        assertEquals(BleAvailability.READY, controller.availability.value)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, startCount)

        controller.stopScan()
        advanceUntilIdle()
    }

    @Test
    fun aFailedScanShowsTheLivePermission() = runTest {
        var granted = true
        var startCount = 0
        val controller = KableBleScanController(
            scope = this,
            scanPermissionGranted = { granted },
            adapterEnabled = { true },
            observeAdapterState = { _ -> FakeAdapterObservation() },
            options = KableBleScanController.Options(
                onStartingScan = { startCount++ },
                collectAdvertisements = {
                    granted = false
                    error("scanner died")
                },
                scanRestartDelay = 1.seconds,
            ),
        )

        controller.startScan()
        runCurrent()
        assertEquals(BleAvailability.UNAUTHORIZED, controller.availability.value)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(1, startCount)

        controller.stopScan()
        advanceUntilIdle()
    }

    @Test
    fun aScanErrorRestartsWhileTheRequestIsHeld() = runTest {
        var startCount = 0
        var attempts = 0
        val controller = KableBleScanController(
            scope = this,
            scanPermissionGranted = { true },
            adapterEnabled = { true },
            observeAdapterState = { _ -> FakeAdapterObservation() },
            options = KableBleScanController.Options(
                onStartingScan = { startCount++ },
                collectAdvertisements = {
                    attempts++
                    if (attempts >= 2) awaitCancellation()
                    throw KableScanProbeError()
                },
                scanRestartDelay = 1.seconds,
            ),
        )

        controller.startScan()
        runCurrent()
        assertEquals(1, startCount)
        assertEquals(BleAvailability.READY, controller.availability.value)

        advanceTimeBy(1.seconds)
        runCurrent()
        assertEquals(2, startCount)

        controller.stopScan()
        advanceUntilIdle()
    }

    @Test
    fun aCancelledScanDoesNotRestart() = runTest {
        var startCount = 0
        val controller = KableBleScanController(
            scope = this,
            scanPermissionGranted = { true },
            adapterEnabled = { true },
            observeAdapterState = { _ -> FakeAdapterObservation() },
            options = KableBleScanController.Options(
                onStartingScan = { startCount++ },
                collectAdvertisements = { throw CancellationException("stopped") },
                scanRestartDelay = 1.seconds,
            ),
        )

        controller.startScan()
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(1, startCount)

        controller.stopScan()
        advanceUntilIdle()
    }

    @Test
    fun stoppingTheScanCancelsAPendingRestart() = runTest {
        var startCount = 0
        val controller = KableBleScanController(
            scope = this,
            scanPermissionGranted = { true },
            adapterEnabled = { true },
            observeAdapterState = { _ -> FakeAdapterObservation() },
            options = KableBleScanController.Options(
                onStartingScan = { startCount++ },
                collectAdvertisements = {
                    error("scanner died")
                },
                scanRestartDelay = 1.seconds,
            ),
        )

        controller.startScan()
        runCurrent()
        assertEquals(1, startCount)

        controller.stopScan()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(1, startCount)
    }

    @Test
    fun cancellationDoesNotCountAsAScanFailureRestart() = runTest {
        var startCount = 0
        val hang = Job()
        val controller = KableBleScanController(
            scope = this,
            scanPermissionGranted = { true },
            adapterEnabled = { true },
            observeAdapterState = { _ -> FakeAdapterObservation() },
            options = KableBleScanController.Options(
                onStartingScan = { startCount++ },
                collectAdvertisements = { hang.join() },
                scanRestartDelay = 1.seconds,
            ),
        )

        controller.startScan()
        runCurrent()
        assertEquals(1, startCount)

        controller.stopScan()
        hang.cancel()
        advanceTimeBy(5.seconds)
        runCurrent()

        assertEquals(1, startCount)
    }

    @Test
    fun aHangingScanDoesNotRestartUntilTheFlowFails() = runTest {
        var startCount = 0
        val controller = KableBleScanController(
            scope = this,
            scanPermissionGranted = { true },
            adapterEnabled = { true },
            observeAdapterState = { _ -> FakeAdapterObservation() },
            options = KableBleScanController.Options(
                onStartingScan = { startCount++ },
                collectAdvertisements = { awaitCancellation() },
                scanRestartDelay = 1.seconds,
            ),
        )

        controller.startScan()
        runCurrent()
        advanceTimeBy(5.seconds)
        runCurrent()
        assertEquals(1, startCount)

        controller.stopScan()
        runCurrent()
    }

    @Test
    fun aRadioThatStopsAdvertisingIsDropped() = runTest {
        val controller = KableBleScanController(
            scope = this,
            scanPermissionGranted = { true },
            adapterEnabled = { true },
            observeAdapterState = { _ -> FakeAdapterObservation() },
            options = KableBleScanController.Options(
                collectAdvertisements = { emit ->
                    emit(ScannedDevice(id = "a", name = "Alpha", rssi = -70))
                    awaitCancellation()
                },
                nowMillis = { testScheduler.currentTime },
            ),
        )

        controller.startScan()
        runCurrent()
        assertEquals(1, controller.devices.value.size)

        advanceTimeBy(6.seconds)
        runCurrent()
        assertEquals(emptyList(), controller.devices.value)

        controller.stopScan()
        runCurrent()
    }
}

private class KableScanProbeError : Error("probe")
