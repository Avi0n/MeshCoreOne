package one.meshcore.shared.chat.text

/**
 * The `meshcoreone://mention/<name>` deep link a mention span installs and a mention tap consumes.
 * Ported from the iOS `MentionDeeplinkSupport`; centralising the scheme, host, and encoding keeps the
 * producer and consumer from diverging, which would route a tap to the wrong contact.
 */
object MentionDeeplink {
    const val SCHEME: String = "meshcoreone"
    const val HOST: String = "mention"

    /**
     * `meshcoreone://mention/<percent-encoded-name>` for a mention run, or null when [name] is empty or
     * cannot be encoded. `/` is excluded from the allowed set so a name containing a slash stays one path
     * component instead of splitting the path.
     */
    fun url(name: String): String? {
        val encoded = percentEncodePathComponent(name)
        if (encoded.isEmpty()) return null
        return "$SCHEME://$HOST/$encoded"
    }

    /**
     * The decoded mention name if [url] is a mention deep link, else null. Reads the still-encoded path
     * and decodes it exactly once; splitting on a decoded `/` is avoided so a name with a slash survives.
     */
    fun name(url: String): String? {
        val parts = parseMeshLink(url)
        if (parts == null || parts.scheme != SCHEME || parts.host != HOST) return null
        val encoded = parts.rawPath.removePrefix("/")
        return if (encoded.isEmpty()) null else percentDecodeOnce(encoded)?.ifEmpty { null }
    }
}
