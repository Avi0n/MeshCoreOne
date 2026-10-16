package one.meshcore.android

import androidx.annotation.MainThread

/** Rejects callbacks from a cancelled or superseded Companion Device Manager association attempt. */
@MainThread
internal class CompanionAssociationAttemptGate {
    private var nextAttempt = 0L
    private var activeAttempt: Long? = null
    private val cancelledAttempts = mutableListOf<Long>()

    enum class Completion {
        ACCEPTED,
        CANCELLED,
        INACTIVE,
    }

    fun begin(): Long {
        val attempt = ++nextAttempt
        activeAttempt = attempt
        return attempt
    }

    fun isActive(attempt: Long): Boolean = activeAttempt == attempt

    fun complete(attempt: Long): Completion {
        if (attempt in cancelledAttempts) return Completion.CANCELLED
        if (!isActive(attempt)) return Completion.INACTIVE
        activeAttempt = null
        return Completion.ACCEPTED
    }

    fun cancel(): Long? {
        val attempt = activeAttempt ?: return null
        activeAttempt = null
        cancelledAttempts += attempt
        if (cancelledAttempts.size > MAX_RETAINED_CANCELLED_ATTEMPTS) {
            cancelledAttempts.removeAt(0)
        }
        return attempt
    }

    private companion object {
        const val MAX_RETAINED_CANCELLED_ATTEMPTS = 8
    }
}
