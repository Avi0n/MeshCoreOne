package one.meshcore.shared.settings.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.contact.shouldShowStaleCleanupLastRun
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_days
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_footer_disabled
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_footer_disconnected
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_footer_enabled
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_footer_select
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_header
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_last_run
import one.meshcore.shared.resources.settings_nodes_stale_cleanup_select
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubRadioRow
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import one.meshcore.shared.ui.namedRelativeLabel
import org.jetbrains.compose.resources.stringResource

/** Day options for auto-remove stale nodes (iOS StaleNodeCleanupSection). */
val STALE_CLEANUP_DAY_OPTIONS: List<Int> = listOf(7, 14, 30, 90)

/** 0 means "no threshold chosen yet" — the same sentinel the toggle-off path writes. */
private const val STALE_CLEANUP_UNSET = 0

/**
 * Settings section: toggle + day picker for automatic non-favorite node cleanup.
 * [thresholdDays] 0 means disabled. Changing the threshold while connected should force a cleanup
 * via [onThresholdChange].
 */
@Composable
fun StaleNodeCleanupSection(
    thresholdDays: Int,
    isDeviceConnected: Boolean,
    onThresholdChange: (Int) -> Unit,
    lastRunSeconds: Long = 0L,
    nowSeconds: () -> Long = { 0L },
    modifier: Modifier = Modifier,
) {
    var enabled by remember(thresholdDays) { mutableStateOf(thresholdDays > 0) }
    Column(modifier.fillMaxWidth()) {
        PreferenceHubSection(
            title = stringResource(Res.string.settings_nodes_stale_cleanup_header),
            applyHorizontalPadding = false,
            contentModifier = Modifier.selectableGroup(),
        ) {
            SettingsToggleRow(
                label = stringResource(Res.string.settings_nodes_stale_cleanup_header),
                checked = enabled,
                isRadioReady = isDeviceConnected,
                onCheckedChange = { on ->
                    enabled = on
                    // Turning on never auto-picks a day count — the picker starts on "Select" (0) exactly
                    // like the iOS default, forcing an explicit threshold choice before cleanup can run.
                    if (!on) onThresholdChange(STALE_CLEANUP_UNSET)
                },
            )
            if (enabled) {
                PreferenceHubRadioRow(
                    label = stringResource(Res.string.settings_nodes_stale_cleanup_select),
                    selected = thresholdDays == STALE_CLEANUP_UNSET,
                    onSelect = { onThresholdChange(STALE_CLEANUP_UNSET) },
                )
                STALE_CLEANUP_DAY_OPTIONS.forEach { days ->
                    PreferenceHubRadioRow(
                        label = formatTemplate(
                            stringResource(Res.string.settings_nodes_stale_cleanup_days),
                            listOf(days),
                        ),
                        selected = thresholdDays == days,
                        onSelect = { onThresholdChange(days) },
                    )
                }
                if (shouldShowStaleCleanupLastRun(thresholdDays, lastRunSeconds)) {
                    ListItem(
                        headlineContent = {
                            Text(
                                text = formatTemplate(
                                    stringResource(Res.string.settings_nodes_stale_cleanup_last_run),
                                    listOf(namedRelativeLabel(lastRunSeconds, nowSeconds())),
                                ),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        PreferenceHubFooter(
            footerText(enabled = enabled, thresholdDays = thresholdDays, isDeviceConnected = isDeviceConnected),
            applyHorizontalPadding = false,
        )
    }
}

@Composable
private fun footerText(enabled: Boolean, thresholdDays: Int, isDeviceConnected: Boolean): String = when {
    !enabled -> stringResource(Res.string.settings_nodes_stale_cleanup_footer_disabled)
    thresholdDays <= 0 -> stringResource(Res.string.settings_nodes_stale_cleanup_footer_select)
    isDeviceConnected -> formatTemplate(
        stringResource(Res.string.settings_nodes_stale_cleanup_footer_enabled),
        listOf(thresholdDays),
    )
    else -> stringResource(Res.string.settings_nodes_stale_cleanup_footer_disconnected)
}
