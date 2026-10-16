package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Leading progress for a filled button: content color, icon size, then the button icon gap. */
@Composable
internal fun FilledButtonProgress(size: Dp = ButtonDefaults.IconSize) {
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        color = MaterialTheme.colorScheme.onPrimary,
        strokeWidth = 2.dp,
    )
    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
}
