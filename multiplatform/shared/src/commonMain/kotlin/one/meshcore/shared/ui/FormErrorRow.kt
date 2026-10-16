package one.meshcore.shared.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.ui.icons.AppIcons

/** A form-level error row: leading warning plus an error headline. */
@Composable
internal fun FormErrorRow(message: String, modifier: Modifier = Modifier) {
    ListItem(
        headlineContent = {
            Text(
                text = message,
                color = MaterialTheme.colorScheme.error,
            )
        },
        leadingContent = {
            Icon(
                imageVector = AppIcons.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.fillMaxWidth(),
    )
}
