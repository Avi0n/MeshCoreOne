package one.meshcore.shared.battery

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.BatteryInfo
import kotlin.math.roundToInt

/**
 * OCV-array lookup for battery percentage and contact-stored curve resolution. Ported from iOS
 * `BatteryInfo.percentage(using:)` and the contact OCV load path on `NodeStatusViewModel`.
 */
object BatteryOcv {
    private const val FULL_PERCENT = 100
    private const val EMPTY_PERCENT = 0
    private const val SEGMENT_SPAN_PERCENT = 10
    private const val LINEAR_MIN_VOLTS = 3.0
    private const val LINEAR_SPAN_VOLTS = 1.2
    private const val MV_PER_VOLT = 1000.0

    /**
     * Percentage from millivolts using an 11-point descending OCV curve with linear interpolation
     * inside each 10% segment. Falls back to a simple LiPo linear map when [ocvArray] is not 11 long.
     */
    fun percentage(millivolts: Int, ocvArray: List<Int>): Int {
        if (ocvArray.size != OCVPreset.POINT_COUNT) {
            return linearPercentage(millivolts)
        }
        if (millivolts >= ocvArray[0]) return FULL_PERCENT
        if (millivolts <= ocvArray[OCVPreset.POINT_COUNT - 1]) return EMPTY_PERCENT
        for (index in 0 until OCVPreset.POINT_COUNT - 1) {
            val upperV = ocvArray[index]
            val lowerV = ocvArray[index + 1]
            if (millivolts >= lowerV) {
                val segment = (millivolts - lowerV).toDouble() / (upperV - lowerV).toDouble()
                val basePercent = (OCVPreset.POINT_COUNT - 2 - index) * SEGMENT_SPAN_PERCENT
                return basePercent + (segment * SEGMENT_SPAN_PERCENT).roundToInt()
            }
        }
        return EMPTY_PERCENT
    }

    fun percentage(info: BatteryInfo, ocvArray: List<Int>): Int = percentage(info.level, ocvArray)

    /** Active OCV curve for a contact: custom 11-point string when present, else named preset, else Li-Ion. */
    fun activeArray(presetName: String?, customArrayString: String?): List<Int> {
        val custom = parseCustomArray(customArrayString)
        if (presetName == OCVPreset.CUSTOM.rawValue && custom != null) {
            return custom
        }
        val preset = presetName?.let { OCVPreset.fromRaw(it) } ?: OCVPreset.LI_ION
        return when {
            preset == OCVPreset.CUSTOM && custom != null -> custom
            preset == OCVPreset.CUSTOM -> OCVPreset.LI_ION.ocvArray
            else -> preset.ocvArray
        }
    }

    fun activeArray(contact: ContactDto): List<Int> = activeArray(contact.ocvPreset, contact.customOCVArrayString)

    /** Resolved preset + values for the battery-curve editor, mirroring iOS `loadOCVSettings`. */
    fun loadEditorState(presetName: String?, customArrayString: String?): OcvEditorState {
        val custom = parseCustomArray(customArrayString)
        if (presetName == OCVPreset.CUSTOM.rawValue && custom != null) {
            return OcvEditorState(preset = OCVPreset.CUSTOM, values = custom)
        }
        val preset = presetName?.let { OCVPreset.fromRaw(it) }
        return if (preset != null && preset != OCVPreset.CUSTOM) {
            OcvEditorState(preset = preset, values = preset.ocvArray)
        } else {
            OcvEditorState(preset = OCVPreset.LI_ION, values = OCVPreset.LI_ION.ocvArray)
        }
    }

    fun parseCustomArray(customArrayString: String?): List<Int>? {
        if (customArrayString.isNullOrBlank()) return null
        val parsed = customArrayString.split(',').mapNotNull { part ->
            part.trim().toIntOrNull()
        }
        return parsed.takeIf { it.size == OCVPreset.POINT_COUNT }
    }

    fun encodeCustomArray(values: List<Int>): String = values.joinToString(",")

    /**
     * Validates an 11-point descending millivolt curve. Returns a machine key for localization:
     * `out_of_range:<percent>` or `not_descending`, or null when valid.
     */
    fun validationErrorKey(values: List<Int>): String? {
        val rangeError = values.mapIndexedNotNull { index, value ->
            if (value !in OCVPreset.VALID_MILLIVOLT_RANGE) {
                val percent = (OCVPreset.POINT_COUNT - 1 - index) * SEGMENT_SPAN_PERCENT
                "out_of_range:$percent"
            } else {
                null
            }
        }.firstOrNull()
        val orderError = if (
            values.size != OCVPreset.POINT_COUNT ||
            values.zipWithNext().any { (current, next) -> current <= next }
        ) {
            "not_descending"
        } else {
            null
        }
        return rangeError ?: orderError
    }

    private fun linearPercentage(millivolts: Int): Int {
        val volts = millivolts / MV_PER_VOLT
        val percent = ((volts - LINEAR_MIN_VOLTS) / LINEAR_SPAN_VOLTS) * FULL_PERCENT
        return percent.roundToInt().coerceIn(EMPTY_PERCENT, FULL_PERCENT)
    }
}

/** Preset + millivolt series for the status battery-curve disclosure. */
data class OcvEditorState(val preset: OCVPreset, val values: List<Int>)
