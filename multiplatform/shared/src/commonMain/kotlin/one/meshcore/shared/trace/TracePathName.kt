package one.meshcore.shared.trace

/** Longest hex prefix used for the no-names fallback save name, matching Swift `fullPathString.prefix(8)`. */
private const val FALLBACK_HEX_PREFIX = 8

/**
 * A default save name for a trace path from its resolved hop names, mirroring Swift `generatePathName`:
 * no names → the `prefix` template over the first [FALLBACK_HEX_PREFIX] hex chars of [fallbackHex]; one
 * name → that name; two → `twoEndpoints`; three or more → `multipleEndpoints` over the first and last.
 *
 * The three localized templates are passed in so this stays free of Compose resources and unit-testable.
 */
fun generateTracePathName(
    resolvedNames: List<String>,
    fallbackHex: String,
    prefix: (String) -> String,
    twoEndpoints: (String, String) -> String,
    multipleEndpoints: (String, String) -> String,
): String = when (resolvedNames.size) {
    0 -> prefix(fallbackHex.take(FALLBACK_HEX_PREFIX))
    1 -> resolvedNames[0]
    2 -> twoEndpoints(resolvedNames[0], resolvedNames[1])
    else -> multipleEndpoints(resolvedNames[0], resolvedNames[resolvedNames.size - 1])
}
