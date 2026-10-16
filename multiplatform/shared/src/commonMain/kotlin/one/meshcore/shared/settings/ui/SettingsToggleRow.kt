package one.meshcore.shared.settings.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_accessibility_off
import one.meshcore.shared.resources.localizable_accessibility_on
import one.meshcore.shared.resources.localizable_accessibility_requires_radio_connection
import org.jetbrains.compose.resources.stringResource

private const val DISABLED_CONTENT_ALPHA = 0.38f

/**
 * Labels a settings switch with its own row title plus the consequence of flipping it, so the row
 * speaks for itself when a screen reader lands on it rather than relying on the neighbouring text.
 * [onOff] is the app-locale On/Off value, matching iOS `accessibilityValue` rather than the system
 * Switch language.
 */
internal fun toggleAccessibilityModifier(label: String, hint: String?, onOff: String): Modifier = Modifier.semantics {
    if (hint != null) {
        contentDescription = "$label. $hint"
    }
    stateDescription = onOff
}

/** App-locale On/Off for a settings switch, matching iOS `L10n.Localizable.Accessibility`. */
internal fun toggleOnOffLabel(checked: Boolean, onLabel: String, offLabel: String): String =
    if (checked) onLabel else offLabel

/**
 * Material 3 settings toggle: [ListItem] headline (+ optional supporting) with a trailing [Switch].
 * Prefer this over ad-hoc [Row] + [Text] + [Switch] so density and a11y match across settings surfaces.
 *
 * The whole row is the switch (Android Settings). [accessibilityHint] is the analogue of the iOS
 * `accessibilityHint` on a `Toggle` and rides the row, which is the element a screen reader focuses
 * to act. On/Off comes from L10n so TalkBack follows the app locale.
 */
@Composable
fun SettingsToggleRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
    isRadioReady: Boolean = true,
    accessibilityHint: String? = null,
    leadingIcon: ImageVector? = null,
    /** Overrides On/Off when the value is unknown (iOS Loading… / Failed to load). */
    valueDescription: String? = null,
) {
    val canAct = enabled && isRadioReady
    val hint = if (!isRadioReady) {
        stringResource(Res.string.localizable_accessibility_requires_radio_connection)
    } else {
        accessibilityHint
    }
    val onOff = valueDescription ?: toggleOnOffLabel(
        checked = checked,
        onLabel = stringResource(Res.string.localizable_accessibility_on),
        offLabel = stringResource(Res.string.localizable_accessibility_off),
    )
    val headlineColor = if (canAct) {
        MaterialTheme.colorScheme.onSurface
    } else {
        MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_CONTENT_ALPHA)
    }
    val supportingColor = if (canAct) {
        MaterialTheme.colorScheme.onSurfaceVariant
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
    }
    ListItem(
        modifier = modifier
            .toggleable(
                value = checked,
                enabled = canAct,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .then(toggleAccessibilityModifier(label = label, hint = hint, onOff = onOff)),
        headlineContent = {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = headlineColor,
            )
        },
        supportingContent = supporting?.let { text ->
            {
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodySmall,
                    color = supportingColor,
                )
            }
        },
        leadingContent = leadingIcon?.let { icon ->
            {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (canAct) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = DISABLED_CONTENT_ALPHA)
                    },
                )
            }
        },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                enabled = canAct,
                modifier = Modifier.clearAndSetSemantics {},
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}
