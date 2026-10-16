package one.meshcore.shared.settings

/**
 * Pure helpers for the node-config export/import UI (section auto-select, confirmation copy kind,
 * filename sanitization, radio formatting). Mirrors the Swift view-model logic without Compose or L10n.
 */

/** Snapshot of live device values shown as "Current" diffs on the import preview. */
data class NodeConfigDeviceDiff(
    val name: String?,
    val radio: MeshCoreNodeConfig.RadioSettings?,
    val position: MeshCoreNodeConfig.PositionSettings?,
    internal val isCurrent: () -> Boolean = { true },
)

/** Which confirmation dialog strings to use, matching the iOS overwrite/additive matrix. */
enum class ConfigImportConfirmKind {
    /** Channels and/or contacts only; no overwrite sections. */
    ADD,

    /** Overwrite-only sections (identity/radio/position/other/channel overwrite). */
    OVERWRITE,

    /** Both overwrite and additive sections selected. */
    MIXED,

    /** Fallback merge wording when neither branch is exclusive. */
    DEFAULT,
}

/**
 * Auto-selects only sections present in [config], matching iOS `applyParseSuccess`:
 * position is skipped when the file carries a zero coordinate pair.
 */
fun autoSelectSections(config: MeshCoreNodeConfig): ConfigSections = ConfigSections(
    nodeIdentity = config.name != null || config.publicKey != null || config.privateKey != null,
    radioSettings = config.radioSettings != null,
    positionSettings = config.positionSettings != null && !(config.positionSettings?.isZero ?: true),
    otherSettings = config.otherSettings != null,
    channels = config.channels != null,
    contacts = config.contacts != null,
)

/**
 * Classifies the confirmation dialog from the selected [sections] and whether the preview found a
 * channel that would replace an already-configured slot.
 */
fun configImportConfirmKind(sections: ConfigSections, channelsWouldOverwrite: Boolean): ConfigImportConfirmKind {
    val hasOverwrite = sections.radioSettings ||
        sections.nodeIdentity ||
        sections.positionSettings ||
        sections.otherSettings ||
        (sections.channels && channelsWouldOverwrite)
    val hasAdditive = sections.channels || sections.contacts
    return when {
        !hasOverwrite && hasAdditive -> ConfigImportConfirmKind.ADD
        hasOverwrite && !hasAdditive -> ConfigImportConfirmKind.OVERWRITE
        hasOverwrite && hasAdditive -> ConfigImportConfirmKind.MIXED
        else -> ConfigImportConfirmKind.DEFAULT
    }
}

/**
 * Formats radio settings for a one-line diff (MHz / kHz / SF / CR), matching iOS `RadioFormatter`.
 * Frequency is stored in kHz; bandwidth in Hz.
 */
fun formatRadioSettingsForDisplay(radio: MeshCoreNodeConfig.RadioSettings): String {
    val freqMhz = radio.frequency.toDouble() / KHZ_PER_MHZ
    val bwKhz = radio.bandwidth.toDouble() / HZ_PER_KHZ
    val freqText = formatTrimmed(freqMhz, maxFraction = FREQ_MAX_FRACTION)
    val bwText = formatTrimmed(bwKhz, maxFraction = BW_MAX_FRACTION)
    return "$freqText MHz, BW $bwText kHz, SF ${radio.spreadingFactor}, CR ${radio.codingRate}"
}

/**
 * Builds the export basename (no extension): `{sanitized}_meshcore_config_{timestamp}`.
 * [timestamp] is already formatted (e.g. `yyyy-MM-dd-HHmmss`).
 */
fun nodeConfigExportBasename(deviceNodeName: String?, configName: String?, timestamp: String): String {
    val raw = deviceNodeName ?: configName ?: FALLBACK_NODE_NAME
    val sanitized = sanitizeNodeNameForFilename(raw)
    return "${sanitized}_meshcore_config_$timestamp"
}

/** Strips non `[a-zA-Z0-9_-]` and trims leading/trailing underscores (iOS export filename rules). */
fun sanitizeNodeNameForFilename(name: String): String {
    val replaced = name.map { ch ->
        if (ch.isLetterOrDigit() || ch == '_' || ch == '-') ch else '_'
    }.joinToString("")
    return replaced.trim('_').ifEmpty { FALLBACK_NODE_NAME }
}

private fun formatTrimmed(value: Double, maxFraction: Int): String {
    val asLong = value.toLong()
    if (value == asLong.toDouble()) return asLong.toString()
    val factor = POW10[maxFraction.coerceIn(0, POW10.lastIndex)]
    val scaled = kotlin.math.round(value * factor) / factor
    val text = scaled.toString()
    return if (text.contains('.')) text.trimEnd('0').trimEnd('.') else text
}

private const val FALLBACK_NODE_NAME = "unknown"
private const val KHZ_PER_MHZ = 1000.0
private const val HZ_PER_KHZ = 1000.0
private const val FREQ_MAX_FRACTION = 3
private const val BW_MAX_FRACTION = 1
private val POW10 = doubleArrayOf(1.0, 10.0, 100.0, 1_000.0, 10_000.0)
