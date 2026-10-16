package one.meshcore.shared.connection

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionContactBulkRetirementOwnershipTest {
    @Test
    fun retiredManualRefreshCannotCommitItsCompletedOldSnapshot() = retired(refresh = true)

    @Test
    fun retiredAdmittedWriterRollsBackItsUncommittedOldContact() = retired(refresh = false)

    @Test
    fun liveAdmittedWriterCommitsNormalMergeAndFullPrune() = exercise(refresh = false) {
        seed()
        val original = database.contactDao().listByRadio(radio)
        val retained = original.single { it.publicKey.contentEquals(ConnectionInitialContactReturnRows.KEY_P) }
        val missing = original.single { it.publicKey.contentEquals(ConnectionInitialContactReturnRows.KEY_N) }
        val retainedIds = database.messageDao().listByRadio(radio).filter {
            it.contactID == retained.id
        }.map { it.id }.toSet()
        start()
        assertNull(releaseAndJoin())
        val merged = requireNotNull(database.contactDao().getById(retained.id))
        val frame = ConnectionInitialContactReturnFixture.oldFrame
        val expected = retained.copy(
            name = frame.advertisedName, typeRawValue = frame.typeRawValue,
            flags = (retained.flags and 1) or (frame.flags and 1.inv()),
            outPathLength = frame.outPathLength, outPath = frame.outPath,
            lastAdvertTimestamp = frame.lastAdvertisement, latitude = frame.latitude,
            longitude = frame.longitude, lastModified = frame.lastModified,
        )
        assertEquals(rows.contactSnapshot(expected), rows.contactSnapshot(merged))
        assertNull(database.contactDao().getById(missing.id))
        assertEquals(retainedIds, database.messageDao().listByRadio(radio).map { it.id }.toSet())
        assertTrue(database.reactionDao().listByRadio(radio).all { it.contactID == retained.id })
        assertEquals(1, database.reactionDao().listByRadio(radio).size)
        assertTrue(database.messageRepeatDao().listAll().all { it.messageID in retainedIds })
        assertEquals(1, database.messageRepeatDao().listAll().size)
        noOutgoing()
    }

    private fun retired(refresh: Boolean) = exercise(refresh) {
        seed()
        val before = rows.snapshot(radio)
        start()
        retire()
        val cause = releaseAndJoin()
        val afterRetirement = rows.snapshot(radio)
        freshWritesRemainUsable()
        assertTrue(cause == null || cause is CancellationException)
        assertEquals(before, afterRetirement, "LAST: retired bulk operation committed old contact/history")
    }

    private fun exercise(refresh: Boolean, block: suspend ConnectionContactBulkRetirementFixture.() -> Unit) {
        val fixture = ConnectionContactBulkRetirementFixture(refresh)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(ConnectionContactBulkRetirementFixture.TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
