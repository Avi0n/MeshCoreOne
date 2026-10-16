package one.meshcore.shared.onboarding

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import one.meshcore.shared.backup.RegionSelection
import one.meshcore.shared.backup.RegionSource
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.settings.RadioDeviceSnapshot
import one.meshcore.shared.settings.RadioPreset
import one.meshcore.shared.ui.InertBleScanController
import one.meshcore.shared.ui.InertConnectionActions
import one.meshcore.shared.ui.InertPermissionController
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionUiState
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.ShellMaintenanceInputs
import one.meshcore.shared.ui.ShellPreferenceInputs
import one.meshcore.shared.ui.ShellRadioInputs
import one.meshcore.shared.ui.ShellServiceInputs
import one.meshcore.shared.ui.ShellSessionInputs
import one.meshcore.shared.ui.ShellSharingInputs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OnboardingLiveOwnersInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun replacementResolverCancelsRetiredLookupAndStartsCurrentOne() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val onboarding = OnboardingViewModel(InMemoryPreferencesStore())
        val firstCalls = AtomicInteger()
        val firstCancelled = AtomicBoolean()
        val replacementCalls = AtomicInteger()
        val deps = mutableStateOf(
            shell(
                scope = scope,
                onboarding = onboarding,
                resolveRegion = {
                    firstCalls.incrementAndGet()
                    try {
                        awaitCancellation()
                    } finally {
                        firstCancelled.set(true)
                    }
                },
            ),
        )

        try {
            compose.setContent { MaterialTheme { RegionStep(deps.value) } }
            compose.waitUntil(timeoutMillis = OWNER_TRANSITION_TIMEOUT_MS) { firstCalls.get() == 1 }

            compose.runOnIdle {
                deps.value = shell(
                    scope = scope,
                    onboarding = onboarding,
                    resolveRegion = {
                        replacementCalls.incrementAndGet()
                        REPLACEMENT_REGION
                    },
                )
            }

            compose.waitUntil(timeoutMillis = OWNER_TRANSITION_TIMEOUT_MS) {
                replacementCalls.get() == 1 && firstCancelled.get()
            }
            assertEquals(1, firstCalls.get())
            assertTrue(firstCancelled.get())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun presetRetryUsesReplacementRadioWriter() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val onboarding = OnboardingViewModel(InMemoryPreferencesStore())
        val replacementOnboarding = OnboardingViewModel(InMemoryPreferencesStore())
        val firstCalls = AtomicInteger()
        val replacementCalls = AtomicInteger()
        val preset = onboardingSelectablePresets(PRESET_REGION).first()
        val deps = mutableStateOf(
            shell(
                scope = scope,
                onboarding = onboarding,
                regionSelection = { PRESET_REGION },
                radioSnapshot = { CONNECTED_RADIO },
                applyRadioPreset = {
                    firstCalls.incrementAndGet()
                    error("retired writer")
                },
            ),
        )

        try {
            compose.setContent { MaterialTheme { PresetStep(deps.value) } }
            compose.onNodeWithText(preset.name).performClick()
            compose.onNodeWithText("Use ${preset.name}").performClick()
            compose.waitUntil(timeoutMillis = OWNER_TRANSITION_TIMEOUT_MS) { firstCalls.get() == 1 }
            compose.onNodeWithText("Retry").assertExists()

            compose.runOnIdle {
                deps.value = shell(
                    scope = scope,
                    onboarding = replacementOnboarding,
                    regionSelection = { PRESET_REGION },
                    radioSnapshot = { CONNECTED_RADIO },
                    applyRadioPreset = { replacementCalls.incrementAndGet() },
                )
            }
            compose.onNodeWithText("Retry").performClick()

            compose.waitUntil(timeoutMillis = OWNER_TRANSITION_TIMEOUT_MS) {
                replacementCalls.get() == 1 && replacementOnboarding.completed.value
            }
            assertEquals(1, firstCalls.get())
            assertFalse(onboarding.completed.value)
        } finally {
            scope.cancel()
        }
    }

    private fun shell(
        scope: CoroutineScope,
        onboarding: OnboardingViewModel,
        resolveRegion: suspend () -> RegionSelection? = { null },
        regionSelection: () -> RegionSelection? = { null },
        radioSnapshot: () -> RadioDeviceSnapshot? = { null },
        applyRadioPreset: suspend (RadioPreset) -> Unit = {},
    ): ShellDependencies = ShellDependencies(
        session = ShellSessionInputs(
            coordinator = NavigationCoordinator(scope),
            onboarding = onboarding,
            initialOnboardingComplete = false,
            statusPill = MutableStateFlow(StatusPillState.Hidden),
            battery = MutableStateFlow(null),
            connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED),
            connectionAlert = MutableStateFlow(null),
            unreadCount = MutableStateFlow(0),
            localNodeName = MutableStateFlow(null),
            permissions = InertPermissionController(
                PermissionUiState(
                    granted = setOf(OnboardingPermission.LOCATION),
                    determined = setOf(OnboardingPermission.LOCATION),
                ),
            ),
            bleScan = InertBleScanController(),
            actions = InertConnectionActions(),
        ),
        services = ShellServiceInputs(),
        sharing = ShellSharingInputs(),
        preferences = ShellPreferenceInputs(),
        radio = ShellRadioInputs(
            settingsStartupReadsAllowed = MutableStateFlow(false),
            refreshDeviceSettings = {},
            radioSnapshot = radioSnapshot,
            regionSelection = regionSelection,
            resolveRegion = resolveRegion,
            applyRadioPreset = applyRadioPreset,
        ),
        maintenance = ShellMaintenanceInputs(),
    )

    private companion object {
        const val OWNER_TRANSITION_TIMEOUT_MS = 5_000L
        val REPLACEMENT_REGION = RegionSelection(countryCode = "US", source = RegionSource.LOCATION)
        val PRESET_REGION = RegionSelection(countryCode = "US", source = RegionSource.MANUAL)
        val CONNECTED_RADIO = RadioDeviceSnapshot(
            frequencyKHz = 910_525,
            bandwidthHz = 62_500,
            spreadingFactor = 7,
            codingRate = 5,
        )
    }
}
