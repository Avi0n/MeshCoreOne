package one.meshcore.shared.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import one.meshcore.shared.chat.ChatFixtures
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class RoomContactDeleteAtomicityTest {
    private lateinit var fixture: RoomContactDeleteAtomicityFixture

    @BeforeTest
    fun setUp() {
        fixture = RoomContactDeleteAtomicityFixture()
    }

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    @Test
    fun failedFinalContactWriteRollsBackEveryPersistedChild() = runBlocking {
        fixture.seed()
        val before = fixture.snapshot()
        val fault = RoomContactDeleteAtomicityFixture.FinalDeleteFailure()
        fixture.failFinalDelete(fixture.firstContact, fault)

        val thrown = assertFailsWith<RoomContactDeleteAtomicityFixture.FinalDeleteFailure> {
            fixture.store.deleteContact(fixture.firstContact)
        }

        assertOriginalFault(fault, thrown)
        assertTrue(fixture.childRowsClearedAtFault, "failure occurs after real child deletes")
        assertEquals(before, fixture.snapshot(), "contact and full child DTOs roll back together")
    }

    @Test
    fun secondFinalContactWriteFailureRollsBackTheWholeBatch() = runBlocking {
        fixture.seed()
        val before = fixture.snapshot()
        val fault = RoomContactDeleteAtomicityFixture.FinalDeleteFailure()
        fixture.failFinalDelete(fixture.secondContact, fault)

        val thrown = assertFailsWith<RoomContactDeleteAtomicityFixture.FinalDeleteFailure> {
            fixture.store.deleteContacts(
                ChatFixtures.RADIO,
                listOf(fixture.firstKey, fixture.secondKey),
                skippingPublicKeys = { emptySet() },
            )
        }

        assertOriginalFault(fault, thrown)
        assertTrue(fixture.childRowsClearedAtFault)
        assertTrue(fixture.earlierContactRemovedAtFault, "the first real contact delete ran before failure")
        assertEquals(before, fixture.snapshot(), "all selected contacts and child DTOs roll back")
    }

    @Test
    fun cancelledFinalContactWriteRollsBackEveryPersistedChild() = runBlocking {
        fixture.seed()
        val before = fixture.snapshot()
        val cancelled = CancellationException("Fixture final write cancelled")
        fixture.failFinalDelete(fixture.firstContact, cancelled)

        val thrown = assertFailsWith<CancellationException> {
            fixture.store.deleteContact(fixture.firstContact)
        }

        assertOriginalFault(cancelled, thrown)
        assertTrue(fixture.childRowsClearedAtFault)
        assertEquals(before, fixture.snapshot())
    }

    @Test
    fun successfulSingleDeletePreservesTheOtherPartitionAndChannelHistory() = runBlocking {
        fixture.seed()
        val survivors = fixture.survivors()

        fixture.store.deleteContact(fixture.firstContact)

        assertNull(fixture.database.contactDao().getById(fixture.firstContact))
        assertEquals(0, fixture.childRowCount(fixture.firstContact))
        assertNotNull(fixture.database.contactDao().getById(fixture.secondContact))
        assertTrue(fixture.childRowCount(fixture.secondContact) > 0)
        assertEquals(survivors, fixture.survivors())
    }

    @Test
    fun successfulBatchDeletesOnlyTheSelectedPartitionContacts() = runBlocking {
        fixture.seed()
        val survivors = fixture.survivors()

        val deleted = fixture.store.deleteContacts(
            ChatFixtures.RADIO,
            listOf(fixture.firstKey, fixture.secondKey),
            skippingPublicKeys = { emptySet() },
        )

        assertEquals(listOf(fixture.firstContact, fixture.secondContact), deleted)
        assertNull(fixture.database.contactDao().getById(fixture.firstContact))
        assertNull(fixture.database.contactDao().getById(fixture.secondContact))
        assertEquals(0, fixture.childRowCount(fixture.firstContact))
        assertEquals(0, fixture.childRowCount(fixture.secondContact))
        assertEquals(survivors, fixture.survivors())
    }

    @Test
    fun messagesOnlyLateFailureRollsBackHistoryAndKeepsTheContact() = runBlocking {
        fixture.seed()
        val before = fixture.snapshot()
        val fault = RoomContactDeleteAtomicityFixture.FinalDeleteFailure()
        fixture.failFinalMessageDelete(fixture.firstContact, fault)

        val thrown = assertFailsWith<RoomContactDeleteAtomicityFixture.FinalDeleteFailure> {
            fixture.store.deleteMessagesForContact(fixture.firstContact)
        }

        assertOriginalFault(fault, thrown)
        assertTrue(fixture.priorChildRowsClearedAtMessageFault, "real dependent writes precede the message fault")
        assertNotNull(fixture.database.contactDao().getById(fixture.firstContact))
        assertEquals(before, fixture.snapshot(), "all history and pending columns roll back with the message failure")
    }

    @Test
    fun unreferencedFinalContactFailureRollsBackOrphanChildRows() = runBlocking {
        fixture.seed()
        fixture.prepareUnreferencedFirstContact()
        assertTrue(fixture.database.messageDao().directMessageIdsForContact(fixture.firstContact).isEmpty())
        assertTrue(fixture.childRowCount(fixture.firstContact) > 0, "orphan reactions and pending sends remain")
        val before = fixture.snapshot()
        val fault = RoomContactDeleteAtomicityFixture.FinalDeleteFailure()
        fixture.failFinalDelete(fixture.firstContact, fault)

        val thrown = assertFailsWith<RoomContactDeleteAtomicityFixture.FinalDeleteFailure> {
            fixture.store.deleteContactIfUnreferenced(fixture.firstContact)
        }

        assertOriginalFault(fault, thrown)
        assertTrue(fixture.childRowsClearedAtFault)
        assertEquals(before, fixture.snapshot(), "contact and orphan children roll back; there were no DMs")
    }

    private fun assertOriginalFault(expected: Throwable, actual: Throwable?) {
        if (actual === expected) return
        val recovered = assertNotNull(actual)
        assertEquals(expected.javaClass, recovered.javaClass)
        assertSame(expected, recovered.cause)
        assertTrue(recovered.stackTrace.any { it.className.startsWith(COROUTINE_RECOVERY_FRAME_PREFIX) })
    }

    private companion object {
        const val COROUTINE_RECOVERY_FRAME_PREFIX = "_COROUTINE."
    }
}
