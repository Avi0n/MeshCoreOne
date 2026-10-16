package one.meshcore.shared.map.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.chat.ui.ThreadErrorDialog
import one.meshcore.shared.contact.ui.message
import one.meshcore.shared.map.MapLoadError
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_add_error_not_connected
import one.meshcore.shared.resources.contacts_common_error
import one.meshcore.shared.resources.contacts_common_ok
import one.meshcore.shared.ui.AcknowledgedAlertDialog
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun MapScreenDialogs(state: MapScreenState) {
    val addErrorText = state.addError?.message()
    if (addErrorText != null) {
        AcknowledgedAlertDialog(
            title = stringResource(Res.string.contacts_common_error),
            message = addErrorText,
            confirmLabel = stringResource(Res.string.contacts_common_ok),
            onDismiss = { state.addError = null },
        )
    }
    state.loadError?.let { error ->
        ThreadErrorDialog(
            message = error.mapLoadMessage(),
            onDismiss = { state.loadError = null },
        )
    }
}

/** The user-facing message for a failed contact load, the analogue of iOS `error.userFacingMessage`. */
@Composable
private fun MapLoadError.mapLoadMessage(): String = when (this) {
    MapLoadError.NotConnected -> stringResource(Res.string.contacts_add_error_not_connected)
    is MapLoadError.ServiceMessage -> message
}
