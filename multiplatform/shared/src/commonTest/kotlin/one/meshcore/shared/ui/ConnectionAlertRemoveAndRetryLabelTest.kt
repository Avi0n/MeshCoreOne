package one.meshcore.shared.ui

import one.meshcore.shared.connection.PairingFailureKind
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_onboarding_device_scan_error_authentication_failed_settings
import one.meshcore.shared.resources.android_onboarding_device_scan_error_pin_rejected
import one.meshcore.shared.resources.android_onboarding_device_scan_error_pin_rejected_settings
import one.meshcore.shared.resources.localizable_accessibility_alert_connection_failed_remove_and_retry
import one.meshcore.shared.resources.onboarding_device_scan_error_authentication_failed
import one.meshcore.shared.resources.onboarding_device_scan_error_connection_failed
import kotlin.test.Test
import kotlin.test.assertEquals

class ConnectionAlertRemoveAndRetryLabelTest {
    @Test
    fun removeAndRetrySpeaksPairingRemoval() {
        assertEquals(
            Res.string.localizable_accessibility_alert_connection_failed_remove_and_retry,
            removeAndRetrySpokenResource(),
        )
    }

    @Test
    fun pinRejectionUsesTheAndroidForgetAndReenterCopyWhenTheOsBondCanDrop() {
        assertEquals(
            Res.string.android_onboarding_device_scan_error_pin_rejected,
            pairingFailureMessageResource(PairingFailureKind.PIN_REJECTED, canDropOsBond = true),
        )
        assertEquals(
            Res.string.onboarding_device_scan_error_authentication_failed,
            pairingFailureMessageResource(PairingFailureKind.AUTHENTICATION, canDropOsBond = true),
        )
        assertEquals(
            Res.string.onboarding_device_scan_error_connection_failed,
            pairingFailureMessageResource(PairingFailureKind.TRANSIENT, canDropOsBond = true),
        )
    }

    @Test
    fun pairingCopyNamesBluetoothSettingsWhenTheOsBondCannotDrop() {
        assertEquals(
            Res.string.android_onboarding_device_scan_error_pin_rejected_settings,
            pairingFailureMessageResource(PairingFailureKind.PIN_REJECTED, canDropOsBond = false),
        )
        assertEquals(
            Res.string.android_onboarding_device_scan_error_authentication_failed_settings,
            pairingFailureMessageResource(PairingFailureKind.AUTHENTICATION, canDropOsBond = false),
        )
        assertEquals(
            Res.string.onboarding_device_scan_error_connection_failed,
            pairingFailureMessageResource(PairingFailureKind.TRANSIENT, canDropOsBond = false),
        )
    }
}
