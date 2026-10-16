package one.meshcore.shared.tools

import one.meshcore.shared.meshcore.SelfInfo
import one.meshcore.shared.model.toUppercaseHex
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * App-side output strings for local radio commands, mirroring the Swift `LocalCommandOutput`.
 * Deliberately unlocalized: the terminal shows raw device replies on remote sessions, and this output is
 * copy-pasted into bug reports and compared against meshcore-cli. Most strings echo firmware/meshcore-cli
 * wording; the advert confirmations below are our own display text (firmware's own advert reply differs).
 */
object LocalCommandOutput {
    const val OK = "OK"
    const val OK_REBOOTING = "OK - rebooting"

    // App-authored advert confirmations, not verbatim firmware text (firmware emits "OK - zerohop advert
    // sent" / "OK - Advert sent"); the local-command layer picks its own wording.
    const val ZERO_HOP_ADVERT = "OK - zero-hop advert sent"
    const val FLOOD_ADVERT = "OK - flood advert sent"

    // Custom-var output mirrors meshcore-cli's bare `get`/`set` and the repeater text CLI: `Var <key> set to
    // <value>` on a write, `Unknown var <key>` on a read miss, `can't find custom var` when firmware rejects a set.
    const val UNKNOWN_CUSTOM_VAR = "can't find custom var"
    const val NO_CUSTOM_VARS = "no custom var"

    fun value(text: String): String = "> $text"

    fun unknownVar(key: String): String = "Unknown var $key"

    fun customVarSet(key: String, value: String): String = "Var $key set to $value"

    /**
     * The `get custom` reply: a `N vars` header then one `name=value` line per var, keys sorted (the map
     * decode loses firmware order), or the empty-set string.
     */
    fun customVarList(vars: Map<String, String>): String {
        if (vars.isEmpty()) return NO_CUSTOM_VARS
        val lines = vars.keys.sorted().joinToString("\n") { "$it=${vars[it].orEmpty()}" }
        return "${vars.size} vars\n$lines"
    }

    /** Formats a device clock reading (epoch seconds, UTC) as `HH:MM - D/M/YYYY UTC`. */
    fun clock(epochSeconds: Long): String = formatClock(epochSeconds)

    /** The `clock sync` reply, echoing the time written to the radio. */
    fun clockSet(epochSeconds: Long): String = "OK - clock set: ${formatClock(epochSeconds)}"

    fun radio(info: SelfInfo): String =
        "${decimal(info.radioFrequency)},${decimal(info.radioBandwidth)},${info.radioSpreadingFactor}," +
            "${info.radioCodingRate}"

    fun hex(data: ByteArray): String = data.toUppercaseHex()

    fun decimal(value: Double): String = trimmed(formatFixed(value, DECIMAL_PLACES))

    fun coordinate(value: Double): String = trimmed(formatFixed(value, COORDINATE_PLACES))

    private const val DECIMAL_PLACES = 3
    private const val COORDINATE_PLACES = 6
}

private const val DECIMAL_BASE = 10L
private const val SECONDS_PER_DAY = 86_400L
private const val SECONDS_PER_HOUR = 3_600L
private const val SECONDS_PER_MINUTE = 60L

private const val CIVIL_EPOCH_DAYS = 719_468L
private const val ERA_LENGTH_DAYS = 146_097L
private const val ERA_LENGTH_BIAS = 146_096L
private const val FOUR_YEAR_DAYS = 1_460L
private const val CENTURY_DAYS = 36_524L
private const val YEAR_DAYS = 365L
private const val ERA_YEARS = 400L
private const val CENTURY_YEARS = 100L
private const val LEAP_CYCLE_YEARS = 4L
private const val MONTH_CYCLE_DAYS = 153L
private const val MONTH_CYCLE_DIVISOR = 5L
private const val MARCH_INDEX_LIMIT = 10L
private const val MARCH_MONTH = 3L
private const val MONTHS_AFTER_FEBRUARY = 9L

private fun trimmed(formatted: String): String {
    if (!formatted.contains('.')) return formatted
    var result = formatted
    while (result.endsWith("0")) result = result.dropLast(1)
    if (result.endsWith(".")) result = result.dropLast(1)
    return result
}

/** Fixed-point decimal with [places] fractional digits, ties away from zero. */
private fun formatFixed(value: Double, places: Int): String {
    var scale = 1L
    repeat(places) { scale *= DECIMAL_BASE }
    val scaled = (abs(value) * scale).roundToLong()
    val intPart = scaled / scale
    val fracPart = (scaled % scale).toString().padStart(places, '0')
    val sign = if (value < 0 && scaled != 0L) "-" else ""
    return "$sign$intPart.$fracPart"
}

private fun formatClock(epochSeconds: Long): String {
    val days = epochSeconds.floorDiv(SECONDS_PER_DAY)
    val secondOfDay = epochSeconds.mod(SECONDS_PER_DAY)
    val hour = secondOfDay / SECONDS_PER_HOUR
    val minute = (secondOfDay % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val (year, month, day) = civilFromDays(days)
    return "${pad2(hour)}:${pad2(minute)} - $day/$month/$year UTC"
}

private fun pad2(value: Long): String = value.toString().padStart(2, '0')

/** Howard Hinnant's civil-from-days: the proleptic-Gregorian UTC date for days since 1970-01-01. */
private fun civilFromDays(days: Long): Triple<Long, Long, Long> {
    val z = days + CIVIL_EPOCH_DAYS
    val era = (if (z >= 0) z else z - ERA_LENGTH_BIAS) / ERA_LENGTH_DAYS
    val doe = z - era * ERA_LENGTH_DAYS
    val yoe = (doe - doe / FOUR_YEAR_DAYS + doe / CENTURY_DAYS - doe / ERA_LENGTH_BIAS) / YEAR_DAYS
    val year = yoe + era * ERA_YEARS
    val doy = doe - (YEAR_DAYS * yoe + yoe / LEAP_CYCLE_YEARS - yoe / CENTURY_YEARS)
    val mp = (MONTH_CYCLE_DIVISOR * doy + 2) / MONTH_CYCLE_DAYS
    val day = doy - (MONTH_CYCLE_DAYS * mp + 2) / MONTH_CYCLE_DIVISOR + 1
    val month = if (mp < MARCH_INDEX_LIMIT) mp + MARCH_MONTH else mp - MONTHS_AFTER_FEBRUARY
    return Triple(if (month <= 2) year + 1 else year, month, day)
}
