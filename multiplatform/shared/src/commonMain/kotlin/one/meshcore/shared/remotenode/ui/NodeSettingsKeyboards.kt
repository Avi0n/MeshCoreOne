package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

/**
 * Whole-number node/room settings fields. iOS puts Done on the number-pad toolbar; [ImeAction.Done]
 * is the Android equivalent so the pad is not stuck open.
 */
internal val NODE_SETTINGS_NUMBER_KEYBOARD = KeyboardOptions(
    keyboardType = KeyboardType.Number,
    imeAction = ImeAction.Done,
)

/** Decimal node/room settings fields (frequency, coordinates). */
internal val NODE_SETTINGS_DECIMAL_KEYBOARD = KeyboardOptions(
    keyboardType = KeyboardType.Decimal,
    imeAction = ImeAction.Done,
)

/** Password node/room settings fields. iOS puts Done on the keyboard toolbar. */
internal val NODE_SETTINGS_PASSWORD_KEYBOARD = KeyboardOptions(
    keyboardType = KeyboardType.Password,
    imeAction = ImeAction.Done,
)

/** iOS keyboard Done clears focus. */
internal fun nodeSettingsDoneKeyboardActions(focusManager: FocusManager): KeyboardActions =
    KeyboardActions(onDone = { focusManager.clearFocus() })
