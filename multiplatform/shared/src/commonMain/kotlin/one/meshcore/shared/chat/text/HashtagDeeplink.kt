package one.meshcore.shared.chat.text

/**
 * The `meshcoreone://hashtag/<channel>` deep link a hashtag span installs and a hashtag tap consumes.
 * Ported from the iOS `HashtagDeeplinkSupport`. Parsing and secret derivation belong to the join flow
 * ([ChannelSecret][one.meshcore.shared.channel.ChannelSecret]); this centralises the scheme/host and
 * the name normalization the producer and consumer share.
 */
object HashtagDeeplink {
    const val SCHEME: String = "meshcoreone"
    const val HOST: String = "hashtag"

    /** `meshcoreone://hashtag/<normalizedName>` for a hashtag run, or null when [normalizedName] is empty. */
    fun url(normalizedName: String): String? {
        if (normalizedName.isEmpty()) return null
        return "$SCHEME://$HOST/${percentEncodePathComponent(normalizedName)}"
    }

    /** The channel name (first path component, percent-decoded) if [url] is a hashtag link, else null. */
    fun channelNameFromUrl(url: String): String? {
        val parts = parseMeshLink(url) ?: return null
        if (parts.scheme != SCHEME || parts.host != HOST) return null
        val encoded = parts.rawPath.removePrefix("/").substringBefore('/')
        if (encoded.isEmpty()) return null
        return percentDecodeOnce(encoded)?.ifEmpty { null }
    }

    /**
     * The full `#name` channel name for a raw tapped name, or null when the normalized name is invalid.
     * Both the channel name and its hashed passphrase carry this `#`, so callers must not strip it before
     * deriving the secret (see [ChannelSecret][one.meshcore.shared.channel.ChannelSecret]).
     */
    fun fullChannelName(rawName: String): String? {
        val normalized = HashtagUtilities.normalizeHashtagName(rawName)
        if (!HashtagUtilities.isValidHashtagName(normalized)) return null
        return "#$normalized"
    }
}
