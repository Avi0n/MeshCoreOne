package one.meshcore.shared.channel

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid

/**
 * The decrypt-cache sink [ChannelService] feeds after every channel write and sync so captured packets
 * can be decrypted against the current channel list. Mirrors the Swift `RxLogService.updateChannels(from:)`
 * dependency. [NoOpChannelListSink] lets the service run when no RX-log decrypt cache is installed.
 */
fun interface ChannelListSink {
    /** Forwards the current channel list for the radio whose channels just changed. */
    suspend fun updateChannels(channels: List<ChannelDto>)
}

/** A [ChannelListSink] that drops the update. */
object NoOpChannelListSink : ChannelListSink {
    override suspend fun updateChannels(channels: List<ChannelDto>) = Unit
}

/**
 * Fired when a slot's occupant changes (vacated, or rewritten with a different secret) so the host can
 * drop per-slot drafts, cached timelines, and an open chat. Mirrors the Swift
 * `slotOccupantChangedHandler` installed by AppState.
 */
fun interface ChannelSlotOccupantChanged {
    /** The [slots] on [radioID] whose occupant just changed. */
    suspend fun onOccupantChanged(radioID: Uuid, slots: Set<Int>)
}

/** A [ChannelSlotOccupantChanged] that drops the notification. */
object NoOpChannelSlotOccupantChanged : ChannelSlotOccupantChanged {
    override suspend fun onOccupantChanged(radioID: Uuid, slots: Set<Int>) = Unit
}
