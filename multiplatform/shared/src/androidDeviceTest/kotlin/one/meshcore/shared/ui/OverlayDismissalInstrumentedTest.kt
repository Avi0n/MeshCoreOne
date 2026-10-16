package one.meshcore.shared.ui

import android.view.KeyEvent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.PairingFailureKind
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.onboarding.PairStepDialogs
import one.meshcore.shared.onboarding.TroubleshootingDialog
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_thread_back_accessibility
import org.jetbrains.compose.resources.stringResource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OverlayDismissalInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun nestedBackDismissesEachOverlayWithoutRunningItsActions() = assertDismissal(systemBack = false)

    @Test
    fun androidBackDismissesEachOverlayWithoutRunningItsActions() = assertDismissal(systemBack = true)

    private fun assertDismissal(systemBack: Boolean) {
        val fixture = OverlayFixture()
        lateinit var back: String
        compose.setContent {
            back = stringResource(Res.string.chats_thread_back_accessibility)
            MaterialTheme {
                Text(PARENT)
                fixture.Content()
            }
        }
        OverlayKind.entries.forEachIndexed { index, kind ->
            compose.runOnIdle { fixture.shown.value = kind }
            compose.onAllNodes(isDialog()).assertCountEquals(1)
            val button = compose.onNodeWithContentDescription(back).assertIsDisplayed()
            if (systemBack) {
                InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK)
            } else {
                button.performClick()
            }
            compose.waitForIdle()
            compose.onAllNodes(isDialog()).assertCountEquals(0)
            compose.onNodeWithText(PARENT).assertIsDisplayed()
            compose.runOnIdle {
                assertNull(fixture.shown.value)
                assertEquals(index + 1, fixture.dismissals.size)
                assertEquals(kind, fixture.dismissals.last())
                assertEquals(0, fixture.actionsInvoked)
            }
        }
    }

    private companion object {
        const val PARENT = "Parent content"
    }
}

private enum class OverlayKind { BASE, ONBOARDING_WIFI, TRANSIENT_PAIRING, SYSTEM_PAIRING, TROUBLESHOOTING }

private class OverlayFixture {
    val shown = mutableStateOf<OverlayKind?>(null)
    val dismissals = mutableListOf<OverlayKind>()
    var actionsInvoked = 0
        private set

    private val action: () -> Unit = { actionsInvoked++ }
    private val actions = object : ConnectionActions by InertConnectionActions() {
        override suspend fun connect(deviceId: String) {
            action()
        }

        override suspend fun disconnect() {
            action()
        }

        override suspend fun deleteSavedDevice(deviceId: Uuid) {
            action()
        }

        override fun requestConnectPicker() {
            action()
        }
    }

    @Composable
    fun Content() {
        val kind = shown.value ?: return
        val dismiss = {
            dismissals += kind
            shown.value = null
        }
        when (kind) {
            OverlayKind.BASE -> FullScreenOverlay(onDismiss = dismiss, title = "Overlay") { Text("Overlay content") }
            OverlayKind.ONBOARDING_WIFI -> PairStepDialogs(
                showNoDeviceSheet = false,
                showDemoUnlockedAlert = false,
                showWiFi = true,
                showTroubleshooting = false,
                connecting = false,
                openBluetoothSettings = action,
                onExplore = action,
                onDismissNoDevice = action,
                onDismissDemoAlert = action,
                onDismissWiFi = dismiss,
                onConnectWiFi = { _, _ -> action() },
                onDismissTroubleshooting = action,
                companionAssociations = emptyList(),
                onClearStalePairings = action,
            )
            OverlayKind.TRANSIENT_PAIRING -> ConnectionAlertContent(
                alert = ConnectionAlert.Pairing("radio", PairingFailureKind.TRANSIENT),
                actions = actions,
                onDismiss = dismiss,
            )
            OverlayKind.SYSTEM_PAIRING -> SystemPairingSetupDialog(
                accessories = listOf(CompanionAssociation("radio", "Radio")),
                onForget = action,
                onCancel = dismiss,
            )
            OverlayKind.TROUBLESHOOTING -> TroubleshootingDialog(
                onDismiss = dismiss,
                onOpenBluetoothSettings = action,
                associations = emptyList(),
                onClearStalePairings = action,
            )
        }
    }
}
