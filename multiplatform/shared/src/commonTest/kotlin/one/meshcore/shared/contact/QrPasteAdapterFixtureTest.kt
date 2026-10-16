package one.meshcore.shared.contact

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.navigation.MeshCoreUrlParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contact QR text enters through [ScanContactController.onDecoded]. Paste enters through
 * [AddContactController.pasteUrl]. Neither path writes until the user confirms.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class QrPasteAdapterFixtureTest {
    @Test
    fun malformedAndOversizedPasteDoesNotAdd() = runTest {
        var serviceReads = 0
        val session = FakeContactSession()
        val controller = addController(session) { serviceReads += 1 }
        val keyHex = publicKey(KEY_FILL).toUppercaseHex()

        controller.pasteUrl("meshcore://contact/add?name=Ops%ZZ&public_key=$keyHex")
        assertEquals(true, controller.state.value.pasteFailed)
        assertEquals("", controller.state.value.name)

        controller.pasteUrl(oversizedKeyUrl())
        assertEquals(true, controller.state.value.pasteFailed)
        assertEquals("", controller.state.value.publicKeyHex)

        val longName = "N".repeat(MAX_USABLE_NAME_BYTES + NAME_OVERSHOOT_BYTES)
        controller.pasteUrl(ContactCardUri.export(longName, publicKey(KEY_FILL), ContactType.CHAT))

        assertEquals(MAX_USABLE_NAME_BYTES, controller.state.value.name.encodeToByteArray().size)
        assertEquals(false, controller.state.value.pasteFailed)
        assertEquals(0, serviceReads)
        assertTrue(session.addedContacts.isEmpty())
        assertEquals(false, controller.state.value.isSubmitting)
    }

    @Test
    fun malformedAndOversizedScansDoNotImport() = runTest {
        val session = FakeContactSession()
        val controller = scanController(session)
        controller.onDecoded("meshcore://contact/add?name=Ops%ZZ&public_key=${publicKey(KEY_FILL).toUppercaseHex()}")
        controller.onDecoded(oversizedKeyUrl())
        controller.onDecoded("x".repeat(HOSTILE_PAYLOAD_CHARS))
        controller.onPrimaryAction()
        advanceUntilIdle()

        assertEquals(ScanContactError.InvalidFormat, controller.state.value.error)
        assertNull(controller.state.value.scannedContact)
        assertTrue(session.addedContacts.isEmpty())
        assertEquals(false, controller.state.value.isImporting)
    }

    @Test
    fun aContactOnAnotherRadioIsNotTreatedAsSaved() = runTest {
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val otherRadio = Uuid.normalized("B0000000-0000-0000-0000-000000000002")
        store.saveContact(
            contactDto(
                id = ContactTestIds.fresh(),
                radioID = otherRadio,
                keyByte = KEY_FILL,
                name = "OtherRadio",
            ),
        )
        val controller = scanController(session, store = store, radioId = ContactTestIds.RADIO)
        val url = ContactCardUri.export("OpsNode", publicKey(KEY_FILL), ContactType.CHAT)

        controller.onDecoded(url)
        advanceUntilIdle()

        assertNull(controller.state.value.existingContact)
        assertEquals("OpsNode", controller.state.value.scannedContact?.name)
        assertTrue(session.addedContacts.isEmpty())
        assertEquals(false, controller.state.value.isImporting)
    }

    @Test
    fun aLaterPasteReplacesTheFirstAndABadPasteLeavesIt() = runTest {
        val session = FakeContactSession()
        val controller = addController(session)
        controller.pasteUrl(ContactCardUri.export("First", publicKey(KEY_FILL), ContactType.CHAT))
        controller.pasteUrl(ContactCardUri.export("Second", publicKey(OTHER_KEY_FILL), ContactType.CHAT))
        controller.pasteUrl("not a contact url")

        assertEquals(true, controller.state.value.pasteFailed)
        assertEquals("Second", controller.state.value.name)
        assertEquals(publicKey(OTHER_KEY_FILL).toUppercaseHex().lowercase(), controller.state.value.publicKeyHex)
        assertTrue(session.addedContacts.isEmpty())
        assertEquals(false, controller.state.value.isSubmitting)
    }

    @Test
    fun aDeniedCameraAndACancelledLookupDoNotImport() = runTest {
        val session = FakeContactSession()
        var serviceReads = 0
        val denied = scanController(session) { serviceReads += 1 }
        denied.onPermissionDenied()
        assertEquals(true, denied.state.value.permissionDenied)
        assertEquals(0, serviceReads)
        denied.onDecoded(ContactCardUri.export("Late", publicKey(KEY_FILL), ContactType.CHAT))
        advanceUntilIdle()
        assertNotNull(denied.state.value.scannedContact)
        assertTrue(session.addedContacts.isEmpty())

        val scopeJob = SupervisorJob()
        val held = ScanContactController(
            scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler)),
            dependencies = ScanContactController.Dependencies(
                contactService = {
                    testContactService(
                        session = session,
                        store = object : ContactStore by InMemoryContactStore() {
                            override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): Nothing =
                                awaitCancellation()
                        },
                    )
                },
                radioId = { ContactTestIds.RADIO },
                isRadioConnected = { true },
                now = { CONTACT_TEST_NOW_SECONDS },
                errorText = { LOCALIZED_ERROR },
                unmappedError = { LOCALIZED_ERROR },
            ),
        )
        held.onDecoded(ContactCardUri.export("Held", publicKey(KEY_FILL), ContactType.CHAT))
        runCurrent()
        held.close()
        held.onPrimaryAction()
        runCurrent()

        assertTrue(session.addedContacts.isEmpty())
        assertEquals(false, held.state.value.isImporting)
        scopeJob.cancel()
    }

    private fun TestScope.scanController(
        session: FakeContactSession,
        store: ContactStore = InMemoryContactStore(),
        radioId: Uuid? = ContactTestIds.RADIO,
        onService: () -> Unit = {},
    ): ScanContactController = ScanContactController(
        scope = CoroutineScope(UnconfinedTestDispatcher(testScheduler)),
        dependencies = ScanContactController.Dependencies(
            contactService = {
                onService()
                testContactService(session = session, store = store)
            },
            radioId = { radioId },
            isRadioConnected = { true },
            now = { CONTACT_TEST_NOW_SECONDS },
            errorText = { LOCALIZED_ERROR },
            unmappedError = { LOCALIZED_ERROR },
        ),
    )

    private fun addController(session: FakeContactSession, onService: () -> Unit = {}): AddContactController =
        AddContactController(
            AddContactController.Dependencies(
                contactService = {
                    onService()
                    testContactService(session)
                },
                radioId = { ContactTestIds.RADIO },
                isRadioConnected = { true },
                now = { CONTACT_TEST_NOW_SECONDS },
                errorText = { LOCALIZED_ERROR },
            ),
        )

    private fun oversizedKeyUrl(): String {
        val hex = "AB".repeat(MeshCoreUrlParser.PUBLIC_KEY_SIZE_BYTES + 1)
        return "meshcore://contact/add?name=Ops&public_key=$hex"
    }

    private companion object {
        const val KEY_FILL = 0x22
        const val OTHER_KEY_FILL = 0x33
        const val NAME_OVERSHOOT_BYTES = 8
        const val HOSTILE_PAYLOAD_CHARS = 8192
        const val LOCALIZED_ERROR = "Localized error"
    }
}
