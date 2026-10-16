package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * Contact-detail action faults that must keep the thrown instance. A service exception still becomes
 * a failed outcome in the existing detail tests.
 */
class ContactDetailActionFaultTest {
    @Test
    fun aFailedActionKeepsTheSameException() = runTest {
        val failure = IllegalStateException("socket closed")
        val outcome = ContactDetailLogic.runContactAction(testContactService()) { throw failure }
        val failed = assertIs<ContactDetailLogic.ActionOutcome.Failed>(outcome)
        assertSame(failure, failed.error)
    }

    @Test
    fun aCancelledActionRethrowsTheSameException() = runTest {
        val cancelled = CancellationException("superseded")
        val thrown = assertFailsWith<CancellationException> {
            ContactDetailLogic.runContactAction(testContactService()) { throw cancelled }
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun anActionErrorRethrowsTheSameError() = runTest {
        val failure = DetailActionProbeError()
        val thrown = assertFailsWith<DetailActionProbeError> {
            ContactDetailLogic.runContactAction(testContactService()) { throw failure }
        }
        assertSame(failure, thrown)
    }
}

private class DetailActionProbeError : Error("probe")
