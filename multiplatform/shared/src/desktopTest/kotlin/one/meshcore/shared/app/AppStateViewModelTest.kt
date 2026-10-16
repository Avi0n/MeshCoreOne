package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.ConnectionIntent
import one.meshcore.shared.connection.ConnectionIntentStore
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionManagerPorts
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.DeviceSelectionFilter
import one.meshcore.shared.connection.FakeMeshLink
import one.meshcore.shared.connection.FakeMeshSession
import one.meshcore.shared.connection.FakeWiFiLink
import one.meshcore.shared.connection.LastConnectionStore
import one.meshcore.shared.connection.PairingFailure
import one.meshcore.shared.connection.PairingFailureKind
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.connection.SyncPhase
import one.meshcore.shared.connection.connect
import one.meshcore.shared.connection.defaultSelfInfo
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.connection.setPairingInProgress
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.notification.BatteryNotifier
import one.meshcore.shared.persistence.MeshDatabase
import one.meshcore.shared.persistence.meshDatabase
import one.meshcore.shared.persistence.seedDevice
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.simulator.MockDataProvider
import one.meshcore.shared.transport.BondLostException
import one.meshcore.shared.transport.EncryptionTimeoutException
import one.meshcore.shared.transport.MeshLink
import one.meshcore.shared.transport.MeshLinkConnectException
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * The [AppStateViewModel] decision surface: the foreground/launch auto-reconnect decision table (intent
 * × last-connected × reachability method) and the status-pill derivation through the combined flows
 * driven on a virtual clock.
 */
class AppStateViewModelTest {
    private lateinit var dbFile: File
    private lateinit var db: MeshDatabase
    private lateinit var prefs: InMemoryPreferencesStore

    private val publicKey = ByteArray(32) { 0x01 }
    private val lastId = Uuid.normalized("A0000000-0000-0000-0000-000000000001")
    private val radioId = Uuid.normalized("B0000000-0000-0000-0000-000000000002")

    @BeforeTest
    fun setUp() {
        dbFile = File.createTempFile("mesh-appstate", ".db").also { it.delete() }
        db = meshDatabase(dbFile.absolutePath)
        prefs = InMemoryPreferencesStore()
    }

    @AfterTest
    fun tearDown() {
        db.close()
        dbFile.delete()
    }

    @Test
    fun activateSkipsWhenUserExplicitlyDisconnected() = withScope { scope ->
        ConnectionIntentStore(prefs).persist(ConnectionIntent.UserDisconnected)
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = "AA:BB")
        db.deviceDao().upsert(deviceEntity(lastId, listOf(ConnectionMethod.WiFi(HOST, PORT, null))))
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(scope, bleCalls, wifiCalls)

        assertEquals(AutoReconnectDecision.SKIP_USER_DISCONNECTED, vm.activate())
        assertEquals(0, bleCalls.get())
        assertEquals(0, wifiCalls.get())
    }

    @Test
    fun activateSkipsWhenPairingIsInProgress() = withScope { scope ->
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
        db.deviceDao().upsert(
            deviceEntity(
                lastId,
                listOf(ConnectionMethod.Bluetooth(Uuid.normalized("C0000000-0000-0000-0000-000000000003"), null)),
            ),
        )
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(scope, bleCalls, wifiCalls, pairingInProgress = true)

        assertEquals(AutoReconnectDecision.SKIP_PAIRING_IN_PROGRESS, vm.activate())
        assertEquals(0, bleCalls.get())
        assertEquals(0, wifiCalls.get())
    }

    @Test
    fun activateReconnectsTheSimulatorWithoutTouchingRadios() = withScope { scope ->
        val simulatorId = MockDataProvider.simulatorDeviceID
        LastConnectionStore(prefs).persist(simulatorId, simulatorId, "Sim", handle = MockDataProvider.SIMULATOR_HANDLE)
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(scope, bleCalls, wifiCalls)

        assertEquals(AutoReconnectDecision.RECONNECT_SIMULATOR, vm.activate())
        assertEquals(0, bleCalls.get())
        assertEquals(0, wifiCalls.get())
    }

    @Test
    fun activateDoesNothingWithNoLastConnectedDevice() = withScope { scope ->
        val vm = viewModel(scope, AtomicInteger(), AtomicInteger())
        assertEquals(AutoReconnectDecision.NOTHING_TO_RECONNECT, vm.activate())
    }

    @Test
    fun activateReconnectsViaWiFiWhenTheLastDeviceHasAWiFiMethod() = withScope { scope ->
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = "AA:BB")
        db.deviceDao().upsert(deviceEntity(lastId, listOf(ConnectionMethod.WiFi(HOST, PORT, null))))
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(scope, bleCalls, wifiCalls)

        assertEquals(AutoReconnectDecision.RECONNECT_WIFI, vm.activate())
        assertEquals(1, wifiCalls.get())
        assertEquals(0, bleCalls.get())
    }

    @Test
    fun activateFallsBackToBleWhenOnlyABluetoothMethodAndHandleExist() = withScope { scope ->
        val bluetooth = ConnectionMethod.Bluetooth(Uuid.normalized("C0000000-0000-0000-0000-000000000003"), null)
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
        db.deviceDao().upsert(deviceEntity(lastId, listOf(bluetooth)))
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(scope, bleCalls, wifiCalls)

        assertEquals(AutoReconnectDecision.RECONNECT_BLE, vm.activate())
        assertEquals(1, bleCalls.get())
        assertEquals(0, wifiCalls.get())
    }

    @Test
    fun activateSkipsWhenAnotherAppHoldsTheRadioAfterAdoptRefuses() = withScope { scope ->
        val bluetooth = ConnectionMethod.Bluetooth(
            Uuid.normalized("C0000000-0000-0000-0000-000000000003"),
            BLE_HANDLE,
        )
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = "other-handle")
        db.deviceDao().upsert(deviceEntity(lastId, listOf(bluetooth)))
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(
            scope,
            bleCalls,
            wifiCalls,
            systemConnectedHandles = { setOf(BLE_HANDLE) },
        )

        assertEquals(AutoReconnectDecision.RECONNECT_BLE, vm.activate())
        delay(AWAIT_POLL_MS)
        assertEquals(0, bleCalls.get())
        assertEquals(0, wifiCalls.get())
        awaitCondition {
            LastConnectionStore(prefs).disconnectDiagnostic()
                ?.contains("activate.autoReconnectSkippedOtherApp") == true
        }
    }

    @Test
    fun activateAdoptsWhenTheLastRadioIsSystemConnectedAndIdle() = withScope { scope ->
        val bluetooth = ConnectionMethod.Bluetooth(Uuid.normalized("C0000000-0000-0000-0000-000000000003"), null)
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
        db.deviceDao().upsert(deviceEntity(lastId, listOf(bluetooth)))
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(
            scope,
            bleCalls,
            wifiCalls,
            systemConnectedHandles = { setOf(BLE_HANDLE) },
        )

        assertEquals(AutoReconnectDecision.RECONNECT_BLE, vm.activate())
        assertEquals(1, bleCalls.get())
        assertEquals(0, wifiCalls.get())
        awaitCondition {
            LastConnectionStore(prefs).disconnectDiagnostic()
                ?.contains("activate.adoptSystemConnectedPeripheral") == true
        }
    }

    @Test
    fun activateDoesNotBleConnectAWifiTransportHandle() = withScope { scope ->
        val wifiHandle = DeviceSelectionFilter.wifiTransportHandle(HOST, PORT)
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = wifiHandle)
        db.deviceDao().upsert(
            deviceEntity(
                lastId,
                listOf(
                    ConnectionMethod.WiFi(HOST, PORT, null),
                    ConnectionMethod.Bluetooth(
                        Uuid.normalized("C0000000-0000-0000-0000-000000000003"),
                        displayName = null,
                    ),
                ),
            ),
        )
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(scope, bleCalls, wifiCalls)

        assertEquals(AutoReconnectDecision.RECONNECT_WIFI, vm.activate())
        assertEquals(1, wifiCalls.get())
        assertEquals(0, bleCalls.get())
    }

    @Test
    fun reconnectLastDevicePrefersWifiOverAWifiTransportHandle() = withScope { scope ->
        val wifiHandle = DeviceSelectionFilter.wifiTransportHandle(HOST, PORT)
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = wifiHandle)
        db.deviceDao().upsert(deviceEntity(lastId, listOf(ConnectionMethod.WiFi(HOST, PORT, null))))
        val bleCalls = AtomicInteger()
        val wifiCalls = AtomicInteger()
        val vm = viewModel(scope, bleCalls, wifiCalls)

        assertTrue(vm.reconnectLastDevice())
        assertEquals(1, wifiCalls.get())
        assertEquals(0, bleCalls.get())
    }

    // Unconfined so the eager combine/stateIn collectors propagate each StateFlow update synchronously;
    // the ready-toast delay still runs on the test scheduler, driven by advanceTimeBy.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun statusPillDerivesFromTimedFlagsOnAVirtualClock() = runTest(UnconfinedTestDispatcher()) {
        val ui = ConnectionUiState(backgroundScope)
        val manager = ConnectionManager(
            database = db,
            scope = backgroundScope,
            linkFactory = { FakeMeshLink() },
            sessionFactory = { FakeMeshSession() },
            nowEpochSeconds = { 1_000.0 },
        )
        val vm = AppStateViewModel(
            connectionManager = manager,
            stores = AppStateStores(
                lastConnectionStore = LastConnectionStore(prefs),
                intentStore = ConnectionIntentStore(prefs),
                prefs = prefs,
                database = db,
            ),
            scope = backgroundScope,
            runtime = AppStateRuntime(
                connectionUi = ui,
                navigation = NavigationCoordinator(backgroundScope),
            ),
        )
        assertEquals(StatusPillState.Hidden, vm.statusPill.value)

        ui.syncStarted()
        assertEquals(StatusPillState.Syncing, vm.statusPill.value)

        ui.syncEnded(succeeded = true) // drops to 0 and shows the ready toast
        assertEquals(StatusPillState.Ready, vm.statusPill.value)

        advanceTimeBy(2_100.milliseconds) // ready toast (2s) expires
        assertEquals(StatusPillState.Hidden, vm.statusPill.value)
    }

    // MARK: - Disconnected pill

    // Unconfined so the state collector and the eager stateIn/combine collectors run synchronously; the
    // pill's 1s arming delay runs on the test scheduler, driven by advanceTimeBy.
    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theDisconnectedPillArmsWhileDisconnectedWithALastRadioAndNoUserDisconnect() =
        runTest(UnconfinedTestDispatcher()) {
            LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
            val ui = ConnectionUiState(backgroundScope)
            val vm = harness(backgroundScope, ui) { FakeMeshLink() }.viewModel

            advanceTimeBy(PILL_DELAY_ELAPSED)
            runCurrent()

            assertTrue(ui.disconnectedPillVisible.value, "the pill must arm on the iOS condition")
            assertEquals(StatusPillState.Disconnected, vm.statusPill.value)
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun theDisconnectedPillStaysHiddenAfterAnExplicitUserDisconnect() = runTest(UnconfinedTestDispatcher()) {
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
        ConnectionIntentStore(prefs).persist(ConnectionIntent.UserDisconnected)
        val ui = ConnectionUiState(backgroundScope)
        harness(backgroundScope, ui) { FakeMeshLink() }

        advanceTimeBy(PILL_DELAY_ELAPSED)
        runCurrent()

        assertFalse(ui.disconnectedPillVisible.value, "an explicit disconnect suppresses the pill")
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFailedConnectRearmsTheDisconnectedPillOnceTheRungSettles() = runTest(UnconfinedTestDispatcher()) {
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
        val ui = ConnectionUiState(backgroundScope)
        val vm = harness(backgroundScope, ui) {
            FakeMeshLink().apply { failConnectWith = MeshLinkConnectException("radio out of range") }
        }.viewModel

        // connect() hides the pill and cancels the launch arming, so a visible pill afterwards can only
        // come from the rung settling back to disconnected.
        assertFailsWith<MeshLinkConnectException> { vm.connect(BLE_HANDLE) }
        advanceTimeBy(PILL_DELAY_ELAPSED)
        runCurrent()

        assertTrue(ui.disconnectedPillVisible.value)
    }

    // MARK: - Connection alerts

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFailedConnectToASavedRadioPresentsTheConnectionFailureAlert() = runTest(UnconfinedTestDispatcher()) {
        val ui = ConnectionUiState(backgroundScope)
        seedSavedBleRadio()
        val vm = harness(backgroundScope, ui) {
            FakeMeshLink().apply { failConnectWith = MeshLinkConnectException("radio out of range") }
        }.viewModel

        assertFailsWith<MeshLinkConnectException> { vm.connect(BLE_HANDLE) }
        runCurrent()

        assertEquals(ConnectionAlert.Generic(null), vm.connectionAlert.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFreshPairThatWillNotConnectOffersTheRetryRecovery() = runTest(UnconfinedTestDispatcher()) {
        // No saved row: this radio was just chosen in the picker, so the failure is a pairing failure and
        // carries Try Again rather than an OK-only alert on the one screen that exists to get a radio working.
        val ui = ConnectionUiState(backgroundScope)
        val vm = harness(backgroundScope, ui) {
            FakeMeshLink().apply { failConnectWith = MeshLinkConnectException("radio out of range") }
        }.viewModel

        assertFailsWith<MeshLinkConnectException> { vm.connect(BLE_HANDLE) }
        runCurrent()

        assertEquals(ConnectionAlert.Pairing(BLE_HANDLE, PairingFailureKind.TRANSIENT), vm.connectionAlert.value)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anInvalidatedBondOnASavedRadioPresentsTheGuidedRepairAlert() = runTest(UnconfinedTestDispatcher()) {
        val ui = ConnectionUiState(backgroundScope)
        seedSavedBleRadio()
        val harness = harness(backgroundScope, ui) {
            FakeMeshLink().apply { failConnectWith = BondLostException("bond invalidated") }
        }

        assertFailsWith<BondLostException> { harness.viewModel.connect(BLE_HANDLE) }
        runCurrent()

        assertEquals(ConnectionState.BondLost(BLE_HANDLE), harness.manager.state.value)
        assertEquals(
            ConnectionAlert.Pairing(BLE_HANDLE, PairingFailureKind.AUTHENTICATION),
            harness.viewModel.connectionAlert.value,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFreshPairRefusedByTheRadioNamesTheRejectedPin() = runTest(UnconfinedTestDispatcher()) {
        // The same throw as a dead saved bond, but there is no saved pairing to be dead: on a first pair the
        // radio refused the PIN, and telling the responder their saved pairing stopped working is a lie.
        val ui = ConnectionUiState(backgroundScope)
        val harness = harness(backgroundScope, ui) {
            FakeMeshLink().apply { failConnectWith = BondLostException("bond invalidated") }
        }

        assertFailsWith<BondLostException> { harness.viewModel.connect(BLE_HANDLE) }
        runCurrent()

        assertEquals(
            ConnectionAlert.Pairing(BLE_HANDLE, PairingFailureKind.PIN_REJECTED),
            harness.viewModel.connectionAlert.value,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun aFreshPairEncryptionTimeoutNamesTheRejectedPin() = runTest(UnconfinedTestDispatcher()) {
        // Android PIN refusal is GATT 8/22/133, remapped to EncryptionTimeoutException. On a first pair
        // that is the same refused PIN as BondLostException, not a generic OK-only failure.
        val ui = ConnectionUiState(backgroundScope)
        val harness = harness(backgroundScope, ui) {
            FakeMeshLink().apply { failConnectWith = EncryptionTimeoutException("GATT 133") }
        }

        assertFailsWith<EncryptionTimeoutException> { harness.viewModel.connect(BLE_HANDLE) }
        runCurrent()

        assertEquals(
            ConnectionAlert.Pairing(BLE_HANDLE, PairingFailureKind.PIN_REJECTED),
            harness.viewModel.connectionAlert.value,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun anEncryptionTimeoutOnASavedRadioStaysTheConnectionFailureAlert() = runTest(UnconfinedTestDispatcher()) {
        val ui = ConnectionUiState(backgroundScope)
        seedSavedBleRadio()
        val vm = harness(backgroundScope, ui) {
            FakeMeshLink().apply { failConnectWith = EncryptionTimeoutException("GATT 133") }
        }.viewModel

        assertFailsWith<EncryptionTimeoutException> { vm.connect(BLE_HANDLE) }
        runCurrent()

        assertEquals(ConnectionAlert.Generic(null), vm.connectionAlert.value)
    }

    /** A Device row already reachable over BLE at [BLE_HANDLE], so a connect to it is not a fresh pair. */
    private suspend fun seedSavedBleRadio() {
        db.deviceDao().upsert(deviceEntity(lastId, listOf(ConnectionMethod.Bluetooth(lastId, BLE_HANDLE))))
    }

    // MARK: - Disconnect reset and battery

    @Test
    fun aLiveConnectionClearsALatchedPairingFailure() = withScope { scope ->
        val ui = ConnectionUiState(scope)
        val harness = harness(scope, ui) { FakeMeshLink() }
        ui.presentPairingFailure(
            PairingFailure.ConnectionFailed(BLE_HANDLE, isAuthenticationFailure = true),
        )
        assertIs<ConnectionAlert.Pairing>(ui.connectionAlert.value)

        harness.manager.connect(BLE_HANDLE)
        awaitCondition { ui.connectionAlert.value == null }
    }

    @Test
    fun losingTheServicesResetsTheConnectionUiFlags() = withScope { scope ->
        val ui = ConnectionUiState(scope)
        val harness = harness(scope, ui) { FakeMeshLink() }
        harness.manager.connect(BLE_HANDLE)
        ui.syncStarted()
        ui.isNodeStorageFull = true
        ui.currentSyncPhase = SyncPhase.MESSAGES

        harness.viewModel.disconnect()
        awaitCondition { ui.syncActivityCount.value == 0 }

        assertEquals(0, ui.syncActivityCount.value)
        assertFalse(ui.isNodeStorageFull)
        assertNull(ui.currentSyncPhase)
    }

    @Test
    fun theBatteryPollSeedsTheReadoutAndTheLowBatteryThreshold() = withScope { scope ->
        val notifier = RecordingBatteryNotifier()
        val reading = BatteryInfo(level = LOW_BATTERY_MILLIVOLTS)
        val harness = harness(
            scope = scope,
            ui = ConnectionUiState(scope),
            batteryNotifier = notifier,
            session = { BatteryReportingSession(FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)), reading) },
        ) { FakeMeshLink() }

        harness.manager.connect(BLE_HANDLE)
        awaitCondition { harness.viewModel.battery.value != null }

        assertEquals(reading, harness.viewModel.battery.value)
        awaitCondition { notifier.seeded.isNotEmpty() }
        assertEquals(1, notifier.seeded.size, "the first reading seeds the thresholds exactly once")
    }

    @Test
    fun connectFaultSuccessSkipsTheAlert() = withScope { scope ->
        val vm = viewModel(scope, AtomicInteger(), AtomicInteger())
        var presented = false

        vm.runConnect({ presented = true }) { }

        assertFalse(presented)
    }

    @Test
    fun connectFaultPresentsThenRethrows() = withScope { scope ->
        val vm = viewModel(scope, AtomicInteger(), AtomicInteger())
        val failure = IllegalStateException("link down")
        var presented: Throwable? = null

        val thrown = assertFailsWith<IllegalStateException> {
            vm.runConnect({ presented = it }) { throw failure }
        }

        assertEquals(failure, presented)
        assertEquals(failure, thrown)
    }

    @Test
    fun connectFaultCancellationIsNotPresented() = withScope { scope ->
        val vm = viewModel(scope, AtomicInteger(), AtomicInteger())
        var presented = false

        val error = assertFailsWith<CancellationException> {
            vm.runConnect({ presented = true }) { throw CancellationException("stopped") }
        }

        assertEquals("stopped", error.message)
        assertFalse(presented)
    }

    @Test
    fun connectFaultErrorIsPresentedThenRethrown() = withScope { scope ->
        val vm = viewModel(scope, AtomicInteger(), AtomicInteger())
        var presented: Throwable? = null

        val thrown = assertFailsWith<ConnectProbeError> {
            vm.runConnect({ presented = it }) { throw ConnectProbeError() }
        }

        assertIs<ConnectProbeError>(presented)
        assertEquals(thrown, presented)
    }

    @Test
    fun aBatteryPublishFailureKeepsTheCollectorAlive() = withBatteryScope { scope ->
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(publicKey))
        val notifier = ThrowingBatteryNotifier(IllegalStateException("db locked"))
        val harness = harness(
            scope = scope,
            ui = ConnectionUiState(scope),
            batteryNotifier = notifier,
            session = { session },
        ) { FakeMeshLink() }
        harness.viewModel.rebindBatteryCollector(session)
        awaitCondition { session.eventSubscribers.value > 0 }

        session.emit(MeshEvent.BatteryReceived(BatteryInfo(level = LOW_BATTERY_MILLIVOLTS)))
        awaitCondition { notifier.calls == 1 }
        session.emit(MeshEvent.BatteryReceived(BatteryInfo(level = NEXT_BATTERY_MILLIVOLTS)))
        awaitCondition { notifier.calls == 2 }

        assertEquals(NEXT_BATTERY_MILLIVOLTS, harness.viewModel.battery.value?.level)
        assertTrue(harness.viewModel.batteryCollector?.isActive == true)
    }

    @Test
    fun aCancelledBatteryPublishStopsTheCollector() = withBatteryScope { scope ->
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(publicKey))
        val notifier = ThrowingBatteryNotifier(CancellationException("stopped"))
        val harness = harness(
            scope = scope,
            ui = ConnectionUiState(scope),
            batteryNotifier = notifier,
            session = { session },
        ) { FakeMeshLink() }
        harness.viewModel.rebindBatteryCollector(session)
        awaitCondition { session.eventSubscribers.value > 0 }

        session.emit(MeshEvent.BatteryReceived(BatteryInfo(level = LOW_BATTERY_MILLIVOLTS)))
        val collector = harness.viewModel.batteryCollector
        awaitCondition { collector?.isCancelled == true }
        var cause: Throwable? = null
        collector?.invokeOnCompletion { cause = it }

        assertEquals("stopped", (cause as? CancellationException)?.message, "calls=${notifier.calls} cause=$cause")
        assertEquals(1, notifier.calls)
        assertEquals(LOW_BATTERY_MILLIVOLTS, harness.viewModel.battery.value?.level)
        assertEquals(0, session.eventSubscribers.value)
    }

    @Test
    fun aBatteryPublishErrorStopsTheCollector() = withBatteryScope(
        // The job already carries this error. Handling it here keeps it off the process uncaught list.
        CoroutineExceptionHandler { _, _ -> },
    ) { scope ->
        LastConnectionStore(prefs).persist(lastId, radioId, "Radio", handle = BLE_HANDLE)
        val session = FakeMeshSession(selfInfo = defaultSelfInfo(publicKey))
        val notifier = ThrowingBatteryNotifier(BatteryProbeError())
        val harness = harness(
            scope = scope,
            ui = ConnectionUiState(scope),
            batteryNotifier = notifier,
            session = { session },
        ) { FakeMeshLink() }
        harness.viewModel.rebindBatteryCollector(session)
        awaitCondition { session.eventSubscribers.value > 0 }

        session.emit(MeshEvent.BatteryReceived(BatteryInfo(level = LOW_BATTERY_MILLIVOLTS)))
        val collector = harness.viewModel.batteryCollector
        awaitCondition { collector?.isCompleted == true }
        var cause: Throwable? = null
        collector?.invokeOnCompletion { cause = it }

        assertEquals(1, notifier.calls)
        assertEquals(LOW_BATTERY_MILLIVOLTS, harness.viewModel.battery.value?.level)
        assertIs<BatteryProbeError>(cause)
        assertEquals(0, session.eventSubscribers.value)
    }

    private class ConnectProbeError : Error("probe")

    private class BatteryProbeError : Error("probe")

    private class ThrowingBatteryNotifier(private val failure: Throwable) : BatteryNotifier {
        var calls = 0

        override suspend fun initializeBatteryThreshold(
            deviceName: String,
            percentage: Int?,
            isBatteryPresent: Boolean,
        ) {
            calls += 1
            throw failure
        }

        override suspend fun checkBatteryThreshold(deviceName: String, percentage: Int, isBatteryPresent: Boolean) {
            calls += 1
            throw failure
        }

        override suspend fun checkMissedBatteryThreshold(
            deviceName: String,
            percentage: Int,
            isBatteryPresent: Boolean,
        ) {
            calls += 1
            throw failure
        }

        override fun clearBatteryThresholds() = Unit
    }

    private class Harness(val manager: ConnectionManager, val viewModel: AppStateViewModel)

    private fun harness(
        scope: CoroutineScope,
        ui: ConnectionUiState,
        batteryNotifier: BatteryNotifier = BatteryNotifier.NoOp,
        session: (MeshLink) -> MeshSession = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) },
        link: (String) -> MeshLink,
    ): Harness {
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = link,
            sessionFactory = session,
            nowEpochSeconds = { 1_000.0 },
            ports = ConnectionManagerPorts(
                newRadioId = { radioId },
                lastConnectionStore = LastConnectionStore(prefs),
                intentStore = ConnectionIntentStore(prefs),
            ),
        )
        return Harness(
            manager,
            AppStateViewModel(
                connectionManager = manager,
                stores = AppStateStores(
                    lastConnectionStore = LastConnectionStore(prefs),
                    intentStore = ConnectionIntentStore(prefs),
                    prefs = prefs,
                    database = db,
                ),
                scope = scope,
                runtime = AppStateRuntime(
                    connectionUi = ui,
                    navigation = NavigationCoordinator(scope),
                ),
                edges = AppStateEdges(batteryNotifier = batteryNotifier),
            ),
        )
    }

    /** A session that answers `getBattery`, which the ceremony fake deliberately leaves unmodelled. */
    private class BatteryReportingSession(delegate: MeshSession, private val reading: BatteryInfo) :
        MeshSession by delegate {
        override suspend fun getBattery(): BatteryInfo = reading
    }

    private class RecordingBatteryNotifier : BatteryNotifier {
        val seeded = mutableListOf<Int?>()
        val checked = mutableListOf<Int>()
        val missed = mutableListOf<Int>()

        override suspend fun initializeBatteryThreshold(
            deviceName: String,
            percentage: Int?,
            isBatteryPresent: Boolean,
        ) {
            seeded += percentage
        }

        override suspend fun checkBatteryThreshold(deviceName: String, percentage: Int, isBatteryPresent: Boolean) {
            checked += percentage
        }

        override suspend fun checkMissedBatteryThreshold(
            deviceName: String,
            percentage: Int,
            isBatteryPresent: Boolean,
        ) {
            missed += percentage
        }

        override fun clearBatteryThresholds() = Unit
    }

    private suspend fun awaitCondition(condition: suspend () -> Boolean) {
        withTimeout(AWAIT_TIMEOUT_MS) {
            while (!condition()) delay(AWAIT_POLL_MS)
        }
    }

    private fun viewModel(
        scope: CoroutineScope,
        bleCalls: AtomicInteger,
        wifiCalls: AtomicInteger,
        pairingInProgress: Boolean = false,
        systemConnectedHandles: () -> Set<String> = { emptySet() },
    ): AppStateViewModel {
        val manager = ConnectionManager(
            database = db,
            scope = scope,
            linkFactory = {
                bleCalls.incrementAndGet()
                FakeMeshLink()
            },
            sessionFactory = { FakeMeshSession(selfInfo = defaultSelfInfo(publicKey)) },
            nowEpochSeconds = { 1_000.0 },
            ports = ConnectionManagerPorts(
                wifiLinkFactory = { h, p, hb ->
                    wifiCalls.incrementAndGet()
                    FakeWiFiLink(h, p, hb)
                },
                lastConnectionStore = LastConnectionStore(prefs),
                intentStore = ConnectionIntentStore(prefs),
                systemConnectedHandles = systemConnectedHandles,
            ),
        )
        if (pairingInProgress) manager.setPairingInProgress(true)
        return AppStateViewModel(
            connectionManager = manager,
            stores = AppStateStores(
                lastConnectionStore = LastConnectionStore(prefs),
                intentStore = ConnectionIntentStore(prefs),
                prefs = prefs,
                database = db,
            ),
            scope = scope,
            runtime = AppStateRuntime(
                connectionUi = ConnectionUiState(scope),
                navigation = NavigationCoordinator(scope),
            ),
        )
    }

    // The view model collects connection state on this same scope and rebinds the battery
    // collector to the live session. The first emission is disconnected, which clears any
    // collector already installed. Unconfined runs that clear during construction, then the
    // test's collector subscribes on the calling thread while the suite's shared pool is busy.
    private fun withBatteryScope(handler: CoroutineExceptionHandler? = null, block: suspend (CoroutineScope) -> Unit) =
        runBlocking {
            val base = SupervisorJob() + Dispatchers.Unconfined
            val scope = CoroutineScope(if (handler == null) base else base + handler)
            try {
                block(scope)
            } finally {
                withTimeout(AWAIT_TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
            }
        }

    private fun withScope(block: suspend (CoroutineScope) -> Unit) = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            block(scope)
        } finally {
            withTimeout(AWAIT_TIMEOUT_MS) { scope.coroutineContext.job.cancelAndJoin() }
        }
    }

    private fun deviceEntity(id: Uuid, methods: List<ConnectionMethod>) = seedDevice(id, radioId, publicKey, methods)

    private companion object {
        const val HOST = "10.0.0.5"
        const val PORT = 4403
        const val BLE_HANDLE = "device-1"
        const val AWAIT_TIMEOUT_MS = 5_000L
        const val AWAIT_POLL_MS = 10L

        /** A millivolt reading well under the lowest warning threshold. */
        const val LOW_BATTERY_MILLIVOLTS = 3_300

        /** A later reading, distinct from [LOW_BATTERY_MILLIVOLTS], still under the warning threshold. */
        const val NEXT_BATTERY_MILLIVOLTS = 3_400

        /** Past the pill's 1s arming delay. */
        val PILL_DELAY_ELAPSED = 1_100.milliseconds
    }
}
