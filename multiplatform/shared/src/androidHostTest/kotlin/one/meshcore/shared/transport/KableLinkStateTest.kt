package one.meshcore.shared.transport

import com.juul.kable.State
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.LinkPeripheralState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * Kable names the Android `GATT_CONN_*` disconnect codes instead of surfacing them, so the
 * encryption-timeout tally (8, 22, 133) and the dead-bond set (5, 15, 137) must see the code
 * behind each named status. The link state must also stay readable after the peripheral scope
 * (where a `stateIn` would have run) is cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Suppress("MagicNumber") // Transport byte counts and timestamps are fixture values.
class KableLinkStateTest {
    @Test
    fun namedDisconnectStatusesMapToTheirGattCodes() {
        assertEquals(8, State.Disconnected.Status.Timeout.gattStatusCode())
        assertEquals(22, State.Disconnected.Status.CentralDisconnected.gattStatusCode())
        assertEquals(19, State.Disconnected.Status.PeripheralDisconnected.gattStatusCode())
        assertEquals(133, State.Disconnected.Status.Unknown(133).gattStatusCode())
        assertEquals(137, State.Disconnected.Status.Unknown(137).gattStatusCode())
    }

    @Test
    fun cleanAndAppleOnlyStatusesHaveNoGattCode() {
        assertNull((null as State.Disconnected.Status?).gattStatusCode())
        assertNull(State.Disconnected.Status.EncryptionTimedOut.gattStatusCode())
    }

    @Test
    fun timeoutAndLocalHostTerminateCountTowardEncryptionTimeout() {
        assertIs<EncryptionTimeoutException>(
            classifyGattConnectFailure(State.Disconnected.Status.Timeout.gattStatusCode()!!),
        )
        assertIs<EncryptionTimeoutException>(
            classifyGattConnectFailure(State.Disconnected.Status.CentralDisconnected.gattStatusCode()!!),
        )
        assertNull(classifyGattConnectFailure(State.Disconnected.Status.PeripheralDisconnected.gattStatusCode()!!))
    }

    @Test
    fun linkStateFollowsTheSourceWithoutACollector() = runTest(UnconfinedTestDispatcher()) {
        val source = MutableStateFlow<State>(State.Disconnected())
        val peripheralScope = CoroutineScope(Job())
        val state = KableLinkStateFlow(source)

        source.value = State.Connected(peripheralScope)
        assertEquals(LinkPeripheralState.CONNECTED, state.value)

        source.value = State.Disconnected(State.Disconnected.Status.Timeout)
        peripheralScope.cancel()
        assertEquals(LinkPeripheralState.DISCONNECTED, state.value)
    }

    @Test
    fun collectEmitsTheCurrentValueAndDedupesConnectingSubstates() = runTest(UnconfinedTestDispatcher()) {
        val source = MutableStateFlow<State>(State.Disconnected())
        val state = KableLinkStateFlow(source)
        val seen = mutableListOf<LinkPeripheralState>()
        val job = launch { state.collect { seen += it } }

        source.value = State.Connecting.Bluetooth
        source.value = State.Connecting.Services
        source.value = State.Connecting.Observes
        source.value = State.Connected(this)
        source.value = State.Disconnected()
        job.cancel()

        assertEquals(
            listOf(
                LinkPeripheralState.DISCONNECTED,
                LinkPeripheralState.CONNECTING,
                LinkPeripheralState.CONNECTED,
                LinkPeripheralState.DISCONNECTED,
            ),
            seen,
        )
    }
}
