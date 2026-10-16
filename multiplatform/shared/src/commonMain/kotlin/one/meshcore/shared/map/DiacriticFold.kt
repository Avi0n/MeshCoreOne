package one.meshcore.shared.map

/**
 * Diacritic-insensitive folding of a string (e.g. "Córdoba" → "cordoba"), the multiplatform stand-in
 * for iOS `String.folding(options: .diacriticInsensitive)`. Used by [RegionResolver] to normalize
 * geocoded administrative names before matching. The JVM actual uses Unicode NFD decomposition; there
 * is no common-Kotlin equivalent, and both real targets (Android + desktop) are JVM.
 */
expect fun foldDiacritics(value: String): String

/**
 * Case- and diacritic-insensitive substring match, the Kotlin stand-in for iOS
 * `String.localizedStandardContains`. Lowercases with the Unicode default first so Turkish İ folds
 * to i after [foldDiacritics] strips the combining dot.
 */
fun String.containsIgnoringCaseAndDiacritics(query: String): Boolean =
    foldDiacritics(lowercase()).contains(foldDiacritics(query.lowercase()))
