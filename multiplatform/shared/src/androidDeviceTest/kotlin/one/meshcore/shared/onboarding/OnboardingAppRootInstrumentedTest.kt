package one.meshcore.shared.onboarding

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.DeviceConnectionState
import one.meshcore.shared.connection.PairingFailureKind
import one.meshcore.shared.connection.StatusPillState
import one.meshcore.shared.navigation.NavigationCoordinator
import one.meshcore.shared.prefs.InMemoryPreferencesStore
import one.meshcore.shared.prefs.PersistenceKeys
import one.meshcore.shared.ui.AppRoot
import one.meshcore.shared.ui.InertBleScanController
import one.meshcore.shared.ui.InertConnectionActions
import one.meshcore.shared.ui.OnboardingPermission
import one.meshcore.shared.ui.PermissionController
import one.meshcore.shared.ui.PermissionUiState
import one.meshcore.shared.ui.ShellDependencies
import one.meshcore.shared.ui.ShellMaintenanceInputs
import one.meshcore.shared.ui.ShellPreferenceInputs
import one.meshcore.shared.ui.ShellRadioInputs
import one.meshcore.shared.ui.ShellServiceInputs
import one.meshcore.shared.ui.ShellSessionInputs
import one.meshcore.shared.ui.ShellSharingInputs
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/**
 * Onboarding through [AppRoot]: Welcome, a denied permission card, back, a pairing alert while the
 * shell is hidden, and a new view model on the same store before completion.
 */
class OnboardingAppRootInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var prefs: InMemoryPreferencesStore
    private lateinit var alert: MutableStateFlow<ConnectionAlert?>

    @Before
    fun setUp() {
        prefs = InMemoryPreferencesStore()
        alert = MutableStateFlow(null)
        // Welcome's mesh draws with withFrameNanos, which never goes idle while the clock auto-advances.
        compose.mainClock.autoAdvance = false
    }

    @After
    fun tearDown() {
        compose.mainClock.autoAdvance = true
        scope.cancel()
    }

    @Test
    fun welcomeBackAndADeniedPermissionStayOutOfTheShell() {
        val onboarding = OnboardingViewModel(prefs)
        show(onboarding)
        compose.onNodeWithText(GET_STARTED).assertExists()
        compose.onNodeWithText(CHATS).assertDoesNotExist()

        clickText(GET_STARTED)
        assertDeniedPermissionCards()
        clickText(CONTINUE)
        compose.onNodeWithText(PAIR_TITLE).assertExists()
        clickBack()
        assertDeniedPermissionCards()
        clickBack()
        compose.onNodeWithText(GET_STARTED).assertExists()

        compose.runOnIdle {
            assertFalse(onboarding.completed.value)
            assertNull(onboarding.currentStep)
        }
        assertOnboardingUnfinished()
    }

    @Test
    fun aPairingFailureShowsWhileTheShellIsHidden() {
        val onboarding = OnboardingViewModel(prefs)
        onboarding.seedResume(listOf(OnboardingStep.PERMISSIONS, OnboardingStep.PAIR))
        alert.value = ConnectionAlert.Pairing(FAILED_PAIR_HANDLE, PairingFailureKind.PIN_REJECTED)
        show(onboarding)
        compose.onNodeWithText(PAIR_FAILED).assertExists()
        compose.onNodeWithText(PAIR_TITLE).assertExists()
        compose.onNodeWithText(CHATS).assertDoesNotExist()
        compose.runOnIdle { assertFalse(onboarding.completed.value) }
        assertOnboardingUnfinished()
    }

    @Test
    fun aNewViewModelOnTheSameStoreStaysAtWelcome() {
        val first = OnboardingViewModel(prefs)
        val shown = mutableStateOf(first)
        compose.setContent { AppRoot(shell(shown.value, deniedPermissions())) }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(GET_STARTED).assertExists()
        val restarted = OnboardingViewModel(prefs)
        compose.runOnIdle { shown.value = restarted }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText(GET_STARTED).assertExists()
        compose.onNodeWithText(CHATS).assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(first.completed.value)
            assertFalse(restarted.completed.value)
            assertNull(restarted.currentStep)
        }
        assertOnboardingUnfinished()
    }

    private fun show(onboarding: OnboardingViewModel) {
        compose.setContent { AppRoot(shell(onboarding, deniedPermissions())) }
        compose.mainClock.advanceTimeByFrame()
    }

    private fun clickText(label: String) {
        compose.onNodeWithText(label).performClick()
        compose.mainClock.advanceTimeBy(STEP_TRANSITION_MILLIS)
    }

    private fun clickBack() {
        compose.onNodeWithContentDescription(BACK).performClick()
        compose.mainClock.advanceTimeBy(STEP_TRANSITION_MILLIS)
    }

    private fun assertDeniedPermissionCards() =
        compose.onAllNodesWithText(OPEN_SETTINGS).assertCountEquals(DENIED_PERMISSION_COUNT)

    private fun assertOnboardingUnfinished() {
        runBlocking {
            assertNull(prefs.getBoolean(PersistenceKeys.HAS_COMPLETED_ONBOARDING))
        }
    }

    private fun deniedPermissions(): PermissionController = DenyingPermissionController(
        PermissionUiState(
            granted = emptySet(),
            determined = setOf(
                OnboardingPermission.NOTIFICATIONS,
                OnboardingPermission.LOCATION,
                OnboardingPermission.BLUETOOTH,
            ),
        ),
    )

    private fun shell(onboarding: OnboardingViewModel, permissions: PermissionController): ShellDependencies =
        ShellDependencies(
            session = ShellSessionInputs(
                coordinator = NavigationCoordinator(scope),
                onboarding = onboarding,
                initialOnboardingComplete = false,
                statusPill = MutableStateFlow(StatusPillState.Hidden),
                battery = MutableStateFlow(null),
                connectionState = MutableStateFlow(DeviceConnectionState.DISCONNECTED),
                connectionAlert = alert,
                unreadCount = MutableStateFlow(0),
                localNodeName = MutableStateFlow(null),
                permissions = permissions,
                bleScan = InertBleScanController(),
                actions = InertConnectionActions(),
            ),
            services = ShellServiceInputs(),
            sharing = ShellSharingInputs(),
            preferences = ShellPreferenceInputs(),
            radio = ShellRadioInputs(
                settingsStartupReadsAllowed = MutableStateFlow(false),
                refreshDeviceSettings = {},
                radioSnapshot = { null },
                regionSelection = { null },
                resolveRegion = { null },
                applyRadioPreset = {},
            ),
            maintenance = ShellMaintenanceInputs(),
        )

    private class DenyingPermissionController(initial: PermissionUiState) : PermissionController {
        private val current = MutableStateFlow(initial)
        override val state: StateFlow<PermissionUiState> = current

        override fun request(permission: OnboardingPermission) {
            current.value = current.value.copy(determined = current.value.determined + permission)
        }

        override fun refresh() = Unit
    }

    private companion object {
        const val GET_STARTED = "Get Started"
        const val CONTINUE = "Continue"
        const val OPEN_SETTINGS = "Settings"
        const val BACK = "Back"
        const val PAIR_TITLE = "Pair your device"
        const val PAIR_FAILED = "Couldn't Pair"
        const val CHATS = "Chats"
        const val FAILED_PAIR_HANDLE = "AA:BB:CC:DD:EE:01"
        const val DENIED_PERMISSION_COUNT = 3
        const val STEP_TRANSITION_MILLIS = 400L
    }
}
