package one.meshcore.shared.remotenode

import one.meshcore.shared.settings.RadioOptions

/** LoRa radio fields and the commands that load and apply them. */
interface NodeRadioFields {
    var frequency: Double?
    var bandwidth: Double?
    var spreadingFactor: Int?
    var codingRate: Int?
    val radioError: Boolean

    /** Set by the UI when a radio field is edited; a yellow restart warning shows while true. */
    var radioSettingsModified: Boolean
    val radioLoaded: Boolean

    suspend fun fetchRadioSettings()

    suspend fun applyRadioSettings(): SettingsApplyResult
}

internal class NodeRadioSettings(private val exchange: NodeSettingsExchange) : NodeRadioFields {
    override var frequency: Double? = null
    override var bandwidth: Double? = null
    override var spreadingFactor: Int? = null
    override var codingRate: Int? = null
    override var radioError: Boolean = false
        internal set
    override var radioSettingsModified: Boolean = false
    override val radioLoaded: Boolean get() = frequency != null
    private var originalFrequency: Double? = null
    private var originalBandwidth: Double? = null
    private var originalSpreadingFactor: Int? = null
    private var originalCodingRate: Int? = null

    override suspend fun fetchRadioSettings() {
        radioError = false
        val hadTimeout = runSettingsFetch {
            (CLIResponse.parse(exchange.sendAndWait("get radio"), "get radio") as? CLIResponse.Radio)?.let(::adoptRadio)
        }
        if (hadTimeout) radioError = true
    }

    /**
     * Stores a radio reading. Firmware reports bandwidth as a rounded float, so 7.8 kHz comes back
     * as 7.799. Snapping onto a nearby listed option lets the picker select it; a reading far from
     * every option stays as reported.
     */
    internal fun adoptRadio(radio: CLIResponse.Radio) {
        adoptSettingsReading(frequency, originalFrequency, radio.frequency) {
            frequency = it
            originalFrequency = it
        }
        adoptSettingsReading(bandwidth, originalBandwidth, RadioOptions.nearestBandwidthKHz(radio.bandwidth)) {
            bandwidth = it
            originalBandwidth = it
        }
        adoptSettingsReading(spreadingFactor, originalSpreadingFactor, radio.spreadingFactor) {
            spreadingFactor = it
            originalSpreadingFactor = it
        }
        adoptSettingsReading(codingRate, originalCodingRate, radio.codingRate) {
            codingRate = it
            originalCodingRate = it
        }
    }

    override suspend fun applyRadioSettings(): SettingsApplyResult {
        val freq = frequency
        val bw = bandwidth
        val sf = spreadingFactor
        val cr = codingRate
        return when {
            freq == null || bw == null || sf == null || cr == null -> SettingsApplyResult.NotLoaded
            else -> try {
                if (exchange.commandSucceeded("set radio $freq,$bw,$sf,$cr")) {
                    originalFrequency = freq
                    originalBandwidth = bw
                    originalSpreadingFactor = sf
                    originalCodingRate = cr
                    radioSettingsModified =
                        frequency != freq ||
                        bandwidth != bw ||
                        spreadingFactor != sf ||
                        codingRate != cr
                    SettingsApplyResult.Applied
                } else {
                    SettingsApplyResult.PartialFailure
                }
            } catch (e: RemoteNodeError) {
                SettingsApplyResult.TransportError(e)
            }
        }
    }
}
