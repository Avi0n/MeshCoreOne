package one.meshcore.shared.chat

import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.yield
import one.meshcore.shared.model.Uuid
import kotlin.coroutines.resume

/** One in-flight wait for a render write that can honor or retire [messageID]. */
internal class ScrollRenderWaiter(val messageID: Uuid, val token: Uuid, val resume: () -> Unit)

/**
 * Suspends until a render write can honor or retire a scroll to [messageID]. An id that is already
 * indexed, or history that has already ended, returns without replacing the installed waiter.
 * Cancellation drops this wait. A later register resumes the waiter it replaces.
 */
internal suspend fun ChatCoordinator.waitForHonorableScrollRender(messageID: Uuid) {
    if (renderState.itemIndexByID[messageID] != null || !renderState.hasMoreMessages) return
    suspendCancellableCoroutine { continuation ->
        val token = Uuid.random()
        val replaced = scrollRenderWaiter
        val once = OnceResume(continuation)
        scrollRenderWaiter = ScrollRenderWaiter(messageID, token, once::resume)
        replaced?.let(::scheduleScrollRenderResume)
        continuation.invokeOnCancellation { finishScrollRenderWait(token) }
    }
}

/**
 * Resumes the installed waiter when this write grew the fetched count, ended history, or indexed
 * its message. The resume is a later turn so a count published before the matching prepend is not
 * observed before those rows exist.
 */
internal fun ChatCoordinator.resumeScrollRenderWaiterIfHonorable(previous: ChatRenderState, updated: ChatRenderState) {
    val waiter = scrollRenderWaiter ?: return
    val countGrew = updated.totalFetchedCount > previous.totalFetchedCount
    val historyEnded = previous.hasMoreMessages && !updated.hasMoreMessages
    val targetIndexed = previous.itemIndexByID[waiter.messageID] == null &&
        updated.itemIndexByID[waiter.messageID] != null
    if (!countGrew && !historyEnded && !targetIndexed) return
    scrollRenderWaiter = null
    scheduleScrollRenderResume(waiter)
}

private fun ChatCoordinator.finishScrollRenderWait(token: Uuid) {
    val waiter = scrollRenderWaiter ?: return
    if (waiter.token != token) return
    scrollRenderWaiter = null
}

private fun ChatCoordinator.scheduleScrollRenderResume(waiter: ScrollRenderWaiter) {
    scope.launch {
        yield()
        waiter.resume()
    }
}

/** Resumes its continuation at most once. */
private class OnceResume(private val continuation: CancellableContinuation<Unit>) {
    private var pending = true

    fun resume() {
        if (!pending) return
        pending = false
        if (continuation.isActive) continuation.resume(Unit)
    }
}
