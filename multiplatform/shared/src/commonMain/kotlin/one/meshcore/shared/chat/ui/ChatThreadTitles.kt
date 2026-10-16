package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.channelDisplayName
import one.meshcore.shared.contact.displayName

/** iOS `ChatConversationType.navigationTitle`: DM nickname/name, else channel display name, else the route fallback. */
internal fun threadHeaderTitle(
    contact: ContactDto?,
    channel: ChannelDto?,
    fallback: String,
    unnamedChannelName: (Int) -> String,
): String = contact?.displayName?.takeIf { it.isNotEmpty() }
    ?: channel?.let { channelDisplayName(it.name, it.index, unnamedChannelName) }?.takeIf { it.isNotEmpty() }
    ?: fallback

/**
 * The channel the open thread keeps after Channel Info dismisses. A fetch miss leaves the previous
 * snapshot so a transient store read cannot blank the header or flood-scope push.
 */
internal fun channelAfterInfoDismiss(current: ChannelDto, fetched: ChannelDto?): ChannelDto = fetched ?: current
