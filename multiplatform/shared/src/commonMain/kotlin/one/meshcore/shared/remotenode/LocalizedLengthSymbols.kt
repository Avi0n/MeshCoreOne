package one.meshcore.shared.remotenode

/**
 * Abbreviated length unit words for road / altitude / scale-bar readouts. Latin defaults match the
 * iOS `Measurement` abbreviated tokens in en and most Western locales; ru/uk/zh supply localized
 * words from compose resources.
 */
data class LocalizedLengthSymbols(val kilometer: String, val meter: String, val mile: String, val foot: String) {
    companion object {
        val LATIN = LocalizedLengthSymbols(
            kilometer = "km",
            meter = "m",
            mile = "mi",
            foot = "ft",
        )
    }
}
