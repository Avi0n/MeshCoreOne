// PortedFrom: MC1Services/Tests/MC1ServicesTests/PersistenceStoreTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.database

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.meshcoreone.android.core.model.DecryptStatus
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.protocol.event.PayloadType
import com.meshcoreone.android.core.protocol.event.RouteType
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class CorruptRxProjectionTest {
    private lateinit var db: MeshCoreDatabase
    private val radio = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val otherRadio = UUID.fromString("00000000-0000-0000-0000-000000000002")

    @Before fun open() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext<Context>(), MeshCoreDatabase::class.java,
        ).build()
    }

    @After fun close() { db.close() }

    private fun valid(id: Long = 1): RxLogEntryEntity = RxLogEntryEntity(
        radio, UUID(0, id), StoredInstant(1_700_000_000, 123_456_789), -12.5, -90,
        1, 15, 0, Bytes.of(0x10, 0x20), 0, Bytes.EMPTY, Bytes.of(0xAB, 0xCD), Bytes.of(0xFF),
        "deadbeef", null, "retained", DecryptStatus.NOT_APPLICABLE.rawValue, "from", "to",
        null, "region", SnapshotList.of("region"), 15,
    )

    @Test fun eachOriginalCorruptFieldUsesPinnedFallbackWithoutReplacingEitherRadioRow() = runTest {
        val valid = valid()
        val cases = listOf(
            valid.copy(routeType = 999),
            valid.copy(payloadType = -1),
            valid.copy(payloadVersion = 5000),
            valid.copy(pathLength = 400),
            valid.copy(channelIndex = 9999),
            valid.copy(senderTimestamp = -10),
        )
        for ((index, corrupt) in cases.withIndex()) {
            val raw = corrupt.copy(id = UUID(0, index.toLong() + 10))
            val peer = valid.copy(radioId = otherRadio, id = raw.id)
            db.rxLogs().insert(peer)
            val peerDTO = peer.toDTO()
            db.rxLogs().insert(raw)
            repeat(2) {
                val stored = assertNotNull(db.rxLogs().byId(radio, raw.id))
                assertEquals(raw, stored)
                val dto = stored.toDTO()
                assertEquals(RouteType.FLOOD, dto.routeType)
                assertEquals(if (index == 1) PayloadType.UNKNOWN else PayloadType.RAW_CUSTOM, dto.payloadType)
                assertEquals(0.toUByte(), dto.payloadVersion)
                assertEquals(0.toUByte(), dto.pathLength)
                assertNull(dto.channelIndex)
                assertNull(dto.senderTimestamp)
                assertEquals(raw.id, dto.id)
                assertEquals(radio, dto.radioId.value)
                assertEquals(StoredInstant(1_700_000_000, 123_456_789).toInstant(), dto.receivedAt)
                assertEquals(-12.5, dto.snr)
                assertEquals(-90L, dto.rssi)
                assertEquals(Bytes.of(0x10, 0x20), dto.transportCode)
                assertEquals(Bytes.of(0xAB, 0xCD), dto.packetPayload)
                assertEquals(Bytes.of(0xFF), dto.rawPayload)
                assertEquals("deadbeef", dto.packetHash)
                assertEquals("retained", dto.channelName)
                assertEquals("region", dto.regionScope)
                assertEquals(SnapshotList.of("region"), dto.regionScopeMatches)
                assertNull(dto.decodedText)
                assertEquals(raw, assertNotNull(db.rxLogs().byId(radio, raw.id)))
                assertEquals(peer, assertNotNull(db.rxLogs().byId(otherRadio, peer.id)))
                assertEquals(peerDTO, assertNotNull(db.rxLogs().byId(otherRadio, peer.id)).toDTO())
                assertEquals(index.toLong() + 1, db.rxLogs().count(radio))
                assertEquals(index.toLong() + 1, db.rxLogs().count(otherRadio))
            }
        }
        val expected = cases.mapIndexed { index, row -> row.copy(id = UUID(0, index.toLong() + 10)) }
        assertEquals(expected.toSet(), db.rxLogs().newest(radio, -1).toSet())
    }

    @Test fun originalSixCorruptValuesProjectExactPinnedDefaultsAndPreserveEveryRawColumn() = runTest {
        val raw = valid().copy(
            snr = null, rssi = null, transportCode = null, pathNodes = Bytes.EMPTY,
            packetPayload = Bytes.EMPTY, rawPayload = Bytes.EMPTY, channelName = null,
            fromContactName = null, toContactName = null, regionScope = null,
            regionScopeMatches = SnapshotList.empty(), payloadTypeBits = 0,
            routeType = 999, payloadType = -1, payloadVersion = 5000, pathLength = 400,
            channelIndex = 9999, senderTimestamp = -10,
        )
        db.rxLogs().insert(raw)
        repeat(2) {
            val stored = assertNotNull(db.rxLogs().byId(radio, raw.id))
            assertEquals(999L, stored.routeType)
            assertEquals(-1L, stored.payloadType)
            assertEquals(5000L, stored.payloadVersion)
            assertEquals(400L, stored.pathLength)
            assertEquals(9999L, stored.channelIndex)
            assertEquals(-10L, stored.senderTimestamp)
            val dto = stored.toDTO()
            assertEquals(raw.id, dto.id)
            assertEquals(radio, dto.radioId.value)
            assertEquals(RouteType.FLOOD, dto.routeType)
            assertEquals(PayloadType.UNKNOWN, dto.payloadType)
            assertEquals(0.toUByte(), dto.payloadVersion)
            assertEquals(0.toUByte(), dto.pathLength)
            assertNull(dto.channelIndex)
            assertNull(dto.senderTimestamp)
            assertEquals(Bytes.EMPTY, dto.pathNodes)
            assertEquals(Bytes.EMPTY, dto.packetPayload)
            assertEquals(Bytes.EMPTY, dto.rawPayload)
            assertEquals("deadbeef", dto.packetHash)
            assertEquals(listOf(raw), db.rxLogs().newest(radio, -1))
        }
    }

    @Test fun validUnsignedBoundsAndUnknownEnumsFollowFailableSourceConversionsWithoutTruncation() {
        val min = valid().copy(routeType = 0, payloadType = 0, channelIndex = 0, senderTimestamp = 0).toDTO()
        assertEquals(RouteType.TC_FLOOD, min.routeType)
        assertEquals(PayloadType.REQUEST, min.payloadType)
        assertEquals(0.toUByte(), min.channelIndex)
        assertEquals(0.toUInt(), min.senderTimestamp)
        val max = valid().copy(
            routeType = 3, payloadType = 255, payloadVersion = 255, pathLength = 255,
            channelIndex = 255, senderTimestamp = 0xFFFF_FFFFL,
        ).toDTO()
        assertEquals(RouteType.TC_DIRECT, max.routeType)
        assertEquals(PayloadType.UNKNOWN, max.payloadType)
        assertEquals(255.toUByte(), max.payloadVersion)
        assertEquals(255.toUByte(), max.pathLength)
        assertEquals(255.toUByte(), max.channelIndex)
        assertEquals(UInt.MAX_VALUE, max.senderTimestamp)
        for (invalid in listOf(Long.MIN_VALUE, -1, 256, Long.MAX_VALUE)) {
            val dto = valid().copy(
                routeType = invalid, payloadType = invalid, payloadVersion = invalid,
                pathLength = invalid, channelIndex = invalid,
            ).toDTO()
            assertEquals(RouteType.FLOOD, dto.routeType)
            assertEquals(PayloadType.UNKNOWN, dto.payloadType)
            assertEquals(0.toUByte(), dto.payloadVersion)
            assertEquals(0.toUByte(), dto.pathLength)
            assertNull(dto.channelIndex)
        }
        for (invalid in listOf(Long.MIN_VALUE, -1, 0x1_0000_0000L, Long.MAX_VALUE)) {
            assertNull(valid().copy(senderTimestamp = invalid).toDTO().senderTimestamp)
        }
        assertEquals(RouteType.FLOOD, valid().copy(routeType = 4).toDTO().routeType)
        assertEquals(PayloadType.UNKNOWN, valid().copy(payloadType = 12).toDTO().payloadType)
        assertEquals(DecryptStatus.NOT_APPLICABLE, valid().copy(decryptStatus = Long.MAX_VALUE).toDTO().decryptStatus)
        assertEquals(15.toUByte(), valid().copy(payloadTypeBits = -1).toDTO().payloadTypeBits)
    }
}
