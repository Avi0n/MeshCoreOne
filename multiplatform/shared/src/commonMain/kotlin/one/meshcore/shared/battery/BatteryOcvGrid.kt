package one.meshcore.shared.battery

/**
 * Pure helpers for the battery-curve voltage grid (iOS `VoltageFieldsGrid` / percent labels).
 * Kept separate from [BatteryOcv] so the percentage object stays under the function budget.
 */
object BatteryOcvGrid {
    private const val SEGMENT_SPAN_PERCENT = 10

    /**
     * Per-field error for the voltage grid. A field errors when out of range or when it breaks
     * descending order with either neighbor.
     */
    fun fieldHasError(index: Int, values: List<Int>): Boolean {
        if (index !in values.indices) return true
        val value = values[index]
        val outOfRange = value !in OCVPreset.VALID_MILLIVOLT_RANGE
        val breaksPrev = index > 0 && values[index - 1] <= value
        val breaksNext = index < values.lastIndex && value <= values[index + 1]
        return outOfRange || breaksPrev || breaksNext
    }

    /** Percent label for curve index 0…10 → 100…0. */
    fun percentAtIndex(index: Int): Int = (OCVPreset.POINT_COUNT - 1 - index) * SEGMENT_SPAN_PERCENT
}
