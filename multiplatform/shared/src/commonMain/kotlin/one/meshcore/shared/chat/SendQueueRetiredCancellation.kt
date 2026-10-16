package one.meshcore.shared.chat

import kotlinx.coroutines.CancellationException

internal class SendQueueRetiredCancellation : CancellationException("Send queue retired")

internal fun Throwable.isSendQueueRetirement(): Boolean = this is CancellationException && hasRetirementCause()

private fun Throwable.hasRetirementCause(): Boolean {
    val visited = mutableListOf<Throwable>()
    var current: Throwable? = this
    while (current != null && visited.size < MAX_RETIREMENT_CAUSE_DEPTH && visited.none { it === current }) {
        if (current is SendQueueRetiredCancellation) return true
        visited += current
        current = current.cause
    }
    return false
}

private const val MAX_RETIREMENT_CAUSE_DEPTH = 32
