package one.meshcore.shared.transport

import android.content.Context
import com.juul.kable.Scanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.RSSI_EXPIRY_TICK_MS
import one.meshcore.shared.connection.RssiScanTracker
import one.meshcore.shared.ui.BleAvailability
import one.meshcore.shared.ui.BleScanController
import one.meshcore.shared.ui.ScannedDevice
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.uuid.ExperimentalUuidApi

/**
 * The Android [BleScanController], backed by Kable's [Scanner]. It scans for the MeshCore Nordic-UART
 * service and accumulates the discovered radios (deduplicated by peripheral id, RSSI smoothed and
 * stale adverts expired like iOS `RSSIScanTracker`) so the onboarding picker — modelled on the macOS
 * `DeviceScannerSheet` — can list them. Kable is confined to
 * androidMain (as with [KableBleTransport]); the picker composable in commonMain sees only the interface.
 *
 * [availability] is derived from the live permission and adapter state, re-derived whenever a scan starts
 * and whenever the adapter is switched on or off, mirroring the Swift `handleBluetoothStateChange`. It is
 * never latched off a failure: a grant or a power-on has to be able to clear the picker's remedy state,
 * or the user is left with a prompt that can never be satisfied.
 *
 * Scanning surfaces the peripheral's volatile advertisement id as [ScannedDevice.id]; that id is a
 * connection handle, never persisted as identity (three-axis invariant). A Kable scan Flow that
 * fails or completes while the picker still wants results restarts after a short delay; stopping the
 * scan cancels that restart. CoreBluetooth has no equivalent terminating Flow.
 */
class KableBleScanController internal constructor(
    private val scope: CoroutineScope,
    private val scanPermissionGranted: () -> Boolean,
    private val adapterEnabled: () -> Boolean,
    private val observeAdapterState: (onChanged: () -> Unit) -> AutoCloseable,
    options: Options = Options(),
) : BleScanController {
    private val onStartingScan = options.onStartingScan
    private val collectAdvertisements = options.collectAdvertisements
    private val scanRestartDelay = options.scanRestartDelay
    private val nowMillis = options.nowMillis

    constructor(scope: CoroutineScope, context: Context) : this(
        scope,
        { context.hasBleScanPermission() },
        { context.bluetoothAdapterEnabled() },
        { onChanged -> context.observeBluetoothAdapterState(onChanged) },
    )

    private val _availability = MutableStateFlow(currentAvailability())
    override val availability: StateFlow<BleAvailability> = _availability.asStateFlow()

    private val _devices = MutableStateFlow<List<ScannedDevice>>(emptyList())
    override val devices: StateFlow<List<ScannedDevice>> = _devices.asStateFlow()

    private val tracker = RssiScanTracker(nowMillis)

    private var scanJob: Job? = null
    private var adapterObservation: AutoCloseable? = null
    private var scanRequested = false

    override fun startScan() {
        scanRequested = true
        refreshAvailability()
        if (adapterObservation == null) adapterObservation = observeAdapterState(::onAdapterChanged)
        // Hold the request while the adapter is off so a power-on can resume without closing the sheet
        // (iOS `pendingScanRequest` + `startScanning` on `.poweredOn`).
        if (!adapterEnabled() || !scanPermissionGranted()) {
            scanJob?.cancel()
            scanJob = null
            return
        }
        // A completed job — one whose scan failed — must not block a retry, or the grant that fixes the
        // failure can never take effect.
        if (scanJob?.isActive == true) return
        onStartingScan()
        tracker.clear()
        _devices.value = emptyList()
        scanJob = scope.launch {
            val expiry = launch {
                while (true) {
                    delay(RSSI_EXPIRY_TICK_MS)
                    tracker.expireStale()
                    _devices.value = tracker.devices()
                }
            }
            try {
                runKableScan(
                    onFailure = {
                        // Re-derive rather than assume: a scan usually fails because the permission is
                        // missing or the adapter is off, and both show as a remedy the user can act on.
                        // A failure with neither wrong keeps the picker scanning, matching iOS mapping
                        // every state it cannot name to `.ready`.
                        refreshAvailability()
                    },
                ) {
                    collectNordicUartAdvertisements { device ->
                        tracker.ingest(device)
                        _devices.value = tracker.devices()
                    }
                }
            } finally {
                expiry.cancel()
            }
            // A finished or failed Flow is not a user stop; hold the request and scan again.
            delay(scanRestartDelay.inWholeMilliseconds)
            if (scanRequested && adapterEnabled() && scanPermissionGranted()) {
                scanJob = null
                startScan()
            }
        }
    }

    override fun stopScan() {
        scanRequested = false
        scanJob?.cancel()
        scanJob = null
        adapterObservation?.close()
        adapterObservation = null
    }

    private fun onAdapterChanged() {
        refreshAvailability()
        if (!adapterEnabled()) {
            scanJob?.cancel()
            scanJob = null
            return
        }
        if (scanRequested && scanJob?.isActive != true) {
            startScan()
        }
    }

    private suspend fun collectNordicUartAdvertisements(emit: (ScannedDevice) -> Unit) {
        val custom = collectAdvertisements
        if (custom != null) {
            custom(emit)
            return
        }
        meshCoreScanner().advertisements.collect { advertisement ->
            emit(
                ScannedDevice(
                    id = advertisement.identifier.toString(),
                    name = advertisement.name,
                    rssi = advertisement.rssi,
                ),
            )
        }
    }

    // Built inside the scan coroutine so constructing the controller touches no Bluetooth stack.
    @OptIn(ExperimentalUuidApi::class)
    private fun meshCoreScanner() = Scanner {
        filters {
            match { services = listOf(NORDIC_UART_SERVICE) }
        }
    }

    private fun refreshAvailability() {
        _availability.value = currentAvailability()
    }

    private fun currentAvailability(): BleAvailability =
        bleScanAvailability(scanPermissionGranted = scanPermissionGranted(), adapterEnabled = adapterEnabled())

    internal data class Options(
        val onStartingScan: () -> Unit = {},
        val collectAdvertisements: (suspend (emit: (ScannedDevice) -> Unit) -> Unit)? = null,
        val scanRestartDelay: Duration = SCAN_ERROR_RESTART_DELAY,
        val nowMillis: () -> Long = { System.currentTimeMillis() },
    ) {
        private companion object {
            val SCAN_ERROR_RESTART_DELAY = 1.seconds
        }
    }
}
