package one.meshcore.shared.onboarding

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.bleHandleIsHeldByOtherApp
import one.meshcore.shared.connection.clearStaleCompanionPairings
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.onboarding_device_scan_add_device
import one.meshcore.shared.resources.onboarding_device_scan_connect_simulator
import one.meshcore.shared.resources.onboarding_device_scan_connect_via_wifi
import one.meshcore.shared.resources.onboarding_device_scan_connecting
import one.meshcore.shared.resources.onboarding_device_scan_continue_demo
import one.meshcore.shared.resources.onboarding_device_scan_device_not_appearing
import one.meshcore.shared.resources.onboarding_device_scan_no_device_yet
import one.meshcore.shared.resources.onboarding_device_scan_retry_connection
import one.meshcore.shared.resources.onboarding_device_scan_subtitle
import one.meshcore.shared.resources.onboarding_device_scan_title
import one.meshcore.shared.resources.onboarding_device_scanner_title
import one.meshcore.shared.ui.AppHaptics
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.SystemPairingSetupDialog
import one.meshcore.shared.ui.rememberAppHaptics
import org.jetbrains.compose.resources.stringResource

/**
 * The Pair step, ported from the iOS `DeviceScanView`. When already connected and synced it shows the
 * "already paired" state with Continue; otherwise Add Device opens Companion Device Manager (ASK analog)
 * and falls back to the in-app NUS scan when CDM is missing. WiFi and troubleshooting stay secondary.
 */
@Composable
internal fun PairStep(deps: ShellDependencies) {
    val scope = rememberCoroutineScope()
    val haptics = rememberAppHaptics()
    val currentDeps by rememberUpdatedState(deps)
    val connectionState by deps.connectionState.collectAsStateWithLifecycle()

    var scanning by remember { mutableStateOf(false) }
    var showWiFi by remember { mutableStateOf(false) }
    var showTroubleshooting by remember { mutableStateOf(false) }
    var connecting by remember { mutableStateOf(false) }
    var showNoDeviceSheet by remember { mutableStateOf(false) }
    var showDemoUnlockedAlert by remember { mutableStateOf(false) }
    var pendingSetup by remember { mutableStateOf<List<CompanionAssociation>?>(null) }
    var otherAppDeviceId by remember { mutableStateOf<String?>(null) }
    val companionDevices = deps.companionDevices
    val actions = deps.actions
    DisposableEffect(companionDevices, actions) {
        onDispose {
            companionDevices.cancelAssociate()
            actions.setPairingInProgress(false)
        }
    }
    val connectionAlert by deps.connectionAlert.collectAsStateWithLifecycle()
    LaunchedEffect(connectionAlert) {
        otherAppDeviceId = latchedOtherAppDeviceId(otherAppDeviceId, connectionAlert)
    }

    // Demo mode, unlocked by the hidden 3-tap on the title, lets a Play reviewer enter the app without a
    // radio (iOS DeviceScanView demo-mode easter egg + "Continue demo" CTA).
    val demoModeStore = deps.demoModeStore
    val demoModeFlow = remember(demoModeStore) { demoModeEnabledState(demoModeStore?.isEnabled) }
    val demoEnabled by demoModeFlow.collectAsStateWithLifecycle()

    // Explore the shell without a radio. Completes onboarding and nothing else: the region is write-once
    // with no editor, so inventing one here would pin the user to a country they never chose. Every region
    // read is nullable for exactly this state. Shared by the NoDeviceSheet confirm and the demo-mode CTA.
    val exploreWithoutRadio = { scope.launch { currentDeps.onboarding.complete() } }

    val beginConnect: (suspend () -> Unit, suspend () -> Unit) -> Unit = { block, onSuccess ->
        launchPairConnect(
            scope = scope,
            connect = { runConnect(haptics = haptics, connect = block) },
            onSuccess = onSuccess,
            setConnecting = { connecting = it },
        )
    }

    val currentPairing: suspend () -> OnboardingCompanionPairing = {
        OnboardingCompanionPairing(
            associations = currentDeps.companionDevices.associations(),
            savedDevices = currentDeps.actions.listSavedDevices(),
            connectionState = currentDeps.connectionState.value,
            lastConnectedId = currentDeps.actions.lastConnectedDeviceId(),
            isRadioLinked = currentDeps.connectionState.value.isRadioLinked,
            companionAvailable = currentDeps.companionDevices.isAvailable,
            bluetoothGranted = currentDeps.permissions.state.value.isGranted(OnboardingPermission.BLUETOOTH),
            disconnect = { currentDeps.actions.disconnectForPairing() },
            noteWantsConnection = { currentDeps.actions.noteWantsConnection() },
            setPendingSetup = { pendingSetup = it },
            setPairingInProgress = { currentDeps.actions.setPairingInProgress(it) },
            requestBluetooth = { currentDeps.permissions.request(OnboardingPermission.BLUETOOTH) },
            showInAppScan = { scanning = true },
            associate = { currentDeps.companionDevices.associate() },
            isHeld = { handle ->
                bleHandleIsHeldByOtherApp(
                    handle,
                    currentDeps.connectionState.value,
                    currentDeps.actions.systemConnectedBleHandles(),
                )
            },
            onHeld = { handle -> currentDeps.actions.presentConnectedElsewhereAlert(handle) },
            onConnect = { handle ->
                beginConnect({ currentDeps.actions.connect(handle) }) { currentDeps.onboarding.advance() }
            },
            onCancelled = { currentDeps.actions.setPairingInProgress(false) },
        )
    }

    val beginCompanionPairing: () -> Unit = {
        startOnboardingCompanionPairing(
            connecting = connecting,
            stopScan = { currentDeps.bleScan.stopScan() },
            scope = scope,
            session = currentPairing,
        )
    }

    val showConnectPicker by deps.shouldShowConnectPicker.collectAsStateWithLifecycle()
    LaunchedEffect(showConnectPicker) {
        openOnboardingConnectPicker(
            show = showConnectPicker,
            consumePicker = { currentDeps.consumeConnectPicker() },
            consumeCompanion = { currentDeps.consumeCompanionPairingOnPicker() },
            begin = beginCompanionPairing,
        )
    }

    val completeFreshCompanionPairing: () -> Unit = {
        completeFreshOnboardingPairing(
            connecting = connecting,
            setPairingInProgress = { currentDeps.actions.setPairingInProgress(it) },
            stopScan = { currentDeps.bleScan.stopScan() },
            scope = scope,
            session = currentPairing,
        )
    }

    val confirmLeftoverForget: () -> Unit = {
        val accessories = pendingSetup.orEmpty()
        pendingSetup = null
        forgetLeftoverCompanionAccessories(accessories) { handle ->
            currentDeps.companionDevices.disassociate(handle)
        }
        completeFreshCompanionPairing()
    }

    if (connectionState == DeviceConnectionState.READY) {
        AlreadyPairedContent(onContinue = {
            scope.launch { currentDeps.onboarding.advance() }
        }, onBack = { currentDeps.onboarding.back() })
        return
    }

    OnboardingScaffold(
        onBack = { currentDeps.onboarding.back() },
        primary = {
            PairStepPrimaryButton(
                deps = deps,
                demoEnabled = demoEnabled,
                otherAppDeviceId = otherAppDeviceId,
                connecting = connecting,
                onScan = { beginCompanionPairing() },
                onRetry = { id ->
                    beginConnect({ currentDeps.actions.connect(id) }) { currentDeps.onboarding.advance() }
                },
                onSimulatorConnect = {
                    beginConnect({ currentDeps.actions.connectSimulator() }) { currentDeps.onboarding.advance() }
                },
            )
        },
    ) {
        PulsingAntenna()
        // Hidden 3-tap on the title unlocks demo mode for Play reviewers (iOS DeviceScanView TapGesture(3)).
        Box(
            modifier = Modifier.pointerInput(deps.demoModeStore) {
                detectTapGestures {
                    if (deps.demoModeStore?.registerUnlockTap() == true) {
                        // The only sign the hidden tap count landed: the title itself does not change.
                        haptics.success()
                        showDemoUnlockedAlert = true
                    }
                }
            },
        ) {
            StepTitle(stringResource(Res.string.onboarding_device_scan_title))
        }
        StepSubtitle(stringResource(Res.string.onboarding_device_scan_subtitle))

        OutlinedButton(
            onClick = { showWiFi = true },
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.onboarding_device_scan_connect_via_wifi))
        }
        TextButton(
            onClick = { showTroubleshooting = true },
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.onboarding_device_scan_device_not_appearing))
        }
        // Browse the shell without a radio (reviewers / screenshots / first-run explore); confirmed
        // through the NoDeviceSheet first, matching iOS.
        OutlinedButton(
            onClick = { showNoDeviceSheet = true },
            enabled = !connecting,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(Res.string.onboarding_device_scan_no_device_yet))
        }
    }

    if (scanning) {
        FullScreenOverlay(
            title = stringResource(Res.string.onboarding_device_scanner_title),
            onDismiss = { if (!connecting) scanning = false },
        ) { innerPadding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
            ) {
                BleDevicePicker(
                    deps = deps,
                    connecting = connecting,
                    onSelect = { id ->
                        startScannedDeviceConnect(
                            connecting = connecting,
                            stopScan = { currentDeps.bleScan.stopScan() },
                            connect = {
                                beginConnect({ currentDeps.actions.connect(id) }) {
                                    currentDeps.onboarding.advance()
                                }
                            },
                        )
                    },
                )
            }
        }
    }

    PairStepDialogs(
        showNoDeviceSheet = showNoDeviceSheet,
        showDemoUnlockedAlert = showDemoUnlockedAlert,
        showWiFi = showWiFi,
        showTroubleshooting = showTroubleshooting,
        connecting = connecting,
        openBluetoothSettings = deps.openBluetoothSettings,
        onExplore = {
            showNoDeviceSheet = false
            exploreWithoutRadio()
        },
        onDismissNoDevice = {
            showNoDeviceSheet = false
        },
        onDismissDemoAlert = { showDemoUnlockedAlert = false },
        onDismissWiFi = { if (!connecting) showWiFi = false },
        onConnectWiFi = { host, port ->
            beginConnect({ currentDeps.actions.connectViaWiFi(host, port) }) {
                showWiFi = false
                currentDeps.onboarding.advance()
            }
        },
        onDismissTroubleshooting = { showTroubleshooting = false },
        companionAssociations = deps.companionDevices.associations(),
        onClearStalePairings = {
            clearStaleCompanionPairings(deps.companionDevices.associations()) { handle ->
                currentDeps.companionDevices.disassociate(handle)
            }
            showTroubleshooting = false
        },
    )
    pendingSetup?.let { accessories ->
        SystemPairingSetupDialog(
            accessories = accessories,
            onForget = { confirmLeftoverForget() },
            onCancel = {
                pendingSetup = null
                currentDeps.actions.setPairingInProgress(false)
            },
        )
    }
}

/** The primary connect affordance: simulator, demo, other-app retry, else BLE scan. */
@Composable
private fun PairStepPrimaryButton(
    deps: ShellDependencies,
    demoEnabled: Boolean,
    otherAppDeviceId: String?,
    connecting: Boolean,
    onScan: () -> Unit,
    onRetry: (String) -> Unit,
    onSimulatorConnect: () -> Unit,
) {
    val retryId = otherAppDeviceId.takeIf {
        pairPrimaryUsesRetryConnection(
            otherAppDeviceId = otherAppDeviceId,
            supportsSimulatorConnect = deps.supportsSimulatorConnect,
            demoEnabled = demoEnabled,
        )
    }
    Button(
        onClick = {
            when {
                pairPrimaryUsesSimulatorConnect(deps.supportsSimulatorConnect, demoEnabled) -> onSimulatorConnect()
                retryId != null -> onRetry(retryId)
                else -> onScan()
            }
        },
        enabled = !connecting,
        modifier = Modifier.fillMaxWidth(),
    ) {
        if (connecting) {
            FilledButtonProgress()
        }
        Text(
            when {
                connecting -> stringResource(Res.string.onboarding_device_scan_connecting)
                deps.supportsSimulatorConnect ->
                    stringResource(Res.string.onboarding_device_scan_connect_simulator)
                demoEnabled -> stringResource(Res.string.onboarding_device_scan_continue_demo)
                retryId != null -> stringResource(Res.string.onboarding_device_scan_retry_connection)
                else -> stringResource(Res.string.onboarding_device_scan_add_device)
            },
        )
    }
}

/** iOS `DeviceScanView` pins Retry Connection after an other-app failure; simulator/demo stay first. */
internal fun pairPrimaryUsesRetryConnection(
    otherAppDeviceId: String?,
    supportsSimulatorConnect: Boolean,
    demoEnabled: Boolean,
): Boolean = otherAppDeviceId != null && !supportsSimulatorConnect && !demoEnabled

/** Continue Demo and the iOS-simulator CTA both connect the mock radio, not explore-without-radio. */
internal fun pairPrimaryUsesSimulatorConnect(supportsSimulatorConnect: Boolean, demoEnabled: Boolean): Boolean =
    supportsSimulatorConnect || demoEnabled

/** iOS copies a non-nil `otherAppWarningDeviceID` and keeps it after the alert dismisses. */
internal fun latchedOtherAppDeviceId(current: String?, incoming: ConnectionAlert?): String? =
    (incoming as? ConnectionAlert.OtherApp)?.deviceId ?: current

/**
 * Runs [connect] and reports whether it succeeded, firing the matching success/failure haptic (iOS
 * `DeviceScanView` pairing feedback).
 *
 * The failure itself is not rendered here. The connection graph routes it into the app-wide connection
 * alert, which carries localized per-kind copy and the pairing recovery, and which is hosted above the
 * onboarding flow precisely so a first-run pairing failure reaches it. Showing the exception text as well
 * would put untranslated developer English on the screen iOS deliberately leaves clean.
 */
internal suspend inline fun runConnect(haptics: AppHaptics, connect: suspend () -> Unit): Boolean = try {
    connect()
    haptics.success()
    true
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: Exception) {
    // The connection alert owns the message. A failed connect only signals through the haptic.
    haptics.failure()
    false
}
