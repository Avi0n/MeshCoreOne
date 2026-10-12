// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceSendTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceSendDMBookkeepingTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/MessageServiceListenerIntegrationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.bytes.ByteWriter
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.EventDispatcher
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.CommandCode
import com.meshcoreone.android.core.protocol.model.ResponseCode
import java.util.UUID
import kotlin.test.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.jupiter.api.TestFactory

@OptIn(ExperimentalCoroutinesApi::class)
class SourceSendTest {
    private fun sourceContact() =
        ContactDTO(CONTACT, RADIO, Bytes(ByteArray(32) { 0xAB.toByte() }), "TestContact",
            typeRawValue = 1u, lastHeardTimestamp = null)

    private fun sourceDevice(h: Harness) =
        DeviceDTO(id = RADIO.value, radioId = RADIO, publicKey = SELF, nodeName = "Node",
            firmwareVersion = 9u, firmwareVersionString = "v1.13.0", manufacturerName = "TestMfg",
            buildDate = "01 Jan 2025", lastConnected = h.clock.wallClock.instant(), isActive = true)

    private fun installedSelfInfoPacket(publicKey: Bytes): Bytes =
        ByteWriter().appendUInt8(ResponseCode.SELF_INFO.rawValue)
            .appendUInt8(0u).appendUInt8(20u).appendUInt8(20u).append(publicKey)
            .appendInt32LE(0).appendInt32LE(0).appendUInt8(2u).appendUInt8(0u)
            .appendUInt8(2u).appendUInt8(0u).appendUInt32LE(915_000u).appendUInt32LE(250_000u)
            .appendUInt8(10u).appendUInt8(5u).append(Bytes.utf8("TestNode")).toBytes()

    @TestFactory fun sending() = listOf(
        original("MessageServiceSendTests", "sendDirectMessage throws invalidRecipient for repeater contacts") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            assertEquals(MessageServiceError.InvalidRecipient, assertFailsWith<MessageServiceException> {
                h.service.sendDirectMessage("Hello", h.contact.copy(typeRawValue = 2u))
            }.error); assertTrue(h.store.messages.isEmpty()); h.close()
        },
        original("MessageServiceSendTests", "sendDirectMessage throws messageTooLong for oversized text") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            assertEquals(MessageServiceError.MessageTooLong, assertFailsWith<MessageServiceException> {
                h.service.sendDirectMessage("a".repeat(151), h.contact)
            }.error); h.close()
        },
        original("MessageServiceSendTests", "sendDirectMessage saves message to dataStore before send attempt") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            assertFailsWith<MessageServiceException> { h.service.sendDirectMessage("Hello", h.contact) }
            val messages = h.store.fetchMessages(EntityKey(RADIO, h.contact.id), 10, 0)
            assertTrue(messages.isNotEmpty())
            assertEquals("Hello", messages.first().text)
            assertEquals(MessageDirection.OUTGOING, messages.first().direction)
            assertEquals("Hello", h.store.messages.values.single().text)
            assertEquals(MessageDirection.OUTGOING, h.store.messages.values.single().direction); h.close()
        },
        original("MessageServiceSendTests", "sendMessageWithRetry throws invalidRecipient for repeater contacts") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            assertEquals(MessageServiceError.InvalidRecipient, assertFailsWith<MessageServiceException> {
                h.service.sendMessageWithRetry("Hello", h.contact.copy(typeRawValue = 2u))
            }.error); h.close()
        },
        original("MessageServiceSendTests", "sendMessageWithRetry throws messageTooLong for oversized text") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            assertEquals(MessageServiceError.MessageTooLong, assertFailsWith<MessageServiceException> {
                h.service.sendMessageWithRetry("a".repeat(151), h.contact)
            }.error); h.close()
        },
        original("MessageServiceSendTests", "createPendingMessage creates message with pending status") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            val m = h.service.createPendingMessage("Pending", h.contact)
            assertEquals(MessageStatus.PENDING, m.status); assertEquals(MessageDirection.OUTGOING, m.direction)
            assertEquals("Pending", m.text); assertEquals(CONTACT, m.contactID)
            val fetched = assertNotNull(h.store.fetchMessage(EntityKey(RADIO, m.id)))
            assertEquals(MessageStatus.PENDING, fetched.status)
            assertEquals(m, h.stored(m.id)); h.close()
        },
        original("MessageServiceSendTests", "createPendingMessage throws invalidRecipient for repeater") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            assertEquals(MessageServiceError.InvalidRecipient, assertFailsWith<MessageServiceException> {
                h.service.createPendingMessage("Test", h.contact.copy(typeRawValue = 2u))
            }.error); h.close()
        },
        original("MessageServiceSendTests", "createPendingMessage throws messageTooLong for oversized text") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            assertEquals(MessageServiceError.MessageTooLong, assertFailsWith<MessageServiceException> {
                h.service.createPendingMessage("a".repeat(151), h.contact)
            }.error); h.close()
        },
        original("MessageServiceSendTests", "createPendingMessage returns DTO with correct fields") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            val m = h.service.createPendingMessage("Hello world", h.contact)
            assertEquals("Hello world", m.text); assertEquals(CONTACT, m.contactID); assertEquals(RADIO, m.radioId)
            assertEquals(MessageDirection.OUTGOING, m.direction); assertEquals(TextType.PLAIN, m.textType); assertNull(m.channelIndex); h.close()
        },
        original("MessageServiceSendTests", "createPendingMessage stamps lastMessageDate so a first DM appears in the chat list before any send succeeds") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            h.store.saveContact(h.contact)
            assertTrue(h.store.fetchConversations(RADIO).isEmpty())
            h.service.createPendingMessage("First DM", h.contact)
            assertEquals(CONTACT, h.store.fetchConversations(RADIO).single().id)
            assertNotNull(h.store.contacts[EntityKey(RADIO, CONTACT)]?.lastMessageDate); h.close()
        },
        original("MessageServiceSendTests", "sendPendingDirectMessage rejects concurrent send for same messageID") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            val id = UUID.randomUUID(); h.service.claimRetryForTest(id)
            val e = assertFailsWith<MessageServiceException> { h.service.sendPendingDirectMessage(id, h.contact) }
            assertTrue(assertIs<MessageServiceError.SendFailed>(e.error).reason.contains("already in progress")); h.close()
        },
        original("MessageServiceSendTests", "sendPendingDirectMessage throws when message not found") {
            val h = Harness(this, sessionTimeout = 5.0, contact = sourceContact())
            assertIs<MessageServiceError.SendFailed>(assertFailsWith<MessageServiceException> {
                h.service.sendPendingDirectMessage(UUID.randomUUID(), h.contact)
            }.error); h.close()
        },
        original("MessageServiceSendTests", "sendChannelMessage throws when user text plus node name exceeds composed cap") {
            val h = Harness(this, sessionTimeout = 5.0); h.store.saveDevice(sourceDevice(h))
            val text = "a".repeat(ProtocolLimits.maxChannelMessageLength(4).toInt() + 1)
            assertEquals(MessageServiceError.MessageTooLong, assertFailsWith<MessageServiceException> {
                h.service.sendChannelMessage(text, 0u, RADIO)
            }.error); h.close()
        },
        original("MessageServiceSendTests", "sendChannelMessage saves message to dataStore before send attempt") {
            val h = Harness(this, sessionTimeout = 5.0)
            assertFailsWith<MessageServiceException> { h.service.sendChannelMessage("Hello channel", 0u, RADIO) }
            val messages = h.store.fetchMessages(RADIO, 0u, 10, 0)
            assertTrue(messages.isNotEmpty())
            assertEquals("Hello channel", messages.first().text)
            assertEquals(MessageDirection.OUTGOING, messages.first().direction)
            assertEquals(MessageStatus.FAILED, messages.first().status)
            val m = h.store.messages.values.single(); assertEquals("Hello channel", m.text)
            assertEquals(MessageDirection.OUTGOING, m.direction); assertEquals(MessageStatus.FAILED, m.status); h.close()
        },
        original("MessageServiceSendTests", "createPendingChannelMessage saves to dataStore with pending status") {
            val h = Harness(this, sessionTimeout = 5.0); val m = h.service.createPendingChannelMessage("Hello channel", 0u, RADIO)
            assertEquals(MessageStatus.PENDING, m.status); assertEquals(MessageDirection.OUTGOING, m.direction)
            assertEquals("Hello channel", m.text); assertEquals(0.toUByte(), m.channelIndex); assertEquals(RADIO, m.radioId)
            assertNull(m.contactID)
            val stored = assertNotNull(h.store.fetchMessage(EntityKey(RADIO, m.id)))
            assertEquals(MessageStatus.PENDING, stored.status)
            assertEquals(m, h.stored(m.id)); h.close()
        },
        original("MessageServiceSendTests", "createPendingChannelMessage throws when user text plus node name exceeds composed cap") {
            val h = Harness(this, sessionTimeout = 5.0); h.store.saveDevice(sourceDevice(h))
            assertEquals(MessageServiceError.MessageTooLong, assertFailsWith<MessageServiceException> {
                h.service.createPendingChannelMessage("a".repeat(ProtocolLimits.maxChannelMessageLength(4).toInt() + 1), 0u, RADIO)
            }.error); h.close()
        },
        original("MessageServiceSendTests", "createPendingChannelMessage accepts user text that fills the composed cap") {
            val h = Harness(this, sessionTimeout = 5.0); h.store.saveDevice(sourceDevice(h))
            val text = "a".repeat(ProtocolLimits.maxChannelMessageLength(4).toInt())
            val m = h.service.createPendingChannelMessage(text, 0u, RADIO); assertEquals(text, m.text)
            assertEquals(MessageStatus.PENDING, m.status); h.close()
        },
        original("MessageServiceSendTests", "createPendingChannelMessage without a device assumes a 31-byte name") {
            val h = Harness(this, sessionTimeout = 5.0); assertTrue(h.store.devices.isEmpty())
            assertEquals(MessageServiceError.MessageTooLong, assertFailsWith<MessageServiceException> {
                h.service.createPendingChannelMessage("a".repeat(
                    ProtocolLimits.maxChannelMessageLength(ProtocolLimits.MAX_USABLE_NAME_BYTES.toLong()).toInt() + 1), 0u, RADIO)
            }.error); h.close()
        },
        original("MessageServiceSendTests", "sendPendingChannelMessage throws when message not found") {
            val h = Harness(this, sessionTimeout = 5.0)
            assertIs<MessageServiceError.SendFailed>(assertFailsWith<MessageServiceException> {
                h.service.sendPendingChannelMessage(UUID.randomUUID())
            }.error); h.close()
        },
        original("MessageServiceSendTests", "sendPendingChannelMessage sets failed status on send error") {
            val h = Harness(this, sessionTimeout = 5.0); val m = h.service.createPendingChannelMessage("Hello channel", 0u, RADIO)
            assertEquals(MessageStatus.PENDING, m.status)
            assertFailsWith<MessageServiceException> { h.service.sendPendingChannelMessage(m.id) }
            assertEquals(MessageStatus.FAILED, assertNotNull(h.store.fetchMessage(EntityKey(RADIO, m.id))).status); h.close()
        },
        original("MessageServiceSendTests", "resendChannelMessage throws when message not found") {
            val h = Harness(this, sessionTimeout = 5.0)
            assertIs<MessageServiceError.SendFailed>(assertFailsWith<MessageServiceException> {
                h.service.resendChannelMessage(UUID.randomUUID())
            }.error); h.close()
        },
        original("MessageServiceSendTests", "resendChannelMessage throws when message is not a channel message") {
            val h = Harness(this, sessionTimeout = 5.0)
            val m = h.message(MessageStatus.PENDING, text = "Test message",
                timestamp = h.clock.wallClock.instant().epochSecond.toUInt())
            assertIs<MessageServiceError.SendFailed>(assertFailsWith<MessageServiceException> { h.service.resendChannelMessage(m.id) }.error); h.close()
        },
        original("MessageServiceSendTests", "resendChannelMessage writes .sent before broadcasting .resent and refreshes counts") {
            val h = Harness(this, sessionTimeout = 10.0); h.start()
            val m = h.message(MessageStatus.FAILED, 0u, "Test channel message",
                h.clock.wallClock.instant().epochSecond.toUInt())
            h.store.messages[EntityKey(RADIO, m.id)] = m.copy(heardRepeats = 3)
            val events = h.service.statusEvents(); h.service.resendChannelMessage(m.id)
            assertEquals(MessageStatus.SENT, h.stored(m.id).status); assertEquals(0L, h.stored(m.id).heardRepeats)
            assertEquals(2L, h.stored(m.id).sendCount); assertEquals(listOf(MessageStatusEvent.Resent(m.id)), statuses(h.service, events))
            assertEquals(1, h.sends(CommandCode.SEND_CHANNEL_MESSAGE).size); h.close()
        },
        original("MessageServiceSendTests", "resendDirectMessage increments sendCount and broadcasts .resent on a successful resend") {
            val h = Harness(this, sessionTimeout = 10.0, contact = sourceContact()); h.start()
            val m = h.message(MessageStatus.DELIVERED, text = "Test message",
                timestamp = h.clock.wallClock.instant().epochSecond.toUInt())
            val ackCode = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
            h.service.installPendingAck(h.tracking(m, code = ackCode))
            h.transport.expectedAckOverride = ackCode
            h.transport.suggestedTimeout = 5000u
            h.transport.acknowledge = true
            val events = h.service.statusEvents()
            h.service.resendDirectMessage(m.id, h.contact)
            assertEquals(2L, h.stored(m.id).sendCount)
            assertEquals(MessageStatus.DELIVERED, h.stored(m.id).status); assertEquals(123u, h.stored(m.id).roundTripTime)
            assertEquals(listOf(MessageStatusEvent.Resent(m.id)), statuses(h.service, events).filterIsInstance<MessageStatusEvent.Resent>())
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        original("MessageServiceSendTests", "sendPendingDirectMessage does not bump sendCount or broadcast .resent on first send") {
            val h = Harness(this, sessionTimeout = 10.0, contact = sourceContact()); h.start()
            val m = h.message(MessageStatus.PENDING, text = "Test message",
                timestamp = h.clock.wallClock.instant().epochSecond.toUInt())
            val ackCode = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
            h.service.installPendingAck(h.tracking(m, code = ackCode))
            h.transport.expectedAckOverride = ackCode
            h.transport.suggestedTimeout = 5000u
            h.transport.acknowledge = true
            val events = h.service.statusEvents()
            h.service.sendPendingDirectMessage(m.id, h.contact)
            assertEquals(1L, h.stored(m.id).sendCount); assertFalse(statuses(h.service, events).any { it is MessageStatusEvent.Resent })
            assertEquals(MessageStatus.DELIVERED, h.stored(m.id).status); assertEquals(123u, h.stored(m.id).roundTripTime)
            assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        original("MessageServiceSendTests", "sendDirectMessage tracks pending ACK before session.sendMessage so the listener cannot race") {
            val publicKey = Bytes(ByteArray(32) { 0xFE.toByte() })
            val h = Harness(this, sessionTimeout = 10.0, contact = sourceContact(),
                localPublicKey = publicKey, startupPacket = installedSelfInfoPacket(publicKey))
            h.start(); h.transport.holdMessageReplies = true
            val sending = backgroundScope.async { h.service.sendDirectMessage("hi", h.contact) }; runCurrent()
            assertEquals(1, h.service.pendingAckCount); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            sending.cancelAndJoin(); h.close()
        },
        original("MessageServiceSendTests", "sendMessageWithRetry tracks pending ACK before session.sendMessage") {
            val publicKey = Bytes(ByteArray(32) { 0xFE.toByte() })
            val h = Harness(this, sessionTimeout = 10.0, contact = sourceContact(),
                localPublicKey = publicKey, startupPacket = installedSelfInfoPacket(publicKey))
            h.start(); h.transport.holdMessageReplies = true
            val sending = backgroundScope.async { h.service.sendMessageWithRetry("hi", h.contact) }; runCurrent()
            assertEquals(1, h.service.pendingAckCount); assertEquals(1, h.sends(CommandCode.SEND_MESSAGE).size)
            sending.cancelAndJoin(); h.close()
        },
        original("MessageServiceSendTests", "failMessageAndRethrow does not downgrade a delivered DB row") {
            val h = Harness(this, sessionTimeout = 5.0)
            val ackCode = Bytes.of(0xDD, 0x11, 0x22, 0x33)
            val m = h.message(MessageStatus.DELIVERED, text = "Test message",
                timestamp = h.clock.wallClock.instant().epochSecond.toUInt()).copy(ackCode = ackCode.ackCodeUInt32)
            h.store.saveMessage(m); h.service.installPendingAck(h.tracking(m, code = ackCode, delivered = true))
            assertFailsWith<MessageServiceException> { h.service.failMessageAndRethrow(com.meshcoreone.android.core.protocol.config.MeshCoreException.NotConnected(), m.id) }
            assertEquals(MessageStatus.DELIVERED, h.stored(m.id).status); h.close()
        },
        original("MessageServiceSendTests", "finalizeSend exhaustion does not downgrade a delivered DB row") {
            val h = Harness(this, sessionTimeout = 5.0)
            val ackCode = Bytes.of(0xFE, 0xED, 0xFA, 0xCE)
            val m = h.message(MessageStatus.DELIVERED, text = "Test message",
                timestamp = h.clock.wallClock.instant().epochSecond.toUInt()).copy(ackCode = ackCode.ackCodeUInt32)
            h.store.saveMessage(m); h.service.installPendingAck(h.tracking(m, code = ackCode))
            h.service.finalizeSend(m.id, CONTACT, TARGET, null, 0u); assertEquals(MessageStatus.DELIVERED, h.stored(m.id).status); h.close()
        },
        original("MessageServiceSendTests", "retry exhaustion leaves the DM .sent with its pending entry alive, never prematurely .failed") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 5, minTimeout = 0.0),
                sessionTimeout = 10.0, contact = sourceContact())
            h.start(); h.transport.expectedAckOverride = Bytes.of(0xA1, 0xB2, 0xC3, 0xD4)
            val events = h.service.statusEvents()
            val m = h.service.sendMessageWithRetry("exhaust me", h.contact)
            assertEquals(MessageStatus.SENT, m.status); assertEquals(MessageStatus.SENT, h.stored(m.id).status)
            assertEquals(1, h.service.pendingAckCount)
            val out = statuses(h.service, events); assertFalse(out.any { it is MessageStatusEvent.Failed })
            assertTrue(out.any { it is MessageStatusEvent.Retrying }); assertTrue(out.any { it is MessageStatusEvent.StatusResolved }); h.close()
        },
        original("MessageServiceSendTests", "a genuine send exception still fails the DM through the outer catch") {
            val h = Harness(this, sessionTimeout = 10.0, contact = sourceContact()); h.start(); h.transport.mock.failSends(2)
            assertFailsWith<Exception> { h.service.sendMessageWithRetry("boom", h.contact) }
            assertEquals(MessageStatus.FAILED, h.store.fetchMessages(EntityKey(RADIO, h.contact.id), 10, 0).first().status)
            assertEquals(MessageStatus.FAILED, h.store.messages.values.single().status); h.close()
        },
        original("MessageServiceSendTests", "checkExpiredAcks cannot fail a DM while its retry loop is still inside waitForEvent") {
            val dispatcher = EventDispatcher()
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 5),
                sessionTimeout = 20.0, eventDispatcher = dispatcher, contact = sourceContact())
            h.start(); h.transport.expectedAckOverride = Bytes.of(0x7A, 0x7B, 0x7C, 0x7D)
            h.transport.suggestedTimeout = 100_000u
            val sending = backgroundScope.async { h.service.sendMessageWithRetry("in flight", h.contact) }; runCurrent()
            assertEquals(1, dispatcher.subscriberCount)
            h.service.checkExpiredAcks(); val m = h.store.messages.values.single(); assertNotEquals(MessageStatus.FAILED, h.stored(m.id).status)
            assertNotEquals(MessageStatus.FAILED, h.store.fetchMessages(EntityKey(RADIO, CONTACT), 1, 0).first().status)
            assertEquals(1, h.service.pendingAckCount); dispatcher.dispatch(MeshEvent.Acknowledgement(Bytes.of(0x7A, 0x7B, 0x7C, 0x7D), 100u))
            assertEquals(MessageStatus.DELIVERED, sending.await().status); h.close()
        },
        original("MessageServiceSendTests", "checkExpiredAcks respects a slow-preset per-attempt timeout that exceeds a tiny give-up window") {
            val dispatcher = EventDispatcher()
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 5, ackGiveUpWindow = 1.0),
                sessionTimeout = 20.0, eventDispatcher = dispatcher, contact = sourceContact())
            h.start(); h.transport.expectedAckOverride = Bytes.of(0x7A, 0x7B, 0x7C, 0x7D)
            h.transport.suggestedTimeout = 100_000u
            val sending = backgroundScope.async { h.service.sendMessageWithRetry("slow preset", h.contact) }; runCurrent()
            assertEquals(1, dispatcher.subscriberCount)
            advanceTimeBy(2000); runCurrent(); h.service.checkExpiredAcks()
            val m = h.store.messages.values.single(); assertNotEquals(MessageStatus.FAILED, h.stored(m.id).status)
            assertNotEquals(MessageStatus.FAILED, h.store.fetchMessages(EntityKey(RADIO, CONTACT), 1, 0).first().status)
            assertEquals(1, h.service.pendingAckCount); dispatcher.dispatch(MeshEvent.Acknowledgement(Bytes.of(0x7A, 0x7B, 0x7C, 0x7D), 100u))
            assertEquals(MessageStatus.DELIVERED, sending.await().status); h.close()
        },
        original("MessageServiceSendTests", "the retry loop sends exactly config.maxAttempts times under sustained non-ACK") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 2, floodAfter = 5, minTimeout = 0.0),
                sessionTimeout = 10.0, contact = sourceContact())
            h.start(); h.transport.expectedAckOverride = Bytes.of(0xA1, 0xB2, 0xC3, 0xD4)
            assertEquals(MessageStatus.SENT, h.service.sendMessageWithRetry("count me", h.contact).status)
            assertEquals(2, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        original("MessageServiceSendTests", "the retry loop escalates from direct to flood via resetPath after exactly config.floodAfter direct failures") {
            val h = Harness(this, MessageServiceConfig(maxAttempts = 3, floodAfter = 2, minTimeout = 0.0),
                sessionTimeout = 10.0, contact = sourceContact())
            h.start(); h.transport.expectedAckOverride = Bytes.of(0xA1, 0xB2, 0xC3, 0xD4)
            assertEquals(MessageStatus.SENT, h.service.sendMessageWithRetry("escalate me", h.contact).status)
            val reset = h.transport.sentData.indexOfFirst { it[0] == CommandCode.RESET_PATH.rawValue }; assertTrue(reset >= 0)
            assertEquals(2, h.transport.sentData.take(reset).count { it[0] == CommandCode.SEND_MESSAGE.rawValue })
            assertEquals(3, h.sends(CommandCode.SEND_MESSAGE).size); h.close()
        },
        original("MessageServiceSendDMBookkeepingTests", "sendDirectMessage keeps ACK tracking and does not report failure when post-send bookkeeping fails") {
            val h = Harness(this, sessionTimeout = 10.0,
                contact = ContactDTO(CONTACT, RADIO, Bytes(ByteArray(32) { 0xAB.toByte() }), "TestContact",
                    typeRawValue = 1u, lastHeardTimestamp = null))
            h.start(); val events = h.service.statusEvents()
            h.transport.expectedAckOverride = Bytes.of(0xAB, 0xCD, 0xEF, 0x12)
            h.transport.suggestedTimeout = 5000u
            h.transport.beforeReply = {
                if (it[0] == CommandCode.SEND_MESSAGE.rawValue) {
                    val saved = h.store.fetchMessages(EntityKey(RADIO, CONTACT), 10, 0).first()
                    h.store.deleteMessage(EntityKey(RADIO, saved.id))
                }
            }
            assertFailsWith<MessageServiceException> { h.service.sendDirectMessage("hi", h.contact) }
            assertEquals(1, h.service.pendingAckCount); assertFalse(statuses(h.service, events).any { it is MessageStatusEvent.Failed }); h.close()
        },
        original("MessageServiceListenerIntegrationTests", "listener flips message to .delivered after a flood of non-matching events") {
            val dispatcher = EventDispatcher()
            val h = Harness(this, sessionTimeout = 5.0, eventDispatcher = dispatcher); h.start()
            val m = h.message(text = "Test message", timestamp = h.clock.wallClock.instant().epochSecond.toUInt())
            val t = h.tracking(m); h.service.installPendingAck(t)
            h.service.startEventMonitoring()
            assertEquals(1, dispatcher.subscriberCount)
            repeat(500) { dispatcher.dispatch(MeshEvent.Advertisement(Bytes.of(it % 256))) }
            dispatcher.dispatch(MeshEvent.Acknowledgement(t.ackCodes.first(), 1234u)); runCurrent()
            assertEquals(MessageStatus.DELIVERED, h.stored(m.id).status); assertEquals(1234u, h.stored(m.id).roundTripTime)
            assertEquals(0L, dispatcher.droppedEventCount)
            h.service.stopEventMonitoring(); assertEquals(0, dispatcher.subscriberCount); h.close()
        },
        original("MessageServiceListenerIntegrationTests", "listener restart after disconnect/reconnect still observes ACKs") {
            val dispatcher = EventDispatcher()
            val h = Harness(this, sessionTimeout = 5.0, eventDispatcher = dispatcher); h.start()
            val m = h.message(text = "Test message", timestamp = h.clock.wallClock.instant().epochSecond.toUInt())
            val t = h.tracking(m, code = Bytes.of(0x11, 0x22, 0x33, 0x44)); h.service.installPendingAck(t)
            h.service.startEventMonitoring(); assertEquals(1, dispatcher.subscriberCount)
            h.service.stopEventMonitoring(); assertFalse(h.service.isEventMonitoringActive); assertEquals(0, dispatcher.subscriberCount)
            h.service.startEventMonitoring(); assertEquals(1, dispatcher.subscriberCount)
            dispatcher.dispatch(MeshEvent.Acknowledgement(t.ackCodes.first(), 200u)); runCurrent()
            assertEquals(MessageStatus.DELIVERED, h.stored(m.id).status)
            h.service.stopEventMonitoring(); assertEquals(0, dispatcher.subscriberCount); h.close()
        },
    )
}
