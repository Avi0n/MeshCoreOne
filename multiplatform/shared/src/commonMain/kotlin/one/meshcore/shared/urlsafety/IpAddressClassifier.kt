package one.meshcore.shared.urlsafety

/**
 * Classifies an IP-literal string as private/reserved or not, a verbatim port of the Swift
 * `URLSafetyChecker.isPrivateOrReserved` / `isPrivateIPv4` / `isPrivateIPv6`. Pure and
 * side-effect free so every adversarial range vector is exercised in [commonTest].
 *
 * Fail-closed direction: the classifier only ever answers "private/reserved" (block) or "not a
 * recognizable IP literal" (defer to DNS). It never answers "public/allow", so a mis-parse can
 * over-defer to DNS but can never turn a private address into an allowed one. The final allow
 * decision lives in [UrlSafetyChecker], after DNS confirms every resolved address is public.
 *
 * The literal parsers mirror `inet_pton`: strict dotted-quad IPv4 (no octal/hex/leading-zero
 * forms) and standard IPv6 including `::` compression and IPv4-mapped tails. Obfuscated forms
 * (decimal `2130706433`, octal `0177.0.0.1`, hex `0x7f000001`) are not IP literals here; they are
 * left for DNS resolution, which re-checks whatever address they resolve to.
 */
object IpAddressClassifier {
    // IPv4 classification on the 32-bit host-order value (matches Swift `isPrivateIPv4`).
    private const val UNSPECIFIED_V4: Int = 0 // 0.0.0.0
    private const val LOOPBACK_V4: Int = 127 // 127.0.0.0/8 (value ushr 24)
    private const val PRIVATE_A_V4: Int = 10 // 10.0.0.0/8 (value ushr 24)
    private const val PRIVATE_B_V4: Int = 0xAC1 // 172.16.0.0/12 (value ushr 20)
    private const val PRIVATE_C_V4: Int = 0xC0A8 // 192.168.0.0/16 (value ushr 16)
    private const val LINK_LOCAL_V4: Int = 0xA9FE // 169.254.0.0/16 (value ushr 16)
    private const val MULTICAST_V4: Int = 0xE // 224.0.0.0/4 (value ushr 28)
    private const val RESERVED_V4: Int = 0xF // 240.0.0.0/4 (value ushr 28)

    private const val SHIFT_OCTET: Int = 8
    private const val SHIFT_SLASH8: Int = 24
    private const val SHIFT_SLASH12: Int = 20
    private const val SHIFT_SLASH16: Int = 16
    private const val SHIFT_SLASH4: Int = 28

    private const val BYTE_MASK: Int = 0xFF
    private const val V4_OCTETS: Int = 4
    private const val V6_BYTES: Int = 16
    private const val V6_GROUPS: Int = 8
    private const val MAX_OCTET: Int = 255
    private const val MAX_OCTET_DIGITS: Int = 3
    private const val HEXTET_MAX_DIGITS: Int = 4

    // IPv6 classification (matches Swift `isPrivateIPv6`).
    private const val V6_LINK_LOCAL_B0: Int = 0xFE // fe80::/10
    private const val V6_LINK_LOCAL_B1_MASK: Int = 0xC0
    private const val V6_LINK_LOCAL_B1: Int = 0x80
    private const val V6_UNIQUE_LOCAL_MASK: Int = 0xFE // fc00::/7
    private const val V6_UNIQUE_LOCAL: Int = 0xFC
    private const val V6_MAPPED_MARKER: Int = 0xFF // ::ffff:0:0/96 (bytes 10..11)
    private const val V6_MAPPED_PREFIX: Int = 10 // leading zero bytes before the 0xffff marker
    private const val V6_MAPPED_V4_OFFSET: Int = 12 // embedded IPv4 starts here (bytes 12..15)
    private const val V6_MAPPED_HEXTETS: Int = 2 // an embedded IPv4 fills the final two hextets
    private const val V6_MULTICAST_B0: Int = 0xFF // ff00::/8

    /**
     * Returns `true` if [address] is a private/reserved IP literal. A string that is not a valid
     * IPv4 or IPv6 literal returns `false` (it will be resolved via DNS by the caller).
     */
    fun isPrivateOrReserved(address: String): Boolean = parseIpv4(address)?.let { isPrivateIpv4(it) }
        ?: parseIpv6(address)?.let { isPrivateIpv6(it) }
        ?: false

    private fun isPrivateIpv4(bytes: ByteArray): Boolean {
        var value = 0
        for (byte in bytes) value = (value shl SHIFT_OCTET) or (byte.toInt() and BYTE_MASK)
        return when {
            value == UNSPECIFIED_V4 -> true
            value ushr SHIFT_SLASH8 == LOOPBACK_V4 -> true
            value ushr SHIFT_SLASH8 == PRIVATE_A_V4 -> true
            value ushr SHIFT_SLASH12 == PRIVATE_B_V4 -> true
            value ushr SHIFT_SLASH16 == PRIVATE_C_V4 -> true
            value ushr SHIFT_SLASH16 == LINK_LOCAL_V4 -> true
            value ushr SHIFT_SLASH4 == MULTICAST_V4 -> true
            value ushr SHIFT_SLASH4 == RESERVED_V4 -> true
            else -> false
        }
    }

    private fun isPrivateIpv6(bytes: ByteArray): Boolean {
        if (bytes.all { it.toInt() == 0 }) return true // ::
        if ((0 until V6_BYTES - 1).all { bytes[it].toInt() == 0 } && bytes[V6_BYTES - 1].toInt() == 1) {
            return true // ::1
        }
        if (bytes[0].toInt() and BYTE_MASK == V6_LINK_LOCAL_B0 &&
            bytes[1].toInt() and V6_LINK_LOCAL_B1_MASK == V6_LINK_LOCAL_B1
        ) {
            return true // fe80::/10
        }
        if (bytes[0].toInt() and V6_UNIQUE_LOCAL_MASK == V6_UNIQUE_LOCAL) return true // fc00::/7
        if ((0 until V6_MAPPED_PREFIX).all { bytes[it].toInt() == 0 } &&
            bytes[V6_MAPPED_PREFIX].toInt() and BYTE_MASK == V6_MAPPED_MARKER &&
            bytes[V6_MAPPED_PREFIX + 1].toInt() and BYTE_MASK == V6_MAPPED_MARKER
        ) {
            val mapped = (V6_MAPPED_V4_OFFSET until V6_BYTES)
                .joinToString(".") { (bytes[it].toInt() and BYTE_MASK).toString() }
            return isPrivateOrReserved(mapped)
        }
        if (bytes[0].toInt() and BYTE_MASK == V6_MULTICAST_B0) return true // ff00::/8
        return false
    }

    private fun parseIpv4(address: String): ByteArray? {
        val parts = address.split('.')
        if (parts.size != V4_OCTETS) return null
        return packedIpv4(parts)
    }

    private fun packedIpv4(parts: List<String>): ByteArray? {
        val out = ByteArray(V4_OCTETS)
        for (i in 0 until V4_OCTETS) {
            out[i] = ipv4Octet(parts[i])?.toByte() ?: return null
        }
        return out
    }

    // Leading zeros are rejected so a dotted quad cannot be read as octal.
    private fun ipv4Octet(part: String): Int? {
        if (part.isEmpty() || part.length > MAX_OCTET_DIGITS) return null
        if (part.length > 1 && part[0] == '0') return null
        val value = decimalOctet(part) ?: return null
        if (value > MAX_OCTET) return null
        return value
    }

    private fun decimalOctet(part: String): Int? {
        var value = 0
        for (char in part) {
            if (char !in '0'..'9') return null
            value = value * DECIMAL_RADIX + (char - '0')
        }
        return value
    }

    /** Parses a standard IPv6 literal (with `::` compression and IPv4-mapped tail). 16 bytes or `null`. */
    private fun parseIpv6(address: String): ByteArray? {
        if (address.isEmpty() || '%' in address) return null // reject zone ids
        var text = address
        val lastColon = text.lastIndexOf(':')
        if (lastColon >= 0 && '.' in text.substring(lastColon + 1)) {
            val embedded = parseIpv4(text.substring(lastColon + 1)) ?: return null
            val hextets = IntArray(V6_MAPPED_HEXTETS) { h ->
                ((embedded[h * 2].toInt() and BYTE_MASK) shl SHIFT_OCTET) or (embedded[h * 2 + 1].toInt() and BYTE_MASK)
            }
            text = text.substring(0, lastColon + 1) +
                hextets[0].toString(HEX_RADIX) + ":" + hextets[1].toString(HEX_RADIX)
        }
        return parseHextets(text)
    }

    private fun parseHextets(text: String): ByteArray? = Hextets.parse(text)

    /**
     * Standard IPv6 hextets, including one `::` compression. A second `::` or an empty group fails closed.
     * The overlap search starts one character into a match so `:::` is two compressions, not one.
     */
    private object Hextets {
        fun parse(text: String): ByteArray? {
            val groups = groups(text) ?: return null
            return bytes(groups)
        }

        private fun groups(text: String): IntArray? {
            val sides = sides(text) ?: return null
            return fill(sides)
        }

        private fun sides(text: String): Sides? {
            val split = splitAtDoubleColon(text) ?: return null
            val left = groupsOf(split.left) ?: return null
            val right = groupsOf(split.right) ?: return null
            return Sides(left, right, split.compressed)
        }

        private fun splitAtDoubleColon(text: String): Split? {
            val at = text.indexOf(DOUBLE_COLON)
            if (at >= 0 && text.indexOf(DOUBLE_COLON, at + 1) >= 0) return null
            return if (at < 0) {
                Split(text, "", compressed = false)
            } else {
                Split(
                    left = text.substring(0, at),
                    right = text.substring(at + DOUBLE_COLON.length),
                    compressed = true,
                )
            }
        }

        private fun groupsOf(text: String): List<String>? {
            if (text.isEmpty()) return emptyList()
            val groups = text.split(':')
            if (groups.any { it.isEmpty() }) return null
            return groups
        }

        private fun fill(sides: Sides): IntArray? {
            if (sides.compressed) return fillCompressed(sides, sides.left.size + sides.right.size)
            if (sides.left.size + sides.right.size != V6_GROUPS) return null
            return writeGroups(sides.left)
        }

        private fun fillCompressed(sides: Sides, total: Int): IntArray? {
            // "::" must cover at least one group.
            if (total > V6_GROUPS - 1) return null
            val groups = IntArray(V6_GROUPS)
            val rightStart = V6_GROUPS - sides.right.size
            if (!writeInto(groups, sides.left, start = 0) || !writeInto(groups, sides.right, rightStart)) {
                return null
            }
            return groups
        }

        private fun writeGroups(parts: List<String>): IntArray? {
            val groups = IntArray(V6_GROUPS)
            if (!writeInto(groups, parts, start = 0)) return null
            return groups
        }

        private fun writeInto(groups: IntArray, parts: List<String>, start: Int): Boolean {
            for ((offset, part) in parts.withIndex()) {
                groups[start + offset] = parseHextet(part) ?: return false
            }
            return true
        }

        private fun bytes(groups: IntArray): ByteArray {
            val out = ByteArray(V6_BYTES)
            for (i in 0 until V6_GROUPS) {
                out[i * 2] = (groups[i] ushr SHIFT_OCTET).toByte()
                out[i * 2 + 1] = (groups[i] and BYTE_MASK).toByte()
            }
            return out
        }

        private class Split(val left: String, val right: String, val compressed: Boolean)

        private class Sides(val left: List<String>, val right: List<String>, val compressed: Boolean)

        private const val DOUBLE_COLON = "::"
    }

    private fun parseHextet(group: String): Int? {
        if (group.length !in 1..HEXTET_MAX_DIGITS) return null
        var value = 0
        for (char in group) {
            val digit = when (char) {
                in '0'..'9' -> char - '0'
                in 'a'..'f' -> char - 'a' + DECIMAL_RADIX
                in 'A'..'F' -> char - 'A' + DECIMAL_RADIX
                else -> return null
            }
            value = value * HEX_RADIX + digit
        }
        return value
    }

    private const val HEX_RADIX: Int = 16
    private const val DECIMAL_RADIX: Int = 10
}
