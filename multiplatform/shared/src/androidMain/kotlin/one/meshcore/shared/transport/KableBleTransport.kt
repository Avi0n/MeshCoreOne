package one.meshcore.shared.transport

import com.juul.kable.AndroidPeripheral
import com.juul.kable.GattWriteException
import com.juul.kable.Peripheral
import com.juul.kable.PeripheralBuilder
import com.juul.kable.State
import com.juul.kable.WriteType
import com.juul.kable.characteristicOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import one.meshcore.shared.connection.LinkPeripheralState
import one.meshcore.shared.connection.ReconnectPolicy
import kotlin.time.Duration
import kotlin.uuid.ExperimentalUuidApi

/**
 * The Android BLE [MeshLink], backed by Kable. On [connect] it builds a Kable `Peripheral` for the
 * given [deviceId] (platform connection handle / MAC from the scan picker) and delegates orchestration
 * (connect watchdog, write throttling, notification framing, disconnect detection) to a [BleTransport]
 * over a [KableBlePeripheral]. [deviceId] is the picker or saved-radio handle and must be non-blank.
 *
 * [autoConnect] maps to Kable `autoConnectIf` / Android `connectGatt(autoConnect)`: the reconnect
 * factory sets it so a drop waits for the radio instead of failing the 10s direct-connect watchdog.
 *
 * The handle never leaks into persistence as identity (three-axis invariant). MAC randomization only
 * affects this handle, not the `radioID` partition key.
 */
class KableBleTransport(
    private val scope: CoroutineScope,
    private val deviceId: String,
    options: Options = Options(),
) : MeshLink {
    private val writePacingMillis = options.writePacingMillis
    private val policy = options.policy
    private val discoveryWindow = options.discoveryWindow
    private val autoConnect = options.autoConnect
    private val bluetoothConnectAuthorized = options.bluetoothConnectAuthorized
    private var delegate: BleTransport? = null
    private var listener: MeshLinkListener? = null

    override val advertisesWriteWithoutResponse: Boolean
        get() = delegate?.advertisesWriteWithoutResponse ?: false

    override val isConnected: Boolean
        get() = delegate?.isConnected == true

    override fun setListener(listener: MeshLinkListener?) {
        this.listener = listener
        delegate?.setListener(listener)
    }

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun connect() {
        if (!bluetoothConnectAuthorized()) throw BluetoothUnauthorizedException()
        val peripheral = try {
            resolvePeripheral()
        } catch (error: SecurityException) {
            throw BluetoothUnauthorizedException(error)
        }
        val transport = BleTransport(
            peripheral = KableBlePeripheral(
                peripheral,
                holdEmptyGatt = autoConnect,
                emptyGattHold = discoveryWindow,
            ),
            scope = scope,
            options = BleTransport.Options(
                writePacingMillis = writePacingMillis,
                policy = policy,
                gattConnectTimeout = BleConnectMode.gattConnectTimeout(autoConnect),
                discoveryWindow = discoveryWindow,
                deviceId = deviceId,
            ),
        )
        transport.setListener(listener)
        delegate = transport
        transport.connect()
    }

    /**
     * Direct [Peripheral] for [deviceId] (the id the user selected in the scan picker).
     * A blank id is not a scan-first token — the picker and reconnect factory always supply a handle.
     */
    @OptIn(ExperimentalUuidApi::class)
    private fun resolvePeripheral(): Peripheral {
        val builder: PeripheralBuilder.() -> Unit = {
            applyMeshCoreConnectOptions(autoConnect)
        }
        val target = deviceId.trim()
        require(target.isNotEmpty()) { "KableBleTransport requires a non-blank deviceId" }
        return Peripheral(target, builder)
    }

    override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) {
        delegate?.sendBytes(bytes, withoutResponse)
    }

    override suspend fun disconnect() {
        delegate?.disconnect()
        delegate = null
    }

    data class Options(
        val writePacingMillis: Long = 0,
        val policy: ReconnectPolicy = ReconnectPolicy(),
        val discoveryWindow: Duration = BleTransport.DEFAULT_DISCOVERY_WINDOW,
        val autoConnect: Boolean = false,
        val bluetoothConnectAuthorized: () -> Boolean = { true },
    )
}

/** Full ATT MTU request; Android negotiates down to what the link supports. */
internal const val REQUESTED_MTU = 517

internal fun PeripheralBuilder.applyMeshCoreConnectOptions(autoConnect: Boolean) {
    autoConnectIf { autoConnect }
    onServicesDiscovered { requestMtu(REQUESTED_MTU) }
}

/**
 * A [BlePeripheral] over a Kable `Peripheral`: maps Kable's `State` to the watchdog's vocabulary and
 * routes notifications/writes to the Nordic-UART rx/tx characteristics.
 */
@OptIn(ExperimentalUuidApi::class)
private class KableBlePeripheral(
    private val peripheral: Peripheral,
    private val holdEmptyGatt: Boolean = false,
    private val emptyGattHold: Duration = BleTransport.DEFAULT_DISCOVERY_WINDOW,
) : BlePeripheral {
    // Discovery fills the GATT table once per connection and the capability never drops back, so a proven
    // capability is remembered while an unproven one keeps re-reading. Written only from the single writer
    // coroutine, but volatile because the read can also come from a state observer.
    @Volatile
    private var provenWriteWithoutResponse = false
    private var observationJob: Job? = null

    // Every notification is one protocol frame, so RX is buffered without bound and without a subscriber
    // rather than dropped: a burst that outruns the main-thread pump, or a frame that lands between CCCD
    // enable and the pump's subscription, must reach the session.
    private val incoming = Channel<ByteArray>(Channel.UNLIMITED)

    /**
     * Read from the discovered tx characteristic rather than assumed, so a radio whose write
     * characteristic carries no Write Commands degrades to acknowledged writes instead of firing
     * commands into a characteristic that cannot accept them.
     */
    override val advertisesWriteWithoutResponse: Boolean
        get() {
            provenWriteWithoutResponse = rememberWriteWithoutResponse(
                alreadyProven = provenWriteWithoutResponse,
                characteristics = peripheral.services.value.orEmpty().writeCharacteristics(),
            )
            return provenWriteWithoutResponse
        }

    override val state: StateFlow<LinkPeripheralState> = KableLinkStateFlow(peripheral.state)

    override suspend fun connect() {
        runKableConnect(::lastDisconnectGattStatus) {
            peripheral.connect()
            val characteristics = { peripheral.services.value.orEmpty().writeCharacteristics() }
            if (holdEmptyGatt) {
                awaitNordicUartTxRx(
                    snapshots = peripheral.services.map { it.orEmpty().writeCharacteristics() },
                    window = emptyGattHold,
                )
            } else {
                requireNordicUartTxRx(characteristics())
            }
            awaitRxNotificationsEnabled()
        }
    }

    override suspend fun disconnect() {
        observationJob?.cancel()
        observationJob = null
        try {
            withContext(NonCancellable) { peripheral.disconnect() }
        } finally {
            peripheral.close()
        }
    }

    override fun lastDisconnectError(): String? {
        val disconnected = peripheral.state.value as? State.Disconnected ?: return null
        val status = disconnected.status ?: return null
        return when (status) {
            is State.Disconnected.Status.Unknown -> "status=${status.status}"
            else -> status.toString()
        }
    }

    override fun lastDropReason(): LinkDropReason = dropReasonForGattStatus(lastDisconnectGattStatus())

    private fun lastDisconnectGattStatus(): Int? =
        (peripheral.state.value as? State.Disconnected)?.status.gattStatusCode()

    override fun observeNotifications(): Flow<ByteArray> = incoming.receiveAsFlow()

    /** Collects RX so notify is enabled before this connect returns. */
    private suspend fun awaitRxNotificationsEnabled() {
        val subscribed = CompletableDeferred<Unit>()
        suspend fun collectRx() {
            runKableObserved(
                onFailure = { error ->
                    subscribed.completeExceptionally(error)
                    throw error
                },
            ) {
                peripheral.observe(RX_CHARACTERISTIC) { subscribed.complete(Unit) }
                    .collect { incoming.trySend(it) }
            }
        }
        suspend fun failRxSubscription(error: Throwable): Nothing {
            observationJob?.cancel()
            observationJob = null
            val mapped = rxNotifyFailure(error, holdEmptyGatt)
            if (mapped !== error) throw mapped
            runKableObserved(
                onFailure = { cleanupError ->
                    if (cleanupError !== error) error.addSuppressed(cleanupError)
                },
            ) { peripheral.disconnect() }
            throw error
        }
        observationJob = peripheral.scope.launch { collectRx() }
        runKableObserved(onFailure = { error -> failRxSubscription(error) }) {
            subscribed.await()
        }
    }

    override suspend fun write(bytes: ByteArray, kind: BleWriteKind) {
        try {
            peripheral.write(TX_CHARACTERISTIC, bytes, kind.toWriteType())
        } catch (e: CancellationException) {
            throw e
        } catch (e: GattWriteException) {
            if (kind == BleWriteKind.WITHOUT_RESPONSE && isWriteBusy(e)) {
                throw WriteBusyException(e)
            }
            throw e
        }
    }

    private fun BleWriteKind.toWriteType(): WriteType = when (this) {
        BleWriteKind.WITH_RESPONSE -> WriteType.WithResponse
        BleWriteKind.WITHOUT_RESPONSE -> WriteType.WithoutResponse
    }

    private companion object {
        // tx: the central writes to this characteristic. rx: the central receives notifications on it.
        val TX_CHARACTERISTIC = characteristicOf(service = NORDIC_UART_SERVICE, characteristic = NORDIC_UART_TX)
        val RX_CHARACTERISTIC = characteristicOf(service = NORDIC_UART_SERVICE, characteristic = NORDIC_UART_RX)
    }
}

/** Android `ERROR_GATT_WRITE_REQUEST_BUSY`, or pre-API-33 `writeCharacteristic` false. */
internal fun isWriteBusyResult(result: AndroidPeripheral.WriteResult): Boolean =
    result == AndroidPeripheral.WriteResult.WriteRequestBusy ||
        result == AndroidPeripheral.WriteResult.Unknown

internal fun isWriteBusy(error: GattWriteException): Boolean = isWriteBusyResult(error.result)
