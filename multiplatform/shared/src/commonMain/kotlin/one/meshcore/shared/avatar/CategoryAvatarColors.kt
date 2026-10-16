package one.meshcore.shared.avatar

/**
 * Explicit per-category avatar colors, used to pin the channel / repeater / room avatars to fixed
 * values (the System / default theme's historical swatches). A gamut theme leaves this absent and
 * derives its three category colors from its [IdentityGamut] instead. Mirrors Swift `CategoryAvatarColors`.
 */
data class CategoryAvatarColors(val channel: RgbColor, val repeater: RgbColor, val room: RgbColor) {
    fun colorFor(category: AvatarCategory): RgbColor = when (category) {
        AvatarCategory.CHANNEL -> channel
        AvatarCategory.REPEATER -> repeater
        AvatarCategory.ROOM -> room
    }

    companion object {
        // The System theme's fixed legacy swatches (Theme.default.categoryAvatarOverride).
        private const val CHANNEL_ARGB = 0xFF336688.toInt()
        private const val REPEATER_ARGB = 0xFF00AAFF.toInt()
        private const val ROOM_ARGB = 0xFFFF8800.toInt()

        /** The neutral-default fixed category swatches, matching the iOS System theme. */
        val SYSTEM_DEFAULT = CategoryAvatarColors(
            channel = RgbColor.fromArgb(CHANNEL_ARGB),
            repeater = RgbColor.fromArgb(REPEATER_ARGB),
            room = RgbColor.fromArgb(ROOM_ARGB),
        )
    }
}
