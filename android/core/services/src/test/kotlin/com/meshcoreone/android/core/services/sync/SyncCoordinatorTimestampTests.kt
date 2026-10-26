// PortedFrom: MC1Services/Tests/MC1ServicesTests/SyncCoordinatorTimestampTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.sync

import com.meshcoreone.android.core.model.DeliveryContext
import com.meshcoreone.android.core.model.MessageDTO
import com.meshcoreone.android.core.model.MessageDirection
import com.meshcoreone.android.core.model.MessageStatus
import com.meshcoreone.android.core.model.RadioId
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/** Swift `Date().addingTimeInterval(seconds)` keeping sub-second precision. */
private fun Instant.adding(seconds: Double): Instant = plusNanos((seconds * 1_000_000_000).toLong())

/** Original SyncCoordinatorTimestampTests, SyncCoordinatorSortDateTests and SameSenderReorderingTests. */
class SyncCoordinatorTimestampTests {
    private val oneMinute = 60.0
    private val fiveMinutes = 5.0 * 60
    private val sixMinutes = 6.0 * 60
    private val oneWeek = 7.0 * 24 * 60 * 60
    private val threeMonths = 3.0 * 30 * 24 * 60 * 60
    private val sixMonths = 6.0 * 30 * 24 * 60 * 60
    private val sevenMonths = 7.0 * 30 * 24 * 60 * 60

    /** A receive time with a fractional second, like Swift `Date()`. */
    private fun now(): Instant = Instant.now().let { if (it.nano == 0) it.plusMillis(250) else it }

    private fun case(name: String, body: () -> Unit) = pureCase("SyncCoordinatorTimestampTests", name, body)

    private fun assertUnchanged(timestamp: UInt, receive: Instant) {
        val result = SyncCoordinator.correctTimestampIfNeeded(timestamp, receive)
        assertFalse(result.wasCorrected)
        assertEquals(timestamp, result.correctedTimestamp)
    }

    private fun assertCorrected(timestamp: UInt, receive: Instant) {
        val result = SyncCoordinator.correctTimestampIfNeeded(timestamp, receive)
        assertTrue(result.wasCorrected)
        assertEquals(receive.uint32Seconds(), result.correctedTimestamp)
    }

    @TestFactory
    fun timestampCorrection(): List<DynamicTest> = listOf(
        case("Timestamp within valid range is not corrected") { now().let { assertUnchanged(it.uint32Seconds(), it) } },
        case("Timestamp 1 minute in future is not corrected") { now().let { assertUnchanged(it.adding(oneMinute).uint32Seconds(), it) } },
        case("Timestamp exactly 5 minutes in future is not corrected") { now().let { assertUnchanged(it.adding(fiveMinutes).uint32Seconds(), it) } },
        case("Timestamp 6 minutes in future is corrected") { now().let { assertCorrected(it.adding(sixMinutes).uint32Seconds(), it) } },
        case("Timestamp 1 week ago is not corrected") { now().let { assertUnchanged(it.adding(-oneWeek).uint32Seconds(), it) } },
        case("Timestamp 3 months ago is not corrected") { now().let { assertUnchanged(it.adding(-threeMonths).uint32Seconds(), it) } },
        case("Timestamp exactly 6 months in past is not corrected") {
            // Whole-second receive time so UInt32 truncation does not push the timestamp past the boundary.
            val receive = Instant.ofEpochSecond(Instant.now().epochSecond)
            assertUnchanged(receive.adding(-sixMonths).uint32Seconds(), receive)
        },
        case("Timestamp 7 months ago is corrected") { now().let { assertCorrected(it.adding(-sevenMonths).uint32Seconds(), it) } },
        case("Timestamp of zero (Unix epoch) is corrected") { assertCorrected(0u, now()) },
        case("Timestamp from year 2020 is corrected") { assertCorrected(1_577_836_800u, now()) },
        case("Timestamp from year 2030 is corrected") { assertCorrected(1_893_456_000u, now()) },
        case("Original timestamp is preserved when correction is applied") {
            val receive = now()
            val broken = 0u
            val result = SyncCoordinator.correctTimestampIfNeeded(broken, receive)
            assertTrue(result.wasCorrected)
            assertEquals(receive.uint32Seconds(), result.correctedTimestamp)
            assertEquals(0u, broken)
            assertNotEquals(broken, result.correctedTimestamp)
        },
        case("Corrected timestamp differs from original for invalid input") {
            val receive = now()
            val original = receive.adding(365.0 * 24 * 60 * 60).uint32Seconds()
            val result = SyncCoordinator.correctTimestampIfNeeded(original, receive)
            assertTrue(result.wasCorrected)
            assertEquals(receive.uint32Seconds(), result.correctedTimestamp)
            assertNotEquals(original, result.correctedTimestamp)
        },
        case("Receive time near Unix epoch does not crash") { assertUnchanged(500u, Instant.ofEpochSecond(1000)) },
        case("Receive time at Unix epoch handles timestamp validation") {
            val result = SyncCoordinator.correctTimestampIfNeeded(1_000_000u, Instant.EPOCH)
            assertTrue(result.wasCorrected)
            assertEquals(0u, result.correctedTimestamp)
        },
    )

    @TestFactory
    fun sortDate(): List<DynamicTest> {
        val suite = "SyncCoordinatorSortDateTests"
        return listOf(
            pureCase(suite, "Live message sorts by receive time") {
                val now = now()
                assertEquals(now, SyncCoordinator.sortDate(DeliveryContext.Live, now))
            },
            pureCase(suite, "Backlog message sorts by the drain anchor, not its receive time") {
                val anchor = now().adding(-oneMinute)
                assertEquals(anchor, SyncCoordinator.sortDate(DeliveryContext.InitialSync(anchor), now()))
            },
            pureCase(suite, "Every message in one drain shares the anchor as its sort date") {
                val anchor = now()
                listOf(0.0, oneMinute, 2 * oneMinute).forEach {
                    assertEquals(anchor, SyncCoordinator.sortDate(DeliveryContext.InitialSync(anchor), now().adding(it)))
                }
            },
            pureCase(suite, "Distinct drains sort as distinct blocks in delivery order") {
                val earlierDrain = now().adding(-oneMinute)
                val laterDrain = now()
                val earlier = SyncCoordinator.sortDate(DeliveryContext.InitialSync(earlierDrain), now())
                val later = SyncCoordinator.sortDate(DeliveryContext.InitialSync(laterDrain), now())
                assertEquals(earlierDrain, earlier)
                assertEquals(laterDrain, later)
                assertTrue(earlier < later)
            },
        )
    }

    private fun dm(timestamp: UInt, createdAt: Instant, sortDate: Instant? = null, direction: MessageDirection = MessageDirection.INCOMING) =
        MessageDTO(
            radioId = RadioId(UUID.randomUUID()), contactID = UUID.randomUUID(), text = "msg-$timestamp", timestamp = timestamp,
            createdAt = createdAt, sortDate = sortDate ?: createdAt, direction = direction, status = MessageStatus.DELIVERED,
        )

    private fun channel(timestamp: UInt, createdAt: Instant, senderName: String? = null) = MessageDTO(
        radioId = RadioId(UUID.randomUUID()), channelIndex = 0u, text = "msg-$timestamp", timestamp = timestamp,
        createdAt = createdAt, direction = MessageDirection.INCOMING, status = MessageStatus.DELIVERED, senderNodeName = senderName,
    )

    private fun reorder(vararg messages: MessageDTO) = MessageDTO.reorderSameSenderClusters(messages.toList())

    @TestFactory
    fun sameSenderReordering(): List<DynamicTest> {
        val suite = "SameSenderReorderingTests"
        val base = Instant.now()
        return listOf(
            pureCase(suite, "Empty array returns empty") { assertTrue(MessageDTO.reorderSameSenderClusters(emptyList()).isEmpty()) },
            pureCase(suite, "Single message returns unchanged") {
                val msg = dm(100u, base)
                assertEquals(listOf(msg.id), reorder(msg).map { it.id })
            },
            pureCase(suite, "DM messages within 5 seconds are reordered by sender timestamp") {
                assertEquals(listOf(100u, 200u), reorder(dm(200u, base), dm(100u, base.adding(2.0))).map { it.timestamp })
            },
            pureCase(suite, "Outgoing DM messages within 5 seconds are reordered by sender timestamp") {
                val out = MessageDirection.OUTGOING
                assertEquals(listOf(100u, 200u), reorder(dm(200u, base, direction = out), dm(100u, base.adding(2.0), direction = out)).map { it.timestamp })
            },
            pureCase(suite, "DM messages beyond 5 seconds are not reordered") {
                assertEquals(listOf(100u, 200u), reorder(dm(100u, base), dm(200u, base.adding(6.0))).map { it.timestamp })
            },
            pureCase("WP-214", "Reversed sender timestamps across a six second gap preserve separate DM clusters") {
                val first = dm(200u, base)
                val second = dm(100u, base.adding(6.0))
                val result = reorder(first, second)
                assertEquals(listOf(first.id, second.id), result.map { it.id })
                assertEquals(listOf(200u, 100u), result.map { it.timestamp })
            },
            pureCase(suite, "Channel messages from different senders are not clustered") {
                val result = reorder(channel(200u, base, "Alice"), channel(100u, base.adding(2.0), "Bob"))
                assertEquals(listOf("Alice", "Bob"), result.map { it.senderNodeName })
            },
            pureCase(suite, "Channel messages from same sender within window are reordered") {
                val result = reorder(channel(200u, base, "Alice"), channel(100u, base.adding(3.0), "Alice"))
                assertEquals(listOf(100u, 200u), result.map { it.timestamp })
            },
            pureCase(suite, "Mixed directions break clusters") {
                val result = reorder(dm(200u, base), dm(100u, base.adding(1.0), direction = MessageDirection.OUTGOING))
                assertEquals(listOf(MessageDirection.INCOMING, MessageDirection.OUTGOING), result.map { it.direction })
            },
            pureCase(suite, "Three messages in cluster are fully sorted") {
                val result = reorder(dm(300u, base), dm(100u, base.adding(2.0)), dm(200u, base.adding(4.0)))
                assertEquals(listOf(100u, 200u, 300u), result.map { it.timestamp })
            },
            pureCase(suite, "Exactly 5 second gap is included in cluster") {
                assertEquals(listOf(100u, 200u), reorder(dm(200u, base), dm(100u, base.adding(5.0))).map { it.timestamp })
            },
            pureCase(suite, "Multiple consecutive clusters are each reordered independently") {
                val result = reorder(dm(200u, base), dm(100u, base.adding(3.0)), dm(400u, base.adding(13.0)), dm(300u, base.adding(15.0)))
                assertEquals(listOf(100u, 200u, 300u, 400u), result.map { it.timestamp })
            },
            pureCase(suite, "Channel messages with nil sender names are not clustered") {
                assertEquals(listOf(200u, 100u), reorder(channel(200u, base), channel(100u, base.adding(2.0))).map { it.timestamp })
            },
            pureCase(suite, "Same-sender messages with identical timestamps use createdAt as tiebreaker") {
                val first = dm(100u, base)
                val second = dm(100u, base.adding(0.5))
                assertEquals(listOf(first.id, second.id), reorder(second, first).map { it.id })
            },
            pureCase(suite, "Messages already in correct order are unchanged") {
                val messages = listOf(dm(100u, base), dm(200u, base.adding(1.0)), dm(300u, base.adding(2.0)))
                assertEquals(messages.map { it.id }, reorder(*messages.toTypedArray()).map { it.id })
            },
            pureCase(suite, "Far-apart sortDates are not clustered even when createdAt order is inverted") {
                val early = dm(200u, base.adding(3600.0), sortDate = base)
                val late = dm(100u, base, sortDate = base.adding(60.0))
                assertEquals(listOf(early.id, late.id), reorder(early, late).map { it.id })
            },
            pureCase(suite, "Clustering window follows sortDate, not createdAt") {
                val first = dm(200u, base, sortDate = base)
                val second = dm(100u, base.adding(100.0), sortDate = base.adding(2.0))
                assertEquals(listOf(100u, 200u), reorder(first, second).map { it.timestamp })
            },
        )
    }
}
