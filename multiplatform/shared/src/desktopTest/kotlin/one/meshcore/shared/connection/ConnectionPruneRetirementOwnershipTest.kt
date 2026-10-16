package one.meshcore.shared.connection

import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionPruneRetirementFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionPruneRetirementFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionPruneRetirementFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.connection.ConnectionPruneRetirementFixture.Companion.newOnly
import one.meshcore.shared.connection.ConnectionPruneRetirementFixture.Companion.oldFrame
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionPruneRetirementOwnershipTest {
    @Test
    fun queuedOldPruneCannotDeleteFreshSameRadioContactAndHistory() = exercise {
        val old = start()
        seed(old)
        val originalN = database.contactDao().listByRadio(old.container.radioId)
            .single { it.publicKey.contentEquals(newOnly.publicKey) }
        val work = queuedPrune(old)
        val b = replace(DEVICE_B)
        assertTrue(first.closed && old.link.disconnected && old.container.sendQueue.isRetired)
        assertFalse(old.container.isEventMonitoringActive)
        assertTrue(old.container.contactService.contactSyncRetired.isCompleted && old.operation.isCancelled)
        assertTrue(work.context.job.isActive)
        assertFalse(work.ran.get())
        assertNotEquals(old.container.radioId, b.radioId)
        rows.enrich(b.radioId, b.selfPublicKey)
        val bBefore = rows.snapshot(b.radioId)
        val fresh = replace(DEVICE_A)
        assertEquals(old.container.radioId, fresh.radioId)
        assertNotSame(old.container, fresh)
        assertNotSame(first, manager.session)
        assertTrue(manager.connectGeneration > old.generation)
        val currentN = requireNotNull(database.contactDao().getById(originalN.id))
        assertEquals(fresh.radioId, currentN.radioID)
        assertContentEquals(originalN.publicKey, currentN.publicKey)
        assertEquals(newOnly.lastModified, currentN.lastModified)
        assertTrue(work.context.job.isActive && !work.ran.get())
        rows.enrich(fresh.radioId, fresh.selfPublicKey)
        val before = rows.snapshot(fresh.radioId)
        val preferences = preferences()
        val watermark = watermark(fresh.radioId)
        val generation = manager.connectGeneration
        joinOld(old)
        assertCurrent(fresh, DEVICE_A, generation)
        assertEquals(preferences, preferences())
        assertEquals(watermark, watermark(fresh.radioId))
        assertEquals(bBefore, rows.snapshot(b.radioId))
        assertEquals(0, first.autoFetchStartCount)
        assertEquals(0, first.getTimeCount)
        assertEquals(before, rows.snapshot(fresh.radioId), "LAST: queued retired prune deleted fresh A contact/history")
    }

    @Test
    fun liveQueuedPruneStillDeletesMissingOrdinaryContactAndHistory() = exercise {
        val old = start()
        seed(old)
        val original = database.contactDao().listByRadio(old.container.radioId)
        val retained = original.single { it.publicKey.contentEquals(oldFrame.publicKey) }
        val missing = original.single { it.publicKey.contentEquals(newOnly.publicKey) }
        val history = retainedHistory(retained.id, old.container.radioId)
        queuedPrune(old)
        joinOld(old)
        old.caller.await().getOrThrow()
        assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
        assertSame(old.container, manager.services)
        assertFalse(first.closed || old.container.sendQueue.isRetired)
        val merged = requireNotNull(database.contactDao().getById(retained.id))
        val expected = retained.copy(
            name = oldFrame.advertisedName, typeRawValue = oldFrame.typeRawValue,
            flags = (retained.flags and 1) or (oldFrame.flags and 1.inv()),
            outPathLength = oldFrame.outPathLength, outPath = oldFrame.outPath,
            lastAdvertTimestamp = oldFrame.lastAdvertisement, latitude = oldFrame.latitude,
            longitude = oldFrame.longitude, lastModified = oldFrame.lastModified,
        )
        assertEquals(rows.contactSnapshot(expected), rows.contactSnapshot(merged))
        assertEquals(history, retainedHistory(retained.id, old.container.radioId))
        assertNull(database.contactDao().getById(missing.id))
        assertEquals(1, database.messageDao().listByRadio(old.container.radioId).size)
        assertEquals(1, database.reactionDao().listByRadio(old.container.radioId).size)
        assertEquals(1, database.messageRepeatDao().listAll().size)
        assertEquals(1, first.autoFetchStartCount)
        assertEquals(0, first.rejectedClosedDispatches)
        noOutgoing()
    }

    private suspend fun ConnectionPruneRetirementFixture.retainedHistory(id: Uuid, radio: Uuid): List<Any> {
        val messages = database.messageDao().listByRadio(radio).filter { it.contactID == id }.sortedBy { it.id.value }
        assertTrue(messages.isNotEmpty())
        val ids = messages.map { it.id }.toSet()
        return listOf(
            messages.map {
                listOf(
                    it.copy(
                        pathNodes = null,
                        senderKeyPrefix = null,
                        linkPreviewImageData = null,
                        linkPreviewIconData = null,
                    ),
                    it.pathNodes?.toList(),
                    it.senderKeyPrefix?.toList(),
                    it.linkPreviewImageData?.toList(),
                    it.linkPreviewIconData?.toList(),
                )
            },
            database.reactionDao().listByRadio(radio).filter { it.contactID == id }.sortedBy { it.id.value },
            database.messageRepeatDao().listAll().filter { it.messageID in ids }.sortedBy { it.id.value }.map {
                it.copy(pathNodes = EMPTY) to it.pathNodes.toList()
            },
        )
    }

    private fun exercise(block: suspend ConnectionPruneRetirementFixture.() -> Unit) {
        val fixture = ConnectionPruneRetirementFixture()
        try {
            runBlocking(fixture.dispatcher) { withTimeout(TIMEOUT_MS) { fixture.block() } }
        } finally {
            fixture.close()
        }
    }

    companion object {
        private val EMPTY = ByteArray(0)
    }
}
