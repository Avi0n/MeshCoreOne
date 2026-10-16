package one.meshcore.shared.chat.ui

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.channel.ChannelFloodScopeResolver
import one.meshcore.shared.channel.FloodScope
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.pathHopCount

/**
 * The conversation header's second line, resolved before any string lookup so the choice is testable. A
 * channel reports the flood region its broadcasts are scoped to; a direct message reports how the next
 * message travels.
 */
internal sealed interface ThreadHeaderSubtitle {
    /** No second line: a channel whose flood scope resolves to no region. */
    data object None : ThreadHeaderSubtitle

    /** A channel's effective flood region; [isDeviceDefault] marks the radio's own default scope. */
    data class Region(val name: String, val isDeviceDefault: Boolean) : ThreadHeaderSubtitle

    /** A direct message with no out-path set: every message floods the mesh. */
    data object FloodRouting : ThreadHeaderSubtitle

    /**
     * A direct message routed over a stored out-path of [hops] hops. "Direct" names the routing mode, not a
     * zero-hop claim — a direct route can still cross repeaters.
     */
    data class Direct(val hops: Int) : ThreadHeaderSubtitle
}

/**
 * Picks the header subtitle for the open conversation. Mirrors the Swift
 * `ChatConversationType.navigationSubtitle(deviceDefaultFloodScopeName:)`, which builds a line for both
 * conversation kinds. The region resolution delegates to [ChannelFloodScopeResolver] so the header matches
 * the flood scope actually pushed to the radio.
 */
internal fun threadHeaderSubtitle(
    channel: ChannelDto?,
    contact: ContactDto?,
    deviceDefaultFloodScopeName: String?,
): ThreadHeaderSubtitle = when {
    channel != null -> channelRegionSubtitle(channel, deviceDefaultFloodScopeName)
    contact == null -> ThreadHeaderSubtitle.None
    contact.isFloodRouted -> ThreadHeaderSubtitle.FloodRouting
    else -> ThreadHeaderSubtitle.Direct(contact.pathHopCount)
}

private fun channelRegionSubtitle(channel: ChannelDto, deviceDefaultFloodScopeName: String?): ThreadHeaderSubtitle {
    val resolved = ChannelFloodScopeResolver.resolve(
        channelFloodScope = channel.floodScope,
        deviceDefaultFloodScopeName = deviceDefaultFloodScopeName,
        supportsUnscopedFloodSend = false,
    )
    val regionName = ((resolved as? ResolvedFloodScope.Scope)?.scope as? FloodScope.Region)?.name
        ?: return ThreadHeaderSubtitle.None
    return ThreadHeaderSubtitle.Region(regionName, isDeviceDefault = regionName == deviceDefaultFloodScopeName)
}
