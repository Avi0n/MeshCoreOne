// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorMessageHandlerTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.contracts.domain.EntityKey
import com.meshcoreone.android.core.model.ChannelDTO
import com.meshcoreone.android.core.model.ContactDTO
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import com.meshcoreone.android.core.model.RxLogEntryDTO
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.ChannelMessage
import com.meshcoreone.android.core.protocol.event.ContactMessage
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Swift `ParsedRxLogData` + `RxLogEntryDTO(radioID:from:...)` for a decrypted-status group-text row. */
internal fun groupTextRX(
    radioId: RadioId,
    channelIndex: UByte,
    senderTimestamp: UInt,
    pathNodes: Bytes,
    receivedAt: Instant,
    decodedText: String? = null,
    regionScope: String? = null,
): RxLogEntryDTO = RxLogEntryDTO(
    id = UUID.randomUUID(), radioId = radioId, receivedAt = receivedAt, snr = 5.0, rssi = -80, routeType = RouteType.FLOOD,
    payloadType = PayloadType.GROUP_TEXT, payloadVersion = 0u, transportCode = null, pathLength = pathNodes.size.toUByte(),
    pathNodes = pathNodes, packetPayload = Bytes.of(0xAA, 0xBB, 0xCC), rawPayload = Bytes.of(0x10, 0x20, 0x30),
    packetHash = UUID.randomUUID().toString(), channelIndex = channelIndex, channelName = "Public",
    decryptStatus = DecryptStatus.SUCCESS, senderTimestamp = senderTimestamp, regionScope = regionScope,
    regionScopeMatches = listOfNotNull(regionScope).let { com.meshcoreone.android.core.model.SnapshotList(it) },
    payloadTypeBits = 5u, decodedText = decodedText,
)

internal fun testContact(radioId: RadioId, name: String, publicKey: Bytes = key(0x42), isBlocked: Boolean = false, lastHeard: UInt? = 0u) =
    ContactDTO(radioId = radioId, publicKey = publicKey, name = name, isBlocked = isBlocked, lastHeardTimestamp = lastHeard)

internal fun channelMessage(text: String, senderTimestamp: Instant, channelIndex: UByte = 0u, pathLength: UByte = 0u) =
    ChannelMessage(channelIndex, pathLength, 0u, senderTimestamp, text, null)

internal fun contactMessage(prefix: Bytes, text: String, senderTimestamp: Instant, signature: Bytes? = null, textType: UByte = 0u) =
    ContactMessage(prefix, 0u, textType, senderTimestamp, signature, text, null)

/** Original SyncCoordinatorMessageHandlerTests. */
class SyncCoordinatorMessageHandlerTests {
    private val suite = "SyncCoordinatorMessageHandlerTests"
    private fun case(name: String, body: suspend SyncTestScope.() -> Unit) = syncCase(suite, name, body)
    private fun parse(name: String, input: String, sender: String?, text: String) = pureCase(suite, name) {
        val parsed = parseChannelMessage(input)
        assertEquals(sender, parsed.senderNodeName)
        assertEquals(text, parsed.messageText)
    }

    private suspend fun storeWithDevice(radioId: RadioId) = SyncInMemoryStore.createTestDataStore(radioId, nodeName = "TestNode")

    @TestFactory
    fun parsing(): List<DynamicTest> = listOf(
        parse("parseChannelMessage parses standard 'Name: text' format", "NodeAlpha: Hello world", "NodeAlpha", "Hello world"),
        parse("parseChannelMessage handles multiple colons", "Node: time is 12:30:00", "Node", "time is 12:30:00"),
        parse("parseChannelMessage returns nil sender for text without colon", "just plain text", null, "just plain text"),
        parse("parseChannelMessage returns nil sender for empty string", "", null, ""),
        parse("parseChannelMessage handles colon only — split omits empty subsequences", ":", null, ":"),
        parse("parseChannelMessage trims whitespace from sender and text", "  NodeName  :  hello there  ", "NodeName", "hello there"),
        parse("parseChannelMessage handles colon at start — leading empty part omitted by split", ": some text", null, ": some text"),
        parse("parseChannelMessage handles emoji in name", "Node🔥: hello", "Node🔥", "hello"),
        parse("parseChannelMessage handles unicode characters", "Ñoño: café time", "Ñoño", "café time"),
        parse("parseChannelMessage handles text with only sender and colon — trailing empty part omitted", "NodeName:", null, "NodeName:"),
    )

    @TestFactory
    fun blockedCache(): List<DynamicTest> = listOf(
        case("isBlockedSender returns false for empty cache") { assertFalse(coordinator().isBlockedSender("SomeNode")) },
        case("refreshBlockedContactsCache loads blocked contacts by name") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            store.saveContact(testContact(radioId, "BlockedPerson", isBlocked = true))
            coordinator.refreshBlockedContactsCache(radioId, store)
            assertTrue(coordinator.isBlockedSender("BlockedPerson"), "Blocked contact name should be in cache")
        },
        case("refreshBlockedContactsCache does not cache non-blocked contacts") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            store.saveContact(testContact(radioId, "NormalPerson"))
            coordinator.refreshBlockedContactsCache(radioId, store)
            assertFalse(coordinator.isBlockedSender("NormalPerson"))
        },
        case("refreshBlockedContactsCache replaces previous cache") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            val contact = testContact(radioId, "WasBlocked", isBlocked = true)
            store.saveContact(contact)
            coordinator.refreshBlockedContactsCache(radioId, store)
            assertTrue(coordinator.isBlockedSender("WasBlocked"))
            store.deleteContact(EntityKey(radioId, contact.id))
            coordinator.refreshBlockedContactsCache(radioId, store)
            assertFalse(coordinator.isBlockedSender("WasBlocked"))
        },
        case("isBlockedSender returns false for nil name") { assertFalse(coordinator().isBlockedSender(null)) },
        case("blockedSenderNames returns snapshot of cached names") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            store.saveContact(testContact(radioId, "Blocked1", key(1), isBlocked = true))
            store.saveContact(testContact(radioId, "Blocked2", key(2), isBlocked = true))
            coordinator.refreshBlockedContactsCache(radioId, store)
            val names = coordinator.blockedSenderNames()
            assertTrue("Blocked1" in names)
            assertTrue("Blocked2" in names)
        },
    )

    @TestFactory
    fun handlers(): List<DynamicTest> = listOf(
        case("wireMessageHandlers completes without error") {
            val radioId = newRadio()
            val services = SyncTestServices(storeWithDevice(radioId))
            coordinator().wireMessageHandlers(services.dependencies(), radioId)
            assertNotNull(services.polling.capturedContactMessageHandler)
            assertNotNull(services.polling.capturedChannelMessageHandler)
            assertNotNull(services.polling.capturedSignedMessageHandler)
            assertNotNull(services.polling.capturedCLIMessageHandler)
        },
        case("startDiscoveryEventMonitoring completes without error") {
            val coordinator = coordinator()
            val services = SyncTestServices()
            coordinator.startDiscoveryEventMonitoring(services.dependencies(), newRadio())
            settle()
            assertTrue(coordinator.isDiscoveryMonitoring)
            coordinator.cancelDiscoveryEventMonitoring()
            settle()
            assertFalse(coordinator.isDiscoveryMonitoring)
        },
        nativeCase("wired message handlers deliver a DM and channel message into their own conversations") {
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            val contact = testContact(radioId, "Peer")
            val channel = ChannelDTO(radioId = radioId, index = 2u, name = "Oracle")
            store.saveContact(contact)
            store.saveChannel(channel)
            val services = SyncTestServices(store)
            val coordinator = coordinator()
            coordinator.wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedContactMessageHandler)(
                contactMessage(contact.publicKey.prefix(6), "direct oracle", clock.now()), contact, DeliveryContext.Live,
            )
            checkNotNull(services.polling.capturedChannelMessageHandler)(
                channelMessage("Peer: channel oracle", clock.now(), channel.index), channel, DeliveryContext.Live,
            )
            assertEquals(listOf("direct oracle"), store.fetchMessages(EntityKey(radioId, contact.id), 50, 0).map { it.text })
            assertEquals(listOf("channel oracle"), store.fetchMessages(radioId, channel.index, 50, 0).map { it.text })
            assertEquals(2, coordinator.conversationsVersion)
            assertEquals(listOf("dm", "channel"), services.notifications.posts.map { it.kind })
        },
        nativeCase("discovery monitor delivers events and releases cancelled and replaced subscriptions") {
            val coordinator = coordinator()
            val radioId = newRadio()
            val first = SyncTestServices()
            val second = SyncTestServices()
            fun event(name: String) = SyncDiscoveryEvent.NewContactDiscovered(name, UUID.randomUUID(), ContactType.CHAT)
            coordinator.startDiscoveryEventMonitoring(first.dependencies(), radioId)
            settle()
            val firstJob = checkNotNull(coordinator.locked { discoveryEventsJob })
            assertEquals(1, first.adverts.subscriberCount)
            val firstEvent = event("First")
            first.adverts.emit(firstEvent)
            settle()
            assertEquals(1, coordinator.contactsVersion)
            assertEquals(firstEvent.contactID, first.notifications.posts.single().id)

            coordinator.startDiscoveryEventMonitoring(second.dependencies(), radioId)
            firstJob.join()
            settle()
            assertTrue(firstJob.isCancelled)
            assertEquals(0, first.adverts.subscriberCount)
            assertEquals(1, second.adverts.subscriberCount)
            first.adverts.emit(event("Stale"))
            val replacementEvent = event("Replacement")
            second.adverts.emit(replacementEvent)
            settle()
            assertEquals(2, coordinator.contactsVersion, "only the replacement subscription delivers")
            assertEquals(replacementEvent.contactID, second.notifications.posts.single().id)
            assertEquals(1, first.notifications.posts.size)

            val replacementJob = checkNotNull(coordinator.locked { discoveryEventsJob })
            coordinator.cancelDiscoveryEventMonitoring()
            replacementJob.join()
            assertTrue(replacementJob.isCancelled)
            assertFalse(coordinator.isDiscoveryMonitoring)
            assertEquals(0, second.adverts.subscriberCount)
            second.adverts.emit(event("Cancelled"))
            settle()
            assertEquals(2, coordinator.contactsVersion)
            assertEquals(1, second.notifications.posts.size)
        },
        pureCase(suite, "Channel message that resolves to no local channel must not post a notification") {
            assertFalse(shouldPostChannelNotification(null))
        },
        pureCase(suite, "Channel message that resolves to a known local channel posts a notification") {
            val channel = ChannelDTO(radioId = RadioId(UUID.randomUUID()), index = 3u, name = "Test", secret = Bytes(ByteArray(16) { 1 }))
            assertTrue(shouldPostChannelNotification(channel))
        },
        case("inbound DM stamps contact lastHeard") {
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            val publicKey = key(0xAB)
            val contact = testContact(radioId, "Peer", publicKey, lastHeard = 0u)
            store.saveContact(contact)
            val services = SyncTestServices(store)
            coordinator().wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedContactMessageHandler)(
                contactMessage(publicKey.prefix(6), "hello mesh", clock.now()), contact, DeliveryContext.Live,
            )
            val updated = checkNotNull(store.fetchContact(radioId, publicKey))
            assertTrue((updated.lastHeardTimestamp ?: 0u) > 0u)
        },
        case("inbound channel message does not stamp lastHeard on a contact") {
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            val publicKey = key(0xCD)
            store.saveContact(testContact(radioId, "ChannelPeer", publicKey, lastHeard = 0u))
            val services = SyncTestServices(store)
            coordinator().wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(
                channelMessage("ChannelPeer: hello channel", clock.now()), null, DeliveryContext.Live,
            )
            assertEquals(0u, checkNotNull(store.fetchContact(radioId, publicKey)).lastHeardTimestamp ?: 0u)
        },
        case("live channel receive with undecryptable RX does not copy regionScope onto Message") {
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            val senderTimestamp = 1_704_000_500u
            // Dummy 0x88 payload cannot re-decrypt (no decoded text), so key matching misses.
            store.saveRxLogEntry(groupTextRX(radioId, 0u, senderTimestamp, Bytes.EMPTY, clock.now(), regionScope = "Germany"))
            val services = SyncTestServices(store)
            coordinator().wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(
                channelMessage("NodeAlpha: region scope test", Instant.ofEpochSecond(senderTimestamp.toLong())), null, DeliveryContext.Live,
            )
            val message = store.fetchMessages(radioId, 0u, 50, 0).first()
            assertNull(message.regionScope)
            assertTrue(message.regionScopeMatches.isEmpty())
        },
        case("channel save with undecryptable RX harvests no extras and still broadcasts") {
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            val senderTimestamp = 1_704_000_700u
            val earlier = Instant.ofEpochSecond(senderTimestamp.toLong())
            store.saveRxLogEntry(groupTextRX(radioId, 0u, senderTimestamp, Bytes.of(0xA1), earlier))
            store.saveRxLogEntry(groupTextRX(radioId, 0u, senderTimestamp, Bytes.of(0xB2), earlier.plusSeconds(1)))
            val services = SyncTestServices(store)
            val coordinator = coordinator()
            val events = coordinator.dataEventBroadcaster.subscribe()
            val received = ValueTracker<SyncDataEvent>()
            val collector = launch { events.collect { received.record(it) } }
            coordinator.wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(
                channelMessage("NodeAlpha: harvest broadcast", earlier, pathLength = 1u), null, DeliveryContext.Live,
            )
            settle()
            val saved = store.fetchMessages(radioId, 0u, 50, 0)
            assertEquals(1, saved.size)
            assertEquals(0, saved.first().heardRepeats)
            assertTrue(store.fetchMessageRepeats(EntityKey(radioId, saved.first().id)).isEmpty())
            val broadcast = received.values.filterIsInstance<SyncDataEvent.ChannelMessageReceived>().firstOrNull()
            assertNotNull(broadcast, "channel message must still broadcast")
            assertEquals(0, broadcast.message.heardRepeats)
            collector.cancel()
        },
        case("duplicate channel receive with unknown path does not save a second message") {
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            val channel = ChannelDTO(radioId = radioId, index = 0u, name = "Public")
            store.saveChannel(channel)
            val senderTimestamp = 1_704_000_800u
            val dedupKey = fakeDeduplicationKey.contentBased(null, 0u, "NodeAlpha", senderTimestamp, "dup path")
            val existing = incomingChannelRow(radioId, "dup path", senderTimestamp, Bytes.of(0xA1), dedupKey)
            store.saveMessage(existing)
            store.saveRxLogEntry(groupTextRX(radioId, 0u, senderTimestamp, Bytes.of(0xB2), clock.now()))
            val services = SyncTestServices(store)
            coordinator().wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(
                channelMessage("NodeAlpha: dup path", Instant.ofEpochSecond(senderTimestamp.toLong()), pathLength = 1u), channel, DeliveryContext.Live,
            )
            val saved = store.fetchMessages(radioId, 0u, 50, 0)
            assertEquals(listOf(existing.id), saved.map { it.id })
            assertTrue(store.fetchMessageRepeats(EntityKey(radioId, existing.id)).isEmpty())
            assertEquals(0, store.fetchMessage(EntityKey(radioId, existing.id))?.heardRepeats)
            assertEquals(0, store.fetchChannel(EntityKey(radioId, channel.id))?.unreadCount)
        },
        case("same-path duplicate channel receive inserts nothing") {
            val radioId = newRadio()
            val store = storeWithDevice(radioId)
            val senderTimestamp = 1_704_000_900u
            val dedupKey = fakeDeduplicationKey.contentBased(null, 0u, "NodeAlpha", senderTimestamp, "same path")
            val existing = incomingChannelRow(radioId, "same path", senderTimestamp, Bytes.of(0xA1), dedupKey)
            store.saveMessage(existing)
            store.saveRxLogEntry(groupTextRX(radioId, 0u, senderTimestamp, Bytes.of(0xA1), clock.now()))
            val services = SyncTestServices(store)
            coordinator().wireMessageHandlers(services.dependencies(), radioId)
            checkNotNull(services.polling.capturedChannelMessageHandler)(
                channelMessage("NodeAlpha: same path", Instant.ofEpochSecond(senderTimestamp.toLong()), pathLength = 1u), null, DeliveryContext.Live,
            )
            assertEquals(1, store.fetchMessages(radioId, 0u, 50, 0).size)
            assertTrue(store.fetchMessageRepeats(EntityKey(radioId, existing.id)).isEmpty())
            assertEquals(0, store.fetchMessage(EntityKey(radioId, existing.id))?.heardRepeats)
        },
    )
}

/** Swift `MessageDTO.testChannelMessage(...)` with a known path and key. */
internal fun incomingChannelRow(radioId: RadioId, text: String, timestamp: UInt, pathNodes: Bytes, dedupKey: String) = MessageDTO(
    radioId = radioId, channelIndex = 0u, text = text, timestamp = timestamp, direction = MessageDirection.INCOMING,
    status = MessageStatus.DELIVERED, pathLength = 1u, pathNodes = pathNodes, senderNodeName = "NodeAlpha", deduplicationKey = dedupKey,
)
