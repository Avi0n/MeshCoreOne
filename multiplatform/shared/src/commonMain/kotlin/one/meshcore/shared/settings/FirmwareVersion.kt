package one.meshcore.shared.settings

/**
 * Parses firmware version strings (`v1.12.0`, `1.14`, `v1.13.0-rc1`, and CLI `ver` banners like
 * `MeshCore v1.15.0 (2025-04-18)`) for feature gates. Mirrors iOS `String.isAtLeast(major:minor:)`.
 */
object FirmwareVersion {
    const val REGION_DEFAULT_SCOPE_MIN_MAJOR: Int = 1
    const val REGION_DEFAULT_SCOPE_MIN_MINOR: Int = 15

    /**
     * Whether [versionString] is at least [major].[minor], ignoring patch and prerelease suffix.
     * Malformed or empty strings return false. Unknown firmware is treated as unsupported.
     */
    fun isAtLeast(versionString: String, major: Int, minor: Int): Boolean {
        val parsed = parse(versionString) ?: return false
        return when {
            parsed.first > major -> true
            parsed.first < major -> false
            else -> parsed.second >= minor
        }
    }

    /** `region default` exists on MeshCore repeater v1.15.0+. A missing version is unsupported. */
    fun supportsRegionDefaultScope(versionString: String?): Boolean = isAtLeast(
        versionString.orEmpty(),
        REGION_DEFAULT_SCOPE_MIN_MAJOR,
        REGION_DEFAULT_SCOPE_MIN_MINOR,
    )

    private fun parse(raw: String): Pair<Int, Int>? {
        for (token in versionTokens(raw)) {
            val parts = token.split('.')
            if (parts.size >= 2) {
                val maj = parts[0].toIntOrNull()
                val min = parts[1].toIntOrNull()
                if (maj != null && min != null) return maj to min
            }
        }
        return null
    }

    private fun versionTokens(raw: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        for (ch in raw) {
            if (ch.isDigit() || ch == '.') {
                current.append(ch)
            } else if (current.isNotEmpty()) {
                tokens.add(current.toString())
                current.clear()
            }
        }
        if (current.isNotEmpty()) tokens.add(current.toString())
        return tokens
    }
}
