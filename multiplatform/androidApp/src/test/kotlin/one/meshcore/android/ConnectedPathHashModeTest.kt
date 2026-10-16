package one.meshcore.android

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.meshcore.MeshEvent
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The host's connected-hash-mode seam and the zero-hop ping that reads it. A radio configured for 2- or
 * 4-byte hop hashes must be addressed at that width: the path editor encodes routing hops at `mode + 1`
 * bytes and a trace declares `1 shl mode`. Mirrors iOS `Device.hashSize` / `Device.traceHashSize` and
 * `PingHelper.zeroHopPing`.
 */
class ConnectedPathHashModeTest {
    /** Captures what the ping put on the wire; the events flow stays silent so the ping times out. */
    private class TraceCapturingSession : FakeMeshSession() {
        var sentFlags: Int? = null
        var sentPath: ByteArray? = null

        override val events: SharedFlow<MeshEvent> = MutableSharedFlow()

        override suspend fun sendTrace(tag: Long?, authCode: Long?, flags: Int, path: ByteArray?): MessageSentInfo {
            sentFlags = flags
            sentPath = path
            return MessageSentInfo(route = 0, expectedAck = ByteArray(EXPECTED_ACK_BYTES), suggestedTimeoutMs = 1_000)
        }
    }

    private val contact = ContactDto(
        id = Uuid.normalized("A0000000-0000-0000-0000-000000000002"),
        radioID = Uuid.normalized("A0000000-0000-0000-0000-000000000001"),
        publicKey = ByteArray(PUBLIC_KEY_BYTES) { (it + 1).toByte() },
        name = "Repeater",
        typeRawValue = ContactType.REPEATER.raw,
        flags = 0,
        outPathLength = 0xFF,
        outPath = ByteArray(0),
        lastAdvertTimestamp = 0,
        latitude = 0.0,
        longitude = 0.0,
        lastModified = 0,
        isBlocked = false,
        isMuted = false,
        isFavorite = false,
        unreadCount = 0,
    )

    @Test
    fun aDisconnectedRadioLeavesTheEditorOnItsDefaultAndPingsAtOneByte() {
        val mode = ConnectedPathHashMode()

        assertNull(mode.routingHopBytes())
        assertEquals(0, mode.traceHashMode())
    }

    @Test
    fun aCatalogHashModeRefreshMovesTheCacheTheEditorReads() {
        val cache = ConnectedPathHashMode()
        cache.set(0)
        assertEquals(1, cache.routingHopBytes())

        // HU's catalog pathHashMode is 1. applyRadioPreset must land that here the same way
        // setPathHashMode does, or the path editor keeps encoding at connect-time width.
        cache.set(1)
        assertEquals(2, cache.routingHopBytes())
        assertEquals(1, cache.traceHashMode())

        // Routing is mode + 1 (3 bytes) while a trace declares 1 shl mode (4 bytes); conflating them
        // makes the firmware parse the path against the wrong stride.
        cache.set(2)
        assertEquals(3, cache.routingHopBytes())
        assertEquals(2, cache.traceHashMode())
    }

    @Test
    fun aCliHashModeWriteMovesTheCacheTheEditorReads() = runTest {
        val cache = ConnectedPathHashMode()
        cache.set(0)
        var persisted: Int? = null

        cache.applyVerifiedWrite(mode = 2) { persisted = it }

        assertEquals(2, persisted)
        assertEquals(3, cache.routingHopBytes())
        assertEquals(2, cache.traceHashMode())
    }

    @Test
    fun theCachedModeReachesTheTraceThePingSends() = runTest {
        val mode = ConnectedPathHashMode().apply { set(1) }
        val session = TraceCapturingSession()

        contactZeroHopPing(
            contact = contact,
            session = session,
            pathHashMode = mode.traceHashMode(),
            nowMs = { 0L },
        )

        assertEquals(1, session.sentFlags)
        assertArrayEquals(contact.publicKey.copyOf(2), session.sentPath)
    }

    @Test
    fun aFourByteHashRadioIsPingedAtFourBytes() = runTest {
        val mode = ConnectedPathHashMode().apply { set(2) }
        val session = TraceCapturingSession()

        contactZeroHopPing(
            contact = contact,
            session = session,
            pathHashMode = mode.traceHashMode(),
            nowMs = { 0L },
        )

        assertEquals(2, session.sentFlags)
        assertArrayEquals(contact.publicKey.copyOf(4), session.sentPath)
    }

    private companion object {
        const val PUBLIC_KEY_BYTES = 32
        const val EXPECTED_ACK_BYTES = 4
    }
}
