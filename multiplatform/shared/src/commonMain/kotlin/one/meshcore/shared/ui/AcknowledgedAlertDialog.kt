package one.meshcore.shared.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

/** An alert with one button that dismisses it. The caller supplies the title, message, and button label. */
@Composable
internal fun AcknowledgedAlertDialog(title: String, message: String, confirmLabel: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(confirmLabel) }
        },
    )
}
