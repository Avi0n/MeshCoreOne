package one.meshcore.shared.settings

import one.meshcore.shared.backup.RegionSelection
import kotlin.math.abs

/** Catalog RF matching for [RadioPresets]. */
interface RadioPresetMatching {
    /**
     * Every catalog row whose RF tuple matches, in catalog order. More than one name can share a tuple.
     * Device row units: frequency kHz, bandwidth Hz.
     */
    fun matchingPresets(
        frequencyKHz: Long,
        bandwidthHz: Long,
        spreadingFactor: Int,
        codingRate: Int,
    ): List<RadioPreset>

    /**
     * Last-applied id if still RF-equal, then recommended-if-in-set, then a unique match; else null.
     */
    fun resolvedPreset(
        rf: RadioPresets.RadioPresetRf,
        preferredID: String? = null,
        region: RegionSelection? = null,
    ): RadioPreset?

    fun resolvedPreset(
        snapshot: RadioDeviceSnapshot,
        preferredID: String? = snapshot.appliedRadioPresetID,
        region: RegionSelection? = null,
    ): RadioPreset?

    /**
     * [preferredID] when it still names a matching catalog row, or when Repeat Mode is on (restore
     * still paints the community name). Null when the operator never applied a named preset, or live
     * RF no longer matches that id.
     */
    fun retainedAppliedPresetID(rf: RadioPresets.RadioPresetRf, preferredID: String?, clientRepeat: Boolean): String?

    /** Unique RF match, or null when the tuple is unlabeled or collides. */
    fun matchingPreset(frequencyKHz: Long, bandwidthHz: Long, spreadingFactor: Int, codingRate: Int): RadioPreset?

    fun matchingPreset(snapshot: RadioDeviceSnapshot): RadioPreset?

    /** Repeat preset for the device's current frequency (frequency-only match). */
    fun matchingRepeatPreset(frequencyKHz: Long): RadioPreset?

    /**
     * The preset name Settings shows beside Radio: repeat-mode matches frequency only, otherwise the
     * full catalog match, mirroring iOS `SettingsListContent.radioDetailText`.
     */
    fun matchingDisplayedPreset(snapshot: RadioDeviceSnapshot, region: RegionSelection? = null): RadioPreset?

    /** Nearest repeat preset by absolute kHz distance. */
    fun nearestRepeatPreset(toFrequencyKHz: Long): RadioPreset?

    /**
     * Wire units for [one.meshcore.shared.meshcore.MeshSession.setRadio]: frequency MHz, bandwidth kHz.
     * Callers holding Device-row kHz/Hz must convert first (see NodeConfigSessionAdapter).
     */
    fun setRadioArgs(preset: RadioPreset): SetRadioArgs
}

internal object RadioPresetMatcher : RadioPresetMatching {
    override fun matchingPresets(
        frequencyKHz: Long,
        bandwidthHz: Long,
        spreadingFactor: Int,
        codingRate: Int,
    ): List<RadioPreset> {
        val freqMHz = frequencyKHz / KHZ_PER_MHZ
        val bwKHz = bandwidthHz / HZ_PER_KHZ
        return RadioPresets.all.filter { preset ->
            abs(preset.frequencyMHz - freqMHz) < FREQ_MATCH_TOLERANCE_MHZ &&
                abs(preset.bandwidthKHz - bwKHz) < BW_MATCH_TOLERANCE_KHZ &&
                preset.spreadingFactor == spreadingFactor &&
                preset.codingRate == codingRate
        }
    }

    override fun resolvedPreset(
        rf: RadioPresets.RadioPresetRf,
        preferredID: String?,
        region: RegionSelection?,
    ): RadioPreset? {
        val matches = matchingPresets(rf.frequencyKHz, rf.bandwidthHz, rf.spreadingFactor, rf.codingRate)
        return preferredID?.let { id -> matches.firstOrNull { it.id == id } }
            ?: region?.let(RadioPresetRecommender::recommended)?.let { preset ->
                matches.firstOrNull { it.id == preset.id }
            }
            ?: matches.singleOrNull()
    }

    override fun resolvedPreset(
        snapshot: RadioDeviceSnapshot,
        preferredID: String?,
        region: RegionSelection?,
    ): RadioPreset? = resolvedPreset(
        rf = RadioPresets.RadioPresetRf(
            frequencyKHz = snapshot.frequencyKHz,
            bandwidthHz = snapshot.bandwidthHz,
            spreadingFactor = snapshot.spreadingFactor,
            codingRate = snapshot.codingRate,
        ),
        preferredID = preferredID,
        region = region,
    )

    override fun retainedAppliedPresetID(
        rf: RadioPresets.RadioPresetRf,
        preferredID: String?,
        clientRepeat: Boolean,
    ): String? {
        if (preferredID == null) return null
        if (clientRepeat) return preferredID
        return preferredID.takeIf { id ->
            matchingPresets(rf.frequencyKHz, rf.bandwidthHz, rf.spreadingFactor, rf.codingRate).any { it.id == id }
        }
    }

    override fun matchingPreset(
        frequencyKHz: Long,
        bandwidthHz: Long,
        spreadingFactor: Int,
        codingRate: Int,
    ): RadioPreset? = resolvedPreset(RadioPresets.RadioPresetRf(frequencyKHz, bandwidthHz, spreadingFactor, codingRate))

    override fun matchingPreset(snapshot: RadioDeviceSnapshot): RadioPreset? = matchingPreset(
        frequencyKHz = snapshot.frequencyKHz,
        bandwidthHz = snapshot.bandwidthHz,
        spreadingFactor = snapshot.spreadingFactor,
        codingRate = snapshot.codingRate,
    )

    override fun matchingRepeatPreset(frequencyKHz: Long): RadioPreset? =
        RadioPresets.repeatPresets.firstOrNull { it.frequencyKHz == frequencyKHz }

    override fun matchingDisplayedPreset(snapshot: RadioDeviceSnapshot, region: RegionSelection?): RadioPreset? =
        if (snapshot.clientRepeat) {
            matchingRepeatPreset(snapshot.frequencyKHz)
        } else {
            resolvedPreset(snapshot, preferredID = snapshot.appliedRadioPresetID, region = region)
        }

    override fun nearestRepeatPreset(toFrequencyKHz: Long): RadioPreset? =
        RadioPresets.repeatPresets.minByOrNull { abs(it.frequencyKHz - toFrequencyKHz) }

    override fun setRadioArgs(preset: RadioPreset): SetRadioArgs = SetRadioArgs(
        frequencyMHz = preset.frequencyMHz,
        bandwidthKHz = preset.bandwidthKHz,
        spreadingFactor = preset.spreadingFactor,
        codingRate = preset.codingRate,
    )

    private const val KHZ_PER_MHZ = 1000.0
    private const val HZ_PER_KHZ = 1000.0
    private const val FREQ_MATCH_TOLERANCE_MHZ = 0.1
    private const val BW_MATCH_TOLERANCE_KHZ = 1.0
}
