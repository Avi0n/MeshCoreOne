package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.RemoteNodeRole
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.FakeNodeSession
import one.meshcore.shared.remotenode.InMemoryNodePasswordStore
import one.meshcore.shared.remotenode.InMemoryRemoteNodeStore
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteNodeService
import one.meshcore.shared.remotenode.RepeaterAdminService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed login is shown on the sheet. Cancellation settles the sheet and keeps the thrown instance off
 * the error. A timeout stays the timeout error. A thrown error leaves the attempt in flight.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeAuthAuthenticateFaultTest {
    @Test
    fun aCancelledLoginSettlesWithoutAnAuthError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = harness(thrown)
        val vm = viewModel(harness)
        var succeeded: RemoteNodeSessionDto? = null

        vm.authenticate { succeeded = it }
        runCurrent()
        assertEquals(1, harness.nodeSession.sendLoginCalls.size)

        vm.cancel()
        runCurrent()

        assertFalse(vm.state.value.isAuthenticating)
        assertNull(vm.state.value.errorMessage)
        assertNull(succeeded)
        assertTrue(thrown.isEmpty())
    }

    @Test
    fun aLoginFailureIsShownOnTheSheet() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = harness(thrown)
        harness.nodeSession.sendLoginError = AuthAttemptFailure()
        val vm = viewModel(harness)
        var succeeded: RemoteNodeSessionDto? = null

        vm.authenticate { succeeded = it }
        repeat(DRAIN_STEPS) { runCurrent() }

        assertEquals(NodeAuthError.Message("radio"), vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
        assertNull(succeeded)
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.sendLoginCalls.size)
    }

    @Test
    fun aLoginFailureUsesTheLatestLocalizedCopy() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = harness(thrown)
        harness.nodeSession.sendLoginError = AuthAttemptFailure()
        var message = "first language"
        val vm = viewModel(harness) { message }

        message = "second language"
        vm.authenticate {}
        repeat(DRAIN_STEPS) { runCurrent() }

        assertEquals(NodeAuthError.Message("second language"), vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
        assertTrue(thrown.isEmpty())
    }

    @Test
    fun aLoginTimeoutStaysTheTimeoutError() = runTest {
        val thrown = mutableListOf<Throwable>()
        val harness = harness(thrown)
        harness.nodeSession.sendLoginError = RemoteNodeError.Timeout
        val vm = viewModel(harness)
        var succeeded: RemoteNodeSessionDto? = null

        vm.authenticate { succeeded = it }
        repeat(DRAIN_STEPS) { runCurrent() }

        assertIs<NodeAuthError.Timeout>(vm.state.value.errorMessage)
        assertFalse(vm.state.value.isAuthenticating)
        assertFalse(vm.state.value.isRetryingViaFlood)
        assertNull(succeeded)
        assertTrue(thrown.isEmpty())
        assertEquals(1, harness.nodeSession.sendLoginCalls.size)
    }

    @Test
    fun aLoginErrorLeavesTheAttemptInFlightAndPropagates() = runTest {
        val probe = AuthAttemptProbeError()
        val thrown = mutableListOf<Throwable>()
        val harness = harness(thrown, adminError = probe)
        val vm = viewModel(harness)
        var succeeded: RemoteNodeSessionDto? = null

        vm.authenticate { succeeded = it }
        runCurrent()

        assertTrue(vm.state.value.isAuthenticating)
        assertNull(vm.state.value.errorMessage)
        assertNull(succeeded)
        assertSame(probe, thrown.single())
        assertTrue(harness.nodeSession.sendLoginCalls.isEmpty())
    }

    private fun TestScope.harness(thrown: MutableList<Throwable>, adminError: Throwable? = null): AuthAttemptHarness {
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) + CoroutineExceptionHandler { _, error ->
                thrown += error
            },
        )
        return AuthAttemptHarness(scope, adminError)
    }

    private fun viewModel(
        harness: AuthAttemptHarness,
        errorText: (Throwable) -> String = { "radio" },
    ): NodeAuthViewModel = NodeAuthViewModel(
        scope = harness.scope,
        contact = floodContact(),
        role = RemoteNodeRole.REPEATER,
        dependencies = NodeAuthViewModel.Dependencies(
            repeaterAdminService = {
                harness.adminError?.let { throw it }
                harness.admin
            },
            roomServerService = { null },
            passwordStore = { harness.passwordStore },
            contactService = { null },
            radioId = { RADIO_ID },
            errorText = errorText,
        ),
    )
}

private const val DRAIN_STEPS = 10

private val RADIO_ID = Uuid.normalized("A0000000-0000-0000-0000-000000000064")

private fun floodContact() = ContactDto(
    id = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
    radioID = RADIO_ID,
    publicKey = ByteArray(PUBLIC_KEY_LENGTH) { 0xAA.toByte() },
    name = "Repeater",
    typeRawValue = ContactType.REPEATER.raw,
    flags = 0,
    outPathLength = FLOOD_PATH_LENGTH,
    outPath = ByteArray(0),
    lastAdvertTimestamp = 0,
    latitude = 0.0,
    longitude = 0.0,
    lastModified = 0,
    isBlocked = false,
    isMuted = false,
    isFavorite = false,
    unreadCount = 0,
)

private const val PUBLIC_KEY_LENGTH = 32
private const val FLOOD_PATH_LENGTH = 0xFF

private class AuthAttemptHarness(val scope: CoroutineScope, val adminError: Throwable?) {
    val nodeSession = FakeNodeSession()
    val store = InMemoryRemoteNodeStore()
    val passwordStore = InMemoryNodePasswordStore()
    val remote = RemoteNodeService(nodeSession, store, passwordStore, scope)
    val cli = NodeCliCoordinator(nodeSession, store, passwordStore, scope)
    val admin = RepeaterAdminService(remote, cli, nodeSession, store, passwordStore)
}

private class AuthAttemptFailure : Exception("radio")

private class AuthAttemptProbeError : Error("probe")
