package one.meshcore.shared.chat

import one.meshcore.shared.backup.ChannelDto

/** Channel name whose threads never render inline map thumbnails, so the app cannot flood the tile API. */
private const val MAP_PREVIEW_SUPPRESSED_CHANNEL_NAME = "wardriving"

/**
 * Whether map thumbnails are hidden for this conversation regardless of the global show-map-previews
 * setting. A DM (null channel) never suppresses. The match is case-insensitive and tolerates the leading
 * hashtag convention, so both `wardriving` and `#wardriving` suppress. Mirrors the Swift
 * `ChatConversationType.suppressesMapPreviews`.
 */
fun suppressesMapPreviews(channel: ChannelDto?): Boolean {
    val trimmed = channel?.name?.trim() ?: return false
    val normalized = trimmed.removePrefix(HASHTAG_CHANNEL_PREFIX)
    return normalized.equals(MAP_PREVIEW_SUPPRESSED_CHANNEL_NAME, ignoreCase = true)
}
