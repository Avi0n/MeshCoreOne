package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto

/**
 * Localized empty-name fallback for a channel, matching iOS `ChannelDTO.displayName`
 * (`L10n.Chats.Chats.Channel.defaultName`). [defaultName] is the formatted "Channel N" string.
 */
fun channelDisplayName(name: String, index: Int, defaultName: (Int) -> String): String =
    name.ifEmpty { defaultName(index) }

fun ChannelDto.displayName(defaultName: (Int) -> String): String = channelDisplayName(name, index, defaultName)
