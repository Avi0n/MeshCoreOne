package one.meshcore.shared.remotenode

/**
 * A parsed CLI response from a repeater/room, mirroring the Swift `CLIResponse`. [parse] classifies a
 * response line against the query that (may have) produced it; the content-based CLI matcher treats any
 * non-[Raw] parse as a candidate. Correlation to a specific pending request is the caller's job
 * ([NodeCliCoordinator.findBestMatch]).
 */
sealed interface CLIResponse {
    data object Ok : CLIResponse
    data class Error(val text: String) : CLIResponse
    data class UnknownCommand(val text: String) : CLIResponse
    data class Version(val text: String) : CLIResponse
    data class DeviceTime(val text: String) : CLIResponse
    data class Name(val text: String) : CLIResponse
    data class Radio(val frequency: Double, val bandwidth: Double, val spreadingFactor: Int, val codingRate: Int) :
        CLIResponse

    data class TxPower(val value: Int) : CLIResponse
    data class RepeatMode(val enabled: Boolean) : CLIResponse
    data class AdvertInterval(val minutes: Int) : CLIResponse

    /** Value is in hours, not minutes. */
    data class FloodAdvertInterval(val hours: Int) : CLIResponse
    data class FloodMax(val hops: Int) : CLIResponse
    data class Latitude(val value: Double) : CLIResponse
    data class Longitude(val value: Double) : CLIResponse
    data class OwnerInfo(val text: String) : CLIResponse
    data class Raw(val text: String) : CLIResponse

    companion object {
        private const val RADIO_FIELD_COUNT = 4

        /**
         * The canonical query strings. [parse]'s hints and [STRUCTURED_QUERIES] both read them from here,
         * so a query cannot join one and drift from the other. Mirrors the Swift `CLIResponse.Query`.
         */
        private object Query {
            const val VERSION = "ver"
            const val NAME = "get name"
            const val OWNER_INFO = "get owner.info"
            const val CLOCK = "clock"
            const val RADIO = "get radio"
            const val TX_POWER = "get tx"
            const val REPEAT_MODE = "get repeat"
            const val ADVERT_INTERVAL = "get advert.interval"
            const val FLOOD_ADVERT_INTERVAL = "get flood.advert.interval"
            const val FLOOD_MAX = "get flood.max"
            const val LATITUDE = "get lat"
            const val LONGITUDE = "get lon"
        }

        /**
         * Parses [text] into a structured response, using [query] as a hint to disambiguate freeform
         * fields. The MeshCore CLI prompt prefix `"> "` is stripped first (firmware prepends it to every
         * response). Ported verbatim from the Swift `CLIResponse.parse`.
         */
        fun parse(text: String, query: String? = null): CLIResponse = Parser.classify(text, query)

        /**
         * Classification steps for [parse]. A null step falls through to the next: status, version,
         * freeform, clock, radio, then numeric hints.
         */
        private object Parser {
            private const val CLI_PROMPT_PREFIX = "> "
            private const val CLI_PROMPT_BARE = ">"
            private const val FREQUENCY_INDEX = 0
            private const val BANDWIDTH_INDEX = 1
            private const val SPREADING_FACTOR_INDEX = 2
            private const val CODING_RATE_INDEX = 3

            fun classify(text: String, query: String?): CLIResponse {
                val trimmed = stripCliPrompt(text)
                val lower = trimmed.lowercase()
                return statusResponse(trimmed, lower)
                    ?: versionResponse(trimmed, query)
                    ?: freeformResponse(trimmed, query)
                    ?: clockResponse(trimmed, query)
                    ?: radioResponse(trimmed, query)
                    ?: Numeric.parse(trimmed, lower, query)
                    ?: Raw(trimmed)
            }

            private fun stripCliPrompt(text: String): String {
                val trimmed = text.trim()
                return when {
                    trimmed.startsWith(CLI_PROMPT_PREFIX) -> trimmed.substring(CLI_PROMPT_PREFIX.length)
                    trimmed == CLI_PROMPT_BARE -> ""
                    else -> trimmed
                }
            }

            private fun statusResponse(trimmed: String, lower: String): CLIResponse? {
                if (trimmed == "OK" || trimmed.startsWith("OK - ")) return Ok
                if (!lower.startsWith("error") && !trimmed.startsWith("ERR:")) return null
                if (lower.contains("unknown command")) return UnknownCommand(trimmed)
                return Error(trimmed)
            }

            private fun versionResponse(trimmed: String, query: String?): CLIResponse? {
                if (query == Query.VERSION) return Version(trimmed)
                if (trimmed.startsWith("MeshCore v")) return Version(trimmed)
                if (trimmed.startsWith("v") && trimmed.contains("(")) return Version(trimmed)
                return null
            }

            /** Freeform text fields: any remaining text is the value. */
            private fun freeformResponse(trimmed: String, query: String?): CLIResponse? = when (query) {
                Query.NAME -> Name(trimmed)
                Query.OWNER_INFO -> OwnerInfo(trimmed)
                else -> null
            }

            // Clock text is only a time when the query asked for one. The same ":" and "/" shape
            // shows up in names and in the firmware password echo.
            private fun clockResponse(trimmed: String, query: String?): CLIResponse? {
                if (query != Query.CLOCK) return null
                if (trimmed.contains("UTC")) return DeviceTime(trimmed)
                if (trimmed.contains(":") && trimmed.contains("/")) return DeviceTime(trimmed)
                return null
            }

            private fun radioResponse(trimmed: String, query: String?): CLIResponse? {
                if (query != Query.RADIO) return null
                val fields = radioFields(trimmed) ?: return null
                return Radio(fields.frequency, fields.bandwidth, fields.spreadingFactor, fields.codingRate)
            }

            private fun radioFields(text: String): RadioFields? {
                val parts = text.split(",")
                if (parts.size < RADIO_FIELD_COUNT) return null
                return radioFieldsFrom(parts)
            }

            private fun radioFieldsFrom(parts: List<String>): RadioFields? {
                val frequency = parts[FREQUENCY_INDEX].trim().toDoubleOrNull() ?: return null
                val bandwidth = parts[BANDWIDTH_INDEX].trim().toDoubleOrNull() ?: return null
                val spreadingFactor = parts[SPREADING_FACTOR_INDEX].trim().toIntOrNull() ?: return null
                val codingRate = parts[CODING_RATE_INDEX].trim().toIntOrNull() ?: return null
                return RadioFields(frequency, bandwidth, spreadingFactor, codingRate)
            }

            private class RadioFields(
                val frequency: Double,
                val bandwidth: Double,
                val spreadingFactor: Int,
                val codingRate: Int,
            )

            private object Numeric {
                fun parse(trimmed: String, lower: String, query: String?): CLIResponse? =
                    interval(trimmed, query) ?: powerOrPlace(trimmed, lower, query)

                private fun interval(trimmed: String, query: String?): CLIResponse? = when (query) {
                    Query.ADVERT_INTERVAL -> wholeNumber(trimmed) { AdvertInterval(it) }
                    Query.FLOOD_ADVERT_INTERVAL -> wholeNumber(trimmed) { FloodAdvertInterval(it) }
                    Query.FLOOD_MAX -> wholeNumber(trimmed) { FloodMax(it) }
                    else -> null
                }

                private fun powerOrPlace(trimmed: String, lower: String, query: String?): CLIResponse? = when (query) {
                    Query.TX_POWER -> parseTxPowerDBm(trimmed)?.let { TxPower(it) }
                    Query.REPEAT_MODE -> repeatMode(lower)
                    Query.LATITUDE -> decimal(trimmed) { Latitude(it) }
                    Query.LONGITUDE -> decimal(trimmed) { Longitude(it) }
                    else -> null
                }

                private fun repeatMode(lower: String): RepeatMode? = when (lower) {
                    "on" -> RepeatMode(true)
                    "off" -> RepeatMode(false)
                    else -> null
                }

                private fun wholeNumber(text: String, build: (Int) -> CLIResponse): CLIResponse? =
                    text.toIntOrNull()?.let(build)

                private fun decimal(text: String, build: (Double) -> CLIResponse): CLIResponse? =
                    text.toDoubleOrNull()?.let(build)
            }
        }

        /**
         * Reads the configured TX power in dBm from a `get tx` reply. Stock firmware sends a bare integer
         * ("22"); ZephCore appends Adaptive Power Control state ("22dBm (apc=off)"), and while APC is active
         * the leading number is the reduced live power whereas the `max=` ceiling is what `set tx` writes
         * back, so `max=` wins when present. Mirrors the Swift `parseTXPowerDBm`.
         */
        private fun parseTxPowerDBm(text: String): Int? {
            TX_POWER_MAX_REGEX.find(text)?.let { return it.groupValues[1].toIntOrNull() }
            // Accept only a whole leading integer ("22", "22dBm (apc=off)"); a digit run followed by "." or
            // "," is some other value, such as the leading frequency of a radio CSV, never a power.
            return TX_POWER_LEADING_REGEX.find(text)?.let { it.groupValues[1].toIntOrNull() }
        }

        private val TX_POWER_MAX_REGEX = Regex("""max=(-?\d+)""")
        private val TX_POWER_LEADING_REGEX = Regex("""^(-?\d+)(?:dBm|\s|$)""")

        // MARK: Wire-prefix echo

        /** Separator of the optional CLI wire prefix ("XX|") firmware reflects back at the start of a reply. */
        const val ECHO_PREFIX_SEPARATOR: Char = '|'

        private const val ECHO_PREFIX_LENGTH = 3
        private val ECHO_PREFIX_REGEX = Regex("[0-9A-F]{2}\\|")

        /** An echoed wire prefix split off a reply. */
        data class EchoedPrefix(val prefix: String, val body: String)

        /**
         * Splits an echoed wire prefix off a reply, or null when the reply carries none. Only two hex digits
         * plus the separator qualify (matching the prefixes this app generates), so ordinary reply text can't
         * be mistaken for a prefix. Mirrors the Swift `splitEchoedPrefix`.
         */
        fun splitEchoedPrefix(text: String): EchoedPrefix? {
            if (text.length <= ECHO_PREFIX_LENGTH) return null
            val head = text.substring(0, ECHO_PREFIX_LENGTH)
            if (!ECHO_PREFIX_REGEX.matches(head)) return null
            return EchoedPrefix(head, text.substring(ECHO_PREFIX_LENGTH))
        }

        /**
         * Queries whose replies have a machine-checkable shape. Free-form gets and set/action commands are
         * absent because their success replies are arbitrary text (firmware answers `password` with
         * "password now:", not "OK"). Mirrors the Swift `structuredQueries`.
         */
        private val STRUCTURED_QUERIES: Set<String> = setOf(
            Query.RADIO, Query.TX_POWER, Query.REPEAT_MODE, Query.ADVERT_INTERVAL,
            Query.FLOOD_ADVERT_INTERVAL, Query.FLOOD_MAX, Query.LATITUDE, Query.LONGITUDE, Query.CLOCK,
        )

        /** Whether the query's reply has a machine-checkable shape. Mirrors the Swift `isStructuredQuery`. */
        fun isStructuredQuery(query: String): Boolean = query in STRUCTURED_QUERIES

        /**
         * Whether a reply is plausible for the given pending query. Replies to structured gets must parse to
         * their typed case (or an error); everything else matches any text. Mirrors the Swift
         * `isPlausibleResponse`, used as the fallback when firmware does not echo a wire prefix.
         */
        fun isPlausibleResponse(response: String, query: String): Boolean {
            if (!isStructuredQuery(query)) return true
            return parse(response, query) !is Raw
        }
    }
}
