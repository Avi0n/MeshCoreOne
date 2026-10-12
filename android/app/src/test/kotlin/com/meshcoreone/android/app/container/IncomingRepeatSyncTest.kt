// AndroidOnly: WP-216 Real Room and mounted sync regressions for incoming heard-repeat forwarding.
package com.meshcoreone.android.app.container

import com.meshcoreone.android.app.state.MessageEvent
import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.contracts.domain.HeardRepeatPersisting
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DeduplicationKey
import com.meshcoreone.android.core.model.DeviceDTO
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageRepeatDTO
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.crypto.WireCrypto
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.services.reactions.HeardRepeatsService
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IncomingRepeatSyncTest : RoomProcessTest() {
    private val secret = Bytes(ByteArray(16) { (it + 1).toByte() })
    private val stamp = EPOCH.epochSecond.toUInt()
    private val text = "Alice: hello"
    private val contentKey = DeduplicationKey.contentBased(null, 0u, "Alice", stamp, "hello")

    private suspend fun withSession(
        test: TestScope,
        block: suspend (ContainerHarness, RadioSessionContainer) -> Unit,
    ) {
        val harness = ContainerHarness(test, store)
        harness.links.configureRadio = { radio ->
            radio.respond = { command ->
                if (command[0] == 0x1f.toUByte() && command[1] == 0.toUByte()) {
                    listOf(Bytes.of(0x12, 0) + Bytes.utf8("Test").paddedOrTruncated(32) + secret)
                } else null
            }
        }
        try {
            harness.manager.connect(harness.target())
            harness.eventually("real session ready") {
                harness.manager.connectionState ==
                    com.meshcoreone.android.core.contracts.domain.DeviceConnectionState.READY
            }
            val live = assertNotNull(harness.sessions.current)
            assertTrue(live.messagePollingService.hasMessageHandlersWired)
            live.channelService.syncChannels(live.token.radioId, 1u, usePipelinedRead = false)
            assertEquals(secret, store.fetchChannels(live.token.radioId).single().secret)
            block(harness, live)
        } finally {
            harness.container.close()
        }
    }

    private fun entry(radio: RadioId, path: Bytes, offset: Long): RxLogEntryDTO {
        val ciphertext = WireCrypto.encryptAes128EcbZeroPadded(
            le32(stamp.toLong()) + Bytes.of(0) + Bytes.utf8(text), secret,
        )
        val channelHash = Bytes(MessageDigest.getInstance("SHA-256").digest(secret.toByteArray())).prefix(1)
        val payload = channelHash + WireCrypto.truncatedHmacSha256(ciphertext, secret) + ciphertext
        return RxLogEntryDTO(
            id = UUID.randomUUID(), radioId = radio, receivedAt = EPOCH.plusMillis(offset),
            snr = 8.0 + offset, rssi = -77, routeType = RouteType.FLOOD,
            payloadType = PayloadType.GROUP_TEXT, payloadVersion = 0u, transportCode = null,
            pathLength = path.size.toUByte(), pathNodes = path, packetPayload = payload,
            rawPayload = Bytes.of(0x15, path.size) + path + payload,
            packetHash = Bytes(MessageDigest.getInstance("SHA-256").digest(payload.toByteArray())).prefix(8).hexString,
            channelIndex = 0u, channelName = "Test", decryptStatus = DecryptStatus.SUCCESS,
            senderTimestamp = stamp, payloadTypeBits = 5u,
        )
    }

    private fun incoming(radio: RadioId, path: Bytes?) = MessageDTO(
        radioId = radio, channelIndex = 0u, text = "hello", timestamp = stamp,
        createdAt = EPOCH, sortDate = EPOCH, direction = MessageDirection.INCOMING,
        status = MessageStatus.DELIVERED, pathNodes = path, pathLength = path?.size?.toUByte() ?: 0u,
        senderNodeName = "Alice", deduplicationKey = contentKey,
    )

    private suspend fun poll(harness: ContainerHarness, live: RadioSessionContainer, pathLength: Int) {
        val radio = harness.links.radios.last()
        val prior = radio.respond
        val frames = ConcurrentLinkedQueue(listOf(
            Bytes.of(0x11, 20, 0, 0, 0, pathLength, 0) + le32(stamp.toLong()) + Bytes.utf8(text),
        ))
        radio.respond = { command ->
            if (command[0] == 0x0a.toUByte()) listOf(frames.poll() ?: Bytes.of(0x0a))
            else prior(command)
        }
        try {
            assertEquals(1L, live.messagePollingService.pollAllMessages())
        } finally {
            radio.respond = prior
        }
    }

    @Test fun mountedSyncHarvestsPreexistingEncryptedRowsAndRefreshesTheMessageEvent() = runTest {
        withSession(this) { harness, live ->
            val radio = live.token.radioId
            val first = entry(radio, Bytes.of(0xA1), 1)
            val later = entry(radio, Bytes.of(0xB2, 0xB3), 2)
            for (row in listOf(later, first)) store.saveRxLogEntry(row)
            val decoded = live.rxLogService.decodedEntries(listOf(later, first))
            assertEquals(listOf(text, text), decoded.map { it.decodedText })
            for (row in decoded) assertNull(live.heardRepeatsService.processForRepeats(row))
            assertNull(store.fetchMessage(contentKey, radio))
            val events = CopyOnWriteArrayList<MessageEvent>()
            val observer = backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) {
                harness.appState.messageEventStream.events().collect { events += it }
            }
            try {
                poll(harness, live, 1)
                val saved = assertNotNull(store.fetchMessage(contentKey, radio))
                assertEquals(first.pathNodes, saved.pathNodes)
                assertEquals(1L, saved.heardRepeats)
                val repeat = store.fetchMessageRepeats(EntityKey(radio, saved.id)).single()
                assertEquals(later.id, repeat.rxLogEntryID)
                assertEquals(later.pathNodes, repeat.pathNodes)
                assertEquals(later.pathLength, repeat.pathLength)
                assertEquals(later.receivedAt, repeat.receivedAt)
                assertEquals(later.snr, repeat.snr)
                assertEquals(later.rssi, repeat.rssi)
                harness.eventually("refreshed incoming and repeat events") {
                    events.filterIsInstance<MessageEvent.ChannelMessageReceived>().any {
                        it.message.id == saved.id && it.message.heardRepeats == 1L
                    } && events.contains(MessageEvent.HeardRepeatRecorded(saved.id, 1))
                }
                assertFalse(live.reportedGaps.any { it.startsWith("HeardRepeatsService.") })
            } finally {
                observer.cancelAndJoin()
            }
        }
    }

    @Test fun mountedDuplicateArrivalForwardsTheDistinctPathWithoutAnotherMessageOrRepeat() = runTest {
        withSession(this) { harness, live ->
            val radio = live.token.radioId
            val original = incoming(radio, Bytes.of(0xA1))
            store.saveMessage(original)
            val row = entry(radio, Bytes.of(0xB2, 0xB3), 2)
            store.saveRxLogEntry(row)
            poll(harness, live, 2)
            val key = EntityKey(radio, original.id)
            assertEquals(original.pathNodes, assertNotNull(store.fetchMessage(key)).pathNodes)
            assertEquals(1L, assertNotNull(store.fetchMessage(key)).heardRepeats)
            val repeat = store.fetchMessageRepeats(key).single()
            assertEquals(row.pathNodes, repeat.pathNodes)
            assertEquals(row.pathLength, repeat.pathLength)
            assertEquals(5.0, repeat.snr)
            assertNull(repeat.rssi)
            assertNull(repeat.rxLogEntryID)
            poll(harness, live, 2)
            assertEquals(1L, assertNotNull(store.fetchMessage(key)).heardRepeats)
            assertEquals(listOf(repeat), store.fetchMessageRepeats(key))
            assertEquals(listOf(original.id), store.fetchMessages(radio, 0u).map { it.id })
        }
    }

    @Test fun realAdapterAdoptsTheEarliestUnknownPathAndKeepsTheSameIdOnAnotherRadioUntouched() = runTest {
        withSession(this) { _, live ->
            val radio = live.token.radioId
            val other = RadioId(UUID.randomUUID())
            store.saveDevice(DeviceDTO(radioId = other, publicKey = key(8), nodeName = "Other"))
            val unknown = incoming(radio, null)
            val foreign = unknown.copy(radioId = other, pathNodes = Bytes.of(0xC4), pathLength = 1u)
            store.saveMessage(unknown)
            store.saveMessage(foreign)
            val first = entry(radio, Bytes.of(0xA1), 1)
            val later = entry(radio, Bytes.of(0xB2), 2)
            val decoded = live.rxLogService.decodedEntries(listOf(later, first))
            val adapter = SyncHeardRepeatsAdapter(live.heardRepeatsService)
            adapter.harvestIncomingPaths(unknown, decoded)
            val key = EntityKey(radio, unknown.id)
            val saved = assertNotNull(store.fetchMessage(key))
            assertEquals(first.pathNodes, saved.pathNodes)
            assertEquals(1L, saved.heardRepeats)
            assertEquals(later.id, store.fetchMessageRepeats(key).single().rxLogEntryID)
            assertEquals(foreign, store.fetchMessage(EntityKey(other, unknown.id)))
            assertTrue(store.fetchMessageRepeats(EntityKey(other, unknown.id)).isEmpty())
            adapter.harvestIncomingPaths(saved, decoded)
            assertEquals(1L, assertNotNull(store.fetchMessage(key)).heardRepeats)
            assertEquals(1, store.fetchMessageRepeats(key).size)
        }
    }

    @Test fun adapterPropagatesTheSameCallerCancellationWithoutPersistingARepeat() = runTest {
        withSession(this) { _, live ->
            val original = incoming(live.token.radioId, Bytes.of(0xA1))
            store.saveMessage(original)
            val cancellation = CancellationException("caller stopped incoming path recording")
            val cancellingStore = object : HeardRepeatPersisting by store {
                override suspend fun fetchMessageRepeats(message: EntityKey): SnapshotList<MessageRepeatDTO> =
                    throw cancellation
            }
            val adapter = SyncHeardRepeatsAdapter(HeardRepeatsService(cancellingStore))
            assertSame(cancellation, assertFailsWith<CancellationException> {
                adapter.recordDistinctPathIfNeeded(original, Bytes.of(0xB2), 1u, 8.0, -77, EPOCH, null)
            })
            val key = EntityKey(original.radioId, original.id)
            assertEquals(original, store.fetchMessage(key))
            assertTrue(store.fetchMessageRepeats(key).isEmpty())
        }
    }
}
