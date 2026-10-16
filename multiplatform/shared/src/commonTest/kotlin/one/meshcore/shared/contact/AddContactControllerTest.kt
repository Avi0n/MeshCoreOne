package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AddContactControllerTest {
    private val keyHex = publicKey(0x01).toUppercaseHex().lowercase()

    private data class ControllerOptions(
        val isRadioConnected: Boolean? = null,
        val maxContacts: Int? = MAX_CONTACTS,
        val errorText: (Throwable) -> String = { LOCALIZED_ERROR },
    )

    private fun controller(
        session: FakeContactSession = FakeContactSession(),
        service: ContactService? = testContactService(session),
        radioId: Uuid? = ContactTestIds.RADIO,
        options: ControllerOptions = ControllerOptions(),
    ): AddContactController {
        val connected = options.isRadioConnected ?: (service != null && radioId != null)
        return AddContactController(
            AddContactController.Dependencies(
                contactService = { service },
                radioId = { radioId },
                isRadioConnected = { connected },
                maxContacts = { options.maxContacts },
                now = { CONTACT_TEST_NOW_SECONDS },
                errorText = options.errorText,
            ),
        )
    }

    @Test
    fun name_is_truncated_to_the_usable_byte_budget_as_it_is_typed() {
        val controller = controller()
        controller.setName("A".repeat(MAX_USABLE_NAME_BYTES + 9))
        assertEquals(MAX_USABLE_NAME_BYTES, controller.state.value.name.length)
    }

    @Test
    fun typingAnAddContactNameDoesNotRadio() {
        var serviceReads = 0
        var radioReads = 0
        var connectedReads = 0
        var nowReads = 0
        val session = FakeContactSession()
        val controller = AddContactController(
            AddContactController.Dependencies(
                contactService = {
                    serviceReads += 1
                    testContactService(session)
                },
                radioId = {
                    radioReads += 1
                    ContactTestIds.RADIO
                },
                isRadioConnected = {
                    connectedReads += 1
                    true
                },
                now = {
                    nowReads += 1
                    CONTACT_TEST_NOW_SECONDS
                },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        assertEquals("", controller.state.value.name)
        assertEquals("", controller.state.value.publicKeyHex)
        assertEquals(ContactType.CHAT, controller.state.value.type)
        assertFalse(controller.state.value.canAdd)

        controller.setName("Node A")

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(0, connectedReads)
        assertEquals(0, nowReads)
        assertEquals("Node A", controller.state.value.name)
        assertEquals("", controller.state.value.publicKeyHex)
        assertEquals(ContactType.CHAT, controller.state.value.type)
        assertFalse(controller.state.value.canAdd)
        assertFalse(controller.state.value.isSubmitting)
        assertNull(controller.state.value.error)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun typingAnAddContactPublicKeyDoesNotRadio() {
        var serviceReads = 0
        var radioReads = 0
        var connectedReads = 0
        var nowReads = 0
        val session = FakeContactSession()
        val controller = AddContactController(
            AddContactController.Dependencies(
                contactService = {
                    serviceReads += 1
                    testContactService(session)
                },
                radioId = {
                    radioReads += 1
                    ContactTestIds.RADIO
                },
                isRadioConnected = {
                    connectedReads += 1
                    true
                },
                now = {
                    nowReads += 1
                    CONTACT_TEST_NOW_SECONDS
                },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        assertEquals("", controller.state.value.name)
        assertEquals("", controller.state.value.publicKeyHex)
        assertEquals(ContactType.CHAT, controller.state.value.type)
        assertFalse(controller.state.value.canAdd)
        assertFalse(controller.state.value.isValidPublicKey)

        controller.setPublicKeyHex("ab")

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(0, connectedReads)
        assertEquals(0, nowReads)
        assertEquals("", controller.state.value.name)
        assertEquals("ab", controller.state.value.publicKeyHex)
        assertEquals(ContactType.CHAT, controller.state.value.type)
        assertFalse(controller.state.value.canAdd)
        assertFalse(controller.state.value.isValidPublicKey)
        assertFalse(controller.state.value.isSubmitting)
        assertNull(controller.state.value.error)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun public_key_field_strips_grouped_and_unicode_digits() {
        val controller = controller()
        val grouped = keyHex.chunked(2).joinToString(":")
        controller.setPublicKeyHex(grouped)
        assertEquals(keyHex, controller.state.value.publicKeyHex)
        assertTrue(controller.state.value.isValidPublicKey)

        controller.setPublicKeyHex("A\u0661")
        assertEquals("a", controller.state.value.publicKeyHex)
        assertFalse(controller.state.value.isValidPublicKey)
    }

    @Test
    fun public_key_validity_tracks_the_normalized_hex_length() {
        val controller = controller()
        controller.setPublicKeyHex("00 11 gg")
        assertFalse(controller.state.value.isValidPublicKey)
        controller.setPublicKeyHex(keyHex)
        assertTrue(controller.state.value.isValidPublicKey)
        assertEquals(PUBLIC_KEY_HEX_LENGTH, controller.state.value.normalizedPublicKeyHex.length)
    }

    @Test
    fun can_add_requires_a_name_a_full_key_and_no_submit_in_flight() {
        val controller = controller()
        assertFalse(controller.state.value.canAdd)
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)
        assertTrue(controller.state.value.canAdd)
    }

    @Test
    fun pasting_a_valid_uri_prefills_the_form() {
        val controller = controller()
        val uri = ContactService.exportContactURI("Node A", publicKey(0x01), ContactType.ROOM)
        controller.pasteUrl(uri)
        val state = controller.state.value
        assertEquals("Node A", state.name)
        assertEquals(keyHex, state.publicKeyHex)
        assertEquals(ContactType.ROOM, state.type)
        assertFalse(state.pasteFailed)
    }

    @Test
    fun pastingAnAddContactCardDoesNotRadio() {
        var serviceReads = 0
        var radioReads = 0
        var connectedReads = 0
        var nowReads = 0
        val session = FakeContactSession()
        val service = testContactService(session)
        val uri = ContactService.exportContactURI("Node A", publicKey(0x01), ContactType.ROOM)
        val controller = AddContactController(
            AddContactController.Dependencies(
                contactService = {
                    serviceReads += 1
                    service
                },
                radioId = {
                    radioReads += 1
                    ContactTestIds.RADIO
                },
                isRadioConnected = {
                    connectedReads += 1
                    true
                },
                now = {
                    nowReads += 1
                    CONTACT_TEST_NOW_SECONDS
                },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        assertEquals("", controller.state.value.name)
        assertEquals("", controller.state.value.publicKeyHex)
        assertEquals(ContactType.CHAT, controller.state.value.type)
        assertFalse(controller.state.value.pasteFailed)

        controller.pasteUrl(uri)

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(0, connectedReads)
        assertEquals(0, nowReads)
        assertEquals("Node A", controller.state.value.name)
        assertEquals(keyHex, controller.state.value.publicKeyHex)
        assertEquals(ContactType.ROOM, controller.state.value.type)
        assertFalse(controller.state.value.pasteFailed)
        assertFalse(controller.state.value.isSubmitting)
        assertNull(controller.state.value.error)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun pasting_an_invalid_uri_flags_the_inline_error() {
        val controller = controller()
        controller.pasteUrl("not a meshcore url")
        assertTrue(controller.state.value.pasteFailed)
    }

    @Test
    fun add_builds_a_flood_frame_from_the_callers_radio_and_now() = runTest {
        val session = FakeContactSession()
        val controller = controller(session = session, service = testContactService(session))
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)

        var addedId: Uuid? = null
        controller.add { addedId = it }

        assertTrue(addedId != null)
        val sent = session.addedContacts.single()
        assertEquals("Node A", sent.advertisedName)
        assertTrue(publicKey(0x01).contentEquals(sent.publicKey))
        assertEquals(ContactType.CHAT.raw, sent.typeRawValue)
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, sent.outPathLength)
        assertEquals(0, sent.flags)
        assertEquals(CONTACT_TEST_NOW_SECONDS, sent.lastModified)
        assertNull(controller.state.value.error)
    }

    @Test
    fun add_without_a_connection_reports_not_connected() = runTest {
        val controller = controller(service = null, radioId = null)
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)
        controller.add { }
        assertEquals(AddContactError.NotConnected, controller.state.value.error)
    }

    @Test
    fun add_with_an_offline_service_and_last_radio_reports_not_connected() = runTest {
        val session = FakeContactSession()
        val controller = controller(
            session = session,
            service = testContactService(session),
            options = ControllerOptions(isRadioConnected = false),
        )
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)
        controller.add { }
        assertEquals(AddContactError.NotConnected, controller.state.value.error)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun add_rejects_an_odd_length_key() = runTest {
        val session = FakeContactSession()
        val controller = controller(session = session, service = testContactService(session))
        controller.setName("Node A")
        controller.setPublicKeyHex("abc")
        var added = false
        controller.add { added = true }
        assertEquals(AddContactError.InvalidFormat, controller.state.value.error)
        assertFalse(added)
        assertFalse(controller.state.value.isSubmitting)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun add_rejects_a_short_key() = runTest {
        val session = FakeContactSession()
        val controller = controller(session = session, service = testContactService(session))
        controller.setName("Node A")
        controller.setPublicKeyHex("aa")
        var added = false
        controller.add { added = true }
        assertEquals(AddContactError.InvalidSize, controller.state.value.error)
        assertFalse(added)
        assertFalse(controller.state.value.isSubmitting)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun add_maps_a_full_table_to_node_list_full_with_the_device_cap() = runTest {
        val session = FakeContactSession().apply { addContactError = ContactProtocolErrorCode.TABLE_FULL }
        val controller = controller(session = session, service = testContactService(session))
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)
        controller.add { }
        assertEquals(AddContactError.NodeListFull(MAX_CONTACTS), controller.state.value.error)
        assertFalse(controller.state.value.isSubmitting)
    }

    /**
     * A failure below the contact service is not a `ContactServiceError`. Letting it escape strands
     * `isSubmitting`, which leaves `canAdd` false forever: the form is dead with nothing shown.
     */
    @Test
    fun add_surfaces_a_transport_failure_and_re_enables_the_form() = runTest {
        val session = FakeContactSession().apply { addContactThrowable = IllegalStateException("radio down") }
        val controller = controller(
            session = session,
            service = testContactService(session),
            options = ControllerOptions(errorText = { "import fail" }),
        )
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)

        controller.add { }

        assertEquals(AddContactError.ServiceMessage("import fail"), controller.state.value.error)
        assertFalse(controller.state.value.isSubmitting)
        assertTrue(controller.state.value.canAdd, "the form must be usable again after a failed submit")
    }

    @Test
    fun aTransportFailureShowsFallbackNotEnglish() = runTest {
        val session = FakeContactSession().apply { addContactThrowable = IllegalStateException("radio down") }
        val controller = controller(
            session = session,
            service = testContactService(session),
            options = ControllerOptions(errorText = { "Ein Fehler ist aufgetreten" }),
        )
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)
        controller.add { }
        assertEquals(AddContactError.ServiceMessage("Ein Fehler ist aufgetreten"), controller.state.value.error)
    }

    @Test
    fun aCancelledAddPropagatesWithoutReenablingTheForm() = runTest {
        val cancelled = CancellationException("superseded")
        val session = FakeContactSession().apply { addContactThrowable = cancelled }
        val controller = controller(session = session, service = testContactService(session))
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)
        var added = false

        val thrown = assertFailsWith<CancellationException> {
            controller.add { added = true }
        }

        assertSame(cancelled, thrown)
        assertFalse(added)
        assertTrue(controller.state.value.isSubmitting)
        assertNull(controller.state.value.error)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun anAddErrorPropagatesWithoutReenablingTheForm() = runTest {
        val probe = AddContactProbeError()
        val session = FakeContactSession()
        val controller = controller(session = session, service = testContactService(session))
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)

        val thrown = assertFailsWith<AddContactProbeError> {
            controller.add { throw probe }
        }

        assertSame(probe, thrown)
        assertTrue(controller.state.value.isSubmitting)
        assertNull(controller.state.value.error)
        assertEquals(1, session.addedContacts.size)
    }

    @Test
    fun choosing_an_add_contact_type_does_not_radio() {
        var serviceReads = 0
        var radioReads = 0
        val session = FakeContactSession()
        val service = testContactService(session)
        val controller = AddContactController(
            AddContactController.Dependencies(
                contactService = {
                    serviceReads += 1
                    service
                },
                radioId = {
                    radioReads += 1
                    ContactTestIds.RADIO
                },
                isRadioConnected = { true },
                now = { CONTACT_TEST_NOW_SECONDS },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        assertEquals(ContactType.CHAT, controller.state.value.type)

        controller.setType(ContactType.REPEATER)

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(ContactType.REPEATER, controller.state.value.type)
        assertEquals("", controller.state.value.name)
        assertEquals("", controller.state.value.publicKeyHex)
        assertFalse(controller.state.value.isSubmitting)
        assertNull(controller.state.value.error)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun clearing_an_add_contact_error_does_not_radio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val session = FakeContactSession()
        val controller = AddContactController(
            AddContactController.Dependencies(
                contactService = {
                    serviceReads += 1
                    null
                },
                radioId = {
                    radioReads += 1
                    null
                },
                isRadioConnected = { false },
                now = { CONTACT_TEST_NOW_SECONDS },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        controller.setName("Node A")
        controller.setPublicKeyHex(keyHex)
        controller.add { }
        assertEquals(AddContactError.NotConnected, controller.state.value.error)
        assertEquals(1, serviceReads)
        assertEquals(1, radioReads)

        controller.clearError()

        assertEquals(1, serviceReads)
        assertEquals(1, radioReads)
        assertNull(controller.state.value.error)
        assertEquals("Node A", controller.state.value.name)
        assertEquals(keyHex, controller.state.value.publicKeyHex)
        assertFalse(controller.state.value.isSubmitting)
        assertTrue(session.addedContacts.isEmpty())
    }

    private companion object {
        const val MAX_CONTACTS = 100
        const val LOCALIZED_ERROR = "Localized error"
    }
}

private class AddContactProbeError : Error("probe")
