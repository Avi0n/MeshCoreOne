package one.meshcore.shared.transport

import com.juul.kable.State
import kotlinx.coroutines.ExperimentalForInheritanceCoroutinesApi
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.StateFlow
import one.meshcore.shared.connection.LinkPeripheralState

// Android framework `GATT_CONN_*` codes behind the Kable `Disconnected.Status` objects. Kable names
// these instead of surfacing the code, so the classifier's status sets need them restored.
private const val GATT_CONN_L2C_FAILURE = 1
private const val GATT_CONN_TIMEOUT = 8
private const val GATT_CONN_TERMINATE_PEER_USER = 19
private const val GATT_CONN_TERMINATE_LOCAL_HOST = 22
private const val GATT_CONN_LMP_TIMEOUT = 34
private const val GATT_CONN_FAIL_ESTABLISH = 62
private const val GATT_CONN_CANCEL = 256

internal fun State.toLinkState(): LinkPeripheralState = when (this) {
    is State.Connected -> LinkPeripheralState.CONNECTED
    is State.Connecting -> LinkPeripheralState.CONNECTING
    is State.Disconnecting -> LinkPeripheralState.DISCONNECTING
    is State.Disconnected -> LinkPeripheralState.DISCONNECTED
}

/**
 * The Android GATT connection status behind a Kable disconnect, or null for a clean disconnect and
 * for the Apple-only statuses Android never reports.
 */
internal fun State.Disconnected.Status?.gattStatusCode(): Int? = when (this) {
    null -> null
    is State.Disconnected.Status.Unknown -> status
    State.Disconnected.Status.L2CapFailure -> GATT_CONN_L2C_FAILURE
    State.Disconnected.Status.Timeout -> GATT_CONN_TIMEOUT
    State.Disconnected.Status.PeripheralDisconnected -> GATT_CONN_TERMINATE_PEER_USER
    State.Disconnected.Status.CentralDisconnected -> GATT_CONN_TERMINATE_LOCAL_HOST
    State.Disconnected.Status.LinkManagerProtocolTimeout -> GATT_CONN_LMP_TIMEOUT
    State.Disconnected.Status.Failed -> GATT_CONN_FAIL_ESTABLISH
    State.Disconnected.Status.Cancelled -> GATT_CONN_CANCEL
    else -> null
}

/**
 * Kable's `State` projected onto the watchdog vocabulary without a collector coroutine. A `stateIn`
 * on `Peripheral.scope` can be cancelled by `Peripheral.close()` before it observes the final
 * `Disconnected`, freezing the link at CONNECTED and silencing the drop monitor.
 */
@OptIn(ExperimentalForInheritanceCoroutinesApi::class)
internal class KableLinkStateFlow(private val source: StateFlow<State>) : StateFlow<LinkPeripheralState> {
    override val value: LinkPeripheralState
        get() = source.value.toLinkState()

    override val replayCache: List<LinkPeripheralState>
        get() = listOf(value)

    override suspend fun collect(collector: FlowCollector<LinkPeripheralState>): Nothing {
        var last: LinkPeripheralState? = null
        source.collect { state ->
            val mapped = state.toLinkState()
            if (mapped != last) {
                last = mapped
                collector.emit(mapped)
            }
        }
    }
}
