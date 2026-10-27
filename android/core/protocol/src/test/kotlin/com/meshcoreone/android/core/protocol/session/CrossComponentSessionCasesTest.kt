// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/RegionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Validation/ProtocolBugFixTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/V112ProtocolTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Events/EventDispatcherFilteredSubscriptionTests.swift@db14559b39d32322b06477c6ae676112f583db50
// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/UpdateContactTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.EventFilter
import com.meshcoreone.android.core.protocol.event.MeshEvent
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import com.meshcoreone.android.core.protocol.model.MeshContact
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

internal fun testContact(
    key: Bytes = filled(0xdd, 32), rawType: UByte = 2u, pathLength: UByte = 0xffu, path: Bytes = Bytes.EMPTY,
): MeshContact = MeshContact(
    key.hexString, key, ContactType.fromRawValue(rawType) ?: ContactType.CHAT, ContactFlags(0u),
    pathLength, path, "TestRepeater", Instant.ofEpochSecond(1000), 0.0, 0.0, testEpoch, rawType,
)

@OptIn(ExperimentalCoroutinesApi::class)
class CrossComponentSessionCasesTest {
    @TestFactory
    fun originalSeams() = listOf(
        original("ProtocolBugFixTests", "requestNeighbours rejects a short public key before sending") {
            val f = fixture()
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.requestNeighbours(filled(0x31, 6)) }
            assertTrue(f.transport.sent.isEmpty())
            f.session.stop()
        },
        original("ProtocolBugFixTests", "requestStatus throws device error when error response received") {
            val f = fixture(); start(f)
            val key = filled(0x31, 32)
            val task = checkedRequest { f.session.requestStatus(key) }; runCurrent()
            assertEquals(raw(0x1b, key), f.transport.sent.last())
            f.transport.error(10); runCurrent()
            assertEquals(10u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { task.await() }.code)
            f.session.stop()
        },
        original("RequestRegionsIntegrationTests", "full two-phase flow returns parsed regions") {
            val f = fixture(SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"))
            start(f, expectedAppStart = hex("010320202020202054657374"))
            val contact = testContact()
            val tag = hex("01020304")
            val task = checkedRequest { f.session.requestRegions(contact) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            assertEquals(147, f.transport.sent.last().size)
            assertEquals(0u.toUByte(), f.transport.sent.last()[35])
            f.transport.ok(); runCurrent()
            assertEquals(raw(0x39, contact.publicKey + hex("0100")), f.transport.sent.last())
            f.transport.receive(sentPacket(tag))
            f.transport.receive(binaryPacket(tag, little32(0xaabbccddL) + Bytes.utf8("Europe,UK,France"))); runCurrent()
            assertEquals(raw(0x0d, contact.publicKey), f.transport.sent.last())
            assertFalse(task.isCompleted, "Restoration is part of the owned operation")
            f.transport.ok(); runCurrent()
            assertEquals(listOf("Europe", "UK", "France"), task.await())
            assertEquals(listOf(0x01, 0x09, 0x39, 0x0d), f.transport.sent.map { it[0].toInt() })
            f.session.stop()
        },
        original("RequestRegionsIntegrationTests", "timeout when no binaryResponse arrives") {
            val f = fixture(SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test", binaryRequestOverallTimeout = 0.2, binaryRequestRetransmitInterval = null))
            start(f, expectedAppStart = hex("010320202020202054657374"))
            val contact = testContact()
            val task = checkedRequest { f.session.requestRegions(contact) }; runCurrent()
            f.transport.ok(); runCurrent()
            f.transport.receive(sentPacket(hex("deadbeef"), 100)); runCurrent()
            advanceTimeBy(200); runCurrent()
            assertEquals(raw(0x0d, contact.publicKey), f.transport.sent.last())
            f.transport.ok(); runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        original("RequestRegionsIntegrationTests", "device error propagates correctly") {
            val f = fixture(SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"))
            start(f, expectedAppStart = hex("010320202020202054657374"))
            val contact = testContact()
            val task = checkedRequest { f.session.requestRegions(contact) }; runCurrent()
            f.transport.ok(); runCurrent()
            f.transport.error(10); runCurrent()
            assertEquals(raw(0x0d, contact.publicKey), f.transport.sent.last())
            f.transport.ok(); runCurrent()
            assertEquals(10u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { task.await() }.code)
            f.session.stop()
        },
        original("RequestRegionsIntegrationTests", "temporarily sets zero-hop before sending for flood-routed contact") {
            val f = fixture(SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test", binaryRequestOverallTimeout = 0.2, binaryRequestRetransmitInterval = null))
            start(f, expectedAppStart = hex("010320202020202054657374"))
            val contact = testContact()
            val task = checkedRequest { f.session.requestRegions(contact) }; runCurrent()
            val temporary = f.transport.sent.last()
            assertEquals(raw(0x09, contact.publicKey + hex("020000") + filled(0, 64) +
                Bytes.utf8("TestRepeater").paddedOrTruncated(32) + little32(1000) + filled(0, 11)), temporary)
            f.transport.ok(); runCurrent()
            assertEquals(raw(0x39, contact.publicKey + hex("0100")), f.transport.sent.last())
            assertEquals(35, f.transport.sent.last().size)
            f.transport.receive(sentPacket(timeoutMs = 100)); runCurrent()
            advanceTimeBy(200); runCurrent()
            assertEquals(raw(0x0d, contact.publicKey), f.transport.sent.last())
            f.transport.ok(); runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            f.session.stop()
        },
        original("RequestRegionsIntegrationTests", "requestRegions preserves an unmodeled raw type byte in the temp write") {
            val f = fixture(SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test", binaryRequestOverallTimeout = 0.2, binaryRequestRetransmitInterval = null))
            start(f, expectedAppStart = hex("010320202020202054657374"))
            val contact = testContact(filled(0xcd, 32), 4u).copy(id = "r", advertisedName = "Repeater")
            val task = checkedRequest { f.session.requestRegions(contact) }; runCurrent()
            assertEquals(4u.toUByte(), f.transport.sent.last()[33])
            assertEquals(0u.toUByte(), f.transport.sent.last()[35])
            f.transport.ok(); runCurrent(); f.transport.receive(sentPacket(timeoutMs = 100)); runCurrent()
            advanceTimeBy(200); runCurrent()
            assertEquals(raw(0x0d, contact.publicKey), f.transport.sent.last())
            f.transport.ok(); runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            f.session.stop()
        },
        original("V112ProtocolTests", "contactManager tracks contactDeleted") {
            val manager = ContactManager()
            val contact = testContact(filled(0x11, 32), 1u, 0u)
            manager.store(contact)
            manager.addPending(contact)
            manager.markClean(testEpoch)
            assertNotNull(manager.getByPublicKey(contact.publicKey))
            manager.trackChanges(MeshEvent.ContactDeleted(contact.publicKey))
            assertNull(manager.getByPublicKey(contact.publicKey))
            assertTrue(manager.cachedPendingContacts.isEmpty())
            assertTrue(manager.needsRefresh)
        },
        original("V112ProtocolTests", "contactManager tracks contactsFull") {
            val manager = ContactManager()
            manager.markClean(testEpoch)
            assertFalse(manager.needsRefresh)
            manager.trackChanges(MeshEvent.ContactsFull)
            assertTrue(manager.needsRefresh)
        },
        original("EventDispatcherFilteredSubscriptionTests", "filtered subscription receives only matching events") {
            val f = fixture()
            val stream = f.session.events(EventFilter.anyAcknowledgement)
            start(f)
            f.transport.receive(raw(0x80, filled(1, 32)))
            f.transport.receive(ackPacket(hex("10203040"), 500))
            f.transport.receive(raw(0x80, filled(2, 32))); runCurrent()
            val event = assertIs<MeshEvent.Acknowledgement>(stream.first())
            assertEquals(hex("10203040"), event.code)
            assertEquals(500u, event.tripTime)
            f.session.stop()
        },
        original("EventDispatcherFilteredSubscriptionTests", "filtered subscription survives flood of non-matching events") {
            val f = fixture()
            val stream = f.session.events(EventFilter.anyAcknowledgement)
            start(f)
            repeat(500) { f.transport.receive(raw(0x80, filled(it % 256, 32))) }
            f.transport.receive(ackPacket(hex("abcdef12"), 1234)); runCurrent()
            val event = assertIs<MeshEvent.Acknowledgement>(stream.first())
            assertEquals(hex("abcdef12"), event.code)
            assertEquals(1234u, event.tripTime)
            assertEquals(0L, f.session.core.generation().dispatcher.droppedEventCount)
            f.session.stop()
        },
        original("LegacyUpdateContactHardeningTests", "changeContactFlags with a NaN coordinate does not trap and clamps the frame") {
            val f = fixture(SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "Test"))
            start(f, expectedAppStart = hex("010320202020202054657374"))
            val contact = testContact(filled(0xab, 32), 1u).copy(
                id = "x", advertisedName = "N", lastAdvertisement = Instant.ofEpochSecond(-1),
                latitude = Double.NaN, longitude = 200.0,
            )
            val task = checkedRequest { f.session.changeContactFlags(contact, ContactFlags(2u)) }; runCurrent()
            val frame = f.transport.sent.last()
            assertEquals(144, frame.size)
            assertEquals(raw(0x09, contact.publicKey + hex("0102ff") + filled(0, 64) +
                Bytes.utf8("N").paddedOrTruncated(32) + little32(0) + little32(0) + little32(180_000_000)), frame)
            assertFalse(task.isCompleted)
            f.transport.ok(); runCurrent(); task.await()
            f.session.stop()
        },
    )
}
