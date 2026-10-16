package one.meshcore.shared.connection

/** Highest TCP port number, and the floor above the reserved "no port" value 0. */
private const val PORT_MIN = 1
private const val PORT_MAX = 65_535
private const val IPV4_OCTET_COUNT = 4
private const val IPV4_OCTET_MIN = 0
private const val IPV4_OCTET_MAX = 255
private const val HOSTNAME_MAX_LENGTH = 253
private const val HOSTNAME_LABEL_MAX_LENGTH = 63

/**
 * Validates WiFi connection fields before a connect/save attempt, mirroring Swift
 * `WiFiAddressFields`: a dotted-quad IPv4 address or a DNS hostname, and a port that parses
 * to a positive 16-bit value.
 */
object WifiAddressValidator {
    /**
     * iOS `WiFiAddressFields` rewrites a comma to a period as the user types, because some
     * keyboards emit a comma for the decimal key and a dotted-quad otherwise never parses.
     */
    fun sanitizeHostInput(raw: String): String = raw.replace(",", ".")

    fun isValidHost(raw: String): Boolean {
        val host = raw.trim()
        if (host.isEmpty()) return false
        return if (looksLikeIPv4(host)) isValidIPv4(host) else isValidHostname(host)
    }

    fun isValidIPv4(address: String): Boolean {
        val parts = address.split(".")
        if (parts.size != IPV4_OCTET_COUNT) return false
        return parts.all { part ->
            val num = part.toIntOrNull()
            num != null && num in IPV4_OCTET_MIN..IPV4_OCTET_MAX
        }
    }

    fun isValidPort(port: String): Boolean {
        val num = port.toIntOrNull() ?: return false
        return num in PORT_MIN..PORT_MAX
    }

    private fun looksLikeIPv4(host: String): Boolean {
        val parts = host.split(".")
        if (parts.isEmpty()) return false
        return parts.all { part -> part.isNotEmpty() && part.all { it in '0'..'9' } }
    }

    private fun isValidHostname(host: String): Boolean {
        val candidate = if (host.endsWith(".")) host.dropLast(1) else host
        if (candidate.length !in 1..HOSTNAME_MAX_LENGTH) return false
        val labels = candidate.split(".")
        return labels.isNotEmpty() && labels.all { isValidHostnameLabel(it) }
    }

    private fun isValidHostnameLabel(label: String): Boolean {
        if (label.length !in 1..HOSTNAME_LABEL_MAX_LENGTH) return false
        val first = label.first()
        val last = label.last()
        if (first == '-' || last == '-') return false
        return label.all { ch ->
            ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9' || ch == '-'
        }
    }
}
