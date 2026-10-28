// AndroidOnly: WP-303 Mounted consent actions exercise the production AppState, not a presentation-only flag.
package com.meshcoreone.android.app.container.onboarding

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.meshcoreone.android.app.container.FakeConnectionPort
import com.meshcoreone.android.app.container.appStateOf
import com.meshcoreone.android.app.container.deviceOf
import com.meshcoreone.android.app.state.AppState
import com.meshcoreone.android.core.connectivity.pairing.DevicePairingError
import com.meshcoreone.android.core.connectivity.pairing.SystemPairedAccessory
import com.meshcoreone.android.core.connectivity.pairing.SystemPairingSetupPrompt
import com.meshcoreone.android.core.contracts.domain.DeviceConnectionState
import com.meshcoreone.android.core.designsystem.MeshCoreTheme
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], qualifiers = "w400dp-h800dp")
class SystemPairingSetupTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val activity = Robolectric.buildActivity(ComponentActivity::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val port = FakeConnectionPort()
    private val first = SystemPairedAccessory(UUID.randomUUID(), "First radio")
    private val second = SystemPairedAccessory(UUID.randomUUID(), "Second radio")
    private lateinit var state: AppState

    @Before fun setUp() {
        state = appStateOf(scope, port)
        port.paired += listOf(first, second)
        port.isPairingFlowActive = true
        state.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(listOf(first, second))
        activity.setup().visible().get().setContent { MeshCoreTheme { SystemPairingSetupHost(state) } }
    }

    @After fun close() {
        activity.pause().stop().destroy()
        scope.cancel()
    }

    @Test fun mountedSheetNamesTheAssociationsAndDoesNothingUntilConfirmation() {
        compose.onNodeWithText(first.name).assertIsDisplayed()
        compose.onNodeWithText(second.name).assertIsDisplayed()
        compose.onNodeWithTag("system-pairing-forget").assertIsDisplayed()
        assertEquals(0, port.pickerCalls)
        assertNull(port.lastRemoved)
    }

    @Test fun cancelPreservesTheLiveConnectionAndBothAssociations() {
        val live = deviceOf()
        port.connectionState = DeviceConnectionState.CONNECTED
        port.connectedDevice = live
        compose.onNodeWithTag("system-pairing-cancel").performClick()
        compose.onNodeWithTag("system-pairing-setup").assertDoesNotExist()
        assertEquals(listOf(first, second), port.paired)
        assertEquals(live, port.connectedDevice)
        assertEquals(DeviceConnectionState.CONNECTED, port.connectionState)
        assertFalse(port.isPairingFlowActive)
        assertTrue(port.calls.none { it.startsWith("disconnect") })
        assertEquals(0, port.pickerCalls)
    }

    @Test fun forgetRemovesOnlyThePresentedAssociationsAndOpensThePickerOnce() {
        val sibling = SystemPairedAccessory(UUID.randomUUID(), "New sibling")
        port.paired += sibling
        port.pickerOutcomes += { throw DevicePairingError.Cancelled() }
        compose.onNodeWithTag("system-pairing-forget").performClick()
        compose.onNodeWithTag("system-pairing-setup").assertDoesNotExist()
        assertEquals(listOf(sibling), port.paired)
        assertEquals(1, port.pickerCalls)
        assertFalse(state.connectionUI.isBusy)
        assertFalse(port.isPairingFlowActive)
    }

    @Test fun replacingThePromptDoesNotCancelTheReplacementOrUseTheOldAccessoryList() {
        compose.runOnIdle {
            state.connectionUI.pendingSystemPairingSetup = SystemPairingSetupPrompt(listOf(second))
        }
        compose.onNodeWithText("Set Up ${second.name}").assertIsDisplayed()
        assertTrue(port.isPairingFlowActive)
        compose.onNodeWithTag("system-pairing-forget").performClick()
        compose.onNodeWithTag("system-pairing-setup").assertDoesNotExist()
        assertEquals(listOf(first), port.paired)
        assertEquals(1, port.pickerCalls)
    }

    @Suppress("DEPRECATION")
    @Test fun systemBackDismissesWithoutRemovingAssociationsOrOpeningThePicker() {
        compose.onNodeWithTag("system-pairing-setup").assertIsDisplayed()
        compose.runOnUiThread { org.robolectric.shadows.ShadowDialog.getLatestDialog().onBackPressed() }
        compose.onNodeWithTag("system-pairing-setup").assertDoesNotExist()
        assertEquals(listOf(first, second), port.paired)
        assertEquals(0, port.pickerCalls)
        assertFalse(port.isPairingFlowActive)
    }
}
