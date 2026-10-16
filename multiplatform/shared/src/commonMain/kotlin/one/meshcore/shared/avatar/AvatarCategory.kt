package one.meshcore.shared.avatar

/**
 * The three avatar categories that get one fixed color per theme (no per-entity variation): channels in
 * the Chats list, and repeater or room-server nodes in the Nodes list. Keeping all three here ensures
 * their deterministic colors remain mutually distinct.
 *
 * [gamutSeed] is a sentinel string fed to [IdentityGamut] so each category resolves to a distinct,
 * on-theme color; the prefix keeps it from colliding with a real contact or channel name.
 */
enum class AvatarCategory(val gamutSeed: String) {
    CHANNEL("__avatar_category_channel__"),
    REPEATER("__avatar_category_repeater__"),
    ROOM("__avatar_category_room__"),
    ;

    companion object {
        /**
         * Order the category avatars claim gamut anchors in. On a collision the later category steps to
         * the next free anchor, so earlier categories keep their preferred color. A channel and a room
         * can share a Chats list, so their colors must stay distinct.
         */
        val anchorPriority: List<AvatarCategory> = listOf(CHANNEL, REPEATER, ROOM)
    }
}
