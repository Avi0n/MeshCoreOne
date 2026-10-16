package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.connection.LinkPeripheralState
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Exercises the [BleTransport] orchestration over a fake peripheral: the write-type/pacing decision,
 * ordered writes, the "each notification is one frame, empties dropped" read path, and connection-loss
 * signalling. Kable is not involved — this is the isolated, testable core of the Kable transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportTest {
    private class FakePeripheral(override val advertisesWriteWithoutResponse: Boolean, options: Options = Options()) :
        BlePeripheral {
        private val hangConnect = options.hangConnect
        private val hangWrite = options.hangWrite
        private val hangNotifications = options.hangNotifications
        private val connectError = options.connectError
        private val writeError = options.writeError
        private val notificationError = options.notificationError
        private val dropReason = options.dropReason
        private val dropBeforeConnectReturns = options.dropBeforeConnectReturns

        data class Options(
            val hangConnect: CompletableDeferred<Unit>? = null,
            val hangWrite: CompletableDeferred<Unit>? = null,
            val hangNotifications: CompletableDeferred<Unit>? = null,
            val connectError: Throwable? = null,
            val writeError: Throwable? = null,
            val notificationError: Throwable? = null,
            val dropReason: LinkDropReason = LinkDropReason.CONNECTION_LOST,
            val dropBeforeConnectReturns: Boolean = false,
        )

        private val _state = MutableStateFlow(LinkPeripheralState.DISCONNECTED)
        override val state: StateFlow<LinkPeripheralState> = _state.asStateFlow()
        val notifications = MutableSharedFlow<ByteArray>(extraBufferCapacity = 16)
        val writes = mutableListOf<Pair<ByteArray, BleWriteKind>>()
        var writeAttempts = 0
            private set
        var observeStarts = 0
            private set
        var disconnectCount = 0
            private set
        var disconnectError: Throwable? = null

        override suspend fun connect() {
            hangConnect?.await()
            _state.value = LinkPeripheralState.CONNECTED
            hangNotifications?.await()
            connectError?.let { throw it }
            if (dropBeforeConnectReturns) _state.value = LinkPeripheralState.DISCONNECTED
        }

        override suspend fun disconnect() {
            disconnectCount++
            disconnectError?.let { throw it }
            _state.value = LinkPeripheralState.DISCONNECTED
        }

        override fun observeNotifications(): Flow<ByteArray> {
            observeStarts++
            val error = notificationError
            return if (error != null) {
                kotlinx.coroutines.flow.flow { throw error }
            } else {
                notifications
            }
        }

        override suspend fun write(bytes: ByteArray, kind: BleWriteKind) {
            writeAttempts++
            hangWrite?.await()
            writeError?.let { throw it }
            writes += bytes to kind
        }

        override fun lastDropReason(): LinkDropReason = dropReason

        fun setState(state: LinkPeripheralState) {
            _state.value = state
        }
    }

    private class RecordingListener : MeshLinkListener {
        val received = mutableListOf<ByteArray>()
        val drops = mutableListOf<LinkDropReason>()

        override fun onReceived(bytes: ByteArray) {
            received += bytes
        }

        override fun onDisconnected(reason: LinkDropReason) {
            drops += reason
        }
    }

    @Test
    fun writeWithoutResponseCapabilityFollowsThePeripheral() = runTest(UnconfinedTestDispatcher()) {
        val withCommand = FakePeripheral(advertisesWriteWithoutResponse = true)
        val withoutCommand = FakePeripheral(advertisesWriteWithoutResponse = false)
        val nrf = BleTransport(withCommand, backgroundScope)
        val esp = BleTransport(withoutCommand, backgroundScope)
        nrf.connect()
        esp.connect()
        assertEquals(true, nrf.advertisesWriteWithoutResponse)
        assertEquals(false, esp.advertisesWriteWithoutResponse)
        assertEquals(true, nrf.supportsPipelinedReads)
        assertEquals(false, esp.supportsPipelinedReads)
    }

    @Test
    fun isConnectedFollowsPeripheralState() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(advertisesWriteWithoutResponse = true)
        val transport = BleTransport(peripheral, backgroundScope)
        assertEquals(false, transport.isConnected)
        transport.connect()
        assertEquals(true, transport.isConnected)
        peripheral.setState(LinkPeripheralState.DISCONNECTED)
        assertEquals(false, transport.isConnected)
    }

    @Test
    fun connectTimeoutsMatchIosGattDiscoveryAndAutoReconnectClocks() {
        assertEquals(10.seconds, BleTransport.DEFAULT_GATT_CONNECT_TIMEOUT)
        assertEquals(40.seconds, BleTransport.DEFAULT_DISCOVERY_WINDOW)
        assertEquals(15.seconds, BleTransport.AUTO_RECONNECT_DISCOVERY_WINDOW)
        assertEquals(kotlin.time.Duration.INFINITE, BleConnectMode.gattConnectTimeout(autoConnect = true))
        assertEquals(10.seconds, BleConnectMode.gattConnectTimeout(autoConnect = false))
    }

    @Test
    fun writesUsePipelinedWriteCommandWhenTheCharacteristicAdvertisesIt() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(advertisesWriteWithoutResponse = true)
        val transport = BleTransport(peripheral, backgroundScope)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = true)
        advanceUntilIdle()

        assertEquals(1, peripheral.writes.size)
        assertEquals(BleWriteKind.WITHOUT_RESPONSE, peripheral.writes.single().second)
    }

    @Test
    fun writesDegradeToAcknowledgedWhenTheCharacteristicDoesNotAdvertiseIt() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(advertisesWriteWithoutResponse = false)
        val transport = BleTransport(peripheral, backgroundScope)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = true)
        advanceUntilIdle()

        assertEquals(BleWriteKind.WITH_RESPONSE, peripheral.writes.single().second)
    }

    @Test
    fun writesDrainInSubmissionOrder() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(advertisesWriteWithoutResponse = true)
        val transport = BleTransport(peripheral, backgroundScope)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = false)
        transport.sendBytes(byteArrayOf(0x02), withoutResponse = false)
        transport.sendBytes(byteArrayOf(0x03), withoutResponse = false)
        advanceUntilIdle()

        // Compare as Byte lists so ByteArray content (not identity) is checked.
        assertEquals(
            listOf(byteArrayOf(0x01).toList(), byteArrayOf(0x02).toList(), byteArrayOf(0x03).toList()),
            peripheral.writes.map { it.first.toList() },
        )
    }

    @Test
    fun notificationsAreForwardedAsFramesWithEmptiesDropped() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(advertisesWriteWithoutResponse = true)
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        peripheral.notifications.emit(byteArrayOf(0x05, 0x01))
        peripheral.notifications.emit(ByteArray(0))
        peripheral.notifications.emit(byteArrayOf(0x07, 0x02))
        advanceUntilIdle()

        assertEquals(
            listOf(byteArrayOf(0x05, 0x01).toList(), byteArrayOf(0x07, 0x02).toList()),
            listener.received.map { it.toList() },
        )
    }

    @Test
    fun aDropAfterBeingConnectedNotifiesTheListener() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(advertisesWriteWithoutResponse = true)
        val recorded = mutableListOf<DebugLogEntryDto>()
        val transport = BleTransport(
            peripheral = peripheral,
            scope = backgroundScope,
            options = BleTransport.Options(
                deviceId = "AABBCCDD-EEEE-FFFF-0000-111111111111",
            ),
            logger = PersistentLogger(
                scope = this,
                category = "BLEStateMachine",
                record = { recorded += it },
            ),
        )
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        peripheral.setState(LinkPeripheralState.DISCONNECTED)
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        val line = recorded.single { it.message.startsWith("[BLE] Did disconnect:") }
        assertTrue(line.message.contains("AABBCCDD"))
        assertTrue(line.message.contains("peripheralState: disconnected"))
        assertTrue(line.message.contains("error: none"))
    }

    @Test
    fun aDropBeforeTheMonitorStartsIsStillReported() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(dropBeforeConnectReturns = true),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
    }

    @Test
    fun aDefinitiveBondDropNotifiesBondLost() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(dropReason = LinkDropReason.BOND_LOST),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        peripheral.setState(LinkPeripheralState.DISCONNECTED)
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.BOND_LOST), listener.drops)
    }

    @Test
    fun aTimedOutWriteDisconnectsInsteadOfSkipping() = runTest(UnconfinedTestDispatcher()) {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = false,
            options = FakePeripheral.Options(hangWrite = hang),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = false)
        transport.sendBytes(byteArrayOf(0x02), withoutResponse = false)
        advanceTimeBy(4.seconds)
        assertTrue(listener.drops.isEmpty(), "the write backstop must still be waiting before 5s")
        advanceTimeBy(2.seconds)
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        assertEquals(LinkPeripheralState.DISCONNECTED, peripheral.state.value)
        assertEquals(1, peripheral.writeAttempts)
        hang.cancel()
    }

    @Test
    fun aTimedOutWriteCommandDisconnectsInsteadOfSkipping() = runTest(UnconfinedTestDispatcher()) {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangWrite = hang),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = true)
        advanceTimeBy(6.seconds)
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        hang.cancel()
    }

    @Test
    fun aThrownWriteCommandDisconnectsAndDoesNotDrainLaterFrames() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(writeError = IllegalStateException("GATT write not permitted")),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = true)
        transport.sendBytes(byteArrayOf(0x02), withoutResponse = true)
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        assertEquals(LinkPeripheralState.DISCONNECTED, peripheral.state.value)
        assertEquals(1, peripheral.writeAttempts)
        assertTrue(peripheral.writes.isEmpty())
    }

    @Test
    fun aThrownWriteRequestDisconnectsAndDoesNotDrainLaterFrames() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = false,
            options = FakePeripheral.Options(writeError = IllegalStateException("GATT INVALID ATTRIBUTE LENGTH")),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        transport.sendBytes(byteArrayOf(0x01), withoutResponse = false)
        transport.sendBytes(byteArrayOf(0x02), withoutResponse = false)
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        assertEquals(LinkPeripheralState.DISCONNECTED, peripheral.state.value)
        assertEquals(1, peripheral.writeAttempts)
        assertTrue(peripheral.writes.isEmpty())
    }

    @Test
    fun aNotificationPumpFailureDisconnectsAStillConnectedLink() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(notificationError = IllegalStateException("CCCD subscribe failed")),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()

        assertEquals(listOf(LinkDropReason.CONNECTION_LOST), listener.drops)
        assertEquals(LinkPeripheralState.DISCONNECTED, peripheral.state.value)
    }

    @Test
    fun autoConnectGattWaitDoesNotGiveUpAtTenSeconds() = runTest {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangConnect = hang),
        )
        val transport = BleTransport(
            peripheral,
            backgroundScope,
            BleTransport.Options(
                gattConnectTimeout = BleConnectMode.gattConnectTimeout(autoConnect = true),
            ),
        )
        val result = async { runCatching { transport.connect() } }

        advanceTimeBy(30.seconds)
        assertTrue(result.isActive, "OS autoConnect must wait for the radio, not fail at the 10s GATT ceiling")

        hang.complete(Unit)
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)
    }

    @Test
    fun aCancelledConnectDisconnectsThePeripheral() = runTest {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangConnect = hang),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val attempt = launch { transport.connect() }

        advanceTimeBy(1.seconds)
        peripheral.setState(LinkPeripheralState.CONNECTING)
        attempt.cancel()
        advanceUntilIdle()

        assertEquals(1, peripheral.disconnectCount, "cancelling the awaiter must not leave the GATT connect running")
        hang.cancel()
    }

    @Test
    fun cancelledTimeoutCleanupIsNotReportedAsAConnectTimeout() = runTest {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangConnect = hang),
        ).apply { disconnectError = CancellationException("disconnect cancelled") }
        val transport = BleTransport(
            peripheral,
            backgroundScope,
            BleTransport.Options(gattConnectTimeout = 1.seconds),
        )

        val cancelled = assertFailsWith<CancellationException> { transport.connect() }

        assertEquals("disconnect cancelled", cancelled.message)
        assertTrue(peripheral.disconnectCount > 0)
        hang.cancel()
    }

    @Test
    fun gattConnectGivesUpAfterTenSecondsWhenTheLinkNeverComesUp() = runTest {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangConnect = hang),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val result = async { runCatching { transport.connect() } }

        advanceTimeBy(9.seconds)
        assertTrue(result.isActive, "GATT connect must still be waiting before 10s")
        advanceTimeBy(2.seconds)

        val error = result.await().exceptionOrNull()
        assertIs<MeshLinkConnectException>(error)
        hang.cancel()
    }

    @Test
    fun pairingDiscoverySurvivesPastTheGattTimeoutWhileTheLinkIsUp() = runTest {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangConnect = hang),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val result = async { runCatching { transport.connect() } }

        advanceTimeBy(1.seconds)
        peripheral.setState(LinkPeripheralState.CONNECTED)
        advanceTimeBy(20.seconds)
        assertTrue(result.isActive, "a connected pairing/discovery must not be cancelled at the 10s GATT ceiling")

        hang.complete(Unit)
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)
    }

    @Test
    fun aConnectedLinkThatNeverFinishesDiscoveryIsBondLoss() = runTest {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangConnect = hang),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val result = async { runCatching { transport.connect() } }

        advanceTimeBy(1.seconds)
        peripheral.setState(LinkPeripheralState.CONNECTED)
        // Three 40s discovery windows: two extensions, then bond-loss classification.
        advanceTimeBy(121.seconds)

        val error = result.await().exceptionOrNull()
        assertIs<BondLostException>(error)
        hang.cancel()
    }

    @Test
    fun autoReconnectDiscoveryBondLossScalesToFifteenSeconds() = runTest {
        val hang = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangConnect = hang),
        )
        val transport = BleTransport(
            peripheral,
            backgroundScope,
            BleTransport.Options(
                discoveryWindow = BleTransport.AUTO_RECONNECT_DISCOVERY_WINDOW,
            ),
        )
        val result = async { runCatching { transport.connect() } }

        advanceTimeBy(1.seconds)
        peripheral.setState(LinkPeripheralState.CONNECTED)
        // Three 15s reconnect windows must fire well before the pairing 40s clock.
        advanceTimeBy(44.seconds)
        assertTrue(result.isActive, "two 15s extensions must still be waiting")
        advanceTimeBy(2.seconds)

        val error = result.await().exceptionOrNull()
        assertIs<BondLostException>(error)
        hang.cancel()
    }

    @Test
    fun disconnectCancelsTheDropMonitor() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(advertisesWriteWithoutResponse = true)
        val transport = BleTransport(peripheral, backgroundScope)
        val listener = RecordingListener()
        transport.setListener(listener)
        transport.connect()
        advanceUntilIdle()
        transport.disconnect()
        advanceUntilIdle()
        val dropsAfterDisconnect = listener.drops.size

        peripheral.setState(LinkPeripheralState.CONNECTED)
        peripheral.setState(LinkPeripheralState.DISCONNECTED)
        advanceUntilIdle()

        assertEquals(dropsAfterDisconnect, listener.drops.size)
    }

    @Test
    fun missingNordicUartThrowsAndLeavesGattUpWithoutStartingThePump() = runTest(UnconfinedTestDispatcher()) {
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(connectError = CharacteristicNotFoundException()),
        )
        val transport = BleTransport(peripheral, backgroundScope)

        val error = assertFailsWith<CharacteristicNotFoundException> { transport.connect() }

        assertEquals("Unable to communicate with device. Please try reconnecting.", error.message)
        assertEquals(LinkPeripheralState.CONNECTED, peripheral.state.value)
        assertEquals(true, transport.isConnected)
        assertEquals(0, peripheral.observeStarts)
    }

    @Test
    fun connectDoesNotReturnUntilRxNotificationsAreEnabled() = runTest(UnconfinedTestDispatcher()) {
        val hangNotifications = CompletableDeferred<Unit>()
        val peripheral = FakePeripheral(
            advertisesWriteWithoutResponse = true,
            options = FakePeripheral.Options(hangNotifications = hangNotifications),
        )
        val transport = BleTransport(peripheral, backgroundScope)
        val result = async { runCatching { transport.connect() } }

        assertTrue(result.isActive, "connect must wait for RX notify, not return after GATT is up")
        assertEquals(LinkPeripheralState.CONNECTED, peripheral.state.value)
        assertEquals(0, peripheral.observeStarts)

        hangNotifications.complete(Unit)
        advanceUntilIdle()
        assertTrue(result.await().isSuccess)
        assertEquals(1, peripheral.observeStarts)
    }
}
