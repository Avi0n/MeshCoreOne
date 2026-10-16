package one.meshcore.shared.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_settings_device_selection_setup_message
import one.meshcore.shared.resources.android_settings_device_selection_setup_message_plural
import one.meshcore.shared.resources.settings_device_selection_forget_these_devices
import one.meshcore.shared.resources.settings_device_selection_forget_this_device
import one.meshcore.shared.resources.settings_device_selection_setup_conversations
import one.meshcore.shared.resources.settings_device_selection_setup_title
import one.meshcore.shared.resources.settings_device_selection_setup_title_generic
import org.jetbrains.compose.resources.stringResource

/**
 * Android analog of iOS `SystemPairingSetupSheet`: leftover Companion Device Manager
 * associations must be forgotten before the system chooser can add the radio. Nested Up
 * dismisses, matching troubleshooting — a setup explanation is not an AlertDialog.
 */
@Composable
internal fun SystemPairingSetupDialog(
    accessories: List<CompanionAssociation>,
    onForget: () -> Unit,
    onCancel: () -> Unit,
) {
    if (accessories.isEmpty()) return
    val plural = accessories.size > 1
    val title = if (!plural) {
        formatTemplate(
            stringResource(Res.string.settings_device_selection_setup_title),
            listOf(accessories.first().name),
        )
    } else {
        stringResource(Res.string.settings_device_selection_setup_title_generic)
    }
    FullScreenOverlay(onDismiss = onCancel, title = title) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(
                    top = SystemPairingSetupMetrics.topPadding,
                    bottom = SystemPairingSetupMetrics.sectionGap,
                ),
        ) {
            Column(preferenceHubFormScroll()) {
                PreferenceHubSection {
                    PreferenceHubStatusRow(
                        stringResource(
                            if (plural) {
                                Res.string.android_settings_device_selection_setup_message_plural
                            } else {
                                Res.string.android_settings_device_selection_setup_message
                            },
                        ),
                    )
                    if (plural) {
                        accessories.forEach { accessory ->
                            ListItem(
                                headlineContent = { Text(accessory.name) },
                                colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                PreferenceHubFooter(
                    stringResource(Res.string.settings_device_selection_setup_conversations),
                )
            }
            Button(
                onClick = onForget,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(
                        horizontal = PreferenceHubHorizontalPadding,
                        vertical = SystemPairingSetupMetrics.topPadding,
                    ),
            ) {
                Text(
                    text = stringResource(
                        if (plural) {
                            Res.string.settings_device_selection_forget_these_devices
                        } else {
                            Res.string.settings_device_selection_forget_this_device
                        },
                    ),
                )
            }
        }
    }
}
