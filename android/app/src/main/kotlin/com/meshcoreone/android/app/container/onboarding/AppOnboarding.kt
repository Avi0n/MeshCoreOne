// AndroidOnly: WP-303 Builds OnboardingFeatureDependencies from the AppContainer and supplies the activity seams.
// AndroidOnly: WP-211 Supply app-localized resources to the mounted regional catalog.
package com.meshcoreone.android.app.container.onboarding

import android.content.Context
import android.content.Intent
import android.content.res.Resources
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import com.meshcoreone.android.app.container.AppContainer
import com.meshcoreone.android.app.state.ConnectionUiSnapshot
import com.meshcoreone.android.core.connectivity.ble.BleScanCoordinator
import com.meshcoreone.android.core.connectivity.pairing.BluetoothScanPairingService
import com.meshcoreone.android.core.connectivity.permissions.AndroidPermissionState
import com.meshcoreone.android.core.connectivity.permissions.PermissionSnapshot
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.app.content.LocationPermissionResultReporting
import com.meshcoreone.android.core.services.content.RegionResolver
import com.meshcoreone.android.core.services.device.RadioPreset
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceError
import com.meshcoreone.android.core.contracts.domain.errors.SettingsServiceException
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.feature.onboarding.OnboardingDemoPort
import com.meshcoreone.android.feature.onboarding.OnboardingFeatureDependencies
import com.meshcoreone.android.feature.onboarding.OnboardingFlagStore
import com.meshcoreone.android.feature.onboarding.OnboardingPairingPort
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionKind
import com.meshcoreone.android.feature.onboarding.OnboardingPermissionPort
import com.meshcoreone.android.feature.onboarding.OnboardingPresetPort
import com.meshcoreone.android.feature.onboarding.OnboardingRegionPort
import com.meshcoreone.android.feature.onboarding.OnboardingWiFiPort
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Process-lifetime platform pieces the container cannot derive itself; built once by the container factory. */
class OnboardingPlatform(
    val flags: WriteBehindOnboardingFlags,
    val permissionFacts: OnboardingPermissionFacts,
    val requests: RequestedPermissions = RequestedPermissions(),
    val regionResolver: RegionResolver? = null,
    val locationReporter: LocationPermissionResultReporting? = null,
    val scans: BleScanCoordinator? = null,
    val openAppSettings: () -> Unit = {},
    val sdkInt: Int = Build.VERSION.SDK_INT,
    val regionResources: (() -> Resources)? = null,
)

/** [OnboardingPermissionFacts] over the real platform readers. */
class AndroidOnboardingPermissionFacts(
    private val context: Context,
    private val state: AndroidPermissionState = AndroidPermissionState(context),
) : OnboardingPermissionFacts {
    override fun snapshot(): PermissionSnapshot = state.snapshot()
    override fun locationGranted(): Boolean =
        context.checkSelfPermission(OnboardingPermissionMapping.COARSE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission("android.permission.ACCESS_FINE_LOCATION") ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
}

/** Opens this app's system settings page (the target of every "Open Settings" action). */
fun appSettingsOpener(context: Context): () -> Unit = {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
    )
}

/** [OnboardingConnectionHost] over the process app state, pairing coordinator and runtime manager. */
class AppStateOnboardingHost(private val container: AppContainer) : OnboardingConnectionHost {
    private val appState get() = container.appState
    override val currentState: DeviceConnectionState get() = container.connectionManager.connectionState
    override fun connectionStates(): Flow<DeviceConnectionState> =
        container.connectionManager.snapshot.map { it.state }.distinctUntilChanged()
    override val ui: StateFlow<ConnectionUiSnapshot> get() = appState.connectionUI.state
    override val isPairingActive: Boolean get() = container.connectionPort.isPairingFlowActive
    override val registeredAccessoryCount: Int get() = container.pairing.pairing.registeredDeviceCount
    override val hasLastConnectedDevice: Boolean get() = container.connectionPort.lastConnectedDeviceId != null

    override suspend fun startPairing(): com.meshcoreone.android.feature.onboarding.OnboardingConnectOutcome {
        val completed = appState.startDeviceScanAndAwait()
        return OnboardingConnectMapping.afterPairing(currentState, ui.value, completed)
    }

    override suspend fun retryConnect(deviceId: UUID) {
        container.connectionPort.connect(deviceId, forceReconnect = true)
        appState.wireServicesIfConnected()
    }

    override suspend fun connectWiFi(host: String, port: UShort) = appState.connectViaWiFi(host, port)
    override suspend fun clearStaleRegistrations() = container.pairing.pairing.clearStaleRegistrations()

    override fun consumeFailure() {
        appState.connectionUI.clearPairingFailure()
        appState.connectionUI.otherAppWarningDeviceId = null
    }
}

/** [OnboardingRadioConfigurator] over the live session's settings service and the connected-device editor. */
class AppStateRadioConfigurator(private val container: AppContainer) : OnboardingRadioConfigurator {
    override fun connectionStates(): Flow<DeviceConnectionState> =
        container.connectionManager.snapshot.map { it.state }.distinctUntilChanged()
    override val isDemoRadio: Boolean get() = false

    override suspend fun applyPreset(preset: RadioPreset) {
        val session = container.appState.services ?: throw SettingsServiceException(SettingsServiceError.NotConnected)
        val info = session.settingsService.applyRadioPresetVerified(preset)
        container.connectionPort.updateDevice(info, preset.id)
    }
}

/**
 * The app-bound [OnboardingFeatureDependencies]. Permission requests go through an activity-result launcher
 * (`RequestMultiplePermissions`) and system back through `BackHandler`: the seams the feature module cannot compile.
 */
class AppOnboarding(
    override val flags: OnboardingFlagStore,
    override val permissions: AndroidOnboardingPermissionPort,
    override val pairing: OnboardingPairingPort,
    override val wifi: OnboardingWiFiPort,
    override val demo: OnboardingDemoPort,
    override val region: OnboardingRegionPort,
    override val presets: OnboardingPresetPort,
    private val settingsOpener: () -> Unit,
    private val locationReporter: LocationPermissionResultReporting?,
    private val sdkInt: Int,
    /** The first-run gate: true once the persisted completion flag is set. */
    val hasCompleted: StateFlow<Boolean>,
    val pairingState: com.meshcoreone.android.app.state.AppState? = null,
) : OnboardingFeatureDependencies {
    override fun openAppSettings() = settingsOpener()

    /** Records a finished runtime request: the location grant first (the producer reads it), then the snapshot. */
    fun onPermissionResult(kind: OnboardingPermissionKind, grants: Map<String, Boolean>) {
        if (kind == OnboardingPermissionKind.LOCATION) locationReporter?.reportPermissionResult(grants.values.any { it })
        permissions.onRequestFinished(kind)
    }

    @Composable
    override fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit {
        var pending by remember { mutableStateOf<OnboardingPermissionKind?>(null) }
        val latest by rememberUpdatedState(onResult)
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
            pending?.let { onPermissionResult(it, grants) }
            pending = null
            latest()
        }
        return remember(launcher) {
            { kind ->
                val names = permissions.permissionsFor(kind, sdkInt)
                if (names.isEmpty()) {
                    onPermissionResult(kind, emptyMap())
                    latest()
                } else {
                    pending = kind
                    launcher.launch(names.toTypedArray())
                }
            }
        }
    }

    @Composable
    override fun InterceptBack(enabled: Boolean, onBack: () -> Unit) = BackHandler(enabled, onBack)

    /**
     * The same bindings with a throwaway flag store, for re-running radio setup from Settings after onboarding:
     * a completed persisted flag would otherwise end the flow on entry. Completion here persists nothing.
     */
    fun forRerun(): OnboardingFeatureDependencies = Rerun(this)

    private class Rerun(private val base: AppOnboarding) : OnboardingFeatureDependencies by base {
        override val flags: OnboardingFlagStore = com.meshcoreone.android.feature.onboarding.InMemoryOnboardingFlagStore()

        @Composable
        override fun rememberPermissionRequester(onResult: () -> Unit): (OnboardingPermissionKind) -> Unit =
            base.rememberPermissionRequester(onResult)

        @Composable
        override fun InterceptBack(enabled: Boolean, onBack: () -> Unit) = base.InterceptBack(enabled, onBack)
    }
}

object AppOnboardingFactory {
    /** Binds every onboarding port to [container]; [scope] owns the derived state flows (process lifetime). */
    fun create(container: AppContainer, platform: OnboardingPlatform, scope: CoroutineScope): AppOnboarding {
        val host = AppStateOnboardingHost(container)
        val service = container.pairing.pairing as? BluetoothScanPairingService
        val scanFallback = service?.let { svc -> platform.scans?.let { ScanFallbackPort(svc, it) } }
        val permissions = AndroidOnboardingPermissionPort(
            platform.permissionFacts, platform.requests, scanFallback = service != null,
        )
        return AppOnboarding(
            flags = platform.flags,
            permissions = permissions,
            pairing = AppOnboardingPairingPort(host, scope, scanFallback),
            wifi = AppOnboardingWiFiPort(host),
            demo = AppOnboardingDemoPort(DemoModeManager.shared, scope),
            region = AppOnboardingRegionPort(
                ServicesRegionCatalog(resources = platform.regionResources), container.appState.regionSelectionFlow,
                { container.appState.regionSelection = it }, platform.regionResolver?.let { resolver -> suspend { resolver.resolve() } },
            ),
            presets = AppOnboardingPresetPort(AppStateRadioConfigurator(container), scope),
            settingsOpener = platform.openAppSettings,
            locationReporter = platform.locationReporter,
            sdkInt = platform.sdkInt,
            hasCompleted = platform.flags.hasCompleted,
            pairingState = container.appState,
        )
    }
}
