package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runExceptionFallback

/**
 * One channel-list read. Cancellation is rethrown. An error still propagates. Every other
 * failure returns null so the caller keeps its own empty-list or unavailable policy.
 */
internal suspend fun ChannelService.readChannelsOrNull(radioID: Uuid): List<ChannelDto>? =
    runExceptionFallback({ null }) { getChannels(radioID) }
