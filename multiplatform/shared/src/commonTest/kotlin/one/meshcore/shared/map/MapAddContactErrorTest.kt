package one.meshcore.shared.map

import one.meshcore.shared.contact.AddContactError
import one.meshcore.shared.contact.ContactServiceError
import one.meshcore.shared.contact.testContactService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

class MapAddContactErrorTest {
    @Test
    fun anOfflineServiceIsNotAWriteService() {
        val service = testContactService()
        assertNull(mapAddDiscoveredWriteService(service, isRadioConnected = false))
        assertSame(service, mapAddDiscoveredWriteService(service, isRadioConnected = true))
        assertNull(mapAddDiscoveredWriteService(null, isRadioConnected = true))
    }

    @Test
    fun tableFullCarriesMaxContacts() {
        val mapped = mapAddDiscoveredNodeError(
            ContactServiceError.ContactTableFull(),
            maxContacts = 100,
            errorText = { "Localized error" },
        )
        assertEquals(AddContactError.NodeListFull(100), mapped)
    }

    @Test
    fun tableFullKeepsNullCapWhenUnknown() {
        val mapped = mapAddDiscoveredNodeError(
            ContactServiceError.ContactTableFull(),
            maxContacts = null,
            errorText = { "Localized error" },
        )
        assertEquals(AddContactError.NodeListFull(null), mapped)
    }

    @Test
    fun notConnectedMapsToNotConnected() {
        val mapped = mapAddDiscoveredNodeError(
            ContactServiceError.NotConnected(),
            maxContacts = 100,
            errorText = { "Localized error" },
        )
        assertEquals(AddContactError.NotConnected, mapped)
    }

    @Test
    fun otherFailureShowsMappedCopyNotExceptionText() {
        val mapped = mapAddDiscoveredNodeError(
            ContactServiceError.SendFailed(),
            maxContacts = 100,
            errorText = { "send fail" },
        )
        assertEquals(AddContactError.ServiceMessage("send fail"), mapped)
    }
}
