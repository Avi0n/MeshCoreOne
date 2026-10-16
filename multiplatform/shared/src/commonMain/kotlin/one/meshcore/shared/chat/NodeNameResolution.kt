package one.meshcore.shared.chat

/**
 * Whether a name resolution is an exact public-key match, a proximity fallback within a hash-prefix
 * collision set, or no match at all. Mirrors the Swift `NodeNameMatchKind`.
 */
enum class NodeNameMatchKind {
    EXACT,
    FALLBACK,
    UNRESOLVED,
}

/**
 * Resolution result for a sender node name: the resolved display name plus the confidence level, so the
 * caller can tell an exact identity from a proximity-based guess. [unverifiedNickname] is non-null only on
 * the channel-sender path, where identity is matched by name alone. Mirrors the Swift `NodeNameResolution`.
 */
data class NodeNameResolution(
    val displayName: String,
    val matchKind: NodeNameMatchKind,
    val unverifiedNickname: String? = null,
) {
    val isFallback: Boolean get() = matchKind == NodeNameMatchKind.FALLBACK
}
