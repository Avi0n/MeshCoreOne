package one.meshcore.shared.navigation

import one.meshcore.shared.channel.ChannelSecret
import one.meshcore.shared.chat.text.HashtagUtilities
import one.meshcore.shared.chat.text.percentDecodeOnce
import one.meshcore.shared.contact.utf8BytePrefix
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.decodeHex
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.remotenode.MAX_REGION_NAME_BYTES

/**
 * A parsed `meshcore://` deep link. Each variant is produced only after full validation; any malformed
 * or out-of-range input yields `null` from the parser rather than a partially-built value.
 */
sealed interface MeshCoreDeepLink {
    /**
     * `meshcore://channel/add?name=&secret=&region_scope=` — [secret] is exactly 16 bytes, derived from
     * the name for a secretless `#name` link. [regionScope] is an optional app-side flood-scope region
     * preference, not a radio field.
     */
    class Channel(val name: String, val secret: ByteArray, val regionScope: String? = null) : MeshCoreDeepLink {
        /** Uppercase hex of the secret, the channel's stable identity (matches the iOS `ChannelResult.id`). */
        val id: String get() = secret.toUppercaseHex()

        /**
         * True when [name] is a valid hashtag shape but [secret] is not the public name-hash. Callers may
         * warn; they must not refuse the join on this alone.
         */
        val hasHashtagSecretMismatch: Boolean get() = MeshCoreUrlParser.hasHashtagSecretMismatch(name, secret)

        override fun equals(other: Any?): Boolean = this === other ||
            (
                other is Channel &&
                    name == other.name &&
                    regionScope == other.regionScope &&
                    secret.contentEquals(other.secret)
                )

        override fun hashCode(): Int = 31 * (31 * name.hashCode() + secret.contentHashCode()) + regionScope.hashCode()
    }

    /** `meshcore://contact/add?name=&public_key=&type=` — [publicKey] is exactly 32 bytes. */
    class Contact(val name: String, val publicKey: ByteArray, val type: ContactType) : MeshCoreDeepLink {
        override fun equals(other: Any?): Boolean = this === other ||
            (
                other is Contact &&
                    name == other.name &&
                    type == other.type &&
                    publicKey.contentEquals(other.publicKey)
                )

        override fun hashCode(): Int = 31 * (31 * name.hashCode() + publicKey.contentHashCode()) + type.hashCode()
    }

    /** `meshcore://map?lat=&lon=` — plain decimal degrees within range. */
    data class Map(val latitude: Double, val longitude: Double) : MeshCoreDeepLink

    /**
     * `meshcore://send?kind=contact|channel&id=<uuid|composite|name>&text=` — a Shortcuts/App-Action send.
     * [text] is non-empty. [targetLabel] is the spoken or displayed name when the host supplied one.
     * Length and live-radio checks belong to the enqueue path.
     */
    data class SendMessage(val kind: Kind, val targetId: String, val text: String, val targetLabel: String? = null) :
        MeshCoreDeepLink {
        enum class Kind { CONTACT, CHANNEL }
    }

    /**
     * `meshcore://advert?reach=zeroHop|flood` — a Shortcuts/App-Action self-advert.
     * Missing [reach] is zero-hop so a bare invocation never floods the mesh.
     */
    data class SendAdvert(val flood: Boolean) : MeshCoreDeepLink
}

/**
 * Parses `meshcore://` deep links, ported byte-for-byte from the iOS `MeshCoreURLParser`. Every form
 * returns `null` on any validation failure and never throws.
 *
 * Two quirks are load-bearing and preserved:
 * - Contact and channel values use MeshCore form decoding: raw `+` is a space, while `%2B` is a
 *   literal plus. Other deep-link forms retain percent-only decoding.
 * - `lat`/`lon` must be plain decimal degrees ([DECIMAL_DEGREE_PATTERN]); hex floats, `inf`, and `nan`
 *   are rejected even though a bare double parse would accept them and feed the map or an open-in-maps.
 */
object MeshCoreUrlParser {
    const val SCHEME = "meshcore"

    /** Channel secret length in bytes (128-bit shared secret). */
    const val CHANNEL_SECRET_SIZE_BYTES = 16

    /** Public key length in bytes, mirroring the iOS `ProtocolLimits.publicKeySize`. */
    const val PUBLIC_KEY_SIZE_BYTES = 32

    private const val HASHTAG_PREFIX = '#'
    private const val SEND_HOST = "send"
    private const val ADVERT_HOST = "advert"
    private const val ADVERT_REACH_ZERO_HOP = "zerohop"
    private const val ADVERT_REACH_FLOOD = "flood"
    private val UUID_PATTERN = Regex(
        "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$",
    )

    /** Default contact type when `type` is missing or out of range: `chat` (raw 1). */
    private val DEFAULT_CONTACT_TYPE = ContactType.CHAT

    private const val MIN_LATITUDE = -90.0
    private const val MAX_LATITUDE = 90.0
    private const val MIN_LONGITUDE = -180.0
    private const val MAX_LONGITUDE = 180.0

    private val DECIMAL_DEGREE_PATTERN = Regex("""^-?\d{1,3}(\.\d+)?$""")

    /** Tries map, then contact, then channel — the order the iOS `ChatLinkRouter` dispatches in. */
    fun parse(string: String): MeshCoreDeepLink? = parseMapUrl(string)
        ?: parseSendMessageUrl(string)
        ?: parseSendAdvertUrl(string)
        ?: parseContactUrl(string)
        ?: parseChannelUrl(string)

    /**
     * Parses a `meshcore://channel/add` URL. Secret rules (an explicit secret always wins over the
     * name-hash), ported from the iOS parser:
     * - Valid 16-byte hex `secret`: use the bytes as-is; never re-derive from the name.
     * - Missing/empty `secret` with a `#` name: normalize the hashtag and derive
     *   `ChannelSecret.hashSecret(name)`. Reject invalid hashtag bodies.
     * - Missing/empty `secret` without a leading `#`: fail (bare names are not hashtags).
     * - Present but invalid `secret`: fail; do not fall back to the name-hash.
     */
    fun parseChannelUrl(string: String): MeshCoreDeepLink.Channel? {
        val url = ParsedUrl.parse(string) ?: return null
        if (url.scheme != SCHEME || url.host != "channel" || url.path != "/add") return null
        val query = url.formQueryItems ?: return null
        val name = query.firstValue("name").orEmpty()
        if (name.isEmpty()) return null
        return channelFromQuery(name, query)
    }

    private fun channelFromQuery(name: String, query: List<Pair<String, String>>): MeshCoreDeepLink.Channel? {
        fun withSecret(secretRaw: String, regionScope: String?): MeshCoreDeepLink.Channel? {
            val secret = decodeHex(secretRaw)
            if (secret == null || secret.size != CHANNEL_SECRET_SIZE_BYTES) return null
            return MeshCoreDeepLink.Channel(name = name, secret = secret, regionScope = regionScope)
        }

        fun fromHashtag(regionScope: String?): MeshCoreDeepLink.Channel? {
            if (name.firstOrNull() != HASHTAG_PREFIX) return null
            val body = name.drop(1)
            if (!HashtagUtilities.isValidHashtagName(body)) return null
            val normalizedName = HASHTAG_PREFIX + body.lowercase()
            return MeshCoreDeepLink.Channel(
                name = normalizedName,
                secret = ChannelSecret.hashSecret(normalizedName),
                regionScope = regionScope,
            )
        }

        val regionScope = normalizedRegionScope(query.firstValue("region_scope"))
        val secretRaw = query.firstValue("secret")
        if (!secretRaw.isNullOrEmpty()) return withSecret(secretRaw, regionScope)
        return fromHashtag(regionScope)
    }

    /** True when [name] is a valid hashtag shape and [secret] is not the public name-hash. */
    fun hasHashtagSecretMismatch(name: String, secret: ByteArray): Boolean {
        if (name.firstOrNull() != HASHTAG_PREFIX) return false
        val body = name.drop(1)
        if (!HashtagUtilities.isValidHashtagName(body)) return false
        val expected = ChannelSecret.hashSecret(HASHTAG_PREFIX + body.lowercase())
        return !secret.contentEquals(expected)
    }

    /**
     * Trims whitespace and caps to the flood-scope name byte limit. Empty after trim/cap becomes null so a
     * join never persists a blank region preference.
     */
    private fun normalizedRegionScope(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        val capped = trimmed.utf8BytePrefix(MAX_REGION_NAME_BYTES)
        return capped.ifEmpty { null }
    }

    fun parseContactUrl(string: String): MeshCoreDeepLink.Contact? {
        val url = ParsedUrl.parse(string) ?: return null
        if (url.scheme != SCHEME || url.host != "contact" || url.path != "/add") return null
        val query = url.formQueryItems ?: return null

        val name = query.firstValue("name") ?: ""
        val publicKey = decodeHex(query.firstValue("public_key") ?: "")
        if (name.isEmpty() || publicKey == null || publicKey.size != PUBLIC_KEY_SIZE_BYTES) return null

        val typeValue = query.firstValue("type")?.toIntOrNull() ?: DEFAULT_CONTACT_TYPE.raw
        val contactType =
            if (typeValue in 0..UByte.MAX_VALUE.toInt()) {
                ContactType.fromRaw(typeValue) ?: DEFAULT_CONTACT_TYPE
            } else {
                DEFAULT_CONTACT_TYPE
            }

        return MeshCoreDeepLink.Contact(name = name, publicKey = publicKey, type = contactType)
    }

    /**
     * Parses a `meshcore://send` URL used by the Android Send Message shortcut / App Action.
     * iOS `SendMessageIntent` takes a target entity and a message; this URL is the same pair.
     */
    fun parseSendMessageUrl(string: String): MeshCoreDeepLink.SendMessage? {
        val url = ParsedUrl.parse(string) ?: return null
        if (url.scheme != SCHEME || url.host != SEND_HOST) return null
        val query = url.queryItems ?: return null
        return sendMessageLink(query) { UUID_PATTERN.matches(it) }
    }

    /**
     * Parses a `meshcore://advert` URL used by the Android Send Advert shortcut / App Action.
     * iOS `SendAdvertIntent` takes a reach picker that defaults to zero-hop; this URL is the same pair.
     */
    fun parseSendAdvertUrl(string: String): MeshCoreDeepLink.SendAdvert? {
        val url = ParsedUrl.parse(string) ?: return null
        if (url.scheme != SCHEME || url.host != ADVERT_HOST) return null
        if (url.path.isNotEmpty() && url.path != "/") return null
        val reach = url.queryItems?.firstValue("reach")
        val flood = when (reach?.lowercase()) {
            null, "" -> false
            ADVERT_REACH_ZERO_HOP -> false
            ADVERT_REACH_FLOOD -> true
            else -> return null
        }
        return MeshCoreDeepLink.SendAdvert(flood = flood)
    }

    fun parseMapUrl(string: String): MeshCoreDeepLink.Map? {
        val url = ParsedUrl.parse(string) ?: return null
        if (url.scheme != SCHEME || url.host != "map") return null
        val query = url.queryItems ?: return null

        val latitude = decimalDegree(query.firstValue("lat")) ?: return null
        val longitude = decimalDegree(query.firstValue("lon")) ?: return null
        if (latitude !in MIN_LATITUDE..MAX_LATITUDE || longitude !in MIN_LONGITUDE..MAX_LONGITUDE) return null

        return MeshCoreDeepLink.Map(latitude = latitude, longitude = longitude)
    }

    private fun decimalDegree(value: String?): Double? {
        if (value == null || !DECIMAL_DEGREE_PATTERN.matches(value)) return null
        return value.toDoubleOrNull()
    }
}

internal fun sendMessageLink(
    query: List<Pair<String, String>>,
    isUuid: (String) -> Boolean,
): MeshCoreDeepLink.SendMessage? {
    val kind = sendMessageKind(query.firstValue("kind"))
    val name = query.firstValue("name")?.takeIf { it.isNotEmpty() }
    val rawId = query.firstValue("id")?.takeIf { it.isNotEmpty() }
    val targetId = kind?.let { sendMessageTargetId(rawId, name, isUuid) }
    val text = query.firstValue("text")?.takeIf { it.isNotEmpty() }
    return if (kind != null && targetId != null && text != null) {
        MeshCoreDeepLink.SendMessage(kind = kind, targetId = targetId, text = text, targetLabel = name)
    } else {
        null
    }
}

internal fun sendMessageKind(rawKind: String?): MeshCoreDeepLink.SendMessage.Kind? = when (rawKind?.lowercase()) {
    "contact" -> MeshCoreDeepLink.SendMessage.Kind.CONTACT
    "channel" -> MeshCoreDeepLink.SendMessage.Kind.CHANNEL
    else -> null
}

/** A UUID is stored normalized. Any other id, including a composite id, is kept as written. */
internal fun sendMessageTargetId(rawId: String?, name: String?, isUuid: (String) -> Boolean): String? = when {
    rawId != null && isUuid(rawId) -> Uuid.normalized(rawId).value
    rawId != null && parseCompositeId(rawId) != null -> rawId
    rawId != null -> rawId
    name != null -> name
    else -> null
}

/**
 * A minimal `scheme://host/path?query` decomposition. commonMain has no URL type, and the grammar is
 * narrow enough that hand-parsing avoids a dependency while preserving the two decode quirks above.
 */
private class ParsedUrl private constructor(
    val scheme: String,
    val host: String,
    val path: String,
    val queryItems: List<Pair<String, String>>?,
    val formQueryItems: List<Pair<String, String>>?,
) {
    companion object {
        fun parse(string: String): ParsedUrl? {
            val schemeSplit = string.indexOf("://")
            if (schemeSplit <= 0) return null
            val scheme = string.substring(0, schemeSplit)
            val rest = string.substring(schemeSplit + "://".length)

            val queryStart = rest.indexOf('?')
            val authorityAndPath = if (queryStart >= 0) rest.substring(0, queryStart) else rest
            val rawQuery = if (queryStart >= 0) rest.substring(queryStart + 1) else null

            val pathStart = authorityAndPath.indexOf('/')
            val host = if (pathStart >= 0) authorityAndPath.substring(0, pathStart) else authorityAndPath
            val path = if (pathStart >= 0) authorityAndPath.substring(pathStart) else ""

            // A present-but-undecodable query (malformed percent-escape) invalidates the whole URL.
            val queryItems = rawQuery?.let { decodeQueryItems(it, plusAsSpace = false) }
            val formQueryItems = rawQuery?.let { decodeQueryItems(it, plusAsSpace = true) }
            val queryValid = rawQuery == null || queryItems != null
            return if (queryValid) {
                ParsedUrl(
                    scheme = scheme,
                    host = host,
                    path = path,
                    queryItems = queryItems,
                    formQueryItems = formQueryItems,
                )
            } else {
                null
            }
        }

        private fun decodeQueryItems(query: String, plusAsSpace: Boolean): List<Pair<String, String>>? =
            query.split('&').map { pair ->
                val eq = pair.indexOf('=')
                val rawKey = if (eq >= 0) pair.substring(0, eq) else pair
                val rawValue = if (eq >= 0) pair.substring(eq + 1) else ""
                val key = percentDecodeOnce(rawKey) ?: return null
                val value = percentDecodeOnce(
                    if (plusAsSpace) rawValue.replace('+', ' ') else rawValue,
                ) ?: return null
                key to value
            }
    }
}

private fun List<Pair<String, String>>.firstValue(name: String): String? = firstOrNull { it.first == name }?.second
