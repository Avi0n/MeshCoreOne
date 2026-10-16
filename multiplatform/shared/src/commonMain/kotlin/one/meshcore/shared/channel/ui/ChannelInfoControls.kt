package one.meshcore.shared.channel.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import one.meshcore.shared.channel.CHANNEL_NOTIFICATION_LEVELS
import one.meshcore.shared.channel.FloodScopePickerItem
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.NotificationLevel
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.chats_channel_info_region
import one.meshcore.shared.resources.chats_channel_info_region_discover
import one.meshcore.shared.resources.chats_channel_info_region_discovering
import one.meshcore.shared.resources.chats_channel_info_region_explanation
import one.meshcore.shared.resources.chats_channel_info_region_manage_regions
import one.meshcore.shared.resources.chats_channel_info_region_private
import one.meshcore.shared.resources.chats_notification_level_label
import one.meshcore.shared.ui.ButtonIconLabel
import one.meshcore.shared.ui.PreferenceHubHorizontalPadding
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.PreferenceHubStatusRow
import one.meshcore.shared.ui.icons.AppIcons
import org.jetbrains.compose.resources.stringResource

@Composable
internal fun NotificationLevelPicker(selected: NotificationLevel, onSelect: (NotificationLevel) -> Unit) {
    PreferenceHubSection(title = stringResource(Res.string.chats_notification_level_label)) {
        val levelHint = stringResource(notificationLevelPickerHintRes())
        CHANNEL_NOTIFICATION_LEVELS.forEach { level ->
            NotificationLevelRow(
                level = level,
                selected = level == selected,
                levelHint = levelHint,
                onClick = { onSelect(level) },
            )
        }
    }
}

@Composable
internal fun FloodScopeSection(
    items: List<FloodScopePickerItem>,
    knownRegionsEmpty: Boolean,
    isDiscovering: Boolean,
    discoveryMessage: String?,
    canDiscover: Boolean,
    onDiscover: () -> Unit,
    onManage: () -> Unit,
    onSelect: (ChannelFloodScope) -> Unit,
) {
    PreferenceHubSection(
        title = stringResource(Res.string.chats_channel_info_region),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (knownRegionsEmpty) {
                PreferenceHubStatusRow(
                    stringResource(Res.string.chats_channel_info_region_explanation),
                )
            } else {
                for (item in items) {
                    val scope = item.scope
                    ListItem(
                        headlineContent = {
                            Text(
                                item.labelKind.label(),
                                color = if (scope != null) {
                                    Color.Unspecified
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        },
                        supportingContent = if (scope == null) {
                            {
                                Text(stringResource(Res.string.chats_channel_info_region_private))
                            }
                        } else {
                            null
                        },
                        leadingContent = {
                            RadioButton(
                                selected = item.isSelected,
                                onClick = null,
                                enabled = scope != null,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(
                                if (scope != null) {
                                    Modifier.selectable(
                                        selected = item.isSelected,
                                        role = Role.RadioButton,
                                        onClick = { onSelect(scope) },
                                    )
                                } else {
                                    Modifier
                                },
                            ),
                    )
                }
            }
        }
    }
    Column(
        Modifier.padding(horizontal = PreferenceHubHorizontalPadding),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (canDiscover) {
            FilledTonalButton(
                onClick = onDiscover,
                enabled = !isDiscovering,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (isDiscovering) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(ButtonDefaults.IconSize),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    Spacer(Modifier.size(ButtonDefaults.IconSpacing))
                }
                Text(
                    if (isDiscovering) {
                        stringResource(Res.string.chats_channel_info_region_discovering)
                    } else {
                        stringResource(Res.string.chats_channel_info_region_discover)
                    },
                )
            }
        }
        FilledTonalButton(onClick = onManage, modifier = Modifier.fillMaxWidth()) {
            ButtonIconLabel(
                title = stringResource(Res.string.chats_channel_info_region_manage_regions),
                icon = AppIcons.ListAlt,
            )
        }
        discoveryMessage?.let { msg ->
            Text(
                msg,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
