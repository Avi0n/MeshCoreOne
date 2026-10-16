package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.ContactTestIds
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.contactDto
import one.meshcore.shared.contact.testContactService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Applying a trusted-contact selection. A session failure still reports the error. Cancellation and
 * an error propagate and do not record a failure.
 */
class TrustedContactsSelectionTest {
    @Test
    fun aGrantAppliesAndReportsSuccess() = runTest {
        val harness = TrustedSelectionHarness()
        applyTrustedSelection(
            request = harness.request(connected = true),
            onApplied = { harness.applied += 1 },
            onFailure = { harness.failures += it },
        )

        assertEquals(1, harness.applied)
        assertTrue(harness.failures.isEmpty())
        assertEquals(1, harness.session.changeFlagsCalls.size)
    }

    @Test
    fun aDisconnectedSelectionDoesNotApply() = runTest {
        val harness = TrustedSelectionHarness()
        applyTrustedSelection(
            request = harness.request(connected = false),
            onApplied = { harness.applied += 1 },
            onFailure = { harness.failures += it },
        )

        assertEquals(0, harness.applied)
        assertTrue(harness.failures.isEmpty())
        assertTrue(harness.session.changeFlagsCalls.isEmpty())
    }

    @Test
    fun aSessionExceptionReportsTheFailure() = runTest {
        val harness = TrustedSelectionHarness()
        harness.session.changeFlagsThrowable = IllegalArgumentException("bad flags")
        applyTrustedSelection(
            request = harness.request(connected = true),
            onApplied = { harness.applied += 1 },
            onFailure = { harness.failures += it },
        )

        assertEquals(0, harness.applied)
        assertIs<ContactServiceError.SessionError>(harness.failures.single())
        assertEquals("bad flags", harness.failures.single().message)
        assertTrue(harness.session.changeFlagsCalls.isEmpty())
    }

    @Test
    fun aCancelledSelectionPropagatesWithoutAFailure() = runTest {
        val harness = TrustedSelectionHarness()
        val cancelled = CancellationException("superseded")
        harness.session.changeFlagsThrowable = cancelled

        val error = assertFailsWith<CancellationException> {
            applyTrustedSelection(
                request = harness.request(connected = true),
                onApplied = { harness.applied += 1 },
                onFailure = { harness.failures += it },
            )
        }

        assertSame(cancelled, error)
        assertEquals(0, harness.applied)
        assertTrue(harness.failures.isEmpty())
        assertTrue(harness.session.changeFlagsCalls.isEmpty())
    }

    @Test
    fun anErrorPropagatesWithoutAFailure() = runTest {
        val harness = TrustedSelectionHarness()
        val probe = TrustedSelectionProbeError()

        val thrown = assertFailsWith<TrustedSelectionProbeError> {
            applyTrustedSelection(
                request = harness.request(connected = true),
                onApplied = { throw probe },
                onFailure = { harness.failures += it },
            )
        }

        assertSame(probe, thrown)

        assertEquals(1, harness.session.changeFlagsCalls.size)
        assertTrue(harness.failures.isEmpty())
    }
}

private class TrustedSelectionProbeError : Error("probe")

private class TrustedSelectionHarness {
    val session = FakeContactSession()
    val store = InMemoryContactStore()
    val contactId = ContactTestIds.fresh()
    var applied = 0
    val failures = mutableListOf<Throwable>()
    private var stored = false

    suspend fun request(connected: Boolean): TrustedSelectionRequest {
        if (!stored) {
            store.saveContact(contactDto(id = contactId, radioID = ContactTestIds.RADIO))
            stored = true
        }
        return TrustedSelectionRequest(
            contactService = testContactService(session = session, store = store),
            isRadioConnected = connected,
            pendingTrusted = setOf(contactId),
            initialTrusted = emptySet(),
        )
    }
}
