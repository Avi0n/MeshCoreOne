package one.meshcore.shared.trace

/**
 * Hop-hash widths for a MeshCore **trace** packet, and the two distinct hash-size derivations the
 * protocol uses. These must never be conflated:
 *
 * - **Trace** hash size is power-of-2: `1 shl mode` maps modes 0/1/2 to 1/2/4 bytes ([traceHashSize]).
 *   A trace path is a flat buffer of fixed-width hop hashes; one flags byte declares the single width
 *   for the whole path.
 * - **Routing** (out-path) hash size is linear: `mode + 1` maps modes 0/1/2 to 1/2/3 bytes
 *   ([routingHashSize]). This is the `outPathLength` codec used by `decodePathLen`/`encodePathLen`.
 *
 * The two agree at modes 0 and 1 (1 and 2 bytes) and diverge at mode 2 (trace 4 vs routing 3). Mirrors
 * the Swift `TracePathViewModel.hashSize` (`1 << effectiveTraceMode`) versus the `PathEncoding` routing
 * codec.
 */

/** Trace hop widths the firmware accepts, in bytes (power-of-2 encoding). Mirrors Swift `validTraceHashSizes`. */
val VALID_TRACE_HASH_SIZES: List<Int> = listOf(1, 2, 4)

/** Lowest trace hash-size mode (0 → 1 byte). */
const val MIN_TRACE_HASH_MODE: Int = 0

/** Highest trace hash-size mode (2 → 4 bytes; the 2-bit field's value 3 is reserved). */
const val MAX_TRACE_HASH_MODE: Int = 2

/**
 * Trace hop width in bytes for a 2-bit trace mode: `1 shl mode` (mode 0 → 1, 1 → 2, 2 → 4). Power-of-2,
 * unlike [routingHashSize]. Mirrors Swift `TracePathViewModel.hashSize`.
 */
fun traceHashSize(mode: Int): Int = 1 shl mode

/**
 * The trace mode this trace declares: the per-trace [override] when the user set one, otherwise the
 * radio's configured [devicePathHashMode], otherwise mode 0. A radio configured for 2- or 4-byte hop
 * hashes must receive hops of that width or the trace never resolves, so the device mode — not a fixed
 * 1-byte default — is the fallback. Mirrors Swift `TracePathViewModel.effectiveTraceMode`.
 */
fun effectiveTraceMode(override: Int?, devicePathHashMode: Int?): Int =
    (override ?: devicePathHashMode ?: MIN_TRACE_HASH_MODE).coerceIn(MIN_TRACE_HASH_MODE, MAX_TRACE_HASH_MODE)

/**
 * Routing (out-path) hop width in bytes for a 2-bit path-size mode: `mode + 1` (mode 0 → 1, 1 → 2,
 * 2 → 3). Linear, unlike [traceHashSize]. Mirrors the `decodePathLen`/`encodePathLen` codec; kept here
 * so the trace and routing widths sit side by side and can never be conflated.
 */
fun routingHashSize(mode: Int): Int = mode + 1

/**
 * The trace mode (0/1/2) implied by a hop width in bytes (1 → 0, 2 → 1, 4 → 2), via trailing-zero bits.
 * Inverse of [traceHashSize] for the valid power-of-2 widths. Mirrors Swift `size.trailingZeroBitCount`.
 */
fun traceHashModeForSize(size: Int): Int = size.countTrailingZeroBits()

/**
 * The single trace mode (0/1/2 for 1/2/4 bytes) implied by a comma-separated bulk paste of hop codes,
 * or `null` when the codes are empty, non-hex, odd-length, mixed-width, or not a valid power-of-2 trace
 * width. Mirrors Swift `TracePathViewModel.inferredTraceHashMode(from:)`.
 */
fun inferredTraceHashMode(input: String): Int? {
    val tokens = input.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    // Byte width per token, or null for a malformed (odd-length or non-hex) token.
    val widths = tokens.map { token ->
        if (token.length % 2 == 0 && token.all { it.isHexDigit() }) token.length / 2 else null
    }
    val uniform = widths.firstOrNull()
    return when {
        uniform == null -> null
        widths.any { it != uniform } -> null
        uniform !in VALID_TRACE_HASH_SIZES -> null
        else -> traceHashModeForSize(uniform)
    }
}

/**
 * The hash size (bytes) to adopt after an [input] edit: when the radio [supportsOverride] and the codes
 * share one valid trace width differing from [current], returns that width so the paste parses instead of
 * failing as invalid; otherwise returns [current] unchanged. Mirrors Swift `adoptHashSize(forPastedCodes:)`.
 */
fun adoptedTraceHashSize(input: String, current: Int, supportsOverride: Boolean): Int {
    if (!supportsOverride) return current
    val mode = inferredTraceHashMode(input) ?: return current
    val size = traceHashSize(mode)
    return if (size != current) size else current
}

internal fun Char.isHexDigit(): Boolean = this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
