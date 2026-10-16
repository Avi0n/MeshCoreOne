package one.meshcore.shared.chat

/**
 * One outgoing radio send. Cancellation is a failed send: [onFailure] marks the row failed and
 * rethrows the same instance, including a thrown error.
 */
@Suppress("TooGenericExceptionCaught")
internal suspend fun <T> runOutgoingSend(onFailure: suspend (Throwable) -> Nothing, block: suspend () -> T): T = try {
    block()
} catch (error: Throwable) {
    onFailure(error)
}
