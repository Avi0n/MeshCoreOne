package one.meshcore.shared.contact.ui

import one.meshcore.shared.contact.ContactListError
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The alert title/body split the Nodes list renders. iOS shows the offline refusal as a "Cannot Refresh"
 * heading over an explanation of what to do about it; folding the heading into the body loses the
 * explanation entirely, which is how `contacts_list_connect_to_sync` came to ship unreferenced.
 */
class ContactListErrorPresentationTest {
    @Test
    fun anOfflineRefreshExplainsItselfUnderItsOwnHeading() {
        assertEquals("contacts_list_cannot_refresh", ContactListError.CannotRefresh.titleResource().key)
        assertEquals(
            "contacts_list_connect_to_sync",
            ContactListError.CannotRefresh.messageResource()?.key,
            "the offline alert body must be the connect-to-sync explanation, not the heading again",
        )
    }

    @Test
    fun theOtherTypedErrorsKeepTheGenericHeading() {
        assertEquals("contacts_common_error", ContactListError.ConnectToDelete.titleResource().key)
        assertEquals("contacts_common_error", ContactListError.RemoveTimedOut.titleResource().key)
        assertEquals(
            "contacts_view_model_connect_to_delete",
            ContactListError.ConnectToDelete.messageResource()?.key,
        )
        assertEquals(
            "contacts_view_model_remove_timed_out",
            ContactListError.RemoveTimedOut.messageResource()?.key,
        )
    }

    /** A lower-layer failure carries its own text, so it has no resource body. */
    @Test
    fun aServiceMessageHasNoResourceBody() {
        val error = ContactListError.ServiceMessage("boom")

        assertEquals("contacts_common_error", error.titleResource().key)
        assertNull(error.messageResource())
    }
}
