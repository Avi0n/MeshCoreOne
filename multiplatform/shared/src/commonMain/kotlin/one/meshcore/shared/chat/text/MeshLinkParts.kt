package one.meshcore.shared.chat.text

import one.meshcore.shared.model.toUppercaseHex

/**
 * A minimal `scheme://host/rawPath` split for the app's own `meshcoreone://` deep links. commonMain has
 * no URL type; `meshcore://` links route through [one.meshcore.shared.navigation.MeshCoreUrlParser], but
 * the mention and hashtag links need the still-encoded path (percent-decoded exactly once by the caller),
 * which a query-decoding parser would mangle. Query and fragment are dropped before the host/path split.
 */
internal data class MeshLinkParts(val scheme: String, val host: String, val rawPath: String)

internal fun parseMeshLink(url: String): MeshLinkParts? {
    val schemeSplit = url.indexOf("://")
    if (schemeSplit <= 0) return null
    val scheme = url.substring(0, schemeSplit)
    val rest = url.substring(schemeSplit + SCHEME_SEPARATOR_LENGTH)
    val cut = rest.indexOfFirst { it == '?' || it == '#' }
    val authorityAndPath = if (cut >= 0) rest.substring(0, cut) else rest
    val slash = authorityAndPath.indexOf('/')
    val host = if (slash >= 0) authorityAndPath.substring(0, slash) else authorityAndPath
    val rawPath = if (slash >= 0) authorityAndPath.substring(slash) else ""
    return MeshLinkParts(scheme, host, rawPath)
}

private const val SCHEME_SEPARATOR_LENGTH = 3
private const val HEX_RADIX = 16
private const val HIGH_NIBBLE_SHIFT = 4
private const val HEX_PER_BYTE = 2
private const val ASCII_LIMIT = 0x80
private const val BYTE_MASK = 0xFF

/** ASCII characters left literal by Apple `CharacterSet.urlPathAllowed` minus `/`: the encoding set for a
 * single path component that must not split on a slash. */
private const val PATH_ALLOWED_MINUS_SLASH =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._~!$&'()*+,;=:@"

/**
 * Percent-encodes [value]'s UTF-8 into a single URL path component, escaping every byte outside the
 * url-path-allowed-minus-`/` set as uppercase `%XX`. Matches Apple `addingPercentEncoding(withAllowed:)`
 * so a name encoded here decodes to the identical string the iOS producer emitted.
 */
internal fun percentEncodePathComponent(value: String): String {
    val builder = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and BYTE_MASK
        val char = code.toChar()
        if (code < ASCII_LIMIT && char in PATH_ALLOWED_MINUS_SLASH) {
            builder.append(char)
        } else {
            builder.append('%')
            builder.append(byteArrayOf(byte).toUppercaseHex())
        }
    }
    return builder.toString()
}

/**
 * Percent-decodes [value] exactly once (Apple `removingPercentEncoding`): `%XX` becomes its byte, every
 * other character — including a literal `+` — is preserved. Returns null on a malformed `%` escape. A
 * literal `%2F` decodes to `%2F` here only when it was itself encoded as `%252F`, so the round trip keeps
 * a real slash, a percent sign, and a literal escape distinct. Deep-link query items use this same decode;
 * a form-encoded `+` is turned into a space by the caller before this runs.
 */
internal fun percentDecodeOnce(value: String): String? {
    val bytes = ArrayList<Byte>(value.length)
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c == '%') {
            val hi = value.getOrNull(i + 1)?.digitToIntOrNull(HEX_RADIX)
            val lo = value.getOrNull(i + HEX_PER_BYTE)?.digitToIntOrNull(HEX_RADIX)
            if (hi == null || lo == null) return null
            bytes.add(((hi shl HIGH_NIBBLE_SHIFT) or lo).toByte())
            i += 1 + HEX_PER_BYTE
        } else {
            c.toString().encodeToByteArray().forEach { bytes.add(it) }
            i += 1
        }
    }
    return bytes.toByteArray().decodeToString()
}
