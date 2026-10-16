package one.meshcore.shared.app

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ConnectionMethod
import one.meshcore.shared.battery.OCVPreset
import one.meshcore.shared.connection.BLE_FOREGROUND_SKIP_IN_PROGRESS
import one.meshcore.shared.connection.BLE_FOREGROUND_SKIP_POWERED_OFF
import one.meshcore.shared.connection.BLE_REARMED_WATCHDOG_FOREGROUND
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.ConnectionManager
import one.meshcore.shared.connection.ConnectionState
import one.meshcore.shared.connection.ConnectionUiState
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.ServiceContainer
import one.meshcore.shared.connection.StatusPillInputs
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.connection.TransportType
import one.meshcore.shared.connection.WIFI_FOREGROUND_RECONNECT_IN_PROGRESS
import one.meshcore.shared.connection.armBleReconnectIfWanted
import one.meshcore.shared.connection.bleForegroundAttemptingLine
import one.meshcore.shared.connection.canArmBleReconnect
import one.meshcore.shared.connection.checkBLEConnectionHealth
import one.meshcore.shared.connection.checkSyncHealth
import one.meshcore.shared.connection.checkWiFiConnectionHealth
import one.meshcore.shared.connection.connect
import one.meshcore.shared.connection.connectViaWiFi
import one.meshcore.shared.connection.disconnect
import one.meshcore.shared.connection.disconnectForPairing
import one.meshcore.shared.connection.isBleReconnectInProgress
import one.meshcore.shared.connection.reconcileConnectedListeners
import one.meshcore.shared.connection.setAppActive
import one.meshcore.shared.connection.shouldRunForegroundWifiOrBleArm
import one.meshcore.shared.connection.statusPillState
import one.meshcore.shared.connection.toDeviceConnectionState
import one.meshcore.shared.connection.wifiForegroundReconnectFailedLine
import one.meshcore.shared.connection.wifiForegroundReconnectLine
import one.meshcore.shared.meshcore.BatteryInfo
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.onboarding.OnboardingResumeInputs
import one.meshcore.shared.onboarding.OnboardingStep
import one.meshcore.shared.onboarding.suggestedStartingPath
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.settings.canRunSettingsStartupReads
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The Compose-side owner of the connection: the Kotlin analogue of the iOS `AppState`. It composes the
 * [ConnectionManager], the persistence stores, and the [ConnectionUiState] sub-flag machine, and exposes
 * the derived `StateFlow`s the shell, status pill, and battery UI observe. It is not a Compose UI; the
 * Android/desktop lifecycle glue that drives [activate]/[onEnterForeground]/[onEnterBackground] stays
 * thin in the app modules.
 *
 * Lifecycle is a plain class over an injected [scope] so it is fully testable on the desktop JVM with a
 * virtual clock; no platform lifecycle types leak in here.
 */
class AppStateViewModel(
    internal val connectionManager: ConnectionManager,
    stores: AppStateStores,
    internal val scope: CoroutineScope,
    runtime: AppStateRuntime,
    edges: AppStateEdges = AppStateEdges(),
) {
    val connectionUi: ConnectionUiState = runtime.connectionUi
    internal val navigation = runtime.navigation
    internal val nodeDiscoveryWorkspace = runtime.nodeDiscoveryWorkspace
    internal val tracePathWorkspace = runtime.tracePathWorkspace
    internal val remoteAdminWorkspaces = runtime.remoteAdminWorkspaces
    internal val lastConnectionStore = stores.lastConnectionStore
    internal val intentStore = stores.intentStore
    internal val prefs = stores.prefs
    internal val database = stores.database
    internal val batteryNotifier = edges.batteryNotifier
    internal val logger = edges.logger ?: PersistentLogger(scope, category = "ConnectionManager")
    private val bluetoothAdapterEnabled = edges.bluetoothAdapterEnabled

    /** The app-facing connection rung the pill and settings-gating read. */
    val connectionState: StateFlow<DeviceConnectionState> =
        connectionManager.state
            .map { it.toDeviceConnectionState() }
            .stateIn(scope, SharingStarted.Eagerly, DeviceConnectionState.DISCONNECTED)

    /**
     * Whether a Settings screen may re-read the radio right now. Mirrors iOS
     * `AppState.canRunSettingsStartupReads`; the rule itself is [canRunSettingsStartupReads].
     */
    val settingsStartupReadsAllowed: StateFlow<Boolean> =
        combine(connectionState, connectionUi.currentSyncPhaseFlow) { state, phase ->
            canRunSettingsStartupReads(state, phase)
        }.stateIn(scope, SharingStarted.Eagerly, false)

    /** The rendered status-pill state, computed over the connection rung and the timed sub-flags. */
    val statusPill: StateFlow<StatusPillState> =
        combine(
            connectionUi.syncFailedPillVisible,
            connectionUi.syncActivityCount,
            connectionState,
            connectionUi.showReadyToast,
            connectionUi.disconnectedPillVisible,
        ) { failed, count, connState, ready, disconnected ->
            statusPillState(
                StatusPillInputs(
                    syncFailedPillVisible = failed,
                    syncActivityCount = count,
                    connectionState = connState,
                    showReadyToast = ready,
                    disconnectedPillVisible = disconnected,
                ),
            )
        }.stateIn(scope, SharingStarted.Eagerly, StatusPillState.Hidden)

    internal val batteryReading = MutableStateFlow<BatteryInfo?>(null)

    /** The radio's most recent battery/storage readout, or `null` when disconnected or not yet read. */
    val battery: StateFlow<BatteryInfo?> = batteryReading.asStateFlow()

    internal val batteryOcvReading = MutableStateFlow(OCVPreset.LI_ION.ocvArray)

    /**
     * The connected radio's active OCV curve, used to turn the millivolt [battery] reading into a
     * percentage. Falls back to the Li-Ion curve when disconnected or the Device row is missing a preset.
     */
    val batteryOcvArray: StateFlow<List<Int>> = batteryOcvReading.asStateFlow()

    /** The connection/pairing alert to present, or `null`. */
    val connectionAlert: StateFlow<ConnectionAlert?> = connectionUi.connectionAlert

    val shouldShowConnectPicker: StateFlow<Boolean> = connectionUi.shouldShowConnectPicker

    fun consumeConnectPicker() = connectionUi.consumeConnectPicker()

    fun consumeCompanionPairingOnPicker(): Boolean = connectionUi.consumeCompanionPairingOnPicker()

    /** How the live connection reaches the radio, or `null` when disconnected. */
    val currentTransportType: TransportType?
        get() = connectionManager.currentTransportType

    // The per-connection event subscription: battery readouts arrive on the live session's event
    // stream, which is rebuilt on every connect. Resubscribe when the session identity changes and drop
    // the stale reading on disconnect.
    internal var batteryCollector: Job? = null
    internal var batteryPoller: Job? = null
    internal var collectedSession: MeshSession? = null

    // The first reading on a fresh connection seeds the notified-threshold set (and warns once if already
    // low); every later reading is an ordinary threshold check. Mirrors iOS `BatteryMonitor.start`
    // seeding thresholds once after the bootstrap fetch.
    internal var batteryThresholdsSeeded = false

    // Node-storage full flag: ContactService yields NodeStorageFullChanged / NodeDeleted after device
    // 0x90/0x8F pushes (and user deletes). Rebind when the ServiceContainer identity changes.
    internal var contactEventsCollector: Job? = null
    internal var collectedServices: ServiceContainer? = null

    // The connection-UI reset seam, keyed to the live services identity. Distinct from
    // [collectedServices] so the reset and the event rebind stay independently correct.
    internal var connectionUiServices: ServiceContainer? = null
    internal var connectionUiServicesSeen = false

    // The bond-loss episode already surfaced, so a re-entered BondLost state cannot re-present an alert
    // the responder just dismissed. Mirrors the iOS `surfacedAuthFailureDeviceID` latch.
    internal var surfacedBondLossDeviceId: String? = null

    // The radio the staged navigation belongs to, so a switch to a different one is detectable at the
    // moment the fresh container appears. Mirrors the iOS `lastConnectedDeviceIDForCLI` shadow copy.
    internal var navigatedRadioId: Uuid? = null

    init {
        scope.launch {
            connectionManager.state.collect { state ->
                runConnectionStateStep(
                    onFault = { error ->
                        logger.warning("$CONNECTION_STATE_STEP_FAILED: ${error.message ?: error}")
                    },
                ) {
                    val services = connectionManager.services
                    clearStagedNavigation(state, services)
                    rebindBatteryCollector(services?.session)
                    rebindContactServiceEvents(services)
                    routeBondLoss(state)
                    rebindConnectionUi(state, services)
                }
            }
        }
    }

    /** iOS `BatteryMonitor.fetchDeviceBattery` — Device Info appear and pull. */
    suspend fun refreshBattery() {
        val session = connectionManager.services?.session ?: return
        readDeviceBattery(logger::warning) { session.getBattery() }?.let { publishBattery(it) }
    }

    // MARK: - Lifecycle

    /**
     * Scene-phase edge. Fires from `onForegroundChanged` before activate/connect so advert
     * parking sees the app as foreground during first-launch reconnect.
     */
    fun setAppActive(active: Boolean) {
        connectionManager.setAppActive(active)
    }

    internal inner class ForegroundReconcile {
        suspend fun run() {
            connectionManager.checkSyncHealth()
            // iOS `checkWiFiConnectionHealth` case 1: probe a still-Ready WiFi companion immediately.
            connectionManager.checkWiFiConnectionHealth()
            // iOS `checkBLEConnectionHealth`: rebuild/adopt/reconnect, and restart dead listeners.
            connectionManager.checkBLEConnectionHealth()
            // Same listener restart as the health-check connected branch; no-op when already live.
            connectionManager.reconcileConnectedListeners()
            catchUpReadySession()
            clearSurfacedBondLoss()
            armForegroundRadio()
        }

        // iOS `handleReturnToForeground` sweeps pending DM ACKs when Ready. A frozen or delayed
        // expiry ticker can leave `.sent` rows past their give-up until this edge.
        private suspend fun catchUpReadySession() {
            if (connectionManager.state.value !is ConnectionState.Ready) return
            connectionManager.services?.messageService?.checkExpiredAcks()
            val session = connectionManager.services?.session ?: return
            val battery = readDeviceBattery(logger::warning) { session.getBattery() } ?: return
            publishBattery(battery, catchUpMissed = true)
        }

        // iOS `handleBecameActive` clears the auth-failure latch so a still-dead bond re-surfaces
        // from the foreground reconnect. BondLost is the Android parking rung for that same episode.
        private fun clearSurfacedBondLoss() {
            if (connectionManager.state.value is ConnectionState.BondLost) {
                surfacedBondLossDeviceId = null
            }
        }

        private suspend fun armForegroundRadio() {
            if (!shouldRunForegroundWifiOrBleArm(
                    canArmBle = canArmBleReconnect(connectionManager.state.value),
                    bleReconnectInProgress = connectionManager.isBleReconnectInProgress(),
                )
            ) {
                logForegroundArmSkipped()
                return
            }
            reconnectSavedWifi()
            if (!bluetoothAdapterEnabled()) {
                logger.info(BLE_FOREGROUND_SKIP_POWERED_OFF)
                return
            }
            noteArmedBleReconnect()
        }

        private fun logForegroundArmSkipped() {
            if (connectionManager.isBleReconnectInProgress()) {
                logger.info(BLE_FOREGROUND_SKIP_IN_PROGRESS)
            } else if (connectionManager.state.value is ConnectionState.Reconnecting &&
                connectionManager.currentTransportType == TransportType.WIFI
            ) {
                logger.info(WIFI_FOREGROUND_RECONNECT_IN_PROGRESS)
            }
        }

        private suspend fun reconnectSavedWifi() {
            val wifi = savedWifiEndpoint() ?: return
            logger.info(wifiForegroundReconnectLine(wifi.host, wifi.port))
            runSavedWifiReconnect(
                connect = { connectionManager.connectViaWiFi(wifi.host, wifi.port) },
                onFault = { error ->
                    logger.warning(wifiForegroundReconnectFailedLine(error.message ?: error.toString()))
                },
            )
        }

        private suspend fun savedWifiEndpoint(): ConnectionMethod.WiFi? = lastConnectionStore.deviceId()
            ?.let { database.deviceDao().getById(it) }
            ?.connectionMethods
            ?.filterIsInstance<ConnectionMethod.WiFi>()
            ?.firstOrNull()

        private suspend fun noteArmedBleReconnect() {
            if (!connectionManager.armBleReconnectIfWanted()) return
            logger.info(BLE_REARMED_WATCHDOG_FOREGROUND)
            val handle = connectionManager.state.value.deviceId ?: return
            logger.info(bleForegroundAttemptingLine(handle))
        }
    }

    // MARK: - Connection commands

    /** Starts a BLE connect to [deviceId]; the manager persists intent and reachability on success. */
    suspend fun connect(deviceId: String) {
        connectionUi.hideDisconnectedPill()
        runConnect({ presentConnectFailure(deviceId, it) }) {
            connectionManager.connect(deviceId, forceFullSync = true, forceReconnect = true)
        }
    }

    /** Starts a WiFi connect to [host]:[port]; the manager persists intent and reachability on success. */
    suspend fun connectViaWiFi(host: String, port: Int) {
        connectionUi.hideDisconnectedPill()
        // A TCP endpoint carries no pairing identity, so a WiFi failure has only the generic routing.
        runConnect({ connectionUi.presentConnectionFailure(null) }) {
            connectionManager.connectViaWiFi(host, port, forceFullSync = true)
        }
    }

    /** Explicitly disconnects; the manager persists the user-disconnected bit so nothing reconnects. */
    suspend fun disconnect() {
        connectionManager.disconnect()
        refreshDisconnectedPill(suppress = true)
    }

    suspend fun disconnectForPairing() {
        connectionManager.disconnectForPairing()
        refreshDisconnectedPill(suppress = false)
    }

    // MARK: - Onboarding

    /**
     * The onboarding starting path, filling the two persistence-derived inputs (onboarding-complete flag
     * and the last-connected signal) and taking the platform-provided permission/registry inputs from
     * the caller. Delegates to the pure [suggestedStartingPath].
     */
    suspend fun suggestedOnboardingPath(
        pairedAccessoriesCount: Int,
        locationPermissionDetermined: Boolean,
        notificationPermissionDetermined: Boolean,
        regionAlreadySet: Boolean,
    ): List<OnboardingStep> = suggestedStartingPath(
        OnboardingResumeInputs(
            hasCompletedOnboarding = prefs.getBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING) ?: false,
            pairedAccessoriesCount = pairedAccessoriesCount,
            lastConnectedDeviceId = lastConnectionStore.deviceId()?.value,
            locationPermissionDetermined = locationPermissionDetermined,
            notificationPermissionDetermined = notificationPermissionDetermined,
            regionAlreadySet = regionAlreadySet,
        ),
    )

    internal companion object {
        /** How often the connected radio's battery is re-read, matching the iOS `BatteryMonitor` loop. */
        val BATTERY_REFRESH_INTERVAL: Duration = 120.seconds
    }
}

private const val CONNECTION_STATE_STEP_FAILED = "Connection state step failed"

/**
 * A foreground Wi-Fi reconnect. A connect fault is logged and the arm continues.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runSavedWifiReconnect(connect: suspend () -> Unit, onFault: (Exception) -> Unit) {
    try {
        connect()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFault(error)
    }
}

/**
 * One connection-state pass. A Room or prefs fault is logged and the collector stays up so
 * bond-loss routing and battery rebind keep running.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runConnectionStateStep(onFault: (Exception) -> Unit, step: suspend () -> Unit) {
    try {
        step()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        onFault(error)
    }
}
