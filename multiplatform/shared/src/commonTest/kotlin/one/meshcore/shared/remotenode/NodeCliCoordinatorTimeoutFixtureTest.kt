package one.meshcore.shared.remotenode

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.DebugLogEntryDto
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Admin writes start at [NodeCliCoordinator.sendRawCLICommand]. A flood-routed node has no
 * direct-path retry, so the first timeout is [RemoteNodeError.Timeout]. The next write is not
 * sent, the stored password stays, and a late OK is not a successful password change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliCoordinatorTimeoutFixtureTest {
    @Test
    fun firstTimeoutStopsTheWriteSequenceWithoutClearingThePassword() = runTest {
        val harness = harness(backgroundScope)
        val completed = mutableListOf<String>()
        var thrown: Throwable? = null
        runCurrent()

        val job = launch {
            thrown = runCatching {
                for (command in WRITE_COMMANDS) {
                    completed += harness.cli.sendRawCLICommand(harness.sessionId, command)
                }
            }.exceptionOrNull()
        }
        runCurrent()
        assertEquals(1, harness.session.sendCommandCalls.size)

        advanceUntilIdle()
        job.join()

        assertTrue(thrown is RemoteNodeError.Timeout)
        assertTrue(completed.isEmpty())
        assertEquals(listOf(PASSWORD_WRITE), wireCommands(harness))
        assertTrue(harness.session.resetPathCalls.isEmpty())
        assertTrue(harness.cli.pendingCLIRequests.isEmpty())
        assertEquals(STORED_PASSWORD, harness.passwords.retrievePassword(harness.publicKey))
        assertFalse(harness.audit.any { it.message.contains("CLI_RESP") })

        harness.session.emitCliResponse("OK")
        runCurrent()

        assertEquals(STORED_PASSWORD, harness.passwords.retrievePassword(harness.publicKey))
        assertEquals(listOf(PASSWORD_WRITE), wireCommands(harness))
        assertTrue(harness.cli.pendingCLIRequests.isEmpty())
        assertFalse(harness.audit.any { it.message.contains("CLI_RESP") })
    }

    @Test
    fun sendFailureStopsTheWriteSequenceWithoutClearingThePassword() = runTest {
        val harness = harness(backgroundScope, sendError = MeshError.NotConnected)
        val completed = mutableListOf<String>()
        runCurrent()

        val thrown = runCatching {
            for (command in WRITE_COMMANDS) {
                completed += harness.cli.sendRawCLICommand(harness.sessionId, command)
            }
        }.exceptionOrNull()

        assertTrue(thrown is RemoteNodeError.SessionError)
        assertEquals(MeshError.NotConnected, (thrown as RemoteNodeError.SessionError).error)
        assertTrue(completed.isEmpty())
        assertEquals(listOf(PASSWORD_WRITE), wireCommands(harness))
        assertTrue(harness.cli.pendingCLIRequests.isEmpty())
        assertEquals(STORED_PASSWORD, harness.passwords.retrievePassword(harness.publicKey))
        assertFalse(harness.audit.any { it.message.contains("CLI_RESP") })
    }

    private suspend fun harness(scope: CoroutineScope, sendError: Throwable? = null): Harness {
        val session = FakeNodeSession().apply { sendCommandError = sendError }
        val store = InMemoryRemoteNodeStore()
        val passwords = InMemoryNodePasswordStore()
        val dto = testSession(
            id = testUuid(1),
            publicKey = testPublicKey(),
            isConnected = true,
            permissionLevel = RoomPermissionLevel.ADMIN,
        )
        store.saveSession(dto)
        // A missing contact counts as direct and would retry once. Flood routing makes the first timeout terminal.
        store.setContactRouting(dto.radioID, dto.publicKey, isFloodRouted = true)
        passwords.storePassword(dto.publicKey, STORED_PASSWORD)
        val audit = mutableListOf<DebugLogEntryDto>()
        val cli = NodeCliCoordinator(
            session,
            store,
            passwords,
            scope,
            auditLogger = CommandAuditLogger(record = { audit += it }),
        )
        return Harness(session, passwords, cli, dto.id, dto.publicKey, audit)
    }

    private fun wireCommands(harness: Harness): List<String> =
        harness.session.sendCommandCalls.map { it.second.substringAfter('|') }

    private class Harness(
        val session: FakeNodeSession,
        val passwords: InMemoryNodePasswordStore,
        val cli: NodeCliCoordinator,
        val sessionId: Uuid,
        val publicKey: ByteArray,
        val audit: List<DebugLogEntryDto>,
    )

    private companion object {
        const val STORED_PASSWORD: String = "old-pw"
        const val PASSWORD_WRITE: String = "password newsecret"
        const val NEXT_WRITE: String = "set name Alpha"
        val WRITE_COMMANDS: List<String> = listOf(PASSWORD_WRITE, NEXT_WRITE)
    }
}
