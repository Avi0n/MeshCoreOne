package one.meshcore.shared.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType

/**
 * Host-field keyboard matching iOS `WiFiAddressFields`: URL type, never capitalize, no
 * autocorrect, Next to move to the port field.
 */
val WIFI_HOST_KEYBOARD = KeyboardOptions(
    capitalization = KeyboardCapitalization.None,
    autoCorrectEnabled = false,
    keyboardType = KeyboardType.Uri,
    imeAction = ImeAction.Next,
)

/** Port-field keyboard matching iOS compact `numberPad` plus Done. */
val WIFI_PORT_KEYBOARD = KeyboardOptions(
    keyboardType = KeyboardType.Number,
    imeAction = ImeAction.Done,
)

/** iOS host `.onSubmit` moves focus to the port field. */
fun wifiHostKeyboardActions(portFocus: FocusRequester): KeyboardActions =
    KeyboardActions(onNext = { portFocus.requestFocus() })

/** iOS port `.onSubmit` runs the sheet's connect/save action. */
fun wifiPortKeyboardActions(onSubmit: () -> Unit): KeyboardActions = KeyboardActions(onDone = { onSubmit() })
