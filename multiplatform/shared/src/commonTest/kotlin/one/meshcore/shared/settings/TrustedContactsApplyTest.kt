package one.meshcore.shared.settings

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.ContactTestIds
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryContactStore
import one.meshcore.shared.contact.contactDto
import one.meshcore.shared.contact.testContactService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrustedContactsApplyTest {
    @Test
    fun applyWhileDisconnectedDoesNotReachTheRadio() = runTest {
        val store = InMemoryContactStore()
        val session = FakeContactSession()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO))
        val applied = TrustedContactsApply.apply(
            service = testContactService(session = session, store = store),
            isRadioConnected = false,
            toGrant = setOf(contactID),
            toRevoke = emptySet(),
        )
        assertFalse(applied)
        assertTrue(session.changeFlagsCalls.isEmpty())
        assertEquals(0, store.fetchContact(contactID)?.flags)
    }

    @Test
    fun applyOnALiveSessionWritesTelemetryFlags() = runTest {
        val store = InMemoryContactStore()
        val session = FakeContactSession()
        val contactID = ContactTestIds.fresh()
        store.saveContact(contactDto(id = contactID, radioID = ContactTestIds.RADIO))
        val applied = TrustedContactsApply.apply(
            service = testContactService(session = session, store = store),
            isRadioConnected = true,
            toGrant = setOf(contactID),
            toRevoke = emptySet(),
        )
        assertTrue(applied)
        assertEquals(1, session.changeFlagsCalls.size)
        assertEquals(0x0E, store.fetchContact(contactID)?.flags)
    }
}
