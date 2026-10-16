package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.LinkPeripheralState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A failed ATT write drops the GATT link. Cancellation of that write is the scope stopping, so it
 * must not be recorded as a dead controller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleWriteFaultTest {
    @Test
    fun aCancelledWriteLeavesTheLinkUp() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = ThrowingWritePeripheral(CancellationException("stopped"))
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingDropListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = true)
        advanceUntilIdle()

        assertEquals(0, peripheral.disconnectCount)
        assertEquals(emptyList(), listener.drops)
        assertEquals(LinkPeripheralState.CONNECTED, peripheral.state.value)
    }

    @Test
    fun aWriteErrorDropsTheLink() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = ThrowingWritePeripheral(BleWriteProbeError())
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingDropListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = true)
        advanceUntilIdle()

        assertEquals(1, peripheral.disconnectCount)
        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        assertEquals(LinkPeripheralState.DISCONNECTED, peripheral.state.value)
    }
}

private class ThrowingWritePeripheral(private val writeFailure: Throwable) : BlePeripheral {
    private val linkState = MutableStateFlow(LinkPeripheralState.DISCONNECTED)
    override val state: StateFlow<LinkPeripheralState> = linkState.asStateFlow()
    override val advertisesWriteWithoutResponse: Boolean = true
    var disconnectCount: Int = 0
        private set

    override suspend fun connect() {
        linkState.value = LinkPeripheralState.CONNECTED
    }

    override suspend fun disconnect() {
        disconnectCount += 1
        linkState.value = LinkPeripheralState.DISCONNECTED
    }

    override fun observeNotifications(): Flow<ByteArray> = MutableSharedFlow()

    override suspend fun write(bytes: ByteArray, kind: BleWriteKind): Unit = throw writeFailure
}

private class RecordingDropListener : MeshLinkListener {
    val drops = mutableListOf<LinkDropReason>()

    override fun onReceived(bytes: ByteArray) = Unit

    override fun onDisconnected(reason: LinkDropReason) {
        drops += reason
    }
}

private class BleWriteProbeError : Error("probe")
