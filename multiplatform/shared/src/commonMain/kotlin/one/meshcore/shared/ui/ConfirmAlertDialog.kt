package one.meshcore.shared.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color

/**
 * A confirm and dismiss alert. [confirmColor] tints only the confirm label. [confirmButtonColors]
 * colors the whole confirm button. [confirmEnabled] disables that button while a write is busy.
 */
@Composable
internal fun ConfirmAlertDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmColor: Color = Color.Unspecified,
    confirmButtonColors: ButtonColors = ButtonDefaults.textButtonColors(),
    confirmEnabled: Boolean = true,
    confirmModifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                enabled = confirmEnabled,
                colors = confirmButtonColors,
                modifier = confirmModifier,
            ) {
                Text(confirmLabel, color = confirmColor)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(dismissLabel) }
        },
    )
}
