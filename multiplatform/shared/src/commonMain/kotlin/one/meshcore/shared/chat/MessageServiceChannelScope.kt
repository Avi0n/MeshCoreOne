package one.meshcore.shared.chat

import one.meshcore.shared.channel.ChannelFloodScopeResolver
import one.meshcore.shared.channel.FloodScope
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.channel.UNSCOPED_FLOOD_SEND_MIN_FIRMWARE_VERSION
import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.Uuid

private const val FLOOD_SCOPE_MIN_FIRMWARE_VERSION = 8

internal suspend fun MessageService.sendChannelMessageWithScope(
    text: String,
    channelIndex: Int,
    radioID: Uuid,
    timestamp: Long,
) {
    val (channel, device) = store.fetchChannelSendConfiguration(radioID, channelIndex)
    val scope = ChannelFloodScopeResolver.resolve(
        channelFloodScope = channel?.floodScope ?: ChannelFloodScope.Inherit,
        deviceDefaultFloodScopeName = device?.defaultFloodScopeName,
        supportsUnscopedFloodSend = (device?.firmwareVersion ?: 0) >= UNSCOPED_FLOOD_SEND_MIN_FIRMWARE_VERSION,
    )
    if (device != null &&
        device.firmwareVersion < FLOOD_SCOPE_MIN_FIRMWARE_VERSION &&
        scope == ResolvedFloodScope.Scope(FloodScope.Disabled)
    ) {
        session.sendChannelMessage(channelIndex, text, timestamp)
    } else {
        session.sendChannelMessage(channelIndex, text, timestamp, scope)
    }
}
