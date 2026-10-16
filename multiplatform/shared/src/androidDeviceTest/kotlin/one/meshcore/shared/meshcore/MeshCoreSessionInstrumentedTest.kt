package one.meshcore.shared.meshcore

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.model.toLowercaseHex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.security.MessageDigest

/**
 * The first real Swift-on-Android execution through the full stack: loads the cross-compiled
 * `libMeshCoreBridgeKit.so`, constructs a [MeshCoreSession] over an in-memory transport, and drives
 * the app-start handshake from Kotlin — mirroring the Swift `RoundTripTests` from the other side of
 * the JNI boundary. It proves the hand-rolled `nativeCreate` shim, the Swift->Kotlin listener
 * upcalls, and the generated command downcalls all work together on a device.
 */
@RunWith(AndroidJUnit4::class)
@Suppress("MagicNumber") // Exact protocol bytes and timing values are inputs to this bridge test.
class MeshCoreSessionInstrumentedTest {

    /** Captures outbound frames the session writes, and lets the test await the first one. */
    internal class FakeTransport : MeshTransport {
        private val sends = Channel<ByteArray>(Channel.UNLIMITED)

        override fun sendBytes(bytes: ByteArray, withoutResponse: Boolean) {
            sends.trySend(bytes)
        }

        suspend fun awaitSend(): ByteArray = sends.receive()

        /** Drains sends until the first setFloodScope (0x36) frame, skipping the handshake frames ahead of it. */
        suspend fun awaitFloodScopeFrame(): ByteArray {
            var frame = sends.receive()
            while (frame.firstOrNull() != 0x36.toByte()) frame = sends.receive()
            return frame
        }

        /** Drains sends until the first getMessage (0x0A) command. */
        suspend fun awaitGetMessage(): ByteArray {
            var frame = sends.receive()
            while (frame.firstOrNull() != GET_MESSAGE_CODE) frame = sends.receive()
            return frame
        }
    }

    @Test
    fun startHandshakeReturnsSelfInfoAndEmitsEvent() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            // Subscribe to events before the handshake so the pushed selfInfo cannot be missed.
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val started = async(Dispatchers.Default) { session.start() }
            // Report the link up so the session's transport.connect() resumes and writes appStart.
            session.reportConnected()
            withTimeout(TIMEOUT_MS) { transport.awaitSend() }

            // Feed the canned selfInfo response the way an inbound BLE notification would arrive.
            session.deliverReceived(selfInfoPacket())

            val info = withTimeout(TIMEOUT_MS) { started.await() }
            assertEquals("Test", info.name)
            assertEquals(7, info.radioSpreadingFactor)
            assertEquals(32, info.publicKey.size)

            val selfInfoEvent = withTimeout(TIMEOUT_MS) {
                var event = received.receive()
                while (event !is MeshEvent.SelfInfoReceived) event = received.receive()
                event
            }
            assertEquals("Test", selfInfoEvent.info.name)
            collector.cancel()
        }
    }

    @Test
    fun setFloodScopeRegionWritesSubcommandZeroFrame() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            connect(session, transport)

            val command = async(Dispatchers.Default) {
                session.setFloodScope(scopeType = "region", scopeText = "Europe", scopeKey = ByteArray(0))
            }
            val frame = withTimeout(TIMEOUT_MS) { transport.awaitFloodScopeFrame() }

            // Sub-command 0 followed by SHA256("#Europe")[0..15] — the public-region key convention.
            val expected = byteArrayOf(SET_FLOOD_SCOPE_CODE, 0x00) + sha256("#Europe").copyOf(SCOPE_KEY_LENGTH)
            assertTrue("region frame ${frame.toHex()} != ${expected.toHex()}", frame.contentEquals(expected))

            session.deliverReceived(byteArrayOf(OK_RESPONSE_CODE))
            withTimeout(TIMEOUT_MS) { command.await() }
        }
    }

    @Test
    fun setFloodScopeUnscopedWritesSubcommandOneFrame() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            connect(session, transport)

            val command = async(Dispatchers.Default) { session.setFloodScopeUnscoped() }
            val frame = withTimeout(TIMEOUT_MS) { transport.awaitFloodScopeFrame() }

            val expected = byteArrayOf(SET_FLOOD_SCOPE_CODE, 0x01)
            assertTrue("unscoped frame ${frame.toHex()} != ${expected.toHex()}", frame.contentEquals(expected))

            session.deliverReceived(byteArrayOf(OK_RESPONSE_CODE))
            withTimeout(TIMEOUT_MS) { command.await() }
        }
    }

    @Test
    fun startAutoMessageFetchingDrainsOnMessagesWaiting() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            connect(session, transport)
            session.startAutoMessageFetching()

            // 0x83 is a push, not a command result. The Swift session must already be subscribed.
            session.deliverReceived(byteArrayOf(MESSAGES_WAITING_CODE))
            val frame = withTimeout(TIMEOUT_MS) { transport.awaitGetMessage() }
            assertEquals(GET_MESSAGE_CODE, frame.first())
            assertEquals(1, frame.size)

            session.deliverReceived(byteArrayOf(NO_MORE_MESSAGES_CODE))
            session.stopAutoMessageFetching()
        }
    }

    @Test
    fun newAdvertisementPushDecodesAsNewContact() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val key = ByteArray(PUBLIC_KEY_LENGTH) { 0x51 }
            session.deliverReceived(newAdvertisementPacket(key, "Hilltop"))

            val event = withTimeout(TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.NewContact) next = received.receive()
                next
            }
            assertEquals("Hilltop", event.contact.advertisedName)
            assertTrue(event.contact.publicKey.contentEquals(key))
            collector.cancel()
        }
    }

    @Test
    fun acknowledgementPushDecodesCodeAndOptionalTripTime() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val code = byteArrayOf(0x11, 0x22, 0x33, 0x44)
            session.deliverReceived(byteArrayOf(ACK_CODE) + code)
            val withoutTrip = withTimeout(TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.Acknowledgement) next = received.receive()
                next
            }
            assertTrue(withoutTrip.code.contentEquals(code))
            assertEquals(null, withoutTrip.tripTime)

            val tripMs = 250
            session.deliverReceived(
                byteArrayOf(ACK_CODE) + code + byteArrayOf(
                    (tripMs and 0xFF).toByte(),
                    (tripMs ushr 8 and 0xFF).toByte(),
                    (tripMs ushr 16 and 0xFF).toByte(),
                    (tripMs ushr 24 and 0xFF).toByte(),
                ),
            )
            val withTrip = withTimeout(TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.Acknowledgement || next.tripTime == null) next = received.receive()
                next
            }
            assertTrue(withTrip.code.contentEquals(code))
            assertEquals(tripMs, withTrip.tripTime)
            collector.cancel()
        }
    }

    @Test
    fun pathDiscoveryPushDecodesAsPathResponse() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val prefix = byteArrayOf(0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
            session.deliverReceived(
                byteArrayOf(PATH_DISCOVERY_CODE, 0x00) + prefix + byteArrayOf(0x00, 0x00),
            )

            val event = withTimeout(TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.PathResponse) next = received.receive()
                next
            }
            assertTrue(event.path.publicKeyPrefix.contentEquals(prefix))
            assertEquals(0, event.path.outPath.size)
            assertEquals(0, event.path.inPath.size)
            collector.cancel()
        }
    }

    @Test
    fun loginSuccessPushDecodesLegacyAdminPrefix() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val prefix = byteArrayOf(0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
            session.deliverReceived(byteArrayOf(LOGIN_SUCCESS_CODE, 0x01) + prefix)

            val event = withTimeout(TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.LoginSuccess) next = received.receive()
                next
            }
            assertTrue(event.login.isAdmin)
            assertEquals(0x02, event.login.permissions)
            assertTrue(event.login.publicKeyPrefix.contentEquals(prefix))
            collector.cancel()
        }
    }

    @Test
    fun loginFailedPushDecodesPublicKeyPrefix() = runBlocking {
        val transport = FakeTransport()
        MeshCoreSession(transport).use { session ->
            connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val prefix = byteArrayOf(0x21, 0x22, 0x23, 0x24, 0x25, 0x26)
            session.deliverReceived(byteArrayOf(LOGIN_FAILED_CODE, 0x00) + prefix)

            val event = withTimeout(TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.LoginFailed) next = received.receive()
                next
            }
            assertTrue(requireNotNull(event.publicKeyPrefix).contentEquals(prefix))
            collector.cancel()
        }
    }

    internal companion object {
        /** Brings the session up: start, report the link, feed the canned selfInfo, await start. */
        suspend fun connect(session: MeshCoreSession, transport: FakeTransport) = coroutineScope {
            val started = async(Dispatchers.Default) { session.start() }
            session.reportConnected()
            withTimeout(TIMEOUT_MS) { transport.awaitSend() }
            session.deliverReceived(selfInfoPacket())
            withTimeout(TIMEOUT_MS) { started.await() }
            Unit
        }

        const val TIMEOUT_MS = 5_000L
        const val SELF_INFO_RESPONSE_CODE = 0x05
        const val PUBLIC_KEY_LENGTH = 32
        const val SET_FLOOD_SCOPE_CODE: Byte = 0x36
        const val GET_MESSAGE_CODE: Byte = 0x0A
        const val MESSAGES_WAITING_CODE: Byte = 0x83.toByte()
        const val NO_MORE_MESSAGES_CODE: Byte = 0x0A
        const val NEW_ADVERTISEMENT_CODE: Byte = 0x8A.toByte()
        const val ADVERTISEMENT_CODE: Byte = 0x80.toByte()
        const val ADVERTISEMENT_KEY_FILL: Byte = 0x44
        const val PATH_UPDATE_CODE: Byte = 0x81.toByte()
        const val PATH_UPDATE_KEY_FILL: Byte = 0x81.toByte()
        const val CONTACT_DELETED_CODE: Byte = 0x8F.toByte()
        const val CONTACTS_FULL_CODE: Byte = 0x90.toByte()
        const val ACK_CODE: Byte = 0x82.toByte()
        const val PATH_DISCOVERY_CODE: Byte = 0x8D.toByte()
        const val LOGIN_SUCCESS_CODE: Byte = 0x85.toByte()
        const val LOGIN_FAILED_CODE: Byte = 0x86.toByte()
        const val OK_RESPONSE_CODE: Byte = 0x00
        const val SCOPE_KEY_LENGTH = 16
        const val CONTACT_NAME_FIELD_LENGTH = 32
        const val CONTACT_PATH_FIELD_LENGTH = 64

        fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.encodeToByteArray())

        fun ByteArray.toHex(): String = joinToString(" ") { byteArrayOf(it).toLowercaseHex() }

        /**
         * Rebuilds the `selfInfo` response frame the session expects after appStart, matching the
         * Swift `RoundTripTests.selfInfoPacket` wire layout byte-for-byte.
         */
        fun selfInfoPacket(): ByteArray {
            val out = ArrayList<Byte>()
            out.add(SELF_INFO_RESPONSE_CODE.toByte())
            out.add(1) // advertisement type
            out.add(22) // tx power
            out.add(22) // max tx power
            repeat(PUBLIC_KEY_LENGTH) { out.add(0x01) } // public key
            out.addLe32(0) // latitude
            out.addLe32(0) // longitude
            out.addLe32(0) // reserved
            out.addLe32(915_000) // frequency
            out.addLe32(125_000) // bandwidth
            out.add(7) // spreading factor
            out.add(5) // coding rate
            "Test".encodeToByteArray().forEach { out.add(it) } // name
            return out.toByteArray()
        }

        /**
         * 0x8A + the 147-byte `parseContactData` layout used by `Parsers.NewAdvertisement`.
         */
        fun newAdvertisementPacket(publicKey: ByteArray, name: String): ByteArray {
            val out = ArrayList<Byte>()
            out.add(NEW_ADVERTISEMENT_CODE)
            publicKey.forEach { out.add(it) }
            out.add(0x01) // contact type = chat
            out.add(0) // flags
            out.add(0xFF.toByte()) // flood out-path
            repeat(CONTACT_PATH_FIELD_LENGTH) { out.add(0) }
            val nameBytes = name.encodeToByteArray()
            repeat(CONTACT_NAME_FIELD_LENGTH) { i ->
                out.add(if (i < nameBytes.size) nameBytes[i] else 0)
            }
            out.addLe32(0) // last advertisement
            out.addLe32(0) // latitude
            out.addLe32(0) // longitude
            out.addLe32(0) // last modified
            return out.toByteArray()
        }

        private fun MutableList<Byte>.addLe32(value: Int) {
            add((value and 0xFF).toByte())
            add((value ushr 8 and 0xFF).toByte())
            add((value ushr 16 and 0xFF).toByte())
            add((value ushr 24 and 0xFF).toByte())
        }
    }
}

@RunWith(AndroidJUnit4::class)
class MeshCoreSessionPushInstrumentedTest {
    @Test
    fun advertisementPushDecodesAsAdvertisement() = runBlocking {
        val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
        MeshCoreSession(transport).use { session ->
            MeshCoreSessionInstrumentedTest.connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val key =
                ByteArray(MeshCoreSessionInstrumentedTest.PUBLIC_KEY_LENGTH) {
                    MeshCoreSessionInstrumentedTest.ADVERTISEMENT_KEY_FILL
                }
            session.deliverReceived(byteArrayOf(MeshCoreSessionInstrumentedTest.ADVERTISEMENT_CODE) + key)

            val event = withTimeout(MeshCoreSessionInstrumentedTest.TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.Advertisement) next = received.receive()
                next
            }
            assertTrue(event.publicKey.contentEquals(key))
            collector.cancel()
        }
    }

    @Test
    fun pathUpdatePushDecodesAsPathUpdate() = runBlocking {
        val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
        MeshCoreSession(transport).use { session ->
            MeshCoreSessionInstrumentedTest.connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val key =
                ByteArray(MeshCoreSessionInstrumentedTest.PUBLIC_KEY_LENGTH) {
                    MeshCoreSessionInstrumentedTest.PATH_UPDATE_KEY_FILL
                }
            session.deliverReceived(byteArrayOf(MeshCoreSessionInstrumentedTest.PATH_UPDATE_CODE) + key)

            val event = withTimeout(MeshCoreSessionInstrumentedTest.TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.PathUpdate) next = received.receive()
                next
            }
            assertTrue(event.publicKey.contentEquals(key))
            collector.cancel()
        }
    }

    @Test
    fun contactDeletedPushDecodesAsContactDeleted() = runBlocking {
        val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
        MeshCoreSession(transport).use { session ->
            MeshCoreSessionInstrumentedTest.connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            val key =
                ByteArray(MeshCoreSessionInstrumentedTest.PUBLIC_KEY_LENGTH) {
                    MeshCoreSessionInstrumentedTest.CONTACT_DELETED_CODE
                }
            session.deliverReceived(byteArrayOf(MeshCoreSessionInstrumentedTest.CONTACT_DELETED_CODE) + key)

            val event = withTimeout(MeshCoreSessionInstrumentedTest.TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.ContactDeleted) next = received.receive()
                next
            }
            assertTrue(event.publicKey.contentEquals(key))
            collector.cancel()
        }
    }

    @Test
    fun contactsFullPushDecodesAsContactsFull() = runBlocking {
        val transport = MeshCoreSessionInstrumentedTest.FakeTransport()
        MeshCoreSession(transport).use { session ->
            MeshCoreSessionInstrumentedTest.connect(session, transport)
            val received = Channel<MeshEvent>(Channel.UNLIMITED)
            val collector = launch(Dispatchers.Default) { session.events.collect { received.trySend(it) } }
            session.eventSubscriberCount.first { it > 0 }

            session.deliverReceived(byteArrayOf(MeshCoreSessionInstrumentedTest.CONTACTS_FULL_CODE))

            withTimeout(MeshCoreSessionInstrumentedTest.TIMEOUT_MS) {
                var next = received.receive()
                while (next !is MeshEvent.ContactsFull) next = received.receive()
            }
            collector.cancel()
        }
    }

    @Test
    fun commandBeforeConnectFailsNotConnected() = runBlocking {
        MeshCoreSession(MeshCoreSessionInstrumentedTest.FakeTransport()).use { session ->
            var thrown: MeshBridgeException? = null
            try {
                withTimeout(MeshCoreSessionInstrumentedTest.TIMEOUT_MS) { session.getBattery() }
            } catch (e: MeshBridgeException) {
                thrown = e
            }
            assertTrue("getBattery before connect should fail", thrown != null)
            assertEquals(BridgeErrorCode.NOT_CONNECTED, thrown?.code)
        }
    }
}
