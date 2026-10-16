package one.meshcore.shared.navigation

import one.meshcore.shared.model.toUppercaseHex

/**
 * Percent-encodes a query-item value the way Apple `URLComponents.queryItems` does, except `&` and `=`.
 * Those two become `%26` and `%3D` so a value cannot inject another query item. `?` and `:` stay literal.
 * A literal `+` becomes `%2B`.
 */
internal fun percentEncodeQueryValue(value: String): String {
    val builder = StringBuilder(value.length)
    for (byte in value.encodeToByteArray()) {
        val code = byte.toInt() and BYTE_MASK
        val char = code.toChar()
        val literal = code < ASCII_LIMIT &&
            (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in QUERY_VALUE_ALLOWED_ASCII)
        if (literal) {
            builder.append(char)
        } else {
            builder.append('%')
            builder.append(byteArrayOf(byte).toUppercaseHex())
        }
    }
    return builder.toString()
}

/** ASCII left literal in a query-item value: the URL query-allowed set minus `&` and `=`. */
private const val QUERY_VALUE_ALLOWED_ASCII = "!$'()*,-./:;?@_~"

private const val ASCII_LIMIT = 0x80
private const val BYTE_MASK = 0xFF
