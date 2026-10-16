package one.meshcore.shared.channel.ui

import one.meshcore.shared.backup.ChannelDto

/** Which glyph a [ChannelAvatar] shows, mirroring the iOS `ChannelAvatar` symbol choice. */
enum class ChannelAvatarGlyph { PUBLIC, HASHTAG, PRIVATE }

/**
 * The glyph a channel avatar renders: globe for the public channel (slot 0), number for a hashtag
 * channel, lock otherwise. Pure port of the iOS `ChannelAvatar` `systemName` selection.
 */
fun channelAvatarGlyph(channel: ChannelDto): ChannelAvatarGlyph = when {
    channel.index == 0 -> ChannelAvatarGlyph.PUBLIC
    channel.name.startsWith("#") -> ChannelAvatarGlyph.HASHTAG
    else -> ChannelAvatarGlyph.PRIVATE
}
