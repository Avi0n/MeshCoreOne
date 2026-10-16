package one.meshcore.shared.channel

import kotlinx.coroutines.CancellationException

/**
 * A channel clear or delete shows a failed write in the modal error. Cancellation leaves the busy
 * flag set and propagates. Any other failure, including a thrown error, becomes a session error.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runChannelSlotCommand(
    begin: () -> Unit,
    fail: (ChannelActionError) -> Unit,
    finish: () -> Unit,
    command: suspend () -> Unit,
) {
    begin()
    try {
        command()
        finish()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: ChannelServiceError) {
        fail(ChannelActionError.Service(error))
    } catch (error: Throwable) {
        fail(ChannelActionError.Service(ChannelServiceError.SessionError(error)))
    }
}
