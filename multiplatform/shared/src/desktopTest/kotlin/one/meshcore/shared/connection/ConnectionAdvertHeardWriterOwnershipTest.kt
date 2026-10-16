package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionAdvertHeardWriterFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionAdvertHeardWriterFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionAdvertHeardWriterFixture.Companion.NOW
import one.meshcore.shared.connection.ConnectionAdvertHeardWriterFixture.Companion.PUBLIC_A
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ConnectionAdvertHeardWriterOwnershipTest {
    @Test
    fun canceledAdvertWriterCannotOverwriteFreshSameRadioContact() = exercise {
        val initial = readyA()
        val held = queueAdvert(initial)
        val initialP = held.before
        val b = replace(DEVICE_B)
        assertRetired(held)
        val bBefore = rows.snapshot(b.radioId)
        val fresh = replace(DEVICE_A)
        assertEquals(initial.radioId, fresh.radioId)
        val freshP = contact(fresh.radioId)
        assertEquals(initialP.id, freshP.id)
        assertContentEquals(initialP.publicKey, freshP.publicKey)
        rows.enrich(fresh.radioId, PUBLIC_A)
        editCurrent(fresh)
        val expected = rows.snapshot(fresh.radioId)
        val prefs = preferences()
        val watermark = watermark(fresh.radioId)
        val generation = manager.connectGeneration
        assertRetired(held)
        releaseAndJoin(held, live = false)
        assertCurrent(fresh, DEVICE_A, generation)
        assertEquals(bBefore, rows.snapshot(b.radioId))
        assertEquals(prefs, preferences())
        assertTrue(watermark(fresh.radioId) >= watermark)
        assertEquals(expected, rows.snapshot(fresh.radioId), "LAST: canceled advert replayed stale contact row")
    }

    @Test
    fun activeAdvertWriterPreservesConcurrentLocalEditsAndNewerHeardStamp() = exercise {
        val current = readyA()
        val held = queueAdvert(current)
        editCurrent(current)
        val expected = rows.snapshot(current.radioId)
        val prefs = preferences()
        val generation = manager.connectGeneration
        releaseAndJoin(held, live = true)
        assertCurrent(current, DEVICE_A, generation)
        assertEquals(prefs, preferences())
        assertEquals(expected, rows.snapshot(current.radioId), "LAST: active advert replayed stale contact metadata")
    }

    @Test
    fun liveAdvertWriterOnlyAdvancesHeardAndPreservesContactAndHistory() = exercise {
        val current = readyA()
        val held = queueAdvert(current)
        val before = rows.snapshot(current.radioId)
        val expected = rows.contactSnapshot(held.before.copy(lastHeardTimestamp = NOW))
        val generation = manager.connectGeneration
        releaseAndJoin(held, live = true)
        assertCurrent(current, DEVICE_A, generation)
        assertEquals(expected, rows.contactSnapshot(contact(current.radioId)))
        val after = rows.snapshot(current.radioId)
        assertEquals(before.drop(1), after.drop(1))
        val discovered = assertNotNull(database.discoveredNodeDao().find(current.radioId, held.before.publicKey))
        assertEquals(current.radioId, discovered.radioID)
        assertContentEquals(held.before.publicKey, discovered.publicKey)
        assertEquals(NOW, discovered.lastHeardEpochSeconds)
        assertEquals(held.before.name, discovered.name)
    }

    private fun exercise(block: suspend ConnectionAdvertHeardWriterFixture.() -> Unit) {
        val fixture = ConnectionAdvertHeardWriterFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(ConnectionAdvertHeardWriterFixture.TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
