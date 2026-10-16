package one.meshcore.shared.pathedit

import one.meshcore.shared.model.toUppercaseHex

/**
 * One hop of a routing path being edited, ported from the iOS `PathManagementViewModel.PathHop`.
 *
 * [hashBytes] is the public-key prefix actually serialized onto the wire (1-3 bytes for the current
 * hash mode). [publicKey] is the full 32-byte key when the hop resolved to a known node; its presence
 * is what lets the editor re-slice a hop to a different hash width without data loss (see
 * [PathEditLogic.normalizeHop]). [resolvedName] is the contact/discovered name when resolved.
 *
 * Distinct from `chat.pathdetail.PathHop` (raw slice + hex, for display of an already-set path) and
 * `trace.PathHop`; this is the editable model that also carries the full key and resolved name.
 */
class PathHop(val hashBytes: ByteArray, val publicKey: ByteArray? = null, val resolvedName: String? = null) {
    /** Uppercase hex of [hashBytes] — the monospaced id shown for the hop. */
    val hashHex: String get() = hashBytes.toUppercaseHex()

    /** `"$name ($hashHex)"` when resolved, else just the hex prefix. */
    val displayText: String get() = resolvedName?.let { "$it ($hashHex)" } ?: hashHex

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PathHop) return false
        return hashBytes.contentEquals(other.hashBytes) &&
            contentEqualsNullable(publicKey, other.publicKey) &&
            resolvedName == other.resolvedName
    }

    override fun hashCode(): Int {
        var result = hashBytes.contentHashCode()
        result = 31 * result + (publicKey?.contentHashCode() ?: 0)
        result = 31 * result + (resolvedName?.hashCode() ?: 0)
        return result
    }

    override fun toString(): String = "PathHop(hashHex=$hashHex, resolved=$resolvedName)"

    private fun contentEqualsNullable(a: ByteArray?, b: ByteArray?): Boolean =
        if (a == null || b == null) a === b else a.contentEquals(b)
}
