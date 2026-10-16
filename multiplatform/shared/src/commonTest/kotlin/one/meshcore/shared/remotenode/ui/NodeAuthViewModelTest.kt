package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.FakeContactSession
import one.meshcore.shared.contact.InMemoryDiscoveredNodeStore
import one.meshcore.shared.contact.UserLocation
import one.meshcore.shared.contact.testContactService
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.NodePasswordStore
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import one.meshcore.shared.remotenode.testLoginInfo
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercises the node authentication view model's observable behavior: the flood-routing initial state, the
 * saved-password load, the error-clearing input, and the disconnected authenticate path (a `null` radio or
 * service surfaces the not-connected error). The full connect/login success and timeout paths run through
 * the committed `RepeaterAdminService`/`RoomServerService` graph and are covered by their service tests and
 * the on-device smoke.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeAuthViewModelTest {
    private companion object {
        // Passes to drain the retry cascade across the foreground and service (background) scopes.
        const val DRAIN_STEPS = 10
    }

    private val radio = Uuid.normalized("A0000000-0000-0000-0000-000000000064")

    private fun contact(floodRouted: Boolean) = ContactDto(
        id = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
        radioID = radio,
        publicKey = ByteArray(32) { 0xAA.toByte() },
        name = "Repeater",
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = if (floodRouted) 0xFF else 0x01,
        outPath = if (floodRouted) ByteArray(0) else byteArrayOf(0xA1.toByte()),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    private fun TestScope.viewModel(
        contact: ContactDto,
        passwordStore: InMemoryNodePasswordStore = InMemoryNodePasswordStore(),
        radioId: Uuid? = radio,
        errorText: (Throwable) -> String = { LOCALIZED_ERROR },
    ) = NodeAuthViewModel(
        scope = backgroundScope,
        contact = contact,
        role = RemoteNodeRole.REPEATER,
        dependencies = NodeAuthViewModel.Dependencies(
            repeaterAdminService = { null },
            roomServerService = { null },
            passwordStore = { passwordStore },
            contactService = { null },
            radioId = { radioId },
            errorText = errorText,
        ),
    )

    /**
     * A view model whose connect job runs on the foreground test scope so `advanceTimeBy` drives its
     * continuations (the retry cascade after a login timeout); the service collectors stay on backgroundScope.
     */
    private fun TestScope.floodTestViewModel(
        contact: ContactDto,
        admin: RepeaterAdminService,
        passwordStore: InMemoryNodePasswordStore,
    ) = NodeAuthViewModel(
        scope = this,
        contact = contact,
        role = RemoteNodeRole.REPEATER,
        dependencies = NodeAuthViewModel.Dependencies(
            repeaterAdminService = { admin },
            roomServerService = { null },
            passwordStore = { passwordStore },
            contactService = { null },
            radioId = { radio },
            errorText = { LOCALIZED_ERROR },
        ),
    )

    /** Repeater sign-in with no admin service, so an edit can prove it did not radio. */
    private fun TestScope.quietRepeaterSignIn(countPasswordStore: Boolean = true): QuietRepeaterSignIn {
        val reads = QuietRepeaterSignInReads()
        val vm = NodeAuthViewModel(
            scope = backgroundScope,
            contact = contact(floodRouted = false),
            role = RemoteNodeRole.REPEATER,
            dependencies = NodeAuthViewModel.Dependencies(
                repeaterAdminService = {
                    reads.radioReads += 1
                    null
                },
                roomServerService = {
                    reads.radioReads += 1
                    null
                },
                passwordStore = {
                    if (countPasswordStore) reads.radioReads += 1
                    InMemoryNodePasswordStore()
                },
                contactService = {
                    reads.radioReads += 1
                    null
                },
                radioId = { radio },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        return QuietRepeaterSignIn(vm, reads)
    }

    @Test
    fun loadPathCarriesDiscoveredRowsAndTheUserLocationIntoTheState() = runTest {
        // The path preview resolves hop hashes against both sources; without the store a hop heard only
        // through an advert renders as bare hex.
        val store = InMemoryDiscoveredNodeStore()
        val heard = DiscoveredNodeRecord(
            id = Uuid.normalized("A0000000-0000-0000-0000-000000000201"),
            radioID = radio,
            publicKey = ByteArray(32) { 0xA1.toByte() },
            name = "Ridge Relay",
            typeRawValue = ContactType.REPEATER.raw,
            lastHeardEpochSeconds = 0,
        )
        store.upsert(heard)
        val here = UserLocation(latitude = 37.0, longitude = -122.0)
        val vm = NodeAuthViewModel(
            scope = backgroundScope,
            contact = contact(floodRouted = false),
            role = RemoteNodeRole.REPEATER,
            dependencies = NodeAuthViewModel.Dependencies(
                repeaterAdminService = { null },
                roomServerService = { null },
                passwordStore = { InMemoryNodePasswordStore() },
                contactService = { testContactService() },
                radioId = { radio },
                discoveredNodeStore = { store },
                errorText = { LOCALIZED_ERROR },
                userLocation = { here },
            ),
        )

        vm.loadPath()

        assertEquals(listOf(heard), vm.state.value.pathDiscovered)
        assertEquals(here, vm.state.value.pathUserLocation)
    }

    @Test
    fun floodRoutingInitializesFromContact() = runTest {
        assertTrue(viewModel(contact(floodRouted = true)).state.value.useFloodRouting)
        assertFalse(viewModel(contact(floodRouted = false)).state.value.useFloodRouting)
    }

    @Test
    fun cancelWhileIdleDoesNotLogIn() = runTest {
        val quiet = quietRepeaterSignIn(countPasswordStore = false)
        val vm = quiet.vm
        vm.setPassword("secret")
        vm.cancel()
        runCurrent()

        assertEquals(0, quiet.reads.radioReads)
        assertFalse(vm.state.value.isAuthenticating)
        assertEquals("secret", vm.state.value.password)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun closeRejectsLaterAuthentication() = runTest {
        val quiet = quietRepeaterSignIn(countPasswordStore = false)
        val vm = quiet.vm
        vm.setPassword("secret")

        vm.close()
        vm.authenticate {}
        runCurrent()

        assertEquals(0, quiet.reads.radioReads)
        assertFalse(vm.state.value.isAuthenticating)
    }

    @Test
    fun cancelStopsAnInFlightAuthentication() = runTest {
        val flood = contact(floodRouted = true)
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val passwordStore = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(session, store, passwordStore, backgroundScope)
        val cli = NodeCliCoordinator(session, store, passwordStore, backgroundScope)
        val admin = RepeaterAdminService(remote, cli, session, store, passwordStore)
        runCurrent()
        val vm = floodTestViewModel(flood, admin, passwordStore)
        vm.setPassword("pw")
        vm.authenticate {}
        runCurrent()
        assertEquals(1, session.sendLoginCalls.size)
        assertTrue(vm.state.value.isAuthenticating)

        vm.cancel()
        runCurrent()
        val sendsAtCancellation = session.sendLoginCalls.size

        assertFalse(vm.state.value.isAuthenticating)
        assertFalse(vm.state.value.isRetryingViaFlood)
        assertNull(vm.state.value.errorMessage)
        testScheduler.advanceTimeBy(30_000)
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }
        assertEquals(sendsAtCancellation, session.sendLoginCalls.size)
    }

    @Test
    fun togglingSignInFloodRoutingDoesNotRadio() = runTest {
        val quiet = quietRepeaterSignIn()
        val vm = quiet.vm

        vm.setUseFloodRouting(true)
        runCurrent()

        assertEquals(0, quiet.reads.radioReads)
        assertTrue(vm.state.value.useFloodRouting)
        assertTrue(vm.state.value.rememberPassword)
        assertFalse(vm.state.value.isAuthenticating)
        assertEquals("", vm.state.value.password)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun togglingRememberPasswordOnSignInDoesNotRadio() = runTest {
        val quiet = quietRepeaterSignIn()
        val vm = quiet.vm
        assertTrue(vm.state.value.rememberPassword)

        vm.setRememberPassword(false)
        runCurrent()

        assertEquals(0, quiet.reads.radioReads)
        assertFalse(vm.state.value.rememberPassword)
        assertFalse(vm.state.value.useFloodRouting)
        assertFalse(vm.state.value.isAuthenticating)
        assertEquals("", vm.state.value.password)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun typingARepeaterSignInPasswordDoesNotRadio() = runTest {
        val quiet = quietRepeaterSignIn()
        val vm = quiet.vm
        assertEquals("", vm.state.value.password)
        assertTrue(vm.state.value.rememberPassword)
        assertFalse(vm.state.value.useFloodRouting)

        vm.setPassword("harbor9")
        runCurrent()

        assertEquals(0, quiet.reads.radioReads)
        assertEquals("harbor9", vm.state.value.password)
        assertTrue(vm.state.value.rememberPassword)
        assertFalse(vm.state.value.useFloodRouting)
        assertFalse(vm.state.value.isAuthenticating)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun turningOffRepeaterSignInRememberDoesNotRadio() = runTest {
        val quiet = quietRepeaterSignIn()
        val vm = quiet.vm
        assertEquals("", vm.state.value.password)
        assertTrue(vm.state.value.rememberPassword)
        assertFalse(vm.state.value.useFloodRouting)

        vm.setRememberPassword(false)
        runCurrent()

        assertEquals(0, quiet.reads.radioReads)
        assertEquals("", vm.state.value.password)
        assertFalse(vm.state.value.rememberPassword)
        assertFalse(vm.state.value.useFloodRouting)
        assertFalse(vm.state.value.isAuthenticating)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun loadSavedPasswordPopulatesField() = runTest {
        val store = InMemoryNodePasswordStore()
        val target = contact(floodRouted = true)
        store.storePassword(target.publicKey, "hunter2")
        val vm = viewModel(target, passwordStore = store)
        vm.loadSavedPassword()
        assertEquals("hunter2", vm.state.value.password)
        assertTrue(vm.state.value.hasSavedPassword)
    }

    @Test
    fun setPasswordClearsExistingError() = runTest {
        val vm = viewModel(contact(floodRouted = true), radioId = null)
        vm.authenticate {}
        runCurrent()
        assertIs<NodeAuthError.Message>(vm.state.value.errorMessage)
        vm.setPassword("x")
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun aConnectFailureShowsErrorOccurredFallback() = runTest {
        val vm = viewModel(contact(floodRouted = true), radioId = null)
        vm.authenticate {}
        runCurrent()
        val error = vm.state.value.errorMessage
        assertIs<NodeAuthError.Message>(error)
        assertEquals(LOCALIZED_ERROR, error.text)
    }

    @Test
    fun aConnectFailureUsesInjectedErrorText() = runTest {
        val vm = viewModel(
            contact(floodRouted = true),
            radioId = null,
            errorText = { "Kein Funk" },
        )
        vm.authenticate {}
        runCurrent()
        val error = vm.state.value.errorMessage
        assertIs<NodeAuthError.Message>(error)
        assertEquals("Kein Funk", error.text)
    }

    @Test
    fun passwordDeleteDumpLineMatchesIos() {
        assertEquals("Failed to delete saved password", FAILED_DELETE_SAVED_PASSWORD)
        assertEquals("Failed to delete saved password: db locked", failedToDeleteSavedPasswordLine("db locked"))
    }

    @Test
    fun passwordDeletePersistFaultStillCompletesLogin() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val flood = contact(floodRouted = true)
        val session = FakeNodeSession()
        session.onSendLogin = { session.emitLoginSuccess(testLoginInfo(flood.publicKey, isAdmin = true)) }
        val store = InMemoryRemoteNodeStore()
        val passwordStore = object : NodePasswordStore {
            override suspend fun storePassword(publicKey: ByteArray, password: String) = Unit
            override suspend fun retrievePassword(publicKey: ByteArray): String? = "old-pw"
            override suspend fun deletePassword(publicKey: ByteArray) = error("db locked")
            override suspend fun hasPassword(publicKey: ByteArray): Boolean = true
        }
        val remote = RemoteNodeService(session, store, passwordStore, backgroundScope)
        val cli = NodeCliCoordinator(session, store, passwordStore, backgroundScope)
        val admin = RepeaterAdminService(remote, cli, session, store, passwordStore)
        runCurrent()

        val vm = NodeAuthViewModel(
            scope = this,
            contact = flood,
            role = RemoteNodeRole.REPEATER,
            dependencies = NodeAuthViewModel.Dependencies(
                repeaterAdminService = { admin },
                roomServerService = { null },
                passwordStore = { passwordStore },
                contactService = { null },
                radioId = { radio },
                errorText = { LOCALIZED_ERROR },
            ),
            logger = PersistentLogger(
                this,
                category = "NodeAuthenticationSheet",
                record = { recorded += it },
            ),
        )
        vm.loadSavedPassword()
        vm.setRememberPassword(false)
        vm.setPassword("pw")

        var succeeded: RemoteNodeSessionDto? = null
        vm.authenticate { succeeded = it }
        runCurrent()
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }

        assertTrue(succeeded != null)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
        val line = recorded.single { it.message.contains(FAILED_DELETE_SAVED_PASSWORD) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("NodeAuthenticationSheet", line.category)
        assertEquals(failedToDeleteSavedPasswordLine("db locked"), line.message)
    }

    @Test
    fun aCancelledPasswordDeleteClearsAuthenticatingWithoutAnError() = runTest {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val flood = contact(floodRouted = true)
        val session = FakeNodeSession()
        session.onSendLogin = { session.emitLoginSuccess(testLoginInfo(flood.publicKey, isAdmin = true)) }
        val store = InMemoryRemoteNodeStore()
        val passwordStore = object : NodePasswordStore {
            override suspend fun storePassword(publicKey: ByteArray, password: String) = Unit
            override suspend fun retrievePassword(publicKey: ByteArray): String? = "old-pw"
            override suspend fun deletePassword(publicKey: ByteArray) = throw CancellationException("superseded")
            override suspend fun hasPassword(publicKey: ByteArray): Boolean = true
        }
        val remote = RemoteNodeService(session, store, passwordStore, backgroundScope)
        val cli = NodeCliCoordinator(session, store, passwordStore, backgroundScope)
        val admin = RepeaterAdminService(remote, cli, session, store, passwordStore)
        runCurrent()

        val vm = NodeAuthViewModel(
            scope = this,
            contact = flood,
            role = RemoteNodeRole.REPEATER,
            dependencies = NodeAuthViewModel.Dependencies(
                repeaterAdminService = { admin },
                roomServerService = { null },
                passwordStore = { passwordStore },
                contactService = { null },
                radioId = { radio },
                errorText = { LOCALIZED_ERROR },
            ),
            logger = PersistentLogger(
                this,
                category = "NodeAuthenticationSheet",
                record = { recorded += it },
            ),
        )
        vm.loadSavedPassword()
        vm.setRememberPassword(false)
        vm.setPassword("pw")

        var succeeded: RemoteNodeSessionDto? = null
        vm.authenticate { succeeded = it }
        runCurrent()
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }

        assertNull(succeeded)
        assertNull(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
        assertTrue(recorded.none { it.message.contains(FAILED_DELETE_SAVED_PASSWORD) })
    }

    @Test
    fun aPasswordDeleteErrorPropagatesWithoutCompletingLogin() = runTest {
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() +
                UnconfinedTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, error -> errors += error },
        )
        val recorded = mutableListOf<DebugLogEntryDto>()
        val flood = contact(floodRouted = true)
        val session = FakeNodeSession()
        session.onSendLogin = { session.emitLoginSuccess(testLoginInfo(flood.publicKey, isAdmin = true)) }
        val store = InMemoryRemoteNodeStore()
        val passwordStore = object : NodePasswordStore {
            override suspend fun storePassword(publicKey: ByteArray, password: String) = Unit
            override suspend fun retrievePassword(publicKey: ByteArray): String? = "old-pw"
            override suspend fun deletePassword(publicKey: ByteArray) = throw AuthPasswordProbeError()
            override suspend fun hasPassword(publicKey: ByteArray): Boolean = true
        }
        val remote = RemoteNodeService(session, store, passwordStore, backgroundScope)
        val cli = NodeCliCoordinator(session, store, passwordStore, backgroundScope)
        val admin = RepeaterAdminService(remote, cli, session, store, passwordStore)
        runCurrent()

        val vm = NodeAuthViewModel(
            scope = scope,
            contact = flood,
            role = RemoteNodeRole.REPEATER,
            dependencies = NodeAuthViewModel.Dependencies(
                repeaterAdminService = { admin },
                roomServerService = { null },
                passwordStore = { passwordStore },
                contactService = { null },
                radioId = { radio },
                errorText = { LOCALIZED_ERROR },
            ),
            logger = PersistentLogger(
                scope,
                category = "NodeAuthenticationSheet",
                record = { recorded += it },
            ),
        )
        vm.loadSavedPassword()
        vm.setRememberPassword(false)
        vm.setPassword("pw")

        var succeeded: RemoteNodeSessionDto? = null
        vm.authenticate { succeeded = it }
        runCurrent()
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }

        assertTrue(errors.single() is AuthPasswordProbeError)
        assertNull(succeeded)
        assertNull(vm.state.value.errorMessage)
        assertTrue(vm.state.value.isAuthenticating)
        assertTrue(recorded.none { it.message.contains(FAILED_DELETE_SAVED_PASSWORD) })
        scope.cancel()
    }

    @Test
    fun authenticateWhileDisconnectedSurfacesError() = runTest {
        val vm = viewModel(contact(floodRouted = true), radioId = null)
        vm.authenticate {}
        runCurrent()
        assertIs<NodeAuthError.Message>(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
    }

    @Test
    fun authenticateWithOfflineRoomServiceAndLastRadioSurfacesError() = runTest {
        val vm = NodeAuthViewModel(
            scope = backgroundScope,
            contact = contact(floodRouted = true),
            role = RemoteNodeRole.ROOM_SERVER,
            dependencies = NodeAuthViewModel.Dependencies(
                repeaterAdminService = { null },
                roomServerService = { error("offline room service must not be used") },
                passwordStore = { InMemoryNodePasswordStore() },
                contactService = { null },
                radioId = { radio },
                isRadioConnected = { false },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        vm.authenticate {}
        runCurrent()
        assertIs<NodeAuthError.Message>(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
    }

    @Test
    fun directLoginTimeoutAutoRetriesViaFlood() = runTest {
        val nonFlood = contact(floodRouted = false)
        val session = FakeNodeSession()
        val contactSession = FakeContactSession()
        val contactService = testContactService(session = contactSession)
        // The direct attempt retransmits while waiting, so a send index alone can't stand in for the flood
        // login. Gate the answer on the flood fallback actually running — the path reset only happens after
        // the direct attempt times out — so success can only follow the flood login, not a direct retransmit.
        session.onSendLogin = {
            if (contactSession.resetPathPublicKeys.isNotEmpty()) {
                session.emitLoginSuccess(testLoginInfo(nonFlood.publicKey))
            }
        }
        val store = InMemoryRemoteNodeStore()
        val passwordStore = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(session, store, passwordStore, backgroundScope)
        val cli = NodeCliCoordinator(session, store, passwordStore, backgroundScope)
        val admin = RepeaterAdminService(remote, cli, session, store, passwordStore)
        runCurrent()

        // Run the view model's connect job on the foreground test scope so advanceUntilIdle drives its
        // continuations (the retry after the direct-login timeout) to completion; the service collectors stay
        // on backgroundScope.
        val vm = NodeAuthViewModel(
            scope = this,
            contact = nonFlood,
            role = RemoteNodeRole.REPEATER,
            dependencies = NodeAuthViewModel.Dependencies(
                repeaterAdminService = { admin },
                roomServerService = { null },
                passwordStore = { passwordStore },
                contactService = { contactService },
                radioId = { radio },
                errorText = { LOCALIZED_ERROR },
            ),
        )
        vm.setPassword("pw")

        var succeeded: RemoteNodeSessionDto? = null
        vm.authenticate { succeeded = it }
        runCurrent()
        assertEquals(1, session.sendLoginCalls.size)

        // Advance past the direct-login timeout, then drain the cross-scope cascade (retry, flood login,
        // the answered login push); advanceUntilIdle alone doesn't drive the service's backgroundScope jobs.
        testScheduler.advanceTimeBy(30_000)
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }

        // The direct attempt timed out, the path was reset exactly once, and the flood login succeeded.
        assertEquals(1, contactSession.resetPathPublicKeys.size)
        assertTrue(succeeded != null)
        assertFalse(vm.state.value.isAuthenticating)
        assertFalse(vm.state.value.isRetryingViaFlood)
        assertNull(vm.state.value.errorMessage)
    }

    @Test
    fun floodLoginTimeoutIsTerminalWithoutRetry() = runTest {
        // A flood-routed contact has no stored path left to reset, so a timeout is terminal. The login
        // retransmits while waiting, so send counts grow until the deadline; terminality means the error
        // surfaces and no further sends happen afterwards.
        val flood = contact(floodRouted = true)
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val passwordStore = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(session, store, passwordStore, backgroundScope)
        val cli = NodeCliCoordinator(session, store, passwordStore, backgroundScope)
        val admin = RepeaterAdminService(remote, cli, session, store, passwordStore)
        runCurrent()

        val vm = floodTestViewModel(flood, admin, passwordStore)
        vm.setPassword("pw")
        vm.authenticate {}
        runCurrent()
        assertEquals(1, session.sendLoginCalls.size)

        testScheduler.advanceTimeBy(30_000)
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }

        assertIs<NodeAuthError.Timeout>(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
        assertFalse(vm.state.value.isRetryingViaFlood)

        val sendsAtTerminal = session.sendLoginCalls.size
        testScheduler.advanceTimeBy(30_000)
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }
        assertEquals(sendsAtTerminal, session.sendLoginCalls.size)
    }

    @Test
    fun directThenFloodTimeoutSurfacesTimeoutAfterFloodFallback() = runTest {
        // The direct attempt times out, the path is reset and a flood retry is sent; when it also times out
        // the error surfaces and the retry flag settles back to false. Each phase retransmits while waiting,
        // so the invariant is phase progression and terminality, not exact send counts.
        val nonFlood = contact(floodRouted = false)
        val session = FakeNodeSession()
        val store = InMemoryRemoteNodeStore()
        val passwordStore = InMemoryNodePasswordStore()
        val remote = RemoteNodeService(session, store, passwordStore, backgroundScope)
        val cli = NodeCliCoordinator(session, store, passwordStore, backgroundScope)
        val admin = RepeaterAdminService(remote, cli, session, store, passwordStore)
        runCurrent()

        val vm = floodTestViewModel(nonFlood, admin, passwordStore)
        vm.setPassword("pw")
        vm.authenticate {}
        runCurrent()
        assertEquals(1, session.sendLoginCalls.size)

        // Drain the direct-login timeout, the path reset, and the flood retry phase.
        testScheduler.advanceTimeBy(30_000)
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }
        assertTrue(session.sendLoginCalls.size > 1)

        // Drain the flood-login timeout: terminal, and no sends after the error surfaces.
        testScheduler.advanceTimeBy(30_000)
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }

        assertIs<NodeAuthError.Timeout>(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
        assertFalse(vm.state.value.isRetryingViaFlood)

        val sendsAtTerminal = session.sendLoginCalls.size
        testScheduler.advanceTimeBy(30_000)
        repeat(DRAIN_STEPS) { testScheduler.runCurrent() }
        assertEquals(sendsAtTerminal, session.sendLoginCalls.size)
    }

    @Test
    fun countdownAnnouncementFiresAtThresholdsAndFinalSecondsOnly() {
        // Crossing a coarse threshold announces, even when a tick was skipped over the exact value.
        assertTrue(NodeAuthViewModel.shouldAnnounceCountdown(31, 30))
        assertTrue(NodeAuthViewModel.shouldAnnounceCountdown(17, 15))
        assertTrue(NodeAuthViewModel.shouldAnnounceCountdown(12, 9))
        // Between thresholds, no announcement.
        assertFalse(NodeAuthViewModel.shouldAnnounceCountdown(30, 29))
        assertFalse(NodeAuthViewModel.shouldAnnounceCountdown(17, 16))
        // Every tick in the final seconds announces.
        assertTrue(NodeAuthViewModel.shouldAnnounceCountdown(6, 5))
        assertTrue(NodeAuthViewModel.shouldAnnounceCountdown(3, 2))
    }

    @Test
    fun toggleSettersUpdateState() = runTest {
        val vm = viewModel(contact(floodRouted = false))
        vm.setUseFloodRouting(true)
        vm.setRememberPassword(false)
        assertTrue(vm.state.value.useFloodRouting)
        assertFalse(vm.state.value.rememberPassword)
    }
}

private class QuietRepeaterSignIn(val vm: NodeAuthViewModel, val reads: QuietRepeaterSignInReads)

private class QuietRepeaterSignInReads {
    var radioReads = 0
}

private class AuthPasswordProbeError : Error("probe")

private const val LOCALIZED_ERROR = "Localized error"
