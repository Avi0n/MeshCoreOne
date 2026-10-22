// PortedFrom: MC1Services/Tests/MC1ServicesTests/NodeSnapshotServiceTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: real Room replaces SwiftData; one injected store/service clock keeps captures in-window.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.data.repository.RoomPersistenceStore
import com.meshcoreone.android.core.model.NodeLocationFix
import com.meshcoreone.android.core.model.NodeStatusMetrics
import com.meshcoreone.android.core.protocol.bytes.Bytes
import com.meshcoreone.android.core.services.remote.NodeSnapshotService
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test

class NodeSnapshotLocationOriginalPredicateTest : RoomProcessTest() {
    private val publicKey = Bytes(ByteArray(32) { 0x42 })
    private val fix = NodeLocationFix(37.7749, -122.4194)
    private val metrics = NodeStatusMetrics(
        batteryMillivolts = 3850u, lastSNR = 8.5, lastRSSI = -87, noiseFloor = -120,
        uptimeSeconds = 3600u, rxAirtimeSeconds = 100u, packetsSent = 500u, packetsReceived = 1000u,
    )
    private val clock = Clock.fixed(Instant.ofEpochSecond(1_704_067_200), ZoneOffset.UTC)

    private suspend fun TestScope.withSnapshots(
        assertions: suspend (NodeSnapshotService, RoomPersistenceStore) -> Unit,
    ) {
        val persistence = RoomPersistenceStore(db, backgroundScope, clock)
        try {
            assertions(NodeSnapshotService(persistence, clock), persistence)
        } finally {
            persistence.close()
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::A location fix persists on capture()")
    @Test fun originalStatusCaptureCommitsTheSanFranciscoFix() = runTest {
        withSnapshots { service, persistence ->
            val id = assertNotNull(service.recordSnapshot(publicKey, status = metrics, location = fix))

            val latest = assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey))
            assertEquals(37.7749, latest.latitude)
            assertEquals(-122.4194, latest.longitude)
            assertEquals(id, latest.id)
            assertEquals(publicKey, latest.nodePublicKey)
            assertEquals(clock.instant(), latest.timestamp)
            assertEquals(3850.toUShort(), latest.batteryMillivolts)
            assertEquals(3600u, latest.uptimeSeconds)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::A capture without a fix leaves location nil()")
    @Test fun originalStatusOnlyCaptureCommitsARowWithBothCoordinatesAbsent() = runTest {
        withSnapshots { service, persistence ->
            val id = assertNotNull(service.recordSnapshot(publicKey, status = metrics))

            val latest = assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey))
            assertNull(latest.latitude)
            assertNull(latest.longitude)
            assertEquals(id, latest.id)
            assertEquals(1, service.fetchSnapshots(publicKey).size)
            assertEquals(3600u, latest.uptimeSeconds)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::A GPS-only capture persists the fix on a fresh row()")
    @Test fun originalGpsOnlyCaptureCommitsItsFixWithoutStatus() = runTest {
        withSnapshots { service, persistence ->
            val id = assertNotNull(service.recordSnapshot(publicKey, location = fix))

            val latest = assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey))
            assertEquals(37.7749, latest.latitude)
            assertNull(latest.uptimeSeconds)
            assertEquals(-122.4194, latest.longitude)
            assertEquals(id, latest.id)
            assertNull(latest.batteryMillivolts)
            assertNull(latest.altitude)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::An in-window fix is first-wins()")
    @Test fun originalLaterFortyMinusHundredFixCannotOverwriteTheFirstRow() = runTest {
        withSnapshots { service, _ ->
            val id = assertNotNull(service.recordSnapshot(publicKey, location = fix))
            val second = service.recordSnapshot(publicKey, location = NodeLocationFix(40.0, -100.0))

            val snapshots = service.fetchSnapshots(publicKey)
            assertEquals(1, snapshots.size)
            assertEquals(37.7749, snapshots[0].latitude)
            assertEquals(-122.4194, snapshots[0].longitude)
            assertEquals(id, second)
            assertEquals(id, snapshots[0].id)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::An in-window no-fix response does not erase an existing fix()")
    @Test fun originalStatusWithoutFixPreservesTheExistingCoordinates() = runTest {
        withSnapshots { service, persistence ->
            val id = assertNotNull(service.recordSnapshot(publicKey, location = fix))
            val second = service.recordSnapshot(publicKey, status = metrics)

            val latest = assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey))
            assertEquals(37.7749, latest.latitude)
            assertEquals(-122.4194, latest.longitude)
            assertEquals(id, second)
            assertEquals(id, latest.id)
            assertEquals(3600u, latest.uptimeSeconds)
        }
    }

    @OriginalCase("NodeSnapshotServiceTests::An in-window fix enriches a row that had none()")
    @Test fun originalFixEnrichesTheSamePreviouslyFixlessStatusRow() = runTest {
        withSnapshots { service, persistence ->
            val id = assertNotNull(service.recordSnapshot(publicKey, status = metrics))
            val before = assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey))
            assertNull(before.latitude)
            assertNull(before.longitude)

            val second = service.recordSnapshot(publicKey, location = fix)

            assertEquals(1, service.fetchSnapshots(publicKey).size)
            val latest = assertNotNull(persistence.fetchLatestNodeStatusSnapshot(publicKey))
            assertEquals(37.7749, latest.latitude)
            assertEquals(-122.4194, latest.longitude)
            assertEquals(id, second)
            assertEquals(id, latest.id)
            assertEquals(3600u, latest.uptimeSeconds)
        }
    }
}
