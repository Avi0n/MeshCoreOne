// PortedFrom: MC1/Utilities/MeshCoreURLParser.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1/Views/Chats/HashtagDeeplinkSupport.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.feature.chats.list

import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.model.ContactType
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

data class ContactLinkResult(val name: String, val publicKey: Bytes, val contactType: ContactType)
data class ChannelLinkResult(val name: String, val secret: Bytes, val regionScope: String? = null)
data class MapLinkResult(val latitude: Double, val longitude: Double)

/** Parsing seam; WP-405 owns the production `MeshCoreURLParser`, this mirror keeps the router testable. */
interface MeshCoreLinkParsing {
    fun parseMapURL(url: String): MapLinkResult?
    fun parseContactURL(url: String): ContactLinkResult?
    fun parseChannelURL(url: String): ChannelLinkResult?
}

/** Hashtag deeplink helpers (`meshcoreone://hashtag/<name>`), a mirror of `HashtagDeeplinkSupport`. */
object HashtagLinks {
    const val SCHEME = "meshcoreone"
    const val HOST = "hashtag"

    fun channelNameFromUrl(url: URI): String? {
        if (url.scheme != SCHEME || url.host != HOST) return null
        return (url.rawPath ?: "").split('/').filter { it.isNotEmpty() }.firstOrNull()?.let { decode(it) }
    }

    fun isValidHashtagName(name: String): Boolean {
        if (name.isEmpty()) return false
        return name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' } &&
            (name[0] != '-')
    }

    fun fullChannelName(rawName: String): String? {
        val lowered = rawName.lowercase(Locale.ROOT)
        val normalized = if (lowered.startsWith("#")) lowered.drop(1) else lowered
        return if (isValidHashtagName(normalized)) "#$normalized" else null
    }

    fun findChannelByName(name: String, channels: List<ChannelDTO>): ChannelDTO? =
        channels.firstOrNull { ChatTextMatching.caseInsensitiveEquals(it.name, name) }

    private fun decode(segment: String): String = try {
        URLDecoder.decode(segment.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        segment
    }
}

/** Mirror of `MeshCoreURLParser` (map, contact and channel `meshcore://` links). */
object MeshCoreLinkParser : MeshCoreLinkParsing {
    const val SCHEME = "meshcore"
    private const val PUBLIC_KEY_SIZE = 32
    private const val CHANNEL_SECRET_SIZE = 16

    private fun uri(text: String): URI? = try {
        URI(text)
    } catch (_: URISyntaxException) {
        null
    }

    /** Raw (still percent-encoded) query items in order; first occurrence wins. */
    private fun queryItems(url: URI): List<Pair<String, String?>> =
        (url.rawQuery ?: "").split('&').filter { it.isNotEmpty() }.map {
            val index = it.indexOf('=')
            if (index < 0) it to null else it.substring(0, index) to it.substring(index + 1)
        }

    private fun List<Pair<String, String?>>.raw(name: String): String? = firstOrNull { it.first == name }?.second

    private fun percentDecoded(value: String): String? = try {
        URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    } catch (_: IllegalArgumentException) {
        null
    }

    /** Raw `+` is a space; `%2B` is a plus; undecodable input stays as-is (with spaces). */
    private fun formDecoded(value: String?): String {
        val spaced = (value ?: "").replace("+", " ")
        return percentDecoded(spaced) ?: spaced
    }

    override fun parseMapURL(url: String): MapLinkResult? {
        val parsed = uri(url) ?: return null
        if (parsed.scheme != SCHEME || parsed.host != "map") return null
        val items = queryItems(parsed)
        val latitude = decimalDegree(items.raw("lat")?.let(::percentDecoded)) ?: return null
        val longitude = decimalDegree(items.raw("lon")?.let(::percentDecoded)) ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return MapLinkResult(latitude, longitude)
    }

    override fun parseContactURL(url: String): ContactLinkResult? {
        val parsed = uri(url) ?: return null
        if (parsed.scheme != SCHEME || parsed.host != "contact" || parsed.path != "/add") return null
        val items = queryItems(parsed)
        if (items.isEmpty()) return null
        val name = formDecoded(items.raw("name"))
        val key = Bytes.parseHex(items.raw("public_key")?.let(::percentDecoded) ?: "")
        if (name.isEmpty() || key == null || key.size != PUBLIC_KEY_SIZE) return null
        val typeValue = items.raw("type")?.let(::percentDecoded)?.let(::plainInt) ?: 1
        val type = typeValue.takeIf { it in 0..255 }?.let { ContactType.fromRawValue(it.toUByte()) } ?: ContactType.CHAT
        return ContactLinkResult(name, key, type)
    }

    override fun parseChannelURL(url: String): ChannelLinkResult? {
        val parsed = uri(url) ?: return null
        if (parsed.scheme != SCHEME || parsed.host != "channel" || parsed.path != "/add") return null
        val items = queryItems(parsed)
        if (items.isEmpty()) return null
        val name = formDecoded(items.raw("name"))
        if (name.isEmpty()) return null
        val regionScope = normalizedRegionScope(formDecoded(items.raw("region_scope")))
        val secretRaw = items.raw("secret")?.let(::percentDecoded)
        if (!secretRaw.isNullOrEmpty()) {
            val secret = Bytes.parseHex(secretRaw)
            if (secret == null || secret.size != CHANNEL_SECRET_SIZE) return null
            return ChannelLinkResult(name, secret, regionScope)
        }
        if (!name.startsWith("#")) return null
        val body = name.drop(1)
        if (!HashtagLinks.isValidHashtagName(body)) return null
        val normalized = "#" + body.lowercase(Locale.ROOT)
        return ChannelLinkResult(normalized, hashSecret(normalized), regionScope)
    }

    private fun hashSecret(passphrase: String): Bytes =
        Bytes(MessageDigest.getInstance("SHA-256").digest(passphrase.toByteArray(Charsets.UTF_8)).copyOf(CHANNEL_SECRET_SIZE))

    private fun plainInt(text: String): Int? = text.toIntOrNull()

    /** Plain decimal degrees only: rejects hex floats, inf and nan (`^-?[0-9]{1,3}(\.[0-9]+)?$`). */
    private fun decimalDegree(value: String?): Double? {
        if (value == null) return null
        val body = value.removePrefix("-")
        val parts = body.split('.')
        val whole = parts[0]
        val fraction = parts.getOrNull(1)
        val wholeOk = whole.length in 1..3 && whole.all { it in '0'..'9' }
        val fractionOk = fraction == null && parts.size == 1 || (parts.size == 2 && !fraction.isNullOrEmpty() && fraction.all { it in '0'..'9' })
        return if (wholeOk && fractionOk) value.toDoubleOrNull() else null
    }

    /** Trims, caps to the flood-scope name byte limit on a grapheme boundary; blank becomes null. */
    fun normalizedRegionScope(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty()) return null
        return trimmed.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES).ifEmpty { null }
    }
}
