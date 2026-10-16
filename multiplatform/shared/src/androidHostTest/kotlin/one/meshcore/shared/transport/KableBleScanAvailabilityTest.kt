package one.meshcore.shared.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import one.meshcore.shared.ui.BleAvailability
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Scan availability must track the live permission and adapter state, never latch off one reading.
 * A latched `UNAUTHORIZED` leaves the picker showing a permission prompt the user has already answered,
 * and the scan never starts. Mirrors the Swift `handleBluetoothStateChange`, which re-derives
 * availability from the current `CBManagerState` on every update.
 */
class KableBleScanAvailabilityTest {
    // Swallows the scan coroutine so the controller's own logic runs without a Bluetooth stack.
    private object NeverDispatches : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) = Unit
    }

    private class FakeAdapterObservation : AutoCloseable {
        var closed = false

        override fun close() {
            closed = true
        }
    }

    private var granted = false
    private var enabled = true
    private var onAdapterChanged: (() -> Unit)? = null
    private val observation = FakeAdapterObservation()

    private fun controller(onStartingScan: () -> Unit = {}) = KableBleScanController(
        scope = CoroutineScope(Job() + NeverDispatches),
        scanPermissionGranted = { granted },
        adapterEnabled = { enabled },
        observeAdapterState = { onChanged ->
            onAdapterChanged = onChanged
            observation
        },
        options = KableBleScanController.Options(onStartingScan = onStartingScan),
    )

    @Test
    fun missingPermissionReportsUnauthorized() {
        granted = false
        assertEquals(BleAvailability.UNAUTHORIZED, controller().availability.value)
    }

    @Test
    fun poweredOffAdapterReportsPoweredOff() {
        granted = true
        enabled = false
        assertEquals(BleAvailability.POWERED_OFF, controller().availability.value)
    }

    @Test
    fun grantedPermissionOnALiveAdapterIsReady() {
        granted = true
        enabled = true
        assertEquals(BleAvailability.READY, controller().availability.value)
    }

    @Test
    fun aLaterGrantClearsTheRemedy() {
        granted = false
        val controller = controller()
        controller.startScan()
        assertEquals(BleAvailability.UNAUTHORIZED, controller.availability.value)

        granted = true
        controller.stopScan()
        controller.startScan()

        assertEquals(BleAvailability.READY, controller.availability.value)
    }

    @Test
    fun switchingTheAdapterOnClearsTheRemedy() {
        granted = true
        enabled = false
        val controller = controller()
        controller.startScan()
        assertEquals(BleAvailability.POWERED_OFF, controller.availability.value)

        enabled = true
        onAdapterChanged?.invoke()

        assertEquals(BleAvailability.READY, controller.availability.value)
    }

    @Test
    fun switchingTheAdapterOnResumesAHeldScanRequest() {
        granted = true
        enabled = false
        var startCount = 0
        val controller = controller(onStartingScan = { startCount++ })
        controller.startScan()
        assertEquals(BleAvailability.POWERED_OFF, controller.availability.value)
        assertEquals(0, startCount)

        enabled = true
        onAdapterChanged?.invoke()

        assertEquals(BleAvailability.READY, controller.availability.value)
        assertEquals(1, startCount)
    }

    @Test
    fun stoppingTheScanClearsTheHeldRequest() {
        granted = true
        enabled = true
        var startCount = 0
        val controller = controller(onStartingScan = { startCount++ })
        controller.startScan()
        assertEquals(1, startCount)

        controller.stopScan()
        enabled = false
        onAdapterChanged?.invoke()
        enabled = true
        onAdapterChanged?.invoke()

        assertEquals(1, startCount)
        assertTrue(observation.closed)
    }

    @Test
    fun stoppingTheScanReleasesTheAdapterObservation() {
        granted = true
        val controller = controller()
        controller.startScan()
        assertTrue(onAdapterChanged != null)

        controller.stopScan()

        assertTrue(observation.closed)
    }
}
