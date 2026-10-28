// AndroidOnly: WP-303 First-run gate: onboarding until the flag is set, then the shell and the Chats root.
package com.meshcoreone.android.app.container.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.app.navigation.NavigationCoordinator
import com.meshcoreone.android.core.contracts.AppTab
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import com.meshcoreone.android.core.model.RegionSelection
import com.meshcoreone.android.core.services.simulator.DemoModeDefaults
import com.meshcoreone.android.core.services.simulator.DemoModeManager
import com.meshcoreone.android.feature.onboarding.OnboardingState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w400dp-h800dp")
class OnboardingGateTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val host = Robolectric.buildActivity(ComponentActivity::class.java)

    /** Reduce-motion (animator scale 0) stops the welcome mesh animation, which would keep Compose from idling. */
    @Before fun reduceMotion() {
        val resolver = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>().contentResolver
        android.provider.Settings.Global.putFloat(resolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
    }

    @After fun closeHost() { host.pause().stop().destroy() }

    private fun onboarding(
        flags: WriteBehindOnboardingFlags,
        pairingState: com.meshcoreone.android.app.state.AppState? = null,
    ): AppOnboarding {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        val host = FakeConnectionHost()
        val demoStore = HashMap<String, Boolean>()
        return AppOnboarding(
            flags = flags,
            permissions = AndroidOnboardingPermissionPort(FakePermissionFacts(), RequestedPermissions(), scanFallback = false),
            pairing = AppOnboardingPairingPort(host, scope, null),
            wifi = AppOnboardingWiFiPort(host),
            demo = AppOnboardingDemoPort(DemoModeManager(object : DemoModeDefaults {
                override fun bool(forKey: String) = demoStore[forKey] ?: false
                override fun set(value: Boolean, forKey: String) { demoStore[forKey] = value }
            }), scope),
            region = AppOnboardingRegionPort(ServicesRegionCatalog(), MutableStateFlow<RegionSelection?>(null), {}, null),
            presets = AppOnboardingPresetPort(FakeRadio(), scope),
            settingsOpener = {}, locationReporter = null, sdkInt = 34, hasCompleted = flags.hasCompleted,
            pairingState = pairingState,
        )
    }

    private fun flags(completed: Boolean) = WriteBehindOnboardingFlags(
        mapOf(OnboardingState.KEY_HAS_COMPLETED to completed), CoroutineScope(Dispatchers.Unconfined), { _, _ -> },
    )

    private fun showGate(coordinator: NavigationCoordinator, bound: AppOnboarding?) = host.setup().visible().get().setContent {
        MeshCoreTheme { OnboardingGate(coordinator, bound) { Box(Modifier.testTag("shell")) { Text("shell") } } }
    }

    @Test fun `incomplete onboarding hides the shell and completion reveals it and selects chats`() {
        val flags = flags(completed = false)
        val coordinator = NavigationCoordinator().also { it.selectTab(AppTab.SETTINGS) }
        showGate(coordinator, onboarding(flags))
        compose.onNodeWithTag("shell").assertDoesNotExist()
        assertEquals(AppTab.SETTINGS, coordinator.state.value.selectedTab)

        compose.runOnUiThread { OnboardingState(flags).completeOnboarding() }
        compose.waitForIdle()
        compose.onNodeWithTag("shell").assertIsDisplayed()
        assertEquals(AppTab.CHATS, coordinator.state.value.selectedTab)
    }

    @Test fun `already completed install shows the shell at once and leaves navigation alone`() {
        val coordinator = NavigationCoordinator().also { it.selectTab(AppTab.MAP) }
        showGate(coordinator, onboarding(flags(completed = true)))
        compose.onNodeWithTag("shell").assertIsDisplayed()
        assertEquals(AppTab.MAP, coordinator.state.value.selectedTab)
    }

    @Test fun `no bindings shows the shell and never a fake flow`() {
        showGate(NavigationCoordinator(), null)
        compose.onNodeWithTag("shell").assertIsDisplayed()
    }

    @Test fun `consent is mounted above both onboarding and the completed shell`() {
        val scope = CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val state = com.meshcoreone.android.app.container.appStateOf(scope)
            state.connectionUI.pendingSystemPairingSetup =
                com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt(
                    listOf(com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory(
                        java.util.UUID.randomUUID(), "Leftover radio",
                    )),
                )
            val flags = flags(completed = false)
            showGate(NavigationCoordinator(), onboarding(flags, state))
            compose.onNodeWithTag("system-pairing-setup").assertIsDisplayed()
            compose.onNodeWithTag("shell").assertDoesNotExist()
            compose.runOnUiThread { OnboardingState(flags).completeOnboarding() }
            compose.waitForIdle()
            compose.onNodeWithTag("shell").assertExists()
            compose.onNodeWithTag("system-pairing-setup").assertIsDisplayed()
            compose.onNodeWithTag("system-pairing-cancel").performClick()
            compose.onNodeWithTag("system-pairing-setup").assertDoesNotExist()
        } finally { scope.coroutineContext[kotlinx.coroutines.Job]?.cancel() }
    }
}
