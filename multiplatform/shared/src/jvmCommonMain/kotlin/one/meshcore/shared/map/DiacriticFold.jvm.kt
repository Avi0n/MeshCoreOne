package one.meshcore.shared.map

import java.text.Normalizer

/**
 * NFD-decompose the string and strip the combining marks it splits accents into, leaving the base
 * letters. One actual for both JVM targets (Android + desktop); `java.text.Normalizer` is present on
 * both, matching the single-actual pattern used for the crypto seams.
 */
actual fun foldDiacritics(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFD)
    .replace(COMBINING_MARKS, "")

private val COMBINING_MARKS = Regex("\\p{Mn}+")
