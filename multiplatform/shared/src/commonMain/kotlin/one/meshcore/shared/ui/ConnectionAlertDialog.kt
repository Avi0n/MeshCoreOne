package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import one.meshcore.shared.connection.ConnectionAlert
import one.meshcore.shared.connection.PairingFailureKind
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_onboarding_device_scan_error_authentication_failed_settings
import one.meshcore.shared.resources.android_onboarding_device_scan_error_pin_rejected
import one.meshcore.shared.resources.android_onboarding_device_scan_error_pin_rejected_settings
import one.meshcore.shared.resources.localizable_accessibility_alert_connection_failed_remove_and_retry
import one.meshcore.shared.resources.localizable_alert_connection_failed_default_message
import one.meshcore.shared.resources.localizable_alert_connection_failed_remove_and_retry
import one.meshcore.shared.resources.localizable_alert_connection_failed_title
import one.meshcore.shared.resources.localizable_alert_could_not_connect_other_app_message
import one.meshcore.shared.resources.localizable_alert_could_not_connect_title
import one.meshcore.shared.resources.localizable_alert_pairing_failed_title
import one.meshcore.shared.resources.localizable_common_cancel
import one.meshcore.shared.resources.localizable_common_ok
import one.meshcore.shared.resources.localizable_common_try_again
import one.meshcore.shared.resources.localizable_error_ble_bluetooth_unauthorized
import one.meshcore.shared.resources.onboarding_device_scan_error_authentication_failed
import one.meshcore.shared.resources.onboarding_device_scan_error_connection_failed
import org.jetbrains.compose.resources.stringResource

/**
 * Renders a [ConnectionAlert] as a Material dialog, ported from the iOS `ContentView` connection alerts.
 * The safety invariant is preserved verbatim: the destructive "Remove and Try Again" recovery is shown
 * only when [one.meshcore.shared.connection.PairingFailureKind.requiresBondRemoval] is true (an
 * authentication or PIN-rejected failure); a transient failure keeps a non-destructive "Try Again" so a
 * working bond is never torn down by a merely transient error.
 */
@Composable
fun ConnectionAlertContent(alert: ConnectionAlert, actions: ConnectionActions, onDismiss: () -> Unit) {
    when (alert) {
        is ConnectionAlert.BluetoothUnauthorized -> AcknowledgedAlertDialog(
            title = stringResource(Res.string.localizable_alert_connection_failed_title),
            message = stringResource(Res.string.localizable_error_ble_bluetooth_unauthorized),
            confirmLabel = stringResource(Res.string.localizable_common_ok),
            onDismiss = onDismiss,
        )
        is ConnectionAlert.Generic -> AcknowledgedAlertDialog(
            title = stringResource(Res.string.localizable_alert_connection_failed_title),
            message = alert.message
                ?: stringResource(Res.string.localizable_alert_connection_failed_default_message),
            confirmLabel = stringResource(Res.string.localizable_common_ok),
            onDismiss = onDismiss,
        )
        is ConnectionAlert.OtherApp -> AcknowledgedAlertDialog(
            title = stringResource(Res.string.localizable_alert_could_not_connect_title),
            message = stringResource(Res.string.localizable_alert_could_not_connect_other_app_message),
            confirmLabel = stringResource(Res.string.localizable_common_ok),
            onDismiss = onDismiss,
        )
        is ConnectionAlert.Pairing -> PairingAlert(alert, actions, onDismiss)
    }
}

/**
 * The saved Device row behind a transport handle. A pairing failure names the radio by its volatile
 * transport handle (a Kable peripheral id), never by the stable row id, so the destructive recovery has
 * to resolve one to the other or it deletes nothing and leaves the dead bond in place.
 */
internal suspend fun savedDeviceRowId(actions: ConnectionActions, deviceId: String): Uuid? {
    val saved = actions.listSavedDevices()
    return saved.firstOrNull { it.bleHandle == deviceId }?.id
        ?: saved.firstOrNull { it.id == Uuid.normalized(deviceId) }?.id
}

/** Runs a pairing-alert action, then closes the alert. A failed action still closes it. */
internal suspend fun runPairingAlertAction(onDismiss: () -> Unit, action: suspend () -> Unit) {
    try {
        action()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        // A failed cleanup or retry must not leave the alert stuck open.
    }
    onDismiss()
}

@Composable
private fun PairingAlert(alert: ConnectionAlert.Pairing, actions: ConnectionActions, onDismiss: () -> Unit) {
    val destructive = alert.kind.requiresBondRemoval
    val scope = rememberCoroutineScope()
    // Per-kind copy, mirroring iOS `presentFreshPairingFailure` / `presentPairingFailure`: a rejected PIN
    // and a dead bond each name the specific problem and the bond-removal recovery, while a transient
    // failure keeps the generic connection-failed copy so a working bond is never described as broken.
    val titleRes = when (alert.kind) {
        PairingFailureKind.PIN_REJECTED, PairingFailureKind.AUTHENTICATION ->
            Res.string.localizable_alert_pairing_failed_title
        PairingFailureKind.TRANSIENT -> Res.string.localizable_alert_connection_failed_title
    }
    val messageRes = pairingFailureMessageResource(alert.kind, actions.canDropOsBond())
    fun removeAndRetry() {
        // Companion Device Manager drops the OS bond on API 36+ then disassociates, matching
        // ASK removeDevice. Older APIs still open Bluetooth settings from Troubleshooting.
        scope.launch {
            runPairingAlertAction(onDismiss) {
                savedDeviceRowId(actions, alert.deviceId)?.let { actions.deleteSavedDevice(it) }
                actions.disconnect()
                actions.requestConnectPicker()
            }
        }
    }
    fun tryAgain() {
        // A retry failure re-surfaces as a fresh ConnectionAlert through the normal
        // connect-failure routing, so the dialog can dismiss unconditionally here.
        scope.launch {
            runPairingAlertAction(onDismiss) {
                actions.connect(alert.deviceId)
            }
        }
    }
    if (!destructive) {
        FullScreenOverlay(
            onDismiss = onDismiss,
            title = stringResource(titleRes),
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(
                        top = PreferenceHubTopPadding,
                        bottom = PreferenceHubSectionGap,
                    ),
                verticalArrangement = Arrangement.spacedBy(PreferenceHubSectionGap),
            ) {
                Column(preferenceHubFormScroll()) {
                    PreferenceHubSection {
                        PreferenceHubStatusRow(stringResource(messageRes))
                    }
                }
                Button(
                    onClick = { tryAgain() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = PreferenceHubHorizontalPadding),
                ) {
                    Text(stringResource(Res.string.localizable_common_try_again))
                }
                val removeSpoken = stringResource(removeAndRetrySpokenResource())
                OutlinedButton(
                    onClick = { removeAndRetry() },
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.error,
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = PreferenceHubHorizontalPadding)
                        .semantics {
                            contentDescription = removeSpoken
                        },
                ) {
                    Text(stringResource(Res.string.localizable_alert_connection_failed_remove_and_retry))
                }
            }
        }
        return
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(titleRes)) },
        text = { Text(stringResource(messageRes)) },
        confirmButton = {
            RemoveAndRetryButton(onClick = { removeAndRetry() })
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(Res.string.localizable_common_cancel)) }
        },
    )
}

/** TalkBack label iOS `accessibilityLabel` uses so "remove" is heard as removing the pairing, not the radio. */
internal fun removeAndRetrySpokenResource() =
    Res.string.localizable_accessibility_alert_connection_failed_remove_and_retry

/**
 * Per-kind pairing copy. When the app can drop the OS bond (ASK / CDM `removeBond`), PIN rejection
 * uses the Android forget-and-re-enter string and auth uses the in-app re-pair string. When it
 * cannot — iOS Mac, Android below API 36 — the user must forget the radio in Bluetooth settings
 * first (`authenticationFailedMac` / `pinRejectedMac`).
 */
internal fun pairingFailureMessageResource(kind: PairingFailureKind, canDropOsBond: Boolean) = when (kind) {
    PairingFailureKind.PIN_REJECTED -> if (canDropOsBond) {
        Res.string.android_onboarding_device_scan_error_pin_rejected
    } else {
        Res.string.android_onboarding_device_scan_error_pin_rejected_settings
    }
    PairingFailureKind.AUTHENTICATION -> if (canDropOsBond) {
        Res.string.onboarding_device_scan_error_authentication_failed
    } else {
        Res.string.android_onboarding_device_scan_error_authentication_failed_settings
    }
    PairingFailureKind.TRANSIENT -> Res.string.onboarding_device_scan_error_connection_failed
}

@Composable
private fun RemoveAndRetryButton(onClick: () -> Unit) {
    val spoken = stringResource(removeAndRetrySpokenResource())
    TextButton(
        onClick = onClick,
        modifier = Modifier.semantics { contentDescription = spoken },
    ) {
        Text(
            text = stringResource(Res.string.localizable_alert_connection_failed_remove_and_retry),
            color = MaterialTheme.colorScheme.error,
        )
    }
}
