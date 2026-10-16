package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.update
import one.meshcore.shared.settings.RadioOptions

/** Radio parameters and repeater behavior fields. */
interface NodeSettingsTuningEdits {
    fun setFrequencyText(value: String)

    fun setBandwidth(value: Double)

    fun setSpreadingFactor(value: Int)

    fun setCodingRate(value: Int)

    fun setRepeaterEnabled(value: Boolean)

    fun setAdvertIntervalText(value: String)

    fun setFloodAdvertIntervalText(value: String)

    fun setFloodMaxHopsText(value: String)
}

internal class NodeSettingsTuningEditor(private val model: NodeSettingsModel) : NodeSettingsTuningEdits {
    private val helper get() = model.helper
    private val controller get() = model.controller

    override fun setFrequencyText(value: String) {
        setRadioField({ helper.frequency = value.toDoubleOrNull() }) { copy(frequencyText = value) }
    }

    // Bandwidth, spreading factor and coding rate arrive as a chosen option rather than typed text, so
    // no value outside the LoRa grid can reach a remote node.
    override fun setBandwidth(value: Double) {
        setRadioField({ helper.bandwidth = value }) { copy(bandwidthText = RadioOptions.formatBandwidthKHz(value)) }
    }

    override fun setSpreadingFactor(value: Int) {
        setRadioField({ helper.spreadingFactor = value }) { copy(spreadingFactorText = value.toString()) }
    }

    override fun setCodingRate(value: Int) {
        setRadioField({ helper.codingRate = value }) { copy(codingRateText = value.toString()) }
    }

    /** Stores the radio value, then marks the settings modified. */
    private fun setRadioField(assign: () -> Unit, write: NodeSettingsState.() -> NodeSettingsState) {
        assign()
        helper.radioSettingsModified = true
        model.stateUpdates.update { state -> state.write().copy(radioModified = true) }
    }

    override fun setRepeaterEnabled(value: Boolean) {
        controller.repeaterEnabled = value
        model.stateUpdates.update {
            it.copy(repeaterEnabled = value, behaviorModified = controller.behaviorSettingsModified)
        }
    }

    override fun setAdvertIntervalText(value: String) {
        setIntervalText(value, { controller.advertIntervalMinutes = it }) { copy(advertIntervalText = it) }
    }

    override fun setFloodAdvertIntervalText(value: String) {
        setIntervalText(value, { controller.floodAdvertIntervalHours = it }) { copy(floodAdvertIntervalText = it) }
    }

    override fun setFloodMaxHopsText(value: String) {
        setIntervalText(value, { controller.floodMaxHops = it }) { copy(floodMaxHopsText = it) }
    }

    /** Stores the integer before modified is read. An empty value stores null and still clears validation. */
    private fun setIntervalText(
        value: String,
        assign: (Int?) -> Unit,
        write: NodeSettingsState.(String) -> NodeSettingsState,
    ) {
        assign(value.toIntOrNull())
        val modified = controller.behaviorSettingsModified
        model.stateUpdates.update { state ->
            state.write(value).copy(behaviorModified = modified, behaviorValidation = null)
        }
    }
}
