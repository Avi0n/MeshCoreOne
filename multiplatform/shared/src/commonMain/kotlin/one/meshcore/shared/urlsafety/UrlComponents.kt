package one.meshcore.shared.urlsafety

/**
 * Minimal URL splitter for [UrlSafetyChecker], extracting just the scheme and host the SSRF gate
 * needs. Deliberately not a general URL parser: it fails closed (returns `null`) on anything it
 * cannot decompose unambiguously, so a malformed or hostile URL is treated as unsafe rather than
 * guessed at. Mirrors the scheme/host that Foundation's `URL.scheme` / `URL.host()` expose to the
 * Swift checker: userinfo stripped, port dropped, bracketed IPv6 unwrapped, host percent-decoded.
 */
internal data class UrlComponents(val scheme: String?, val host: String?) {
    companion object {
        fun parse(url: String): UrlComponents? {
            val scheme = schemeName(url) ?: return null
            return componentsFor(url, scheme)
        }

        private fun schemeName(url: String): String? {
            val schemeEnd = url.indexOf(':')
            if (schemeEnd <= 0 || !isValidScheme(url.substring(0, schemeEnd))) return null
            return url.substring(0, schemeEnd)
        }

        private fun componentsFor(url: String, scheme: String): UrlComponents? {
            val afterScheme = url.substringAfter(':')
            // No authority component (for example "javascript:..." or "mailto:...") means no host.
            if (!afterScheme.startsWith(AUTHORITY_PREFIX)) return UrlComponents(scheme, null)
            return withHost(scheme, afterScheme.substring(AUTHORITY_PREFIX.length))
        }

        private fun withHost(scheme: String, authorityAndRest: String): UrlComponents? {
            val host = extractHost(bareAuthority(authorityAndRest)) ?: return UrlComponents(scheme, null)
            val decoded = percentDecode(host) ?: return null
            return UrlComponents(scheme, decoded.ifEmpty { null })
        }

        private fun bareAuthority(authorityAndRest: String): String {
            val cut = authorityAndRest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            val authority = if (cut >= 0) authorityAndRest.substring(0, cut) else authorityAndRest
            val atSign = authority.lastIndexOf('@')
            return if (atSign >= 0) authority.substring(atSign + 1) else authority
        }

        private fun isValidScheme(scheme: String): Boolean {
            if (scheme.isEmpty() || scheme[0] !in ALPHA) return false
            return scheme.all { it in ALPHA || it in '0'..'9' || it == '+' || it == '-' || it == '.' }
        }

        private fun extractHost(authority: String): String? {
            fun bracketed(): String? {
                val close = authority.indexOf(']')
                if (close < 0) return null
                return authority.substring(1, close)
            }

            fun portless(): String? {
                val colon = authority.indexOf(':')
                val host = if (colon >= 0) authority.substring(0, colon) else authority
                return host.ifEmpty { null }
            }

            return if (authority.startsWith('[')) bracketed() else portless()
        }

        /** Decodes `%XX` sequences; returns `null` on a malformed escape (fail closed). */
        private fun percentDecode(text: String): String? {
            if ('%' !in text) return text
            return unescape(text)
        }

        private fun unescape(text: String): String? {
            fun decodedAt(index: Int): Char? {
                if (index + HEX_PAIR_OFFSET >= text.length) return null
                val hi = text[index + 1].digitToIntOrNull(HEX_RADIX) ?: return null
                val lo = text[index + HEX_PAIR_OFFSET].digitToIntOrNull(HEX_RADIX) ?: return null
                return (hi * HEX_RADIX + lo).toChar()
            }

            fun appendStep(builder: StringBuilder, index: Int): Int? {
                if (text[index] != '%') {
                    builder.append(text[index])
                    return 1
                }
                val decoded = decodedAt(index) ?: return null
                builder.append(decoded)
                return ESCAPE_LENGTH
            }

            val builder = StringBuilder(text.length)
            var index = 0
            while (index < text.length) {
                val step = appendStep(builder, index) ?: return null
                index += step
            }
            return builder.toString()
        }

        private val ALPHA = ('a'..'z') + ('A'..'Z')
        private const val HEX_RADIX = 16
        private const val ESCAPE_LENGTH = 3
        private const val HEX_PAIR_OFFSET = 2
        private const val AUTHORITY_PREFIX = "//"
    }
}
