package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
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
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ScanContactControllerTest {
    private val validUri = ContactService.exportContactURI("Node A", publicKey(0x01), ContactType.CHAT)

    // An unconfined scope so the controller's fire-and-forget `import` launch runs eagerly to completion
    // against the in-memory store, keeping each test's assertions synchronous.
    private fun TestScope.controllerScope(): CoroutineScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))

    @Suppress("LongParameterList") // Test builder exposing every controller dependency as an optional override.
    private fun controller(
        scope: CoroutineScope,
        session: FakeContactSession = FakeContactSession(),
        service: ContactService? = testContactService(session),
        radioId: Uuid? = ContactTestIds.RADIO,
        isRadioConnected: Boolean = service != null && radioId != null,
        maxContacts: Int? = MAX_CONTACTS,
        onScanned: (ContactDto) -> Unit = {},
        errorText: (Throwable) -> String = { LOCALIZED_ERROR },
        unmappedError: () -> String = { LOCALIZED_ERROR },
    ): ScanContactController = ScanContactController(
        scope = scope,
        dependencies = ScanContactController.Dependencies(
            contactService = { service },
            radioId = { radioId },
            isRadioConnected = { isRadioConnected },
            maxContacts = { maxContacts },
            now = { CONTACT_TEST_NOW_SECONDS },
            onScanned = onScanned,
            errorText = errorText,
            unmappedError = unmappedError,
        ),
    )

    @Test
    fun anApprovedCodeImportsAFloodFrameAndReportsTheScan() = runTest {
        val session = FakeContactSession()
        var scannedName: String? = null
        val controller = controller(
            scope = controllerScope(),
            session = session,
            service = testContactService(session),
            onScanned = { scannedName = it.name },
        )

        controller.onDecoded(validUri)
        assertTrue(session.addedContacts.isEmpty())
        assertEquals(1, controller.state.value.selectionTick)
        assertEquals(0, controller.state.value.successTick)
        controller.onPrimaryAction()

        val sent = session.addedContacts.single()
        assertEquals("Node A", sent.advertisedName)
        assertEquals(CONTACT_FLOOD_PATH_SENTINEL, sent.outPathLength)
        assertEquals(CONTACT_TEST_NOW_SECONDS, sent.lastModified)
        assertEquals("Node A", scannedName)
        assertFalse(controller.state.value.isImporting)
        assertEquals(1, controller.state.value.successTick)
    }

    @Test
    fun an_unparseable_code_stays_scanning_with_an_invalid_format_error() = runTest {
        val session = FakeContactSession()
        val controller = controller(scope = controllerScope(), session = session, service = testContactService(session))
        controller.onDecoded("garbage")
        assertEquals(ScanContactError.InvalidFormat, controller.state.value.error)
        assertEquals(1, controller.state.value.errorTick)
        assertFalse(controller.state.value.isImporting)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun clearing_a_contact_scan_error_does_not_radio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        val session = FakeContactSession()
        val service = testContactService(session)
        val controller = ScanContactController(
            scope = controllerScope(),
            dependencies = ScanContactController.Dependencies(
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
                unmappedError = { LOCALIZED_ERROR },
            ),
        )

        controller.onDecoded("garbage")
        assertEquals(ScanContactError.InvalidFormat, controller.state.value.error)
        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)

        controller.clearError()

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertNull(controller.state.value.error)
        assertEquals(1, controller.state.value.errorTick)
        assertFalse(controller.state.value.isImporting)
        assertTrue(session.addedContacts.isEmpty())
    }

    @Test
    fun acceptingAContactScanWithNothingScannedDoesNotRadio() = runTest {
        var serviceReads = 0
        var radioReads = 0
        var scanned = false
        val controller = ScanContactController(
            scope = controllerScope(),
            dependencies = ScanContactController.Dependencies(
                contactService = {
                    serviceReads += 1
                    error("contact service")
                },
                radioId = {
                    radioReads += 1
                    error("radio")
                },
                isRadioConnected = { error("connected") },
                maxContacts = { error("max") },
                now = { error("clock") },
                onScanned = { scanned = true },
                errorText = { LOCALIZED_ERROR },
                unmappedError = { LOCALIZED_ERROR },
            ),
        )

        controller.onPrimaryAction()

        assertEquals(0, serviceReads)
        assertEquals(0, radioReads)
        assertEquals(false, scanned)
        val state = controller.state.value
        assertNull(state.scannedContact)
        assertNull(state.existingContact)
        assertEquals(false, state.isImporting)
        assertEquals(false, state.isResolvingScan)
        assertNull(state.error)
        assertEquals(0, state.successTick)
        assertEquals(0, state.selectionTick)
    }

    @Test
    fun a_second_code_while_importing_is_ignored() = runTest {
        // A standard dispatcher keeps the review lookup and import queued; duplicate camera callbacks
        // cannot replace the reviewed identity or start a second radio import.
        val session = FakeContactSession()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler))
        val controller = controller(scope = scope, session = session, service = testContactService(session))
        controller.onDecoded(validUri)
        controller.onDecoded(validUri)
        advanceUntilIdle()
        assertTrue(session.addedContacts.isEmpty())
        controller.onPrimaryAction()
        controller.onDecoded(validUri)
        advanceUntilIdle()
        assertEquals(1, session.addedContacts.size)
    }

    @Test
    fun a_disconnected_scan_reports_not_connected() = runTest {
        val controller = controller(scope = controllerScope(), service = null, radioId = null)
        controller.onDecoded(validUri)
        controller.onPrimaryAction()
        assertEquals(ScanContactError.NotConnected, controller.state.value.error)
        assertFalse(controller.state.value.isImporting)
    }

    @Test
    fun an_offline_service_with_a_last_radio_still_reports_not_connected() = runTest {
        val session = FakeContactSession()
        val controller = controller(
            scope = controllerScope(),
            session = session,
            service = testContactService(session),
            isRadioConnected = false,
        )
        controller.onDecoded(validUri)
        controller.onPrimaryAction()
        assertEquals(ScanContactError.NotConnected, controller.state.value.error)
        assertTrue(session.addedContacts.isEmpty())
        assertFalse(controller.state.value.isImporting)
    }

    @Test
    fun a_full_table_maps_to_node_list_full_with_the_device_cap() = runTest {
        val session = FakeContactSession().apply { addContactError = ContactProtocolErrorCode.TABLE_FULL }
        val controller = controller(scope = controllerScope(), session = session, service = testContactService(session))
        controller.onDecoded(validUri)
        controller.onPrimaryAction()
        assertEquals(ScanContactError.NodeListFull(MAX_CONTACTS), controller.state.value.error)
        assertFalse(controller.state.value.isImporting)
    }

    @Test
    fun aMissingSavedRowUsesTheLatestLocalizedCopy() = runTest {
        var message = "first language"
        val session = FakeContactSession()
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = null
        }
        val controller = controller(
            scope = controllerScope(),
            session = session,
            service = testContactService(session = session, store = store),
            unmappedError = { message },
        )
        controller.onDecoded(validUri)

        message = "second language"
        controller.onPrimaryAction()

        assertEquals(ScanContactError.ImportFailed("second language"), controller.state.value.error)
        assertFalse(controller.state.value.isImporting)
    }

    @Test
    fun permission_denial_switches_to_the_settings_prompt() = runTest {
        val controller = controller(scope = controllerScope())
        assertNull(controller.state.value.error)
        controller.onPermissionDenied()
        assertTrue(controller.state.value.permissionDenied)
    }

    /**
     * A failure below the contact service is not a `ContactServiceError`. Letting it escape strands
     * `isImporting`, after which `onDecoded` returns at its guard and the scanner silently ignores every
     * further code with no error shown.
     */
    @Test
    fun a_transport_failure_is_surfaced_and_the_scanner_keeps_accepting_codes() = runTest {
        val session = FakeContactSession().apply { addContactThrowable = IllegalStateException("radio down") }
        val controller = controller(
            scope = controllerScope(),
            session = session,
            service = testContactService(session),
            errorText = { "import fail" },
        )

        controller.onDecoded(validUri)
        controller.onPrimaryAction()

        assertEquals(ScanContactError.ImportFailed("import fail"), controller.state.value.error)
        assertFalse(controller.state.value.isImporting, "a stranded import flag deafens the scanner")

        session.addContactThrowable = null
        controller.onPrimaryAction()

        assertEquals(1, session.addedContacts.size, "the next code must reach the device")
    }

    @Test
    fun aTransportFailureShowsFallbackNotEnglish() = runTest {
        val session = FakeContactSession().apply { addContactThrowable = IllegalStateException("radio down") }
        val controller = controller(
            scope = controllerScope(),
            session = session,
            service = testContactService(session),
            errorText = { "Ein Fehler ist aufgetreten" },
        )
        controller.onDecoded(validUri)
        controller.onPrimaryAction()
        assertEquals(ScanContactError.ImportFailed("Ein Fehler ist aufgetreten"), controller.state.value.error)
    }

    private companion object {
        const val MAX_CONTACTS = 100
        const val LOCALIZED_ERROR = "Localized error"
    }

    @Test
    fun validScanWaitsForExplicitAddAndOpensTheSavedContact() = runTest {
        val session = FakeContactSession()
        val service = testContactService(session)
        var openedName: String? = null
        val controller = controller(
            scope = controllerScope(),
            session = session,
            service = service,
            onScanned = { openedName = it.name },
        )

        controller.onDecoded(validUri)
        assertEquals("Node A", controller.state.value.scannedContact?.name)
        assertTrue(session.addedContacts.isEmpty())

        controller.onPrimaryAction()
        assertEquals(1, session.addedContacts.size)
        assertEquals("Node A", openedName)
    }

    @Test
    fun existingKeyOffersViewWithoutReimport() = runTest {
        val session = FakeContactSession()
        val service = testContactService(session)
        service.addOrUpdateContact(
            radioID = ContactTestIds.RADIO,
            frame = importContactFrame("Saved Node", publicKey(0x01), ContactType.CHAT, CONTACT_TEST_NOW_SECONDS),
        )
        val before = session.addedContacts.size
        var openedName: String? = null
        val controller = controller(
            scope = controllerScope(),
            session = session,
            service = service,
            onScanned = { openedName = it.name },
        )

        controller.onDecoded(validUri)
        assertEquals("Saved Node", controller.state.value.existingContact?.name)
        assertEquals(before, session.addedContacts.size)
        controller.onPrimaryAction()
        assertEquals(before, session.addedContacts.size)
        assertEquals("Saved Node", openedName)
    }

    @Test
    fun scanAgainClearsReviewAndAllowsANewCode() = runTest {
        val controller = controller(scope = controllerScope())
        controller.onDecoded(validUri)
        controller.onDecoded("garbage")
        assertNull(controller.state.value.error)
        controller.resetToScanner()
        assertNull(controller.state.value.scannedContact)
        controller.onDecoded("garbage")
        assertEquals(ScanContactError.InvalidFormat, controller.state.value.error)
    }

    @Test
    fun aFailedLocalLookupStillReviewsTheScannedIdentity() = runTest {
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = error("db locked")
        }
        val controller = controller(scope = controllerScope(), service = testContactService(store = store))

        controller.onDecoded(validUri)

        assertEquals("Node A", controller.state.value.scannedContact?.name)
        assertNull(controller.state.value.existingContact)
        assertFalse(controller.state.value.isResolvingScan)
    }

    @Test
    fun aCancelledLocalLookupLeavesTheScanResolving() = runTest {
        val cancelled = CancellationException("superseded")
        val store = object : ContactStore by InMemoryContactStore() {
            override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = throw cancelled
        }
        val scopeJob = SupervisorJob()
        val controller = controller(
            scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler)),
            service = testContactService(store = store),
        )

        controller.onDecoded(validUri)
        val lookup = scopeJob.children.single()
        var cause: Throwable? = null
        lookup.invokeOnCompletion { cause = it }
        runCurrent()

        assertSame(cancelled, cause)
        assertNull(controller.state.value.scannedContact)
        assertTrue(controller.state.value.isResolvingScan)
        scopeJob.cancel()
    }

    @Test
    fun closeCancelsTheOwnedLookup() = runTest {
        val scopeJob = SupervisorJob()
        val controller = controller(
            scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler)),
            service = testContactService(
                store = object : ContactStore by InMemoryContactStore() {
                    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? =
                        awaitCancellation()
                },
            ),
        )
        controller.onDecoded(validUri)
        runCurrent()
        val lookup = scopeJob.children.single()

        controller.close()
        runCurrent()

        assertTrue(lookup.isCancelled)
        scopeJob.cancel()
    }

    @Test
    fun aLocalLookupErrorLeavesTheScanResolving() = runTest {
        val fault = ScanLookupProbeError()
        val thrown = mutableListOf<Throwable>()
        val scopeJob = SupervisorJob()
        val controller = controller(
            scope = CoroutineScope(
                scopeJob + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
                    thrown += error
                },
            ),
            service = testContactService(
                store = object : ContactStore by InMemoryContactStore() {
                    override suspend fun fetchContact(radioID: Uuid, publicKey: ByteArray): ContactDto? = throw fault
                },
            ),
        )

        controller.onDecoded(validUri)
        runCurrent()

        assertSame(fault, thrown.single())
        assertNull(controller.state.value.scannedContact)
        assertTrue(controller.state.value.isResolvingScan)
        scopeJob.cancel()
    }

    @Test
    fun aCancelledImportLeavesTheReviewImporting() = runTest {
        val cancelled = CancellationException("superseded")
        val session = FakeContactSession().apply { addContactThrowable = cancelled }
        val scopeJob = SupervisorJob()
        val controller = controller(
            scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler)),
            session = session,
            service = testContactService(session),
        )
        controller.onDecoded(validUri)
        runCurrent()

        controller.onPrimaryAction()
        val importing = scopeJob.children.single()
        var cause: Throwable? = null
        importing.invokeOnCompletion { cause = it }
        runCurrent()

        assertSame(cancelled, cause)
        assertTrue(controller.state.value.isImporting)
        assertNull(controller.state.value.error)
        assertEquals(0, controller.state.value.successTick)
        scopeJob.cancel()
    }

    @Test
    fun closeCancelsTheOwnedImportBeforeItCanNavigate() = runTest {
        val session = FakeContactSession()
        val backing = InMemoryContactStore()
        val store = object : ContactStore by backing {
            override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Nothing = awaitCancellation()
        }
        val scopeJob = SupervisorJob()
        var scanned = false
        val controller = controller(
            scope = CoroutineScope(scopeJob + StandardTestDispatcher(testScheduler)),
            session = session,
            service = testContactService(session = session, store = store),
            onScanned = { scanned = true },
        )
        controller.onDecoded(validUri)
        runCurrent()
        controller.onPrimaryAction()
        runCurrent()
        val importing = scopeJob.children.single()
        assertEquals(1, session.addedContacts.size)

        controller.close()
        runCurrent()

        assertTrue(importing.isCancelled)
        assertFalse(scanned)
        scopeJob.cancel()
    }

    @Test
    fun anImportErrorLeavesTheReviewImporting() = runTest {
        val fault = ScanImportProbeError()
        val thrown = mutableListOf<Throwable>()
        val session = FakeContactSession()
        val controller = controller(
            scope = CoroutineScope(
                SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
                    thrown += error
                },
            ),
            session = session,
            service = testContactService(
                session = session,
                store = object : ContactStore by InMemoryContactStore() {
                    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid = throw fault
                },
            ),
        )
        controller.onDecoded(validUri)
        runCurrent()

        controller.onPrimaryAction()
        runCurrent()

        assertSame(fault, thrown.single())
        assertTrue(controller.state.value.isImporting)
        assertNull(controller.state.value.error)
        assertEquals(0, controller.state.value.successTick)
    }
}

private class ScanImportProbeError : Error("probe")

private class ScanLookupProbeError : Error("probe")
