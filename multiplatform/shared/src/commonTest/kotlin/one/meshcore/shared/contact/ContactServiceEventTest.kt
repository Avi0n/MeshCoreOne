package one.meshcore.shared.contact

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContactServiceEventTest {
    @Test
    fun deviceTableMutationsReloadContactUi() {
        assertTrue(ContactServiceEvent.NodeDeleted.reloadsContactUi)
        assertTrue(
            ContactServiceEvent.ContactAdvertisementApplied(
                radioID = ContactTestIds.RADIO,
                publicKey = publicKey(0x01),
            ).reloadsContactUi,
        )
        assertFalse(ContactServiceEvent.MessagesCleared(ContactTestIds.RADIO).reloadsContactUi)
        assertFalse(ContactServiceEvent.SyncProgress(received = 1, total = 2).reloadsContactUi)
    }
}
