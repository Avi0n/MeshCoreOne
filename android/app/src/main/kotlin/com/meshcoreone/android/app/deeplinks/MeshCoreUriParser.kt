// PortedFrom: MC1/Utilities/MeshCoreURLParser.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Sources/MC1Services/Utilities/MeshCoreURIQuery.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.app.deeplinks

import com.meshcoreone.android.core.model.ProtocolLimits
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.bytes.utf8Prefix
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.services.contacts.ChannelService
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

object MeshCoreUriParser {
    const val SCHEME = "meshcore"

    fun parse(uriText: String): MeshCoreDeepLink? =
        parseMap(uriText) ?: parseContact(uriText) ?: parseChannel(uriText)

    fun parseContact(uriText: String): MeshCoreDeepLink.Contact? {
        val uri = uri(uriText) ?: return null
        if (!uri.hasRoute("contact", "/add")) return null
        val items = queryItems(uri) ?: return null
        val name = PercentEncoding.formDecode(items.raw("name")) ?: return null
        val publicKey = items.raw("public_key")?.let(PercentEncoding::decodePathComponent)?.let(Bytes::parseHex)
        if (name.isEmpty() || publicKey == null || publicKey.size != ProtocolLimits.PUBLIC_KEY_SIZE) return null
        val rawType = items.raw("type")
            ?.let(PercentEncoding::decodePathComponent)
            ?.toIntOrNull()
            ?.takeIf { it in UByte.MIN_VALUE.toInt()..UByte.MAX_VALUE.toInt() }
            ?.toUByte()
        return MeshCoreDeepLink.Contact(
            name = name,
            publicKey = publicKey,
            contactType = rawType?.let(ContactType::fromRawValue) ?: ContactType.CHAT,
        )
    }

    fun parseChannel(uriText: String): MeshCoreDeepLink.Channel? {
        val uri = uri(uriText) ?: return null
        if (!uri.hasRoute("channel", "/add")) return null
        val items = queryItems(uri) ?: return null
        val name = PercentEncoding.formDecode(items.raw("name")) ?: return null
        if (name.isEmpty()) return null
        val regionScope = normalizedRegionScope(PercentEncoding.formDecode(items.raw("region_scope")))
        val secretValue = items.raw("secret")
        if (!secretValue.isNullOrEmpty()) {
            val secret = PercentEncoding.decodePathComponent(secretValue)?.let(Bytes::parseHex)
            if (secret == null || secret.size != ProtocolLimits.CHANNEL_SECRET_SIZE) return null
            return MeshCoreDeepLink.Channel(
                name,
                secret,
                regionScope,
                hasHashtagSecretMismatch(name, secret),
            )
        }
        if (!name.startsWith('#')) return null
        val body = name.drop(1)
        if (!HashtagDeepLinkSupport.isValidName(body)) return null
        val normalized = "#" + body.lowercase(Locale.ROOT)
        return MeshCoreDeepLink.Channel(normalized, ChannelService.hashSecret(normalized), regionScope)
    }

    fun parseMap(uriText: String): MeshCoreDeepLink.Map? {
        val uri = uri(uriText) ?: return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true) || !uri.host.equals("map", ignoreCase = true)) return null
        val items = queryItems(uri) ?: return null
        val latitude = items.raw("lat")?.let(PercentEncoding::decodePathComponent)?.let(::decimalDegree) ?: return null
        val longitude = items.raw("lon")?.let(PercentEncoding::decodePathComponent)?.let(::decimalDegree) ?: return null
        if (latitude !in -90.0..90.0 || longitude !in -180.0..180.0) return null
        return MeshCoreDeepLink.Map(latitude, longitude)
    }

    fun hasHashtagSecretMismatch(name: String, secret: Bytes): Boolean {
        if (!name.startsWith('#')) return false
        val body = name.drop(1)
        if (!HashtagDeepLinkSupport.isValidName(body)) return false
        return secret != ChannelService.hashSecret("#" + body.lowercase(Locale.ROOT))
    }

    fun normalizedRegionScope(value: String?): String? {
        val trimmed = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return trimmed.utf8Prefix(ProtocolLimits.MAX_DEFAULT_FLOOD_SCOPE_NAME_BYTES).ifEmpty { null }
    }

    private fun uri(text: String): URI? = try {
        URI(text)
    } catch (_: URISyntaxException) {
        null
    }

    private fun URI.hasRoute(host: String, path: String): Boolean =
        scheme.equals(SCHEME, ignoreCase = true) && this.host.equals(host, ignoreCase = true) && this.path == path

    private fun queryItems(uri: URI): List<Pair<String, String?>>? {
        val query = uri.rawQuery ?: return null
        return query.split('&').filter { it.isNotEmpty() }.map { item ->
            val equals = item.indexOf('=')
            if (equals < 0) item to null else item.substring(0, equals) to item.substring(equals + 1)
        }
    }

    private fun List<Pair<String, String?>>.raw(name: String): String? =
        firstOrNull { (rawName) -> PercentEncoding.decodePathComponent(rawName) == name }?.second

    private fun decimalDegree(value: String): Double? =
        value.takeIf { DECIMAL_DEGREES.matches(it) }?.toDoubleOrNull()

    private val DECIMAL_DEGREES = Regex("^-?[0-9]{1,3}(\\.[0-9]+)?$")
}
