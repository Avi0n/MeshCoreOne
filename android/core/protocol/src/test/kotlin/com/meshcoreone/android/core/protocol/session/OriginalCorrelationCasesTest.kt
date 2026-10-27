// PortedFrom: MeshCore/Tests/MeshCoreTests/Session/MeshCoreSessionCommandCorrelationTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.event.StatusResponse
import com.meshcoreone.android.core.protocol.model.AutoAddConfig
import com.meshcoreone.android.core.protocol.model.ContactType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class OriginalCorrelationCasesTest {
    private fun source(name: String, body: suspend TestScope.() -> Unit) =
        original("MeshCoreSessionCommandCorrelationTests", name, body)

    @TestFactory
    fun sourceCases() = listOf(
        source("simple commands serialize concurrent OK/ERROR waits") {
            val f = fixture(); start(f)
            val first = checkedRequest { f.session.factoryReset() }; runCurrent()
            val second = checkedRequest { f.session.sendAdvertisement(true) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            assertEquals(hex("337265736574"), f.transport.sent.last())
            f.transport.ok(); runCurrent(); first.await()
            assertEquals(3, f.transport.sent.size)
            assertEquals(hex("0701"), f.transport.sent.last())
            f.transport.ok(); runCurrent(); second.await()
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        source("simple commands ignore OK responses with payloads") {
            val f = fixture(SessionConfiguration(defaultTimeout = 0.2, clientIdentifier = "MCTst"))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = checkedRequest { f.session.factoryReset() }; runCurrent()
            f.transport.ok(7u); runCurrent()
            assertFalse(task.isCompleted)
            advanceTimeBy(199); runCurrent()
            assertFalse(task.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(200L, testScheduler.currentTime)
            f.session.stop()
        },
        source("simple commands still fail on device errors") {
            val f = fixture(); start(f)
            val task = checkedRequest { f.session.setAutoAddConfig(AutoAddConfig(0x1eu, 2u)) }; runCurrent()
            assertEquals(hex("3a1e02"), f.transport.sent.last())
            f.transport.error(42); runCurrent()
            assertEquals(42u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { task.await() }.code)
            f.session.stop()
        },
        source("session start ignores unrelated errors until selfInfo arrives") {
            val f = fixture()
            val task = checkedRequest { f.session.start() }; runCurrent()
            f.transport.error(99); f.transport.receive(selfPacket()); runCurrent()
            task.await()
            assertEquals("Test", f.session.currentSelfInfo?.name)
            f.session.stop()
        },
        source("getBattery ignores unrelated errors while waiting for a battery response") {
            val f = fixture(); start(f)
            val task = checkedRequest { f.session.getBattery() }; runCurrent()
            assertEquals(hex("14"), f.transport.sent.last())
            f.transport.error(10); f.transport.receive(batteryPacket(4018)); runCurrent()
            assertEquals(4018L, task.await().level)
            f.session.stop()
        },
        source("getSelfTelemetry ignores telemetry for other nodes") {
            val f = fixture(); start(f)
            val task = checkedRequest { f.session.getSelfTelemetry() }; runCurrent()
            assertEquals(hex("27000000"), f.transport.sent.last())
            f.transport.receive(telemetryPacket(hex("aabbccddeeff"), hex("016700fa")))
            f.transport.receive(telemetryPacket(filled(1, 6), hex("016700f0"))); runCurrent()
            assertEquals(filled(1, 6), task.await().publicKeyPrefix)
            assertEquals(hex("016700f0"), task.await().rawData)
            f.session.stop()
        },
        source("getChannel ignores responses for other channel indexes") {
            val f = fixture(); start(f)
            val task = checkedRequest { f.session.getChannel(3u) }; runCurrent()
            assertEquals(hex("1f03"), f.transport.sent.last())
            f.transport.receive(channelPacket(9, "Wrong", 0xaa))
            f.transport.receive(channelPacket(3, "Right", 0xbb)); runCurrent()
            assertEquals(3u.toUByte(), task.await().index)
            assertEquals("Right", task.await().name)
            f.session.stop()
        },
        source("getContacts succeeds when slow stream keeps making progress") {
            val f = fixture(SessionConfiguration(
                defaultTimeout = 10.0, clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 1.0, contactStreamHardTimeout = 10.0,
            ))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = checkedRequest { f.session.getContacts() }; runCurrent()
            assertEquals(hex("04"), f.transport.sent.last())
            f.transport.receive(contactsStart(3)); runCurrent()
            repeat(3) {
                advanceTimeBy(70)
                f.transport.receive(contactPacket(filled(it + 1, 32), "Node $it")); runCurrent()
            }
            advanceTimeBy(70); f.transport.receive(contactsEnd()); runCurrent()
            assertEquals(listOf("Node 0", "Node 1", "Node 2"), task.await().map { it.advertisedName })
            assertEquals(280L, testScheduler.currentTime)
            assertFalse(f.session.isContactsDirty)
            f.session.stop()
        },
        source("getContactsReportingTotal surfaces the contactsStart total, not the received count") {
            val f = fixture(SessionConfiguration(
                defaultTimeout = 10.0, clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 1.0, contactStreamHardTimeout = 10.0,
            ))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = checkedRequest { f.session.getContactsReportingTotal() }; runCurrent()
            f.transport.receive(contactsStart(3))
            repeat(2) { f.transport.receive(contactPacket(filled(it + 1, 32), "Node $it")) }
            f.transport.receive(contactsEnd()); runCurrent()
            assertEquals(2, task.await().contacts.size)
            assertEquals(3L, task.await().reportedTotal)
            assertTrue(f.session.isContactsDirty)
            assertFalse(assertNotNull(f.session.lastContactFetchProgress).completed)
            f.session.stop()
        },
        source("getContactsReportingTotal returns a nil total when contactsStart never arrives") {
            val f = fixture(SessionConfiguration(
                defaultTimeout = 10.0, clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 1.0, contactStreamHardTimeout = 10.0,
            ))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = checkedRequest { f.session.getContactsReportingTotal() }; runCurrent()
            repeat(2) { f.transport.receive(contactPacket(filled(it + 1, 32), "Node $it")) }
            f.transport.receive(contactsEnd()); runCurrent()
            assertEquals(2, task.await().contacts.size)
            assertNull(task.await().reportedTotal)
            assertTrue(f.session.isContactsDirty)
            f.session.stop()
        },
        source("getContacts times out after inactivity before contactsEnd") {
            val f = fixture(SessionConfiguration(
                defaultTimeout = 10.0, clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 0.08, contactStreamHardTimeout = 1.0,
            ))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = checkedRequest { f.session.getContacts() }; runCurrent()
            f.transport.receive(contactsStart(1)); runCurrent()
            advanceTimeBy(79); runCurrent()
            assertFalse(task.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertTrue(task.isCompleted)
            advanceTimeBy(60); runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(140L, testScheduler.currentTime)
            assertEquals(1L, assertNotNull(f.session.lastContactFetchProgress).reportedTotal)
            assertFalse(assertNotNull(f.session.lastContactFetchProgress).completed)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        source("getContact ignores responses for other public keys") {
            val f = fixture(SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val key = filled(0x11, 32)
            val task = checkedRequest { f.session.getContact(key) }; runCurrent()
            assertEquals(raw(0x1e, key), f.transport.sent.last())
            f.transport.receive(contactPacket(filled(0x22, 32), "Wrong"))
            f.transport.receive(contactPacket(key, "Right")); runCurrent()
            assertEquals(key, assertNotNull(task.await()).publicKey)
            assertEquals("Right", assertNotNull(task.await()).advertisedName)
            f.session.stop()
        },
        source("exportContact ignores contact URIs for other public keys") {
            val f = fixture(SessionConfiguration(defaultTimeout = 10.0, clientIdentifier = "MCTst"))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val key = filled(0x11, 32)
            val task = checkedRequest { f.session.exportContact(key) }; runCurrent()
            assertEquals(raw(0x11, key), f.transport.sent.last())
            f.transport.receive(raw(0x0b, filled(0x22, 32) + filled(0xcd, 8)))
            f.transport.receive(raw(0x0b, key + filled(0xcd, 8))); runCurrent()
            assertEquals("meshcore://${key.hexString}${filled(0xcd, 8).hexString}", task.await())
            f.session.stop()
        },
        source("importPrivateKey ignores OK responses with payloads") {
            val f = fixture(SessionConfiguration(defaultTimeout = 0.2, clientIdentifier = "MCTst"))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = checkedRequest { f.session.importPrivateKey(filled(0x33, 64)) }; runCurrent()
            assertEquals(raw(0x18, filled(0x33, 64)), f.transport.sent.last())
            f.transport.ok(7u); runCurrent()
            advanceTimeBy(199); runCurrent()
            assertFalse(task.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(200L, testScheduler.currentTime)
            assertEquals(2, f.transport.sent.size)
            f.session.stop()
        },
        source("importPrivateKey refreshes cached self info after OK") {
            val f = fixture(); start(f, selfPacket(name = "Temp"))
            val restored = filled(0x44, 32)
            val task = checkedRequest { f.session.importPrivateKey(filled(0x33, 64)) }; runCurrent()
            f.transport.ok(); runCurrent()
            assertEquals(3, f.transport.sent.size)
            assertEquals(hex("01032020202020204d436f7265"), f.transport.sent.last())
            f.transport.receive(selfPacket(restored, "Restored")); runCurrent()
            task.await()
            assertEquals(restored, f.session.currentSelfInfo?.publicKey)
            assertEquals("Restored", f.session.currentSelfInfo?.name)
            f.session.stop()
        },
        source("importPrivateKey rejects a key that is not the expanded private-key length") {
            val f = fixture()
            assertFailsWith<MeshCoreException.InvalidInput> { f.session.importPrivateKey(filled(0x33, 32)) }
            assertTrue(f.transport.sent.isEmpty())
            f.session.stop()
        },
        source("exportPrivateKey throws featureDisabled on disabled response") {
            val f = fixture(); start(f)
            assertFailsWith<MeshCoreException.FeatureDisabled> {
                command(f, hex("17"), raw(0x0f)) { f.session.exportPrivateKey() }
            }
            f.session.stop()
        },
        source("disabled responses do not break unrelated requests") {
            val f = fixture(); start(f)
            val task = checkedRequest { f.session.getBattery() }; runCurrent()
            f.transport.receive(raw(0x0f)); f.transport.receive(batteryPacket(4018)); runCurrent()
            assertEquals(4018L, task.await().level)
            f.session.stop()
        },
        source("requestStatus fails fast on device error before messageSent") {
            val f = fixture(); start(f)
            val key = filled(0x31, 32)
            val task = checkedRequest { f.session.requestStatus(key) }; runCurrent()
            assertEquals(raw(0x1b, key), f.transport.sent.last())
            f.transport.error(10); runCurrent()
            assertEquals(10u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { task.await() }.code)
            f.session.stop()
        },
        source("requestStatus uses dedicated status command and room layout for typed room targets") {
            val f = fixture(); start(f)
            val key = filled(0x31, 32)
            val task = checkedRequest { f.session.requestStatus(key, ContactType.ROOM) }; runCurrent()
            assertEquals(raw(0x1b, key), f.transport.sent.last())
            f.transport.receive(sentPacket())
            f.transport.receive(statusPacket(key, 1000, 17, 9)); runCurrent()
            val status = task.await()
            assertEquals(StatusResponse.Layout.ROOM_SERVER, status.layout)
            assertEquals(1000L, status.battery)
            assertEquals(17u.toUShort(), status.roomServerPostedCount)
            assertEquals(9u.toUShort(), status.roomServerPostPushCount)
            assertEquals(0u, status.rxAirtime)
            f.session.stop()
        },
        source("requestStatus retransmits until a matching response arrives") {
            val f = fixture(SessionConfiguration(clientIdentifier = "MCore", binaryRequestOverallTimeout = 2.0, binaryRequestRetransmitInterval = 0.05))
            start(f)
            val key = filled(0x31, 32)
            val task = checkedRequest { f.session.requestStatus(key) }; runCurrent()
            f.transport.receive(sentPacket(hex("11223344"), 500)); runCurrent()
            advanceTimeBy(999); runCurrent()
            assertEquals(2, f.transport.sent.size)
            advanceTimeBy(1); runCurrent()
            assertEquals(3, f.transport.sent.size)
            f.transport.receive(sentPacket(hex("55667788"), 500))
            f.transport.receive(statusPacket(key, 2200)); runCurrent()
            assertEquals(2200L, task.await().battery)
            assertEquals(listOf(raw(0x1b, key), raw(0x1b, key)), f.transport.sent.drop(1))
            f.session.stop()
        },
        source("binary response matches only the latest retransmit tag") {
            val f = fixture(SessionConfiguration(clientIdentifier = "MCore", binaryRequestOverallTimeout = 2.0, binaryRequestRetransmitInterval = 0.05))
            start(f)
            val task = checkedRequest { f.session.requestTelemetry(filled(0x31, 32)) }; runCurrent()
            f.transport.receive(sentPacket(hex("11223344"), 500)); runCurrent()
            advanceTimeBy(1000); runCurrent()
            f.transport.receive(sentPacket(hex("55667788"), 500)); runCurrent()
            f.transport.receive(binaryPacket(hex("11223344"))); runCurrent()
            assertFalse(task.isCompleted)
            f.transport.receive(binaryPacket(hex("55667788"))); runCurrent()
            assertTrue(task.await().dataPoints.isEmpty())
            f.session.stop()
        },
        source("requestStatus times out after the overall budget without a reply") {
            val f = fixture(SessionConfiguration(clientIdentifier = "MCore", binaryRequestOverallTimeout = 0.5, binaryRequestRetransmitInterval = 0.05))
            start(f)
            val task = checkedRequest { f.session.requestStatus(filled(0x31, 32)) }; runCurrent()
            f.transport.receive(sentPacket(hex("01020304"), 50)); runCurrent()
            advanceTimeBy(499); runCurrent()
            assertFalse(task.isCompleted)
            assertTrue(f.transport.sent.size >= 3)
            advanceTimeBy(1); runCurrent()
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(500L, testScheduler.currentTime)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
        source("requestTelemetry fails fast on device error before messageSent") {
            val f = fixture(); start(f)
            val key = filled(0x31, 32)
            val task = checkedRequest { f.session.requestTelemetry(key) }; runCurrent()
            assertEquals(raw(0x27, filled(0, 3) + key), f.transport.sent.last())
            f.transport.error(11); runCurrent()
            assertEquals(11u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { task.await() }.code)
            f.session.stop()
        },
        source("sendMessage fails fast on device error") {
            val f = fixture(); start(f)
            val task = checkedRequest { f.session.sendMessage(filled(0x11, 32), "hello", testEpoch) }; runCurrent()
            assertEquals(hex("02000080009265") + filled(0x11, 6) + Bytes.utf8("hello"), f.transport.sent.last())
            f.transport.error(5); runCurrent()
            assertEquals(5u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { task.await() }.code)
            f.session.stop()
        },
        source("sendKeepAlive fails fast on device error") {
            val f = fixture(); start(f)
            val task = checkedRequest { f.session.sendKeepAlive(filled(0x22, 32), 0u) }; runCurrent()
            assertEquals(raw(0x32, filled(0x22, 32) + hex("0200000000")), f.transport.sent.last())
            f.transport.error(3); runCurrent()
            assertEquals(3u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { task.await() }.code)
            f.session.stop()
        },
        source("exportPrivateKey fails fast on device error") {
            val f = fixture(); start(f)
            assertEquals(4u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> {
                command(f, hex("17"), hex("0104")) { f.session.exportPrivateKey() }
            }.code)
            f.session.stop()
        },
        source("binary request serializes behind a concurrent text command") {
            val f = fixture(); start(f)
            val keepAlive = checkedRequest { f.session.sendKeepAlive(filled(0x22, 32), 0u) }; runCurrent()
            val status = checkedRequest { f.session.requestStatus(filled(0x31, 32)) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.error(42); runCurrent()
            assertEquals(42u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { keepAlive.await() }.code)
            assertEquals(3, f.transport.sent.size)
            f.transport.error(43); runCurrent()
            assertEquals(43u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { status.await() }.code)
            f.session.stop()
        },
        source("binary request errors release the serializer for following requests") {
            val f = fixture(); start(f)
            val status = checkedRequest { f.session.requestStatus(filled(0x31, 32)) }; runCurrent()
            val telemetry = checkedRequest { f.session.requestTelemetry(filled(0x42, 32)) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.error(12); runCurrent()
            assertEquals(12u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { status.await() }.code)
            assertEquals(3, f.transport.sent.size)
            assertEquals(raw(0x27, filled(0, 3) + filled(0x42, 32)), f.transport.sent.last())
            f.transport.error(13); runCurrent()
            assertEquals(13u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { telemetry.await() }.code)
            f.session.stop()
        },
        source("a response orphaned by a cancelled command is not delivered to the next command") {
            val f = fixture(); start(f)
            val first = checkedRequest { f.session.getBattery() }; runCurrent()
            first.cancel(); runCurrent()
            assertFailsWith<CancellationException> { first.await() }
            val second = checkedRequest { f.session.getBattery() }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.receive(batteryPacket(1111)); runCurrent()
            assertEquals(3, f.transport.sent.size)
            assertFalse(second.isCompleted)
            f.transport.receive(batteryPacket(2222)); runCurrent()
            assertEquals(2222L, second.await().level)
            f.session.stop()
        },
        source("concurrent unicast send and binary request do not share one messageSent") {
            val f = fixture(); start(f)
            val key = filled(0x31, 32)
            val status = checkedRequest { f.session.requestStatus(key, ContactType.ROOM) }; runCurrent()
            val message = checkedRequest { f.session.sendMessage(filled(0x11, 32), "hi", testEpoch) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            f.transport.receive(sentPacket(hex("aabbccdd")))
            f.transport.receive(statusPacket(key, 1234, 5, 2)); runCurrent()
            assertEquals(1234L, status.await().battery)
            assertEquals(3, f.transport.sent.size)
            assertFalse(message.isCompleted)
            f.transport.receive(sentPacket(hex("11223344"))); runCurrent()
            assertEquals(hex("11223344"), message.await().expectedAck)
            f.session.stop()
        },
        source("a command cancelled while waiting on the serializer never writes") {
            val f = fixture(); start(f)
            val first = checkedRequest { f.session.factoryReset() }; runCurrent()
            val second = checkedRequest { f.session.sendAdvertisement(true) }; runCurrent()
            assertEquals(2, f.transport.sent.size)
            second.cancel(); runCurrent()
            f.transport.ok(); runCurrent(); first.await()
            assertFailsWith<CancellationException> { second.await() }
            assertEquals(2, f.transport.sent.size)
            assertEquals(0, f.session.core.generation().pending.size)
            f.session.stop()
        },
    )
}
