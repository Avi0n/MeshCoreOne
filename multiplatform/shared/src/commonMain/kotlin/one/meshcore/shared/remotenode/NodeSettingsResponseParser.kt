package one.meshcore.shared.remotenode

/**
 * Pure parsing of repeater/room CLI responses for the node-settings screens, mirroring the Swift
 * `NodeSettingsResponseParser`. Builds on [CLIResponse] and holds no state, so every function is directly
 * unit-testable. Covers late-reply attribution, clock parsing, owner-info wire mapping, and
 * success/error classification.
 */
object NodeSettingsResponseParser {
    /** An out-of-band CLI reply attributed by elimination to exactly one unanswered query. */
    data class RecoveredReply(val query: String, val value: CLIResponse)

    /**
     * Attribution by elimination for an out-of-band CLI reply. One command is in flight per node, so a
     * reply that no pending command claimed can only answer a command that timed out unanswered. Returns
     * the parsed value when the reply parses to a query-specific shape for exactly one of
     * [unansweredQueries]; several matches are ambiguous, so nothing is returned. Free-form queries such
     * as `ver`, `get name` and `get owner.info` are never candidates: their replies are arbitrary text, so
     * any reply would "match" and land a stray value in an unrelated field of someone else's node.
     * Mirrors the Swift `recoveredResponse`.
     */
    fun recoveredResponse(response: String, unansweredQueries: Set<String>): RecoveredReply? {
        val matches = unansweredQueries.mapNotNull { query ->
            if (!CLIResponse.isStructuredQuery(query)) {
                null
            } else {
                val value = CLIResponse.parse(response, query)
                if (isQueryIndependent(value)) null else RecoveredReply(query, value)
            }
        }
        return matches.singleOrNull()
    }

    /** Whether a parsed shape matches every query equally, leaving nothing to attribute it by. */
    private fun isQueryIndependent(value: CLIResponse): Boolean = value is CLIResponse.Raw ||
        value is CLIResponse.Ok ||
        value is CLIResponse.Error ||
        value is CLIResponse.UnknownCommand ||
        value is CLIResponse.Version

    /** The UTC clock shape in firmware text, including inside an `OK - clock set:` reply. */
    fun clockResponseText(text: String): String? = CLOCK_REGEX.find(text)?.value

    /**
     * Parses a firmware clock response like "06:40 - 18/4/2025 UTC" into epoch seconds (UTC), or null when
     * the text lacks the expected shape. Returns seconds rather than a platform date type so the shared
     * layer stays free of a date library; the app target formats to local time.
     */
    fun utcEpochSeconds(response: String): Long? {
        val match = CLOCK_REGEX.find(response) ?: return null
        val (time, date) = match.destructured
        val (hour, minute) = time.split(":").let { it[0].toInt() to it[1].toInt() }
        val parts = date.split("/")
        val day = parts[0].toInt()
        val month = parts[1].toInt()
        val year = parts[2].toInt()
        return daysFromCivil(year, month, day) * SECONDS_PER_DAY + hour * SECONDS_PER_HOUR + minute * SECONDS_PER_MINUTE
    }

    /** Seconds the node's clock is ahead of [nowEpochSeconds]. Null when [response] has no clock shape. */
    fun clockDriftSeconds(fromClockResponse: String, nowEpochSeconds: Long): Double? {
        val node = utcEpochSeconds(fromClockResponse) ?: return null
        return (node - nowEpochSeconds).toDouble()
    }

    /** Outcome of a `clock sync` command response. */
    sealed interface ClockSyncOutcome {
        data object Synced : ClockSyncOutcome

        /** Firmware refused because its clock is ahead of the phone's. */
        data object ClockAhead : ClockSyncOutcome

        /** Firmware reported an error; [message] has the "ERR: " prefix stripped and may be empty. */
        data class Failed(val message: String) : ClockSyncOutcome

        /** Carries the reply verbatim: what the node said is the whole of the diagnostic value here. */
        data class Unexpected(val response: String) : ClockSyncOutcome
    }

    /** Classifies a `clock sync` response into a typed outcome. */
    fun classifyClockSyncResponse(response: String): ClockSyncOutcome =
        when (val parsed = CLIResponse.parse(response)) {
            is CLIResponse.Ok -> ClockSyncOutcome.Synced
            is CLIResponse.Error ->
                if (parsed.text.contains(CLOCK_AHEAD_FRAGMENT)) {
                    ClockSyncOutcome.ClockAhead
                } else {
                    ClockSyncOutcome.Failed(parsed.text.replace(CLI_ERROR_PREFIX, ""))
                }

            else -> ClockSyncOutcome.Unexpected(response)
        }

    /** Whether a `password` command response indicates the change was accepted. */
    fun isPasswordChangeSuccessful(response: String): Boolean = when (val parsed = CLIResponse.parse(response)) {
        is CLIResponse.Ok -> true
        is CLIResponse.Raw -> parsed.text.startsWith(PASSWORD_CHANGED_PREFIX)
        else -> false
    }

    /** Maps the wire form ("|"-separated) to the multi-line display form. */
    fun displayOwnerInfo(wire: String): String = wire.replace(OWNER_INFO_WIRE_SEPARATOR, OWNER_INFO_DISPLAY_SEPARATOR)

    /** Maps the multi-line display form back to the "|"-separated wire form. */
    fun wireOwnerInfo(display: String): String =
        display.replace(OWNER_INFO_DISPLAY_SEPARATOR, OWNER_INFO_WIRE_SEPARATOR)

    private val CLOCK_REGEX = Regex("""(\d{1,2}:\d{2}) - (\d{1,2}/\d{1,2}/\d{4}) UTC""")
    private const val CLOCK_AHEAD_FRAGMENT = "clock cannot go backwards"
    private const val CLI_ERROR_PREFIX = "ERR: "
    private const val PASSWORD_CHANGED_PREFIX = "password now:"
    private const val OWNER_INFO_WIRE_SEPARATOR = "|"
    private const val OWNER_INFO_DISPLAY_SEPARATOR = "\n"
    private const val SECONDS_PER_DAY = 86_400L
    private const val SECONDS_PER_HOUR = 3_600L
    private const val SECONDS_PER_MINUTE = 60L

    // Howard Hinnant's days-from-civil: days since 1970-01-01 for a proleptic-Gregorian UTC date.
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - ERA_YEARS + 1) / ERA_YEARS
        val yoe = y - era * ERA_YEARS
        val monthIndex = if (month > 2) month - MARCH_MONTH else month + MONTHS_AFTER_FEBRUARY
        val doy = (MONTH_CYCLE_DAYS * monthIndex + 2) / MONTH_CYCLE_DIVISOR + day - 1
        val doe = yoe * YEAR_DAYS + yoe / LEAP_CYCLE_YEARS - yoe / CENTURY_YEARS + doy
        return era.toLong() * ERA_LENGTH_DAYS + doe - CIVIL_EPOCH_DAYS
    }

    private const val CIVIL_EPOCH_DAYS = 719_468
    private const val ERA_LENGTH_DAYS = 146_097
    private const val YEAR_DAYS = 365
    private const val ERA_YEARS = 400
    private const val CENTURY_YEARS = 100
    private const val LEAP_CYCLE_YEARS = 4
    private const val MONTH_CYCLE_DAYS = 153
    private const val MONTH_CYCLE_DIVISOR = 5
    private const val MARCH_MONTH = 3
    private const val MONTHS_AFTER_FEBRUARY = 9
}
