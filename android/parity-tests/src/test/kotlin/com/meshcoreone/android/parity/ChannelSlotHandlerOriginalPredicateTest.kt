// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/ChannelServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: real Room persistence replaces SwiftData; callback assertions observe committed storage.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelInfo
import com.meshcoreone.android.core.protocol.session.ChannelFetchResult
import com.meshcoreone.android.core.protocol.session.ChannelSessionOps
import com.meshcoreone.android.core.services.contacts.ChannelService
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.Test

class ChannelSlotHandlerOriginalPredicateTest : RoomProcessTest() {
    private val radio = RadioId(UUID.randomUUID())

    private fun secret(byte: Int) = Bytes(ByteArray(16) { byte.toByte() })

    private suspend fun seedDevice() {
        store.saveDevice(DeviceDTO(
            id = radio.value, radioId = radio, publicKey = Bytes(ByteArray(32) { 1 }), nodeName = "TestDevice",
            firmwareVersion = 8u, firmwareVersionString = "v1.0.0", manufacturerName = "TestMfg",
            buildDate = "01 Jan 2025", maxContacts = 100u, maxChannels = 8u, multiAcks = 0u, isActive = true,
        ))
    }

    @OriginalCase("ChannelServiceTests::clearChannel deletes the row and its messages, then fires the slot handler()")
    @Test fun originalClearSlotFiveDeletesOldRowAndMessageBeforeItsSingleCallback() = runTest {
        seedDevice()
        store.saveChannel(radio, ChannelInfo(5u, "Old", secret(0x11)))
        val message = MessageDTO(
            radioId = radio, channelIndex = 5u, text = "old", timestamp = 1_704_067_200u,
            createdAt = Instant.ofEpochSecond(1_704_067_200), status = MessageStatus.SENT,
        )
        store.saveMessage(message)
        assertEquals(listOf(message.id), store.fetchMessages(radio, 5u).map { it.id })
        val session = StubSession()
        val service = ChannelService(session, store, null)
        val received = mutableListOf<Set<UByte>>()
        service.setSlotOccupantChangedHandler { actualRadio, indices ->
            assertEquals(radio, actualRadio)
            assertNull(store.fetchChannel(radio, 5u))
            assertTrue(store.fetchMessages(radio, 5u).isEmpty(), "Deletion must be committed before the callback")
            received += indices.toSet()
        }

        service.clearChannel(radio, 5u)

        assertNull(store.fetchChannel(radio, 5u))
        assertTrue(store.fetchMessages(radio, 5u).isEmpty())
        assertEquals(listOf(setOf<UByte>(5u)), received)
        assertEquals(listOf(ChannelInfo(5u, "", secret(0))), session.writes)
    }

    @OriginalCase("ChannelServiceTests::setChannel fires the slot handler on a new secret and not on a same-secret rename or first insert()")
    @Test fun originalFirstAndRenamedOneStaySilentThenReplacedTwoCommitsBeforeOneCallback() = runTest {
        seedDevice()
        val session = StubSession()
        val service = ChannelService(session, store, null)
        val received = mutableListOf<Set<UByte>>()
        var originalSecret: Bytes? = null
        service.setSlotOccupantChangedHandler { actualRadio, indices ->
            assertEquals(radio, actualRadio)
            val committed = assertNotNull(store.fetchChannel(radio, 5u))
            assertEquals("Replaced", committed.name)
            assertNotEquals(assertNotNull(originalSecret), committed.secret)
            received += indices.toSet()
        }

        service.setChannel(radio, 5u, "First", "one")
        val first = assertNotNull(store.fetchChannel(radio, 5u))
        assertEquals("First", first.name)
        originalSecret = first.secret
        service.setChannel(radio, 5u, "Renamed", "one")
        val renamed = assertNotNull(store.fetchChannel(radio, 5u))
        assertEquals("Renamed", renamed.name)
        assertEquals(first.secret, renamed.secret)
        assertTrue(received.isEmpty())

        service.setChannel(radio, 5u, "Replaced", "two")

        assertEquals(listOf(setOf<UByte>(5u)), received)
        assertEquals(listOf("First", "Renamed", "Replaced"), session.writes.map { it.name })
        assertEquals(listOf<UByte>(5u, 5u, 5u), session.writes.map { it.index })
    }

    @OriginalCase("ChannelServiceTests::syncChannels fires the slot handler for vacated and secret-changed slots, not first sightings()")
    @Test fun originalSerialFourSlotSyncCommitsChangedOneVacantTwoAndNewThreeBeforeOneCallback() = runTest {
        seedDevice()
        store.saveChannel(radio, ChannelInfo(1u, "One", secret(0x11)))
        store.saveChannel(radio, ChannelInfo(2u, "Two", secret(0x22)))
        val expected = listOf(ChannelInfo(1u, "OneB", secret(0x99)), ChannelInfo(3u, "First", secret(0x33)))
        val session = StubSession(expected.associateBy { it.index })
        val service = ChannelService(session, store, null)
        val received = mutableListOf<Set<UByte>>()
        service.setSlotOccupantChangedHandler { actualRadio, indices ->
            assertEquals(radio, actualRadio)
            assertNull(store.fetchChannel(radio, 2u))
            assertEquals(expected, store.fetchChannels(radio).sortedBy { it.index }.map {
                ChannelInfo(it.index, it.name, it.secret)
            }, "The complete changed/vacated/first-sighting pass must commit before notification")
            received += indices.toSet()
        }

        val result = service.syncChannels(radio, 4u, usePipelinedRead = false)

        assertEquals(listOf(setOf<UByte>(1u, 2u)), received)
        assertEquals(2L, result.channelsSynced)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf<UByte>(0u, 1u, 2u, 3u), session.reads)
        assertTrue(session.writes.isEmpty())
    }

    private class StubSession(private val channels: Map<UByte, ChannelInfo> = emptyMap()) : ChannelSessionOps {
        val reads = mutableListOf<UByte>()
        val writes = mutableListOf<ChannelInfo>()
        override suspend fun getChannel(index: UByte): ChannelInfo {
            reads += index
            return channels[index] ?: ChannelInfo(index, "", Bytes(ByteArray(16)))
        }
        override suspend fun getChannels(indices: List<UByte>): ChannelFetchResult =
            throw AssertionError("The original slot-handler sync uses serial reads, not a pipeline")
        override suspend fun setChannel(index: UByte, name: String, secret: Bytes) {
            writes += ChannelInfo(index, name, secret)
        }
    }
}
