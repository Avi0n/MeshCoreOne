package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.model.Uuid

/** The result of a channel create/join write, shared by every flow that writes a slot then reads it back. */
internal sealed interface ChannelWriteOutcome {
    /**
     * The radio acked the write. [channel] is the row read back from the store, or null when a best-effort
     * read-back missed (matching the iOS `try?` fetch that still completes the flow with a nil channel).
     */
    data class Written(val channel: ChannelDto?) : ChannelWriteOutcome

    /** The write threw; [error] carries the localized surface. */
    data class Failed(val error: ChannelActionError) : ChannelWriteOutcome
}

/**
 * Runs a channel write ([block]) then reads the resulting `(radioID, slot)` row back, carrying a thrown
 * [ChannelServiceError] as [ChannelActionError.Service] so the UI layer localizes it the way the iOS
 * sheets render `error.userFacingMessage`. Cancellation propagates. Shared so create/join/scan/confirmation
 * flows cannot drift in how they translate a failed device round-trip.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend inline fun ChannelService.runChannelWrite(
    radioID: Uuid,
    slot: Int,
    block: ChannelService.() -> Unit,
): ChannelWriteOutcome = try {
    block()
    ChannelWriteOutcome.Written(getChannel(radioID, slot))
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    val serviceError = error as? ChannelServiceError ?: ChannelServiceError.SessionError(error)
    ChannelWriteOutcome.Failed(ChannelActionError.Service(serviceError))
}
