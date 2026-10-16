package one.meshcore.shared.transport

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The controller rejected a Write Command because it cannot accept another yet. */
class WriteBusyException(cause: Throwable? = null) : Exception("GATT write request busy", cause)

/** The controller never became ready within the iOS `writeTimeout` backstop. */
class WriteWithoutResponseTimeoutException(timeout: Duration, cause: Throwable? = null) :
    Exception("Write-without-response readiness timeout after ${timeout.inWholeSeconds}s", cause)

/**
 * Parks a Write Command until the controller can accept it, the Kotlin analog of iOS
 * `awaitWriteWithoutResponseReadiness`. Android has no `canSendWriteWithoutResponse` callback, so a
 * busy write is retried until [timeout] (iOS default 5s). The first attempt is immediate, matching
 * `alreadyReady`.
 */
object WriteWithoutResponseReadiness {
    val TIMEOUT: Duration = 5.seconds
    val RETRY_DELAY: Duration = 5.milliseconds

    suspend fun write(timeout: Duration = TIMEOUT, retryDelay: Duration = RETRY_DELAY, attempt: suspend () -> Unit) {
        val started = TimeSource.Monotonic.markNow()
        while (true) {
            try {
                attempt()
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: WriteBusyException) {
                if (started.elapsedNow() >= timeout) {
                    throw WriteWithoutResponseTimeoutException(timeout, e)
                }
                delay(retryDelay)
            }
        }
    }
}
