package one.meshcore.shared.room

import kotlinx.coroutines.CancellationException

/**
 * A room load, send, or retry shows the failure on the conversation. Cancellation propagates and
 * leaves the in-flight flag set. Every other failure, including a thrown error, is reported.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runRoomConversation(onFailure: (Throwable) -> Unit, command: suspend () -> Unit) {
    try {
        command()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        onFailure(error)
    }
}
