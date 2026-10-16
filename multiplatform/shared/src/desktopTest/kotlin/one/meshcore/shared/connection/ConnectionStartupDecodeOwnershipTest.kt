package one.meshcore.shared.connection

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.connection.ConnectionStartupDecodeFixture.Companion.DEVICE_A
import one.meshcore.shared.connection.ConnectionStartupDecodeFixture.Companion.DEVICE_B
import one.meshcore.shared.connection.ConnectionStartupDecodeFixture.Companion.SCALAR_BYTES
import one.meshcore.shared.connection.ConnectionStartupDecodeFixture.Companion.TIMEOUT_MS
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConnectionStartupDecodeOwnershipTest {
    @Test
    fun retiredCompletedExportCannotReplaceReadyRadioMaterialOrReactivateMonitoring() = withFixture {
        val old = start(DEVICE_A, first)
        seed()
        assertNull(cache.snapshot().myPrivateKey)
        assertFalse(old.container.isEventMonitoringActive)
        val fresh = start(DEVICE_B, second)
        assertNotEquals(old.container.radioId, fresh.container.radioId)
        assertRetired(old)
        assertFalse(old.container.isEventMonitoringActive)
        finish(fresh)
        assertReady(fresh)
        assertContentEquals(second.payload.copyOf(SCALAR_BYTES), cache.snapshot().myPrivateKey)
        val before = storedRows()
        finish(old, obsolete = true)
        assertReady(fresh)
        assertRetired(old)
        assertEquals(before, storedRows())
        val observed = listOf(
            cache.snapshot().myPrivateKey.contentEquals(second.payload.copyOf(SCALAR_BYTES)),
            !old.container.isEventMonitoringActive,
        )
        assertEquals(listOf(true, true), observed, "Ready replacement cache/retired monitoring survival")
    }

    @Test
    fun cacheWaiterRechecksRetirementBeforePublicationAndMonitoring() = withFixture {
        val old = start(DEVICE_A, first)
        seed()
        assertNull(cache.snapshot().myPrivateKey)
        holdCache()
        first.release.complete(Unit)
        assertActiveOwner(old.caller, first.returning.await())
        barrier()
        assertTrue(old.caller.isActive)
        assertFalse(old.caller.isCompleted)
        assertFalse(old.container.sendQueue.isRetired)
        val fresh = start(DEVICE_B, second)
        assertNotEquals(old.container.radioId, fresh.container.radioId)
        assertRetired(old)
        assertFalse(old.container.isEventMonitoringActive)
        assertTrue(fresh.caller.isActive)
        assertFalse(second.release.isCompleted)
        val before = storedRows()
        releaseCache()
        finish(old, obsolete = true)
        assertRetired(old)
        assertEquals(before, storedRows())
        val observed = listOf(cache.snapshot().myPrivateKey == null, !old.container.isEventMonitoringActive)
        finish(fresh)
        assertReady(fresh)
        assertRetired(old)
        assertContentEquals(second.payload.copyOf(SCALAR_BYTES), cache.snapshot().myPrivateKey)
        assertEquals(listOf(true, true), observed, "Queued cache publication/retired monitoring survival")
    }

    @Test
    fun liveCompletedExportStillPublishesScalarAndStartsNormalMonitoring() = withFixture {
        val live = start(DEVICE_A, first)
        seed()
        val beforeNodes = nodes()
        val beforeDevices = devices()
        assertNull(cache.snapshot().myPrivateKey)
        assertFalse(live.container.isEventMonitoringActive)
        finish(live)
        assertReady(live)
        assertEquals(beforeNodes, nodes())
        assertEquals(beforeDevices, devices())
        assertTrue(database.contactDao().listAll().isEmpty())
        assertTrue(database.channelDao().listByRadio(live.container.radioId).isEmpty())
        assertEquals(1, live.session.getChannelsCallCount)
        assertContentEquals(first.payload.copyOf(SCALAR_BYTES), cache.snapshot().myPrivateKey)
    }

    private fun withFixture(block: suspend ConnectionStartupDecodeFixture.() -> Unit) {
        val fixture = ConnectionStartupDecodeFixture()
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
