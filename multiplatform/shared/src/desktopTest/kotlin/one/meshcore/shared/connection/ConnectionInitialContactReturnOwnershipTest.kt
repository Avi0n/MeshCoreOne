package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionInitialContactReturnFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionInitialContactReturnFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionInitialContactReturnFixture.Companion.PUBLIC_A
import one.meshcore.shared.connection.ConnectionInitialContactReturnFixture.Companion.TIMEOUT_MS
import one.meshcore.shared.connection.ConnectionInitialContactReturnFixture.Companion.newFrame
import one.meshcore.shared.connection.ConnectionInitialContactReturnFixture.Companion.newOnly
import one.meshcore.shared.connection.ConnectionInitialContactReturnFixture.Companion.oldFrame
import one.meshcore.shared.contact.toContactFrame
import one.meshcore.shared.model.MessageDirection
import one.meshcore.shared.persistence.direction
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ConnectionInitialContactReturnOwnershipTest {
    @Test
    fun oldCompletedContactResultCannotOverwriteFreshSameRadioOrPruneItsHistory() = exercise {
        val old = start()
        val b = replace(DEVICE_B)
        assertTrue(first.closed && old.link.disconnected && old.container.sendQueue.isRetired)
        assertFalse(old.container.isEventMonitoringActive)
        assertEquals(1, first.closeCount)
        assertNotEquals(old.container.radioId, b.radioId)
        rows.enrich(b.radioId, b.selfPublicKey)
        val foreignBefore = rows.snapshot(b.radioId)
        val fresh = replace(DEVICE_A)
        assertNotSame(old.container, fresh)
        assertNotSame(first, manager.session)
        assertTrue(manager.connectGeneration > old.generation)
        assertEquals(old.container.radioId, fresh.radioId)
        assertContentEquals(PUBLIC_A, fresh.selfPublicKey)
        assertEquals(2, database.contactDao().listByRadio(fresh.radioId).size)
        database.contactDao().listByRadio(fresh.radioId).forEach { assertEquals(newFrame.advertisedName, it.name) }
        rows.enrich(fresh.radioId, fresh.selfPublicKey)
        val before = rows.snapshot(fresh.radioId)
        val preferences = preferences()
        val watermark = watermark(fresh.radioId)
        val generation = manager.connectGeneration
        assertEquals(ConnectionInitialContactReturnRows.NEW_TIME, watermark)
        joinOld(old)
        assertCurrent(fresh, DEVICE_A, generation)
        assertEquals(preferences, preferences())
        assertEquals(watermark, watermark(fresh.radioId))
        assertEquals(foreignBefore, rows.snapshot(b.radioId))
        assertEquals(0, first.autoFetchStartCount)
        assertEquals(0, first.getTimeCount)
        assertEquals(
            before,
            rows.snapshot(fresh.radioId),
            "LAST: obsolete completed full snapshot changed fresh A contacts/history",
        )
    }

    @Test
    fun ownedCompletedFullResultStillMergesAndPrunesMissingOrdinaryHistory() = exercise {
        val held = start()
        val radio = held.container.radioId
        held.container.contactStore.saveContact(radio, newFrame.toContactFrame())
        held.container.contactStore.saveContact(radio, newOnly.toContactFrame())
        rows.enrich(radio, PUBLIC_A)
        val original = database.contactDao().listByRadio(radio)
        val retained = original.single { it.publicKey.contentEquals(oldFrame.publicKey) }
        val missing = original.single { it.publicKey.contentEquals(newOnly.publicKey) }
        val retainedMessages = database.messageDao().listByRadio(radio).filter { it.contactID == retained.id }
        assertTrue(retainedMessages.isNotEmpty() && retainedMessages.all { it.direction == MessageDirection.INCOMING })
        val retainedReactions = database.reactionDao().listByRadio(radio).filter { it.contactID == retained.id }
        assertTrue(retainedReactions.isNotEmpty())
        joinOld(held)
        held.caller.await().getOrThrow()
        assertEquals(ConnectionState.Ready(DEVICE_A), manager.state.value)
        assertSame(held.container, manager.services)
        assertFalse(first.closed || held.container.sendQueue.isRetired)
        val merged = requireNotNull(database.contactDao().getById(retained.id))
        val expected = retained.copy(
            name = oldFrame.advertisedName, typeRawValue = oldFrame.typeRawValue,
            flags = (retained.flags and 1) or (oldFrame.flags and 1.inv()),
            outPathLength = oldFrame.outPathLength, outPath = oldFrame.outPath,
            lastAdvertTimestamp = oldFrame.lastAdvertisement, latitude = oldFrame.latitude,
            longitude = oldFrame.longitude, lastModified = oldFrame.lastModified,
        )
        assertEquals(rows.contactSnapshot(expected), rows.contactSnapshot(merged))
        assertEquals(oldFrame.advertisedName, merged.name)
        assertEquals(oldFrame.lastModified, merged.lastModified)
        assertContentEquals(oldFrame.outPath, merged.outPath)
        assertEquals(retained.id, merged.id)
        assertEquals(retained.nickname, merged.nickname)
        assertTrue(merged.isFavorite && merged.isMuted && merged.isPinned)
        assertEquals(retained.unreadCount, merged.unreadCount)
        assertContentEquals(retained.avatarImageData, merged.avatarImageData)
        assertEquals(retainedMessages.map { it.id }, database.messageDao().listByRadio(radio).map { it.id })
        assertEquals(retainedReactions, database.reactionDao().listByRadio(radio))
        assertNull(database.contactDao().getById(missing.id))
        val ids = retainedMessages.map { it.id }.toSet()
        assertTrue(database.messageRepeatDao().listAll().all { it.messageID in ids })
        assertEquals(1, database.messageRepeatDao().listAll().size)
        assertEquals(0, first.rejectedClosedDispatches)
        assertEquals(1, first.autoFetchStartCount)
        noOutgoing()
    }

    private fun exercise(block: suspend ConnectionInitialContactReturnFixture.() -> Unit) {
        val fixture = ConnectionInitialContactReturnFixture()
        try {
            runBlocking(fixture.dispatcher) { withTimeout(TIMEOUT_MS) { fixture.block() } }
        } finally {
            fixture.close()
        }
    }
}
