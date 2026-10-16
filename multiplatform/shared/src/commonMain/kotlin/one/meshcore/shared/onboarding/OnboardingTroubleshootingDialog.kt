package one.meshcore.shared.onboarding

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import one.meshcore.shared.connection.CompanionAssociation
import one.meshcore.shared.connection.troubleshootingFactoryResetEnabled
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.android_onboarding_troubleshooting_factory_reset_explanation
import one.meshcore.shared.resources.android_onboarding_troubleshooting_system_settings_header
import one.meshcore.shared.resources.android_onboarding_troubleshooting_system_settings_manage_accessories
import one.meshcore.shared.resources.android_onboarding_troubleshooting_system_settings_path
import one.meshcore.shared.resources.onboarding_troubleshooting_basic_checks_header
import one.meshcore.shared.resources.onboarding_troubleshooting_basic_checks_move_closer
import one.meshcore.shared.resources.onboarding_troubleshooting_basic_checks_not_connected_elsewhere
import one.meshcore.shared.resources.onboarding_troubleshooting_basic_checks_power_on
import one.meshcore.shared.resources.onboarding_troubleshooting_basic_checks_restart
import one.meshcore.shared.resources.onboarding_troubleshooting_factory_reset_clear_pairing
import one.meshcore.shared.resources.onboarding_troubleshooting_factory_reset_confirmation_note
import one.meshcore.shared.resources.onboarding_troubleshooting_factory_reset_header
import one.meshcore.shared.resources.onboarding_troubleshooting_factory_reset_no_pairings
import one.meshcore.shared.resources.onboarding_troubleshooting_factory_reset_pairings_found
import one.meshcore.shared.resources.onboarding_troubleshooting_still_not_appearing_body
import one.meshcore.shared.resources.onboarding_troubleshooting_still_not_appearing_header
import one.meshcore.shared.resources.onboarding_troubleshooting_system_settings_open_settings
import one.meshcore.shared.resources.onboarding_troubleshooting_title
import one.meshcore.shared.ui.FullScreenOverlay
import one.meshcore.shared.ui.PreferenceHubBulletRow
import one.meshcore.shared.ui.PreferenceHubBulletRowGap
import one.meshcore.shared.ui.PreferenceHubFooter
import one.meshcore.shared.ui.PreferenceHubSection
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource

/**
 * Ported from the iOS `TroubleshootingSheet`. Factory-reset clears every Companion Device
 * Manager association; Open Settings remains the Bluetooth settings deep link. Groups use
 * Settings-card chrome; the Bluetooth path is Android Connected devices, not ASK Accessories.
 * Nested Up dismisses, matching Channel Info — a four-group help screen is not an AlertDialog.
 */
@Composable
internal fun TroubleshootingDialog(
    onDismiss: () -> Unit,
    onOpenBluetoothSettings: () -> Unit,
    associations: List<CompanionAssociation>,
    onClearStalePairings: () -> Unit,
) {
    val pairingCount = associations.size
    FullScreenOverlay(
        onDismiss = onDismiss,
        title = stringResource(Res.string.onboarding_troubleshooting_title),
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(top = TroubleshootingMetrics.topPadding, bottom = TroubleshootingMetrics.sectionGap),
        ) {
            PreferenceHubSection(
                title = stringResource(Res.string.onboarding_troubleshooting_basic_checks_header),
            ) {
                Column(
                    Modifier.padding(TroubleshootingMetrics.groupInset),
                    verticalArrangement = Arrangement.spacedBy(PreferenceHubBulletRowGap),
                ) {
                    PreferenceHubBulletRow(
                        stringResource(Res.string.onboarding_troubleshooting_basic_checks_power_on),
                    )
                    PreferenceHubBulletRow(
                        stringResource(Res.string.onboarding_troubleshooting_basic_checks_move_closer),
                    )
                    PreferenceHubBulletRow(
                        stringResource(Res.string.onboarding_troubleshooting_basic_checks_not_connected_elsewhere),
                    )
                    PreferenceHubBulletRow(
                        stringResource(Res.string.onboarding_troubleshooting_basic_checks_restart),
                    )
                }
            }

            PreferenceHubSection(
                title = stringResource(Res.string.onboarding_troubleshooting_factory_reset_header),
                modifier = Modifier.padding(top = TroubleshootingMetrics.sectionGap),
            ) {
                Column(
                    Modifier.padding(TroubleshootingMetrics.groupInset),
                    verticalArrangement = Arrangement.spacedBy(MEDIUM_SPACING),
                ) {
                    Text(
                        text = stringResource(Res.string.android_onboarding_troubleshooting_factory_reset_explanation),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(
                        onClick = onClearStalePairings,
                        enabled = troubleshootingFactoryResetEnabled(pairingCount),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.onboarding_troubleshooting_factory_reset_clear_pairing))
                    }
                }
            }
            PreferenceHubFooter(
                text = stringResource(Res.string.onboarding_troubleshooting_factory_reset_confirmation_note),
            )
            PreferenceHubFooter(
                text = if (pairingCount == 0) {
                    stringResource(Res.string.onboarding_troubleshooting_factory_reset_no_pairings)
                } else {
                    formatTemplate(
                        stringResource(Res.string.onboarding_troubleshooting_factory_reset_pairings_found),
                        listOf(pairingCount),
                    )
                },
            )

            PreferenceHubSection(
                title = stringResource(Res.string.android_onboarding_troubleshooting_system_settings_header),
                modifier = Modifier.padding(top = TroubleshootingMetrics.sectionGap),
            ) {
                Column(
                    Modifier.padding(TroubleshootingMetrics.groupInset),
                    verticalArrangement = Arrangement.spacedBy(MEDIUM_SPACING),
                ) {
                    Text(
                        text = stringResource(
                            Res.string.android_onboarding_troubleshooting_system_settings_manage_accessories,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = stringResource(Res.string.android_onboarding_troubleshooting_system_settings_path),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(
                        onClick = onOpenBluetoothSettings,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(stringResource(Res.string.onboarding_troubleshooting_system_settings_open_settings))
                    }
                }
            }

            PreferenceHubSection(
                title = stringResource(Res.string.onboarding_troubleshooting_still_not_appearing_header),
                modifier = Modifier.padding(top = TroubleshootingMetrics.sectionGap),
            ) {
                Text(
                    text = stringResource(Res.string.onboarding_troubleshooting_still_not_appearing_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(TroubleshootingMetrics.groupInset),
                )
            }
        }
    }
}
