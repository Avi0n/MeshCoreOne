package one.meshcore.shared.room.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.settings.RadioOptions

fun RoomSettingsViewModel.setFrequencyText(value: String) {
    setRadioField({ helper.frequency = value.toDoubleOrNull() }) { copy(frequencyText = value) }
}

// Bandwidth, spreading factor and coding rate arrive as a chosen option rather than typed text, so
// no value outside the LoRa grid can reach a remote room.
fun RoomSettingsViewModel.setBandwidth(value: Double) {
    setRadioField({ helper.bandwidth = value }) { copy(bandwidthText = RadioOptions.formatBandwidthKHz(value)) }
}

fun RoomSettingsViewModel.setSpreadingFactor(value: Int) {
    setRadioField({ helper.spreadingFactor = value }) { copy(spreadingFactorText = value.toString()) }
}

fun RoomSettingsViewModel.setCodingRate(value: Int) {
    setRadioField({ helper.codingRate = value }) { copy(codingRateText = value.toString()) }
}

/** Stores the radio value, then marks the settings modified. */
private fun RoomSettingsViewModel.setRadioField(
    assign: () -> Unit,
    write: RoomSettingsUiState.() -> RoomSettingsUiState,
) {
    assign()
    helper.radioSettingsModified = true
    settingsState.update { state -> state.write().copy(radioModified = true) }
}
