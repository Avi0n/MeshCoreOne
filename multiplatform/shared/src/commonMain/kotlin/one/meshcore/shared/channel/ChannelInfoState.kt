package one.meshcore.shared.channel

import one.meshcore.shared.model.ChannelFloodScope
import one.meshcore.shared.model.NotificationLevel

/** The channel notification-level picker options, mirroring the iOS channel levels. */
val CHANNEL_NOTIFICATION_LEVELS: List<NotificationLevel> =
    listOf(NotificationLevel.MUTED, NotificationLevel.MENTIONS_ONLY, NotificationLevel.ALL)

/** Editable state for channel preferences, destructive actions, and region discovery. */
data class ChannelInfoState(
    val notificationLevel: NotificationLevel,
    val isPinned: Boolean,
    val floodScope: ChannelFloodScope,
    /** Live known-region snapshot so Channel Info refreshes while its nested manager is still open. */
    val knownRegions: List<String> = emptyList(),
    val isDeleting: Boolean = false,
    val isClearingMessages: Boolean = false,
    /** Nearby-region discovery in flight. */
    val isDiscoveringRegions: Boolean = false,
    /** Status line after a region discover attempt. */
    val discoveryMessage: String? = null,
    /** Newly discovered region names awaiting the user's multi-select. */
    val discoveredRegions: List<String>? = null,
    val error: ChannelActionError? = null,
) {
    val isActionInProgress: Boolean get() = isDeleting || isClearingMessages
}
