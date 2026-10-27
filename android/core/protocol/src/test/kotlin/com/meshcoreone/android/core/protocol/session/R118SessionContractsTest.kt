// AndroidOnly: WP-107 User-approved R118 validation, query, baseline and physical-reconnect contracts through real sessions.
package com.meshcoreone.android.core.protocol.session

import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.command.PacketBuilder
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.config.SessionConfiguration
import com.meshcoreone.android.core.protocol.model.ContactFlags
import com.meshcoreone.android.core.protocol.model.ContactType
import java.time.Instant
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.TestFactory
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class R118SessionContractsTest {
    private data class PathCase(val length: Int, val size: Int, val reason: String? = null)

    private val pathCases = listOf(
        PathCase(0x00, 0), PathCase(0x01, 1), PathCase(0x3f, 63),
        PathCase(0x40, 0), PathCase(0x60, 64), PathCase(0x80, 0), PathCase(0x95, 63),
        PathCase(0xff, 0), PathCase(0xff, 64),
        PathCase(0x00, 1, "Encoded contact path length does not match the supplied bytes"),
        PathCase(0x01, 0, "Encoded contact path length does not match the supplied bytes"),
        PathCase(0x7f, 64, "Encoded contact path length does not match the supplied bytes"),
        PathCase(0xbf, 64, "Encoded contact path length does not match the supplied bytes"),
        PathCase(0xc0, 0, "Reserved path hash mode"),
        PathCase(0xfe, 64, "Reserved path hash mode"),
        PathCase(0xff, 65, "Contact path exceeds 64 bytes"),
        PathCase(0xc0, 65, "Contact path exceeds 64 bytes"),
    )

    @TestFactory
    fun legacyPathMatrix() = listOf("update", "flags").flatMap { consumer ->
        pathCases.map { case ->
            nativeCase("R118 G05 $consumer path=${case.length} bytes=${case.size} preserves the approved validation order") {
                val f = fixture(); start(f)
                val path = filled(0x81, case.size)
                val contact = testContact(filled(0x80, 32), 3u, case.length.toUByte(), path)
                suspend fun invoke() {
                    if (consumer == "update") f.session.updateContact(
                        contact.publicKey, ContactType.ROOM, ContactFlags(0x80u), contact.outPathLength, path,
                        "TestRepeater", Instant.ofEpochSecond(1000), 0.0, 0.0,
                    ) else f.session.changeContactFlags(contact, ContactFlags(0x80u))
                }
                if (case.reason != null) {
                    assertEquals(case.reason, assertFailsWith<MeshCoreException.InvalidInput> { invoke() }.reason)
                    assertEquals(1, f.transport.sent.size, "Rejected inputs cannot reach the radio")
                } else {
                    val expected = raw(0x09, filled(0x80, 32) + Bytes.of(3, 0x80, case.length) +
                        path + filled(0, 64 - case.size) + Bytes.utf8("TestRepeater") + filled(0, 20) +
                        hex("e80300000000000000000000"))
                    command(f, expected, hex("00")) { invoke() }
                    assertEquals(144, f.transport.sent.last().size)
                }
                assertEquals(0, f.session.core.generation().pending.size)
                f.session.stop()
            }
        }
    }

    @TestFactory
    fun legacyKeyValidation() = listOf(0, 1, 31, 33, 64).flatMap { size ->
        listOf("update", "flags").map { consumer ->
            nativeCase("R118 G05 $consumer key bytes=$size rejects before an invalid path") {
                val f = fixture(); start(f)
                val contact = testContact(filled(0x80, size), 3u, 0xc0u, filled(0x81, 65))
                val failure = assertFailsWith<MeshCoreException.InvalidInput> {
                    if (consumer == "update") f.session.updateContact(
                        contact.publicKey, ContactType.ROOM, ContactFlags(0u), contact.outPathLength,
                        contact.outPath, "", Instant.EPOCH, 0.0, 0.0,
                    ) else f.session.changeContactFlags(contact, ContactFlags(0u))
                }
                assertEquals("Full 32-byte public key required for updateContact", failure.reason)
                assertEquals(1, f.transport.sent.size)
                assertTrue(f.session.core.generation().pending.isEmpty())
                f.session.stop()
            }
        }
    }

    @TestFactory
    fun changedPathValidation() = listOf(
        Triple(0, 0, "Path requires complete 1-, 2-, or 3-byte hashes"),
        Triple(4, 0, "Path requires complete 1-, 2-, or 3-byte hashes"),
        Triple(3, 2, "Path requires complete 1-, 2-, or 3-byte hashes"),
        Triple(1, 64, "Encoded contact path length does not match the supplied bytes"),
        Triple(1, 65, "Contact path exceeds 64 bytes"),
    ).map { (hashSize, size, reason) ->
        nativeCase("R118 G05 changed path hash=$hashSize bytes=$size validates at the actual caller") {
            val f = fixture(); start(f)
            assertEquals(reason, assertFailsWith<MeshCoreException.InvalidInput> {
                f.session.changeContactPath(testContact(), filled(0x81, size), hashSize.toUByte())
            }.reason)
            assertEquals(1, f.transport.sent.size)
            f.session.stop()
        }
    }

    @TestFactory
    fun legacyWireAndAcknowledgement() = listOf(
        nativeCase("R118 G05 legacy byte-prefix UTF8 name remains distinct from the modern 147-byte layout") {
            val f = fixture(); start(f)
            val contact = testContact(filled(0x80, 32), 3u).copy(
                advertisedName = "N".repeat(31) + "\u00e9", lastAdvertisement = Instant.ofEpochSecond(-1),
                latitude = Double.NaN, longitude = 200.0,
            )
            val expected = raw(0x09, filled(0x80, 32) + hex("0380ff") + filled(0, 64) +
                filled(0x4e, 31) + hex("c3") + hex("00000000000000000095ba0a"))
            command(f, expected, hex("00")) { f.session.changeContactFlags(contact, ContactFlags(0x80u)) }
            assertEquals(144, f.transport.sent.last().size)
            val modern = PacketBuilder.updateContact(contact.copy(flags = ContactFlags(0x80u)))
            assertEquals(147, modern.size)
            assertEquals(filled(0x4e, 31) + hex("00"), modern.slice(100, 132))
            assertEquals(hex("00000000000000000095ba0a000000"), modern.slice(132, 147))
            f.session.stop()
        },
        nativeCase("R118 G05 legacy flag update awaits bare OK and preserves device rejection") {
            val f = fixture(); start(f)
            val task = checkedRequest { f.session.changeContactFlags(testContact(), ContactFlags(2u)) }; runCurrent()
            assertEquals(144, f.transport.sent.last().size)
            f.transport.ok(7u); runCurrent()
            assertFalse(task.isCompleted)
            f.transport.error(0x80); runCurrent()
            assertEquals(0x80u.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { task.await() }.code)
            assertTrue(f.session.core.generation().pending.isEmpty())
            f.session.stop()
        },
    )

    @TestFactory
    fun contactDeviceErrors() = listOf(0, 1, 2, 3, 4, 5, 6, 0x7f, 0x80, 0xff).map { code ->
        nativeCase("R118 G09 contact error=$code is absence only for NOT_FOUND") {
            val f = fixture(); start(f)
            val key = filled(0x80, 32)
            val contacts: ContactSessionOps = f.session
            suspend fun invoke() = command(f, raw(0x1e, key), Bytes.of(1, code)) { contacts.getContact(key) }
            if (code == 2) assertNull(invoke())
            else assertEquals(code.toUByte(), assertFailsWith<MeshCoreException.DeviceError> { invoke() }.code)
            assertTrue(f.session.core.generation().pending.isEmpty())
            f.session.stop()
        }
    }

    @TestFactory
    fun reconnectRecovery() = listOf(false, true).map { lateReply ->
        nativeCase("R118 G01 CM01 unanswered channel lateReply=$lateReply recovers only after physical reconnect") {
            val f = fixture(transport = SessionRadioTransport(true)); start(f)
            val channels: ChannelSessionOps = f.session
            val generation = f.session.core.generation()
            val pipeline = checkedRequest { channels.getChannels(listOf(0u, 1u, 2u)) }; runCurrent()
            f.transport.receive(channelPacket(0, "configured", 0x11))
            f.transport.receive(channelPacket(2, "", 0)); runCurrent()
            advanceTimeBy(200); runCurrent()
            val partial = pipeline.await()
            assertEquals(listOf<UByte>(0u, 2u), partial.received.map { it.index })
            assertEquals(listOf<UByte>(1u), partial.missing)
            assertEquals("configured", partial.received.first().name)
            if (lateReply) { f.transport.receive(channelPacket(1, "orphan", 0xaa)); runCurrent() }
            val sentBeforeRetry = f.transport.sent.size
            val failure = assertFailsWith<MeshCoreException.ConnectionLost> { channels.getChannel(1u) }
            assertEquals("channel:1", generateSequence<Throwable>(failure) { it.cause }
                .filterIsInstance<SessionCorrelationException.UnresolvedReply>().single().responseFamily)
            assertEquals(sentBeforeRetry, f.transport.sent.size)
            f.session.stop(false)
            assertTrue(f.transport.isConnected())
            assertEquals(0, f.transport.disconnects)
            assertIs<SessionCorrelationException.RetainedTransport>(
                assertFailsWith<MeshCoreException.ConnectionLost> { f.session.start() }.cause,
            )
            f.session.stop(true)
            assertFalse(f.transport.isConnected())
            assertEquals(1, f.transport.disconnects)
            assertTrue(generation.job.isCompleted)
            start(f)
            assertNotEquals(generation.number, f.session.core.generation().number)
            val recovered = command(f, hex("1f01"), channelPacket(1, "fresh", 0xbb)) { channels.getChannel(1u) }
            assertEquals("fresh", recovered.name)
            assertEquals(filled(0xbb, 16), recovered.secret)
            assertEquals(1, f.transport.maximumCollectors)
            f.session.stop()
        }
    }

    @TestFactory
    fun contactInactivityWindow() = listOf(
        nativeCase("R118 G10 a stream with no header or progress expires at the original 80ms inactivity tick") {
            val f = fixture(SessionConfiguration(
                defaultTimeout = 10.0, clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 0.08, contactStreamHardTimeout = 1.0,
            ))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = checkedRequest { f.session.getContacts() }; runCurrent()
            advanceTimeBy(79); runCurrent()
            assertFalse(task.isCompleted)
            advanceTimeBy(1); runCurrent()
            assertTrue(task.isCompleted)
            assertFailsWith<MeshCoreException.Timeout> { task.await() }
            assertEquals(80L, testScheduler.currentTime)
            f.session.stop()
        },
        nativeCase("R118 G10 one-second inactivity budget resets on actual stream progress") {
            val f = fixture(SessionConfiguration(
                defaultTimeout = 10.0, clientIdentifier = "MCTst",
                contactStreamInactivityTimeout = 1.0, contactStreamHardTimeout = 10.0,
            ))
            start(f, expectedAppStart = hex("01032020202020204d43547374"))
            val task = checkedRequest { f.session.getContactsReportingTotal() }; runCurrent()
            f.transport.receive(contactsStart(2)); runCurrent()
            advanceTimeBy(999); f.transport.receive(contactPacket(filled(1, 32), "A")); runCurrent()
            advanceTimeBy(999); runCurrent()
            assertFalse(task.isCompleted, "Progress must keep the original one-second inactivity window alive")
            f.transport.receive(contactPacket(filled(2, 32), "B"))
            f.transport.receive(contactsEnd()); runCurrent()
            assertEquals(listOf("A", "B"), task.await().contacts.map { it.advertisedName })
            assertFalse(f.session.isContactsDirty)
            f.session.stop()
        },
    )
}
