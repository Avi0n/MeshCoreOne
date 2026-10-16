package one.meshcore.android

import org.junit.Assert.assertEquals
import org.junit.Test

class CompanionAssociationAttemptGateTest {
    @Test
    fun activeAttemptAcceptsItsCallbackOnce() {
        val gate = CompanionAssociationAttemptGate()
        val attempt = gate.begin()

        assertEquals(CompanionAssociationAttemptGate.Completion.ACCEPTED, gate.complete(attempt))
        assertEquals(CompanionAssociationAttemptGate.Completion.INACTIVE, gate.complete(attempt))
    }

    @Test
    fun cancelledAttemptRejectsLateCallbacks() {
        val gate = CompanionAssociationAttemptGate()
        val attempt = gate.begin()

        assertEquals(attempt, gate.cancel())
        assertEquals(CompanionAssociationAttemptGate.Completion.CANCELLED, gate.complete(attempt))
    }

    @Test
    fun staleAttemptCannotCompleteANewerAttempt() {
        val gate = CompanionAssociationAttemptGate()
        val stale = gate.begin()
        val current = gate.begin()

        assertEquals(CompanionAssociationAttemptGate.Completion.INACTIVE, gate.complete(stale))
        assertEquals(CompanionAssociationAttemptGate.Completion.ACCEPTED, gate.complete(current))
    }

    @Test
    fun consecutiveCancellationsRetainBothLateCallbacks() {
        val gate = CompanionAssociationAttemptGate()
        val first = gate.begin()
        gate.cancel()
        val second = gate.begin()
        gate.cancel()

        assertEquals(CompanionAssociationAttemptGate.Completion.CANCELLED, gate.complete(first))
        assertEquals(CompanionAssociationAttemptGate.Completion.CANCELLED, gate.complete(second))
    }

    @Test
    fun cancelWithoutAnActiveAttemptIsANoOp() {
        assertEquals(null, CompanionAssociationAttemptGate().cancel())
    }
}
