package one.meshcore.shared.contact.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection

@Composable
internal fun DetailSection(
    title: String?,
    danger: Boolean = false,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    if (danger) {
        Column(
            modifier = Modifier.padding(horizontal = SECTION_HORIZONTAL, vertical = SECTION_GAP),
        ) {
            if (title != null) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 4.dp),
                )
            }
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f),
                ),
                shape = MaterialTheme.shapes.extraLarge,
            ) {
                content()
            }
            if (footer != null) {
                PreferenceHubFooter(
                    footer,
                    applyHorizontalPadding = false,
                )
            }
        }
    } else {
        Column(modifier = Modifier.padding(vertical = SECTION_GAP)) {
            PreferenceHubSection(
                title = title,
                applyHorizontalPadding = false,
                modifier = Modifier.padding(horizontal = SECTION_HORIZONTAL),
            ) {
                content()
            }
            if (footer != null) {
                PreferenceHubFooter(
                    footer,
                    applyHorizontalPadding = false,
                    modifier = Modifier.padding(horizontal = SECTION_HORIZONTAL),
                )
            }
        }
    }
}

@Composable
internal fun detailListColors() = ListItemDefaults.colors(
    containerColor = Color.Transparent,
)

@Composable
internal fun DetailInfoItem(
    headline: String,
    supporting: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    monospace: Boolean = false,
    trailing: (@Composable () -> Unit)? = null,
    supportingColor: Color? = null,
    supportingContent: (@Composable () -> Unit)? = null,
) {
    val valueColor = supportingColor ?: MaterialTheme.colorScheme.onSurfaceVariant
    val value: @Composable () -> Unit = {
        Text(
            text = supporting,
            style = if (monospace) {
                MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace)
            } else {
                MaterialTheme.typography.bodyMedium
            },
            color = valueColor,
            maxLines = if (monospace) 3 else 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
    // Values sit in supportingContent (Android Settings subtitle). The trailing slot stays a control.
    ListItem(
        headlineContent = {
            Text(text = headline)
        },
        supportingContent = when {
            supportingContent != null -> supportingContent
            else -> value
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        trailingContent = trailing,
        colors = detailListColors(),
        modifier = modifier,
    )
}

@Composable
internal fun DetailActionItem(
    headline: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    destructive: Boolean = false,
    tonalSurface: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
    radioHint: String? = null,
) {
    val contentColor = when {
        !enabled -> MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurface
    }
    val iconTint = when {
        !enabled -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_ALPHA)
        destructive -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val container = if (tonalSurface) {
        MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        androidx.compose.ui.graphics.Color.Transparent
    }
    ListItem(
        headlineContent = {
            Text(text = headline, color = contentColor)
        },
        leadingContent = {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = iconTint,
            )
        },
        trailingContent = trailing,
        colors = ListItemDefaults.colors(containerColor = container),
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (radioHint != null) {
                    Modifier.semantics { stateDescription = radioHint }
                } else {
                    Modifier
                },
            )
            .clickable(enabled = enabled, onClick = onClick),
    )
}
