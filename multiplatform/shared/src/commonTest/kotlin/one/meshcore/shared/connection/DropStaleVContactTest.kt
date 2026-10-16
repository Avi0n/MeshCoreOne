package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.contact.VContactIdentity
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DropStaleVContactTest {
    private val radioId = Uuid.normalized("A0000000-0000-0000-0000-000000000021")
    private val contactId = Uuid.normalized("B0000000-0000-0000-0000-000000000021")
    private val oldSelfKey = ByteArray(32) { 0x01 }
    private val oldVKey = requireNotNull(VContactIdentity.publicKey(oldSelfKey))

    @Test
    fun deletesTheStaleVContactAndRecordsTheIosInfoLine() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val deleted = mutableListOf<Uuid>()

        dropStaleVContactAfterIdentityRotation(
            previousRadioId = radioId,
            oldSelfPublicKey = oldSelfKey,
            fetchContactId = { id, key ->
                if (id == radioId && key.contentEquals(oldVKey)) contactId else null
            },
            deleteContact = { deleted += it },
            logger = connectionManagerLogger(this, recorded),
        )

        assertEquals(listOf(contactId), deleted)
        val line = recorded.single { it.message == DROPPED_STALE_V_CONTACT_LINE }
        assertEquals(DebugLogLevel.INFO, line.level)
        assertEquals("ConnectionManager", line.category)
    }

    @Test
    fun persistFaultRecordsTheIosWarningAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()

        dropStaleVContactAfterIdentityRotation(
            previousRadioId = radioId,
            oldSelfPublicKey = oldSelfKey,
            fetchContactId = { _, _ -> contactId },
            deleteContact = { error("db locked") },
            logger = connectionManagerLogger(this, recorded),
        )

        val line = recorded.single { it.message.contains("Failed to drop stale V-contact after identity rotation") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals("ConnectionManager", line.category)
        assertTrue(line.message.contains("db locked"))
    }

    @Test
    fun missingVContactIsANoOp() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        var deleted = false

        dropStaleVContactAfterIdentityRotation(
            previousRadioId = radioId,
            oldSelfPublicKey = oldSelfKey,
            fetchContactId = { _, _ -> null },
            deleteContact = { deleted = true },
            logger = connectionManagerLogger(this, recorded),
        )

        assertEquals(false, deleted)
        assertTrue(recorded.none { it.message.contains("V-contact") })
    }

    @Test
    fun cancellationIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val cancelled = CancellationException("teardown")
        val thrown = assertFailsWith<CancellationException> {
            dropStaleVContactAfterIdentityRotation(
                previousRadioId = radioId,
                oldSelfPublicKey = oldSelfKey,
                fetchContactId = { _, _ -> throw cancelled },
                deleteContact = {},
                logger = null,
            )
        }
        assertSame(cancelled, thrown)
    }

    @Test
    fun anErrorIsLoggedAndDoesNotThrow() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<DebugLogEntryDto>()
        val probe = DropStaleVContactProbeError()

        dropStaleVContactAfterIdentityRotation(
            previousRadioId = radioId,
            oldSelfPublicKey = oldSelfKey,
            fetchContactId = { _, _ -> contactId },
            deleteContact = { throw probe },
            logger = connectionManagerLogger(this, recorded),
        )

        val line = recorded.single { it.message.contains("Failed to drop stale V-contact after identity rotation") }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertTrue(line.message.contains("probe"))
    }
}

private class DropStaleVContactProbeError : Error("probe")
