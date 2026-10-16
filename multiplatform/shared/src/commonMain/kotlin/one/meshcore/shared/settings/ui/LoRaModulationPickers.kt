package one.meshcore.shared.settings.ui

import androidx.compose.runtime.Composable
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_accessibility_bandwidth_label
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_accessibility_coding_rate_label
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_accessibility_spreading_factor_label
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_bandwidth_hint
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_bandwidth_k_hz
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_coding_rate
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_coding_rate_hint
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_spreading_factor
import one.meshcore.shared.resources.remotenodes_remote_nodes_settings_spreading_factor_hint
import one.meshcore.shared.resources.settings_advanced_radio_accessibility_bandwidth_hint
import one.meshcore.shared.resources.settings_advanced_radio_accessibility_bandwidth_label
import one.meshcore.shared.resources.settings_advanced_radio_accessibility_coding_rate_hint
import one.meshcore.shared.resources.settings_advanced_radio_accessibility_coding_rate_label
import one.meshcore.shared.resources.settings_advanced_radio_accessibility_spreading_factor_hint
import one.meshcore.shared.resources.settings_advanced_radio_accessibility_spreading_factor_label
import one.meshcore.shared.resources.settings_advanced_radio_bandwidth
import one.meshcore.shared.resources.settings_advanced_radio_coding_rate
import one.meshcore.shared.resources.settings_advanced_radio_spreading_factor
import one.meshcore.shared.settings.RadioOptions
import one.meshcore.shared.ui.formatTemplate
import org.jetbrains.compose.resources.stringResource

/**
 * Bandwidth, spreading factor, and coding rate as menus. An off-grid value encodes onto the wire
 * and leaves the radio unable to hear the mesh, and a bare number tells a screen-reader user nothing.
 */
@Composable
internal fun LoRaModulationPickers(
    copy: LoRaModulationCopy,
    bandwidthSelectedLabel: String,
    spreadingFactorText: String,
    codingRateText: String,
    enabled: Boolean,
    onBandwidth: (Double) -> Unit,
    onSpreadingFactor: (Int) -> Unit,
    onCodingRate: (Int) -> Unit,
) {
    val strings = loRaPickerStrings(copy)
    RadioOptionPicker(
        label = strings.bandwidthLabel,
        selectedLabel = bandwidthSelectedLabel,
        selectedAccessibilityLabel = formatTemplate(strings.bandwidthSpoken, listOf(bandwidthSelectedLabel)),
        accessibilityHint = strings.bandwidthHint,
        options = RadioOptions.bandwidthsKHz,
        optionLabel = { RadioOptions.formatBandwidthKHz(it) },
        optionAccessibilityLabel = {
            formatTemplate(strings.bandwidthSpoken, listOf(RadioOptions.formatBandwidthKHz(it)))
        },
        enabled = enabled,
        onSelect = onBandwidth,
    )
    RadioOptionPicker(
        label = strings.spreadingFactorLabel,
        selectedLabel = spreadingFactorText,
        selectedAccessibilityLabel = formatTemplate(strings.spreadingFactorSpoken, listOf(spreadingFactorText)),
        accessibilityHint = strings.spreadingFactorHint,
        options = RadioOptions.spreadingFactors.toList(),
        optionLabel = { it.toString() },
        optionAccessibilityLabel = { formatTemplate(strings.spreadingFactorSpoken, listOf(it)) },
        enabled = enabled,
        onSelect = onSpreadingFactor,
    )
    RadioOptionPicker(
        label = strings.codingRateLabel,
        selectedLabel = codingRateText,
        selectedAccessibilityLabel = formatTemplate(strings.codingRateSpoken, listOf(codingRateText)),
        accessibilityHint = strings.codingRateHint,
        options = RadioOptions.codingRates.toList(),
        optionLabel = { it.toString() },
        optionAccessibilityLabel = { formatTemplate(strings.codingRateSpoken, listOf(it)) },
        enabled = enabled,
        onSelect = onCodingRate,
    )
}

private data class LoRaPickerStrings(
    val bandwidthLabel: String,
    val bandwidthSpoken: String,
    val bandwidthHint: String,
    val spreadingFactorLabel: String,
    val spreadingFactorSpoken: String,
    val spreadingFactorHint: String,
    val codingRateLabel: String,
    val codingRateSpoken: String,
    val codingRateHint: String,
)

@Composable
private fun loRaPickerStrings(copy: LoRaModulationCopy): LoRaPickerStrings = when (copy) {
    LoRaModulationCopy.REMOTE_NODE -> LoRaPickerStrings(
        bandwidthLabel = stringResource(Res.string.remotenodes_remote_nodes_settings_bandwidth_k_hz),
        bandwidthSpoken = stringResource(Res.string.remotenodes_remote_nodes_settings_accessibility_bandwidth_label),
        bandwidthHint = stringResource(Res.string.remotenodes_remote_nodes_settings_bandwidth_hint),
        spreadingFactorLabel = stringResource(Res.string.remotenodes_remote_nodes_settings_spreading_factor),
        spreadingFactorSpoken = stringResource(
            Res.string.remotenodes_remote_nodes_settings_accessibility_spreading_factor_label,
        ),
        spreadingFactorHint = stringResource(Res.string.remotenodes_remote_nodes_settings_spreading_factor_hint),
        codingRateLabel = stringResource(Res.string.remotenodes_remote_nodes_settings_coding_rate),
        codingRateSpoken = stringResource(Res.string.remotenodes_remote_nodes_settings_accessibility_coding_rate_label),
        codingRateHint = stringResource(Res.string.remotenodes_remote_nodes_settings_coding_rate_hint),
    )
    LoRaModulationCopy.LOCAL_RADIO -> LoRaPickerStrings(
        bandwidthLabel = stringResource(Res.string.settings_advanced_radio_bandwidth),
        bandwidthSpoken = stringResource(Res.string.settings_advanced_radio_accessibility_bandwidth_label),
        bandwidthHint = stringResource(Res.string.settings_advanced_radio_accessibility_bandwidth_hint),
        spreadingFactorLabel = stringResource(Res.string.settings_advanced_radio_spreading_factor),
        spreadingFactorSpoken = stringResource(
            Res.string.settings_advanced_radio_accessibility_spreading_factor_label,
        ),
        spreadingFactorHint = stringResource(Res.string.settings_advanced_radio_accessibility_spreading_factor_hint),
        codingRateLabel = stringResource(Res.string.settings_advanced_radio_coding_rate),
        codingRateSpoken = stringResource(Res.string.settings_advanced_radio_accessibility_coding_rate_label),
        codingRateHint = stringResource(Res.string.settings_advanced_radio_accessibility_coding_rate_hint),
    )
}
