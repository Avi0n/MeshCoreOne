package one.meshcore.android

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.connection.CompanionAssociateResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * A cancelled companion chooser is rethrown after the attempt is cancelled. Any other failure,
 * including a thrown error, completes as a failed association and is not retried.
 */
class CompanionAssociationSessionFaultTest {
    @Test
    fun aCancelledCompanionAssociateIsRethrown() = runBlocking {
        val stopped = CancellationException("stopped")
        var reads = 0
        val state = CompanionRegistryState {
            reads += 1
            throw stopped
        }
        val session = session(state)

        try {
            session.associate()
            fail("expected cancellation")
        } catch (thrown: CancellationException) {
            assertSame(stopped, thrown)
        }

        assertEquals(1, reads)
        assertNull(state.attemptGate.cancel())
    }

    @Test
    fun aCompanionAssociateErrorIsAFailure() = runBlocking {
        var reads = 0
        val state = CompanionRegistryState {
            reads += 1
            throw CompanionAssociateProbeError()
        }
        val session = session(state)

        val result = session.associate()

        assertEquals(CompanionAssociateResult.Failed("probe"), result)
        assertEquals(1, reads)
        assertNull(state.attemptGate.cancel())
    }

    private fun session(state: CompanionRegistryState): CompanionAssociationSession {
        val catalog = CompanionAssociationCatalog(state)
        return CompanionAssociationSession(
            state = state,
            catalog = catalog,
            presence = CompanionPresenceObserver(state, catalog),
            registerChooser = false,
        )
    }
}

private class CompanionAssociateProbeError : Error("probe")
