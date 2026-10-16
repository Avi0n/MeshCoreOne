package one.meshcore.shared.remotenode.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import one.meshcore.shared.remotenode.NodeSettingsValidation
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_value_not_set
import one.meshcore.shared.resources.localizable_common_try_again
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_failed_to_load
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_loading
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_pick_on_map
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.FilledButtonProgress
import one.meshcore.shared.ui.PreferenceHubCardPadding
import one.meshcore.shared.ui.icons.AppIcons
import one.meshcore.shared.ui.radioConnectionHint
import org.jetbrains.compose.resources.stringResource

internal val SETTINGS_FIELD_SPACING = 8.dp
internal val SETTINGS_PROGRESS_SIZE = 20.dp
internal val SETTINGS_DISCLOSURE_BODY_PADDING get() = PreferenceHubCardPadding

/** iOS `ExpandableSettingsSection` failed-to-load `exclamationmark.triangle`. */
internal val SETTINGS_FAILED_TO_LOAD_ICON get() = AppIcons.Warning

/** iOS identity Pick on Map `map`. */
internal val PICK_ON_MAP_ICON get() = AppIcons.Map

/** A collapsible node or room settings group. */
@Composable
internal fun SettingsDisclosure(
    isRadioReady: Boolean,
    title: String,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    isLoading: Boolean,
    onReload: (() -> Unit)? = null,
    footer: String? = null,
    content: @Composable () -> Unit,
) {
    StatusSection(
        title = title,
        expanded = expanded,
        onExpandedChange = onExpandedChange,
        isLoading = isLoading,
        onReload = onReload,
        isRadioReady = isRadioReady,
        footer = footer,
        content = content,
    )
}

@Composable
internal fun SettingsDisclosureFields(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                top = SETTINGS_DISCLOSURE_BODY_PADDING,
                start = SETTINGS_DISCLOSURE_BODY_PADDING,
                end = SETTINGS_DISCLOSURE_BODY_PADDING,
                bottom = SETTINGS_DISCLOSURE_BODY_PADDING,
            ),
        verticalArrangement = Arrangement.spacedBy(SETTINGS_FIELD_SPACING),
        content = content,
    )
}

@Composable
internal fun ReadOnlyRow(label: String, value: String) {
    ListItem(
        headlineContent = { Text(label) },
        supportingContent = {
            Text(
                value.ifEmpty { stringResource(Res.string.android_value_not_set) },
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun LoadingRow() {
    ListItem(
        headlineContent = {
            Text(stringResource(Res.string.remotenodes_remote_nodes_settings_loading))
        },
        trailingContent = {
            CircularProgressIndicator(modifier = Modifier.size(SETTINGS_PROGRESS_SIZE), strokeWidth = 2.dp)
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
internal fun FailedToLoadRow(isRadioReady: Boolean, onRetry: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        ListItem(
            headlineContent = {
                Text(
                    text = stringResource(Res.string.remotenodes_remote_nodes_settings_failed_to_load),
                    color = MaterialTheme.colorScheme.error,
                )
            },
            leadingContent = {
                Icon(
                    imageVector = SETTINGS_FAILED_TO_LOAD_ICON,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        )
        FilledTonalButton(
            onClick = onRetry,
            enabled = isRadioReady,
            modifier = Modifier
                .radioConnectionHint(isRadioReady)
                .fillMaxWidth()
                .padding(
                    start = SETTINGS_DISCLOSURE_BODY_PADDING,
                    end = SETTINGS_DISCLOSURE_BODY_PADDING,
                    bottom = SETTINGS_DISCLOSURE_BODY_PADDING,
                ),
        ) {
            Text(stringResource(Res.string.localizable_common_try_again))
        }
    }
}

/**
 * Loading row until the first successful fetch, retry row when that fetch failed, and the fields
 * afterward. A later refresh keeps the fields on screen.
 */
@Composable
internal fun SettingsDisclosureBody(
    isRadioReady: Boolean,
    isLoading: Boolean,
    loaded: Boolean,
    sectionError: Boolean,
    onReload: () -> Unit,
    content: @Composable () -> Unit,
) {
    when {
        isLoading && !loaded -> LoadingRow()
        sectionError && !loaded -> FailedToLoadRow(isRadioReady = isRadioReady, onRetry = onReload)
        else -> content()
    }
}

@Composable
internal fun OwnerInfoCharCount(length: Int, tooLong: Boolean) {
    Text(
        text = "$length/${NodeSettingsValidation.OWNER_INFO_MAX_LENGTH}",
        style = MaterialTheme.typography.labelSmall,
        color = if (tooLong) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.fillMaxWidth(),
        textAlign = TextAlign.End,
    )
}

@Composable
internal fun ApplyButton(
    isRadioReady: Boolean,
    label: String,
    enabled: Boolean,
    isApplying: Boolean,
    onClick: () -> Unit,
    showSuccess: Boolean = false,
) {
    Button(
        onClick = onClick,
        enabled = isRadioReady && enabled && !showSuccess,
        modifier = Modifier.fillMaxWidth().radioConnectionHint(isRadioReady),
    ) {
        if (isApplying) {
            FilledButtonProgress()
        }
        Text(label)
    }
}

@Composable
internal fun PickOnMapButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    OutlinedButton(onClick = onClick, modifier = modifier.fillMaxWidth()) {
        ButtonIconLabel(
            title = stringResource(Res.string.remotenodes_remote_nodes_settings_pick_on_map),
            icon = PICK_ON_MAP_ICON,
        )
    }
}
