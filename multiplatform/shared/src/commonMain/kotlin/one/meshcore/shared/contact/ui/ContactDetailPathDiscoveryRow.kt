package one.meshcore.shared.contact.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.contacts_detail_discovering_path
import one.meshcore.shared.resources.contacts_detail_seconds_remaining
import one.meshcore.shared.ui.formattedStringResource
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.icons.CloseIconButton
import org.jetbrains.compose.resources.stringResource

/**
 * The in-flight path-discovery row: the "Discovering path" label with a seconds-remaining countdown, a
 * spinner, and a Cancel action, mirroring the iOS `ContactNetworkPathSection` discovering branch.
 */
@Composable
internal fun DiscoveringPathRow(secondsRemaining: Int?, onCancel: (() -> Unit)?) {
    ListItem(
        headlineContent = { Text(stringResource(Res.string.contacts_detail_discovering_path)) },
        supportingContent = secondsRemaining?.takeIf { it > 0 }?.let { remaining ->
            {
                Text(
                    text = formattedStringResource(Res.string.contacts_detail_seconds_remaining, remaining),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        leadingContent = {
            Icon(
                imageVector = AppIcons.Sensors,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp))
                onCancel?.let {
                    CloseIconButton(
                        onClick = it,
                        contentDescription = stringResource(discoveringPathAbortSpokenResource()),
                    )
                }
            }
        },
        colors = detailListColors(),
    )
}
