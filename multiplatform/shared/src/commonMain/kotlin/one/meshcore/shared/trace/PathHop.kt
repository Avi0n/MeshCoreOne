package one.meshcore.shared.trace

/**
 * One hop in the outbound trace path. [hashBytes] is the fixed-width hop hash actually placed on the
 * wire (1/2/4 bytes, uniform across the path); [publicKey] is the full 32-byte key when the hop was
 * resolved from a contact (the source for re-prefixing when the hash width changes); [resolvedName] is
 * the contact display name when known. Mirrors the Swift `PathHop`.
 */
data class PathHop(val hashBytes: ByteArray, val publicKey: ByteArray? = null, val resolvedName: String? = null) {
    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is PathHop &&
                hashBytes.contentEquals(other.hashBytes) &&
                publicKey.contentEqualsNullable(other.publicKey) &&
                resolvedName == other.resolvedName
            )

    override fun hashCode(): Int {
        var result = hashBytes.contentHashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + (resolvedName?.hashCode() ?: 0)
        return result
    }
}

internal fun ByteArray?.contentEqualsNullable(other: ByteArray?): Boolean = when {
    this == null && other == null -> true
    this == null || other == null -> false
    else -> contentEquals(other)
}
