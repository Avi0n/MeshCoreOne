package one.meshcore.shared.chat.text

/** A decimal-degree coordinate detected in message text. */
data class ChatCoordinate(val latitude: Double, val longitude: Double)

/** A coordinate match and its inclusive char range in the source text. */
data class CoordinateMatch(val range: IntRange, val coordinate: ChatCoordinate)

/**
 * Single source of truth for detecting decimal-degree coordinate pairs in message text, shared by the
 * linkifier (tappable text link) and the map-preview fragment builder (thumbnail), so the two never
 * disagree. The `-90..90` / `-180..180` clamp is the sole validity gate: it forwards the parsed
 * coordinate straight to the map, which does not re-validate. Ported from the iOS `ChatCoordinateDetector`.
 *
 * The `java.util.regex` engine backing Kotlin `Regex` on JVM/Android supports the lookbehind and
 * lookahead verbatim. `\w`/`\d` are ASCII here versus ICU-unicode on iOS, but the difference is not
 * observable: a non-ASCII "digit" the ICU pattern would admit still fails the `toDouble` gate, so both
 * reject it.
 */
object ChatCoordinateDetector {
    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    // Each component: sign-optional, 1-3 integer digits, a required point, >=1 fractional digit. The
    // lookbehind excludes an adjacent word char or `.` (no matching inside a longer number or after a
    // version prefix); the trailing lookahead rejects a following `.digit` (`3.4.5`) or word char while
    // allowing trailing punctuation, so a coordinate ending a sentence still matches.
    private val coordinateRegex =
        Regex("""(?<![\w.])(-?\d{1,3}\.\d+)\s*,\s*(-?\d{1,3}\.\d+)(?!\.\d)(?!\w)""")

    // A third comma-number right after the pair marks a numeric list (`1.0, 2.0, 3.0`), not a coordinate.
    private val decimalListGuard = Regex("""^\s*,\s*-?\d""")

    /** All valid coordinate matches in document order, applying the regex, the range clamp, and the guard. */
    fun matches(text: String): List<CoordinateMatch> {
        // A coordinate pair must contain a comma; the fast path skips the regex for the vast majority of
        // bodies, which matters during full timeline rebuilds that re-run this over hundreds of messages.
        if (!text.contains(',')) return emptyList()

        return coordinateRegex.findAll(text).mapNotNull { match ->
            val latitude = match.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val longitude = match.groupValues[2].toDoubleOrNull() ?: return@mapNotNull null
            if (latitude !in MIN_LATITUDE..MAX_LATITUDE || longitude !in MIN_LONGITUDE..MAX_LONGITUDE) {
                return@mapNotNull null
            }

            // A third comma-number right after the pair marks a numeric list, not a coordinate.
            val tail = text.substring(match.range.last + 1)
            if (decimalListGuard.containsMatchIn(tail)) return@mapNotNull null

            CoordinateMatch(match.range, ChatCoordinate(latitude, longitude))
        }.toList()
    }

    /** The first valid coordinate in document order, or null. */
    fun firstCoordinate(text: String): ChatCoordinate? = matches(text).firstOrNull()?.coordinate
}
