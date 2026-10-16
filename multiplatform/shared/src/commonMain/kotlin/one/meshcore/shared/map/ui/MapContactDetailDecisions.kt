package one.meshcore.shared.map.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactPingResult
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.pingAnnouncement

internal fun mapContactRadioHint(isRadioReady: Boolean, hint: String): String? = if (isRadioReady) null else hint

internal fun mapSheetActionEnabled(isRadioReady: Boolean, busy: Boolean): Boolean = isRadioReady && !busy

internal fun mapFavoriteActionText(isFavorite: Boolean, removeLabel: String, addLabel: String): String =
    if (isFavorite) removeLabel else addLabel

internal fun <T> mapIconUnlessBusy(busy: Boolean, icon: T): T? = if (busy) null else icon

internal fun mapTimestampSeconds(timestamp: Long?, skipNonPositive: Boolean): Long? {
    val seconds = timestamp ?: return null
    return if (skipNonPositive && seconds <= 0L) null else seconds
}

internal fun announcePingResult(
    result: ContactPingResult?,
    successTemplate: String,
    failureMessage: String,
    announce: (String) -> Unit,
) {
    val live = result ?: return
    announce(
        pingAnnouncement(
            result = live,
            successTemplate = successTemplate,
            failureMessage = failureMessage,
        ),
    )
}

internal data class MapActionCallbacks(
    val onStarted: () -> Unit,
    val onSuccess: () -> Unit,
    val onFailure: (Throwable) -> Unit,
    val onFinished: () -> Unit,
)

/**
 * A missing service must not mark the row busy. Cancellation still clears the busy flag, then rethrows
 * so the coroutine scope can cancel the click.
 */
@Suppress("TooGenericExceptionCaught")
internal fun launchMapContactAction(
    apply: (suspend () -> Unit)?,
    scope: CoroutineScope,
    callbacks: MapActionCallbacks,
): Job? {
    val action = apply ?: return null
    callbacks.onStarted()
    return scope.launch {
        try {
            action()
            callbacks.onSuccess()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            callbacks.onFailure(error)
        } finally {
            callbacks.onFinished()
        }
    }
}

internal data class MapZeroHopPing(
    val ping: (suspend (ContactDto) -> ContactPingResult)?,
    val isPinging: Boolean,
    val contact: ContactDto,
    val onStart: () -> Unit,
    val onResult: (ContactPingResult) -> Unit,
)

internal fun launchMapZeroHopPing(request: MapZeroHopPing, scope: CoroutineScope): Job? {
    val ping = request.ping
    val job = if (ping != null && !request.isPinging) {
        request.onStart()
        scope.launch { request.onResult(ping(request.contact)) }
    } else {
        null
    }
    return job
}

/** A radio that no longer has the contact still drops the local row. */
internal suspend fun removeMapContactOrLocal(removeRemote: suspend () -> Unit, removeLocal: suspend () -> Unit) {
    try {
        removeRemote()
    } catch (_: ContactServiceError.ContactNotFound) {
        removeLocal()
    }
}
