package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.navigation.AppTab
import one.meshcore.shared.navigation.NavKey

/** Empty-state icons share one size. */
val EMPTY_STATE_ICON_SIZE = 48.dp

/**
 * Empty-state pane: a localized [title], optional [subtitle], and optional [icon]. Used when a split
 * pane has no selection, a radio-required tool is disconnected, or a settings store is not mounted.
 */
@Composable
fun PlaceholderPane(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (icon != null) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(EMPTY_STATE_ICON_SIZE),
                )
            }
            Text(text = title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The label describing a pushed [key], used by the placeholder detail panes. */
@Composable
internal fun navKeyLabel(key: NavKey): String = when (key) {
    is NavKey.ChatDetail -> key.route.conversationId
    is NavKey.ContactDetail -> key.contactId
    is NavKey.Discovery -> tabLabel(AppTab.NODES)
    is NavKey.BlockedContacts -> tabLabel(AppTab.NODES)
    is NavKey.MapFocus -> tabLabel(AppTab.MAP)
}
