package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed local flood-mirror save is logged after the radio path reset. Cancellation keeps the thrown
 * instance and does not log. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NodeCliPathResetSaveFaultTest {
    @Test
    fun aCancelledPathMirrorKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness(stopped)

        val thrown = assertFailsWith<CancellationException> {
            harness.cli.resetContactPathToFlood(harness.radioID, harness.publicKey)
        }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(PATH_MIRROR_FAILED) })
        assertResetWithoutLocalMirror(harness)
    }

    @Test
    fun aPathMirrorFailureIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(PathResetSaveFailure())

        harness.cli.resetContactPathToFlood(harness.radioID, harness.publicKey)

        assertLogged(harness, "radio")
    }

    @Test
    fun aPathMirrorErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness(PathResetSaveProbeError())

        harness.cli.resetContactPathToFlood(harness.radioID, harness.publicKey)

        assertLogged(harness, "probe")
    }

    private suspend fun assertLogged(harness: PathResetSaveHarness, detail: String) {
        val line = harness.logs.single()
        assertEquals("$PATH_MIRROR_FAILED: $detail", line.message)
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertResetWithoutLocalMirror(harness)
    }

    private suspend fun assertResetWithoutLocalMirror(harness: PathResetSaveHarness) {
        assertEquals(1, harness.radio.resetPathCalls.size)
        assertTrue(harness.store.savedContactFrames.isEmpty())
        assertEquals(0, harness.store.fetchContact(harness.radioID, harness.publicKey)!!.outPathLength)
    }

    private suspend fun TestScope.harness(failure: Throwable): PathResetSaveHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val radio = FakeNodeSession()
        val session = testSession()
        val backing = InMemoryRemoteNodeStore()
        backing.setContact(session.radioID, testContact(session.radioID, session.publicKey))
        val cli = NodeCliCoordinator(
            radio,
            PathResetSaveStore(backing, failure),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNode", record = { logs += it }),
        )
        return PathResetSaveHarness(cli, radio, backing, logs, session.radioID, session.publicKey)
    }
}

private class PathResetSaveStore(private val backing: InMemoryRemoteNodeStore, private val failure: Throwable) :
    RemoteNodeStore by backing {
    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame) = throw failure
}

private class PathResetSaveHarness(
    val cli: NodeCliCoordinator,
    val radio: FakeNodeSession,
    val store: InMemoryRemoteNodeStore,
    val logs: List<DebugLogEntryDto>,
    val radioID: Uuid,
    val publicKey: ByteArray,
)

private const val PATH_MIRROR_FAILED: String = "Path reset on radio but failed to sync local contact"

private class PathResetSaveFailure : Exception("radio")

private class PathResetSaveProbeError : Error("probe")
