package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.DebugLogEntryDto
import one.meshcore.shared.settings.DebugLogLevel
import one.meshcore.shared.settings.PersistentLogger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A failed insert-only rollback is logged and the saved row stays. Cancellation is rethrown
 * without that warning. A thrown error stays on the logged path.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ContactInsertRollbackFaultTest {
    private val radioID = ContactTestIds.RADIO

    @Test
    fun aCancelledInsertRollbackIsRethrown() = runTest(UnconfinedTestDispatcher()) {
        val stopped = CancellationException("stopped")
        val logs = mutableListOf<DebugLogEntryDto>()
        val insertedId = ContactTestIds.fresh()
        val seeded = seeded(stopped, CANCEL_KEY_BYTE, insertedId, logs)

        val thrown = assertFailsWith<CancellationException> {
            seeded.service.handleAdvertisement(radioID, publicKey(CANCEL_KEY_BYTE))
        }

        assertSame(stopped, thrown)
        assertEquals(CONTACT_NAME, seeded.store.fetchContact(insertedId)?.name)
        assertTrue(logs.none { it.message.contains(ROLLBACK_FAILED) || it.message.contains(SAVE_FAILED) })
    }

    @Test
    fun anInsertRollbackErrorIsLoggedAndTheRowStays() = runTest(UnconfinedTestDispatcher()) {
        val logs = mutableListOf<DebugLogEntryDto>()
        val insertedId = ContactTestIds.fresh()
        val seeded = seeded(InsertRollbackProbeError(), ERROR_KEY_BYTE, insertedId, logs)

        assertFalse(seeded.service.handleAdvertisement(radioID, publicKey(ERROR_KEY_BYTE)))

        assertEquals(CONTACT_NAME, seeded.store.fetchContact(insertedId)?.name)
        val line = logs.single { it.message.contains(ROLLBACK_FAILED) }
        assertEquals(DebugLogLevel.WARNING, line.level)
        assertEquals(insertOnlyRollbackFailedLine(insertedId, PROBE_TEXT), line.message)
        assertTrue(logs.none { it.message.contains(SAVE_FAILED) })
    }

    private fun TestScope.seeded(
        deleteError: Throwable,
        keyByte: Int,
        insertedId: Uuid,
        logs: MutableList<DebugLogEntryDto>,
    ): SeededInsertRollback {
        lateinit var service: ContactService
        val inner = InMemoryContactStore()
        val store = InsertRollbackFaultStore(inner, insertedId, keyByte, deleteError) { radio, key ->
            service.handleDeviceContactDeleted(radio, key)
        }
        val session = FakeContactSession().apply {
            singleContactLookup = meshContact(keyByte = keyByte, name = CONTACT_NAME)
        }
        service = testContactService(
            session = session,
            store = store,
            logger = PersistentLogger(
                scope = this,
                category = "AdvertisementService",
                record = { logs += it },
            ),
        )
        return SeededInsertRollback(store, service)
    }
}

private const val CANCEL_KEY_BYTE = 0x6A
private const val ERROR_KEY_BYTE = 0x6B
private const val CONTACT_NAME = "Ghost"
private const val ROLLBACK_FAILED = "Insert-only rollback failed"
private const val SAVE_FAILED = "Failed to save fetched contact"
private const val PROBE_TEXT = "probe"

private data class SeededInsertRollback(val store: InsertRollbackFaultStore, val service: ContactService)

private class InsertRollbackFaultStore(
    private val inner: InMemoryContactStore,
    private val insertedId: Uuid,
    private val keyByte: Int,
    private val deleteError: Throwable,
    private val onSave: suspend (Uuid, ByteArray) -> Unit,
) : ContactStore by inner {
    override suspend fun saveContact(radioID: Uuid, frame: ContactFrame): Uuid {
        if (frame.publicKey.isEmpty()) return inner.saveContact(radioID, frame)
        onSave(radioID, frame.publicKey)
        inner.saveContact(contactDto(id = insertedId, radioID = radioID, keyByte = keyByte, name = CONTACT_NAME))
        return insertedId
    }

    override suspend fun deleteContactIfUnreferenced(id: Uuid) {
        if (id != insertedId) {
            inner.deleteContactIfUnreferenced(id)
            return
        }
        throw deleteError
    }
}

private class InsertRollbackProbeError : Error("probe")
