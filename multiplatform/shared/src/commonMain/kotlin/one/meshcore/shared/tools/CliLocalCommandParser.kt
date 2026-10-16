package one.meshcore.shared.tools

// Firmware validation bounds on the companion BLE path (`CMD_SET_RADIO_PARAMS`).
private val FREQUENCY_RANGE_MHZ = 150.0..2500.0
private val BANDWIDTH_RANGE_KHZ = 7.0..500.0
private val SPREADING_FACTOR_RANGE = 5..12
private val CODING_RATE_RANGE = 5..8
private val LATITUDE_RANGE = -90.0..90.0
private val LONGITUDE_RANGE = -180.0..180.0
private val MULTI_ACKS_RANGE = 0..1
private val PATH_HASH_MODE_RANGE = 0..2
private val TX_POWER_RANGE = -128..127

// The `CMD_GET_CUSTOM_VARS` reply concatenates pairs as `name:value` joined by commas, so a key may
// contain neither separator and a value may not contain a comma; a `:` inside a value is safe because
// each pair splits at its first `:`.
private val CUSTOM_VAR_RESERVED_IN_KEY = setOf(':', ',')
private val CUSTOM_VAR_RESERVED_IN_VALUE = setOf(',')

/** The `get custom` dump verb, and the leading-`_` escape hatch that forces a key onto the custom-var path. */
private const val CUSTOM_VAR_DUMP_KEY = "custom"
private const val CUSTOM_VAR_ESCAPE_PREFIX = "_"

// Firmware's per-pair enumeration budget: the `CMD_GET_CUSTOM_VARS` handler starts a new pair only while
// the reply offset is under 140 bytes, so a pair of at most 139 bytes always stays individually
// enumerable. Chosen over the 174-byte set-frame limit so a var written here can always be read back.
private const val MAX_CUSTOM_VAR_PAIR_BYTES = 139

private const val RADIO_FIELD_COUNT = 4
private const val FREQ_FIELD = 0
private const val BANDWIDTH_FIELD = 1
private const val SPREADING_FACTOR_FIELD = 2
private const val CODING_RATE_FIELD = 3

/**
 * Parses a local-session input line into a typed command or error. Pure and dependency-free: no services,
 * no coroutines, no resources. Ported from the Swift `CLILocalCommandParser`.
 */
object CliLocalCommandParser {
    /** Parses a trimmed local-session input line. */
    fun parse(line: String): CliLocalParseResult {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return CliLocalParseResult.NotLocal

        val parts = trimmed.split(" ", limit = 2)
        val command = parts[0].lowercase()
        val rest = if (parts.size > 1) parts[1] else ""

        return when (command) {
            "clock" -> {
                val sub = rest.trim().lowercase()
                command(if (sub == "sync") CliLocalCommand.ClockSync else CliLocalCommand.Clock)
            }
            "ver" -> command(CliLocalCommand.Ver)
            "board" -> command(CliLocalCommand.Board)
            "advert", "advert.zerohop" -> command(CliLocalCommand.Advert(flood = false))
            "floodadv" -> command(CliLocalCommand.Advert(flood = true))
            "reboot" -> command(CliLocalCommand.Reboot)
            "get" -> GetLine.parse(rest)
            "set" -> SetLine.parse(rest)
            else -> CliLocalParseResult.NotLocal
        }
    }
}

/**
 * The dump verb and typed keys match on the folded first token; anything else falls through to a
 * companion custom-var read (verbatim key, one leading `_` stripped). A get never puts an unknown key on
 * the wire; the runner fetches all vars and looks the key up client-side.
 */
private object GetLine {
    fun parse(rest: String): CliLocalParseResult {
        val rawToken = firstToken(rest)
        if (rawToken.isEmpty()) return invalid(CliLocalParseError.BadArguments(CliLocalUsage.GET))
        return resolved(rawToken)
    }

    private fun resolved(rawToken: String): CliLocalParseResult {
        val lowered = rawToken.lowercase()
        if (lowered == CUSTOM_VAR_DUMP_KEY) return command(CliLocalCommand.GetCustomVars)
        return typedOrCustom(lowered, rawToken)
    }

    private fun typedOrCustom(lowered: String, rawToken: String): CliLocalParseResult {
        val typed = CliLocalKey.fromRaw(lowered)
        if (typed != null) return command(CliLocalCommand.GetKey(typed))
        return custom(rawToken)
    }

    private fun custom(rawToken: String): CliLocalParseResult {
        val key = strippedCustomVarKey(rawToken)
        if (key.isEmpty()) return invalid(CliLocalParseError.BadArguments(CliLocalUsage.GET))
        return acceptedCustom(key)
    }

    private fun acceptedCustom(key: String): CliLocalParseResult {
        if (!isValidCustomVarKey(key)) return invalid(CliLocalParseError.InvalidCustomVarToken)
        return command(CliLocalCommand.GetCustomVar(key))
    }
}

private object SetLine {
    fun parse(rest: String): CliLocalParseResult {
        if (rest.isBlank()) return invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET))
        val trimmed = rest.trim()
        val parts = trimmed.split(" ", limit = 2)
        // Keep the key verbatim so a custom-var fallthrough writes the exact name; typed matching folds case.
        val keyToken = parts[0]
        // Trim so extra spacing between key and value ("set tx  22") doesn't leak into numeric parses or the
        // name; inner spaces survive.
        val value = if (parts.size > 1) parts[1].trim() else ""
        val key = CliLocalKey.fromRaw(keyToken.lowercase()) ?: return CustomSet.parse(keyToken, value)
        return dispatch(key, value)
    }

    private fun dispatch(key: CliLocalKey, value: String): CliLocalParseResult = when (key) {
        CliLocalKey.NAME -> name(value)
        CliLocalKey.LAT -> latitude(value)
        CliLocalKey.LON -> longitude(value)
        CliLocalKey.TX -> txPower(value)
        CliLocalKey.RADIO -> RadioSet.parse(value)
        CliLocalKey.FREQ -> frequency(value)
        CliLocalKey.MULTI_ACKS -> multiAcks(value)
        CliLocalKey.PATH_HASH_MODE -> pathHashMode(value)
        // Read-only keys: reject with the settable-keys usage line.
        CliLocalKey.PUBLIC_KEY, CliLocalKey.BAT -> invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET))
    }

    private fun name(value: String): CliLocalParseResult = if (value.isEmpty()) {
        invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET))
    } else {
        command(CliLocalCommand.SetName(value))
    }

    private fun latitude(value: String): CliLocalParseResult =
        parseDouble(value, LATITUDE_RANGE)?.let { command(CliLocalCommand.SetLatitude(it)) } ?: invalidNumber(value)

    private fun longitude(value: String): CliLocalParseResult =
        parseDouble(value, LONGITUDE_RANGE)?.let { command(CliLocalCommand.SetLongitude(it)) } ?: invalidNumber(value)

    private fun txPower(value: String): CliLocalParseResult {
        val raw = value.toIntOrNull() ?: return invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET))
        if (raw !in TX_POWER_RANGE) return invalid(CliLocalParseError.ValueOutOfRange)
        return command(CliLocalCommand.SetTxPower(raw))
    }

    private fun frequency(value: String): CliLocalParseResult =
        parseDouble(value, FREQUENCY_RANGE_MHZ)?.let { command(CliLocalCommand.SetFrequency(it)) }
            ?: invalidNumber(value)

    private fun multiAcks(value: String): CliLocalParseResult =
        parseInt(value, MULTI_ACKS_RANGE)?.let { command(CliLocalCommand.SetMultiAcks(it)) } ?: invalidNumber(value)

    private fun pathHashMode(value: String): CliLocalParseResult =
        parseInt(value, PATH_HASH_MODE_RANGE)?.let { command(CliLocalCommand.SetPathHashMode(it)) }
            ?: invalidNumber(value)
}

/**
 * Routes a `set` whose key is not a typed setting to a companion custom var, stripping one leading `_`
 * (the escape hatch for a fork var whose name collides with a typed key). A missing key or value is an
 * arguments problem; only a reserved char or an over-budget pair is [CliLocalParseError.InvalidCustomVarToken].
 */
private object CustomSet {
    fun parse(keyToken: String, value: String): CliLocalParseResult {
        val key = strippedCustomVarKey(keyToken)
        if (key.isEmpty() || value.isEmpty()) return invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET))
        return accepted(key, value)
    }

    private fun accepted(key: String, value: String): CliLocalParseResult {
        if (rejected(key, value)) return invalid(CliLocalParseError.InvalidCustomVarToken)
        return command(CliLocalCommand.SetCustomVar(key, value))
    }

    private fun rejected(key: String, value: String): Boolean {
        if (!isValidCustomVarKey(key)) return true
        if (!isValidCustomVarValue(value)) return true
        return "$key:$value".encodeToByteArray().size > MAX_CUSTOM_VAR_PAIR_BYTES
    }
}

private object RadioSet {
    fun parse(value: String): CliLocalParseResult {
        val fields = value.split(",").map { it.trim() }
        if (fields.size != RADIO_FIELD_COUNT) return invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET_RADIO))
        return parsedFields(fields)
    }

    private fun parsedFields(fields: List<String>): CliLocalParseResult {
        val freq = fields[FREQ_FIELD].toDoubleOrNull() ?: return invalidRadio()
        val bandwidth = fields[BANDWIDTH_FIELD].toDoubleOrNull() ?: return invalidRadio()
        val spreadingFactor = fields[SPREADING_FACTOR_FIELD].toIntOrNull() ?: return invalidRadio()
        val codingRate = fields[CODING_RATE_FIELD].toIntOrNull() ?: return invalidRadio()
        return ranges(freq, bandwidth, spreadingFactor, codingRate)
    }

    private fun ranges(freq: Double, bandwidth: Double, spreadingFactor: Int, codingRate: Int): CliLocalParseResult {
        if (freq !in FREQUENCY_RANGE_MHZ || bandwidth !in BANDWIDTH_RANGE_KHZ) return outOfRange()
        if (spreadingFactor !in SPREADING_FACTOR_RANGE || codingRate !in CODING_RATE_RANGE) return outOfRange()
        return command(
            CliLocalCommand.SetRadio(
                frequencyMHz = freq,
                bandwidthKHz = bandwidth,
                spreadingFactor = spreadingFactor,
                codingRate = codingRate,
            ),
        )
    }

    private fun invalidRadio(): CliLocalParseResult = invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET_RADIO))

    private fun outOfRange(): CliLocalParseResult = invalid(CliLocalParseError.ValueOutOfRange)
}

private fun command(command: CliLocalCommand): CliLocalParseResult = CliLocalParseResult.Command(command)

private fun invalid(error: CliLocalParseError): CliLocalParseResult = CliLocalParseResult.Invalid(error)

/** The first whitespace-delimited token of a string, or empty. */
private fun firstToken(string: String): String = string.split(" ").firstOrNull { it.isNotEmpty() } ?: ""

/**
 * Strips one leading `_` so a custom-var name that collides with a typed key (or the `custom` dump verb)
 * can still be reached; a bare `_` strips to empty.
 */
private fun strippedCustomVarKey(token: String): String =
    if (token.startsWith(CUSTOM_VAR_ESCAPE_PREFIX)) token.drop(1) else token

private fun isValidCustomVarKey(key: String): Boolean =
    key.isNotEmpty() && key.none { it in CUSTOM_VAR_RESERVED_IN_KEY }

private fun isValidCustomVarValue(value: String): Boolean = value.none { it in CUSTOM_VAR_RESERVED_IN_VALUE }

/**
 * Parses a [Double] and range-checks it. Returns null for both non-numeric and out-of-range input;
 * callers map those to distinct errors via [invalidNumber].
 */
private fun parseDouble(value: String, range: ClosedFloatingPointRange<Double>): Double? {
    val parsed = value.toDoubleOrNull() ?: return null
    return if (parsed in range) parsed else null
}

private fun parseInt(value: String, range: IntRange): Int? {
    val parsed = value.toIntOrNull() ?: return null
    return if (parsed in range) parsed else null
}

/** Distinguishes non-numeric input (bad arguments) from an out-of-range number. */
private fun invalidNumber(value: String): CliLocalParseResult = if (value.toDoubleOrNull() == null) {
    invalid(CliLocalParseError.BadArguments(CliLocalUsage.SET))
} else {
    invalid(CliLocalParseError.ValueOutOfRange)
}
