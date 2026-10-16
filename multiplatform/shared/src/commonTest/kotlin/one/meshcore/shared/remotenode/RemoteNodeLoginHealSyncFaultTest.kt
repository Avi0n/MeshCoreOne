package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A local-row sync that fails after a login heal is logged and the retry continues. Cancellation keeps
 * the thrown instance and does not log that failure. An error is logged the same way and does not escape.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RemoteNodeLoginHealSyncFaultTest {
    @Test
    fun aCancelledLocalRowSyncKeepsTheThrownInstance() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val harness = harness { throw stopped }

        val thrown = assertFailsWith<CancellationException> {
            harness.service.addLocalContactToRadio(harness.publicKey, harness.radioID)
        }

        assertSame(stopped, thrown)
        assertTrue(harness.logs.none { it.message.contains(LOCAL_ROW_SYNC_FAILED) })
        assertTrue(harness.logs.none { it.message == RE_ADDED_MISSING_CONTACT_DURING_LOGIN })
        assertEquals(1, harness.radio.addContactCalls.size)
    }

    @Test
    fun aLocalRowSyncFailureIsLoggedAndTheRetryContinues() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness { throw LoginHealSyncFailure() }

        harness.service.addLocalContactToRadio(harness.publicKey, harness.radioID)

        assertSyncLogged(harness, "radio")
    }

    @Test
    fun aLocalRowSyncErrorIsLoggedAndDoesNotEscape() = runTest(UnconfinedTestDispatcher()) {
        val harness = harness { throw LoginHealSyncProbeError() }

        harness.service.addLocalContactToRadio(harness.publicKey, harness.radioID)

        assertSyncLogged(harness, "probe")
    }

    private fun assertSyncLogged(harness: HealSyncHarness, detail: String) {
        assertTrue(harness.logs.any { it.message == "$LOCAL_ROW_SYNC_FAILED: $detail" })
        assertTrue(harness.logs.any { it.message == RE_ADDED_MISSING_CONTACT_DURING_LOGIN })
        assertEquals(1, harness.radio.addContactCalls.size)
    }

    private suspend fun TestScope.harness(onSave: () -> Nothing): HealSyncHarness {
        val logs = mutableListOf<DebugLogEntryDto>()
        val radio = FakeNodeSession()
        val backing = InMemoryRemoteNodeStore()
        val dto = testSession()
        backing.saveSession(dto)
        backing.setContact(dto.radioID, testContact(dto.radioID, dto.publicKey))
        val service = RemoteNodeService(
            radio,
            HealSyncStore(backing, onSave),
            InMemoryNodePasswordStore(),
            backgroundScope,
            logger = PersistentLogger(this, category = "RemoteNodeService", record = { logs += it }),
        )
        return HealSyncHarness(service, radio, logs, dto.publicKey, dto.radioID)
    }
}

private class HealSyncStore(private val backing: InMemoryRemoteNodeStore, private val onSave: () -> Nothing) :
    RemoteNodeStore by backing {
    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame) {
        onSave()
    }
}

private class HealSyncHarness(
    val service: RemoteNodeService,
    val radio: FakeNodeSession,
    val logs: List<DebugLogEntryDto>,
    val publicKey: ByteArray,
    val radioID: Uuid,
)

private const val LOCAL_ROW_SYNC_FAILED: String = "Re-added contact to radio but failed to sync local row"

private class LoginHealSyncFailure : Exception("radio")

private class LoginHealSyncProbeError : Error("probe")
