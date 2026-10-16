package one.meshcore.shared.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_alert_connection_failed_remove_and_retry
import one.meshcore.shared.resources.localizable_error_ble_bluetooth_unauthorized
import org.jetbrains.compose.resources.stringResource
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BluetoothPermissionAlertInstrumentedTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun permissionAlertRendersTheLocalizedRemedyWithoutDestructiveBondRepair() {
        var remedy = ""
        var removePairing = ""
        compose.setContent {
            remedy = stringResource(Res.string.localizable_error_ble_bluetooth_unauthorized)
            removePairing = stringResource(Res.string.localizable_alert_connection_failed_remove_and_retry)
            MaterialTheme {
                ConnectionAlertContent(ConnectionAlert.BluetoothUnauthorized, InertConnectionActions(), {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText(remedy).assertIsDisplayed()
        compose.onNodeWithText(removePairing).assertDoesNotExist()
    }
}
