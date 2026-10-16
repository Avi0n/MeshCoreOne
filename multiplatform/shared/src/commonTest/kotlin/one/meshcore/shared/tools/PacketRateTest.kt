package one.meshcore.shared.tools

import kotlin.test.Test
import kotlin.test.assertEquals

/** Covers the pure packet-rate math the foreground notification renders (iOS LiveActivityManager parity). */
class PacketRateTest {
    private val now = 1_000_000L
    private val window = PacketRate.WINDOW_MS

    @Test
    fun fullMinuteWindowCountsAllRecentPackets() {
        // 12 packets within the trailing minute: a full-minute window is the true count for that minute.
        val timestamps = (1..12).map { now - it * 1_000L }
        assertEquals(12, PacketRate.packetsPerMinute(timestamps, now))
    }

    @Test
    fun packetsOlderThanWindowAreExcluded() {
        val recent = listOf(now - 5_000L, now - 30_000L)
        val stale = listOf(now - window - 1L, now - window * 2)
        assertEquals(2, PacketRate.packetsPerMinute(recent + stale, now))
    }

    @Test
    fun packetExactlyAtWindowEdgeCounts() {
        assertEquals(1, PacketRate.packetsPerMinute(listOf(now - window), now))
    }

    @Test
    fun noPacketsIsZero() {
        assertEquals(0, PacketRate.packetsPerMinute(emptyList(), now))
    }

    @Test
    fun shortWindowScalesToPerMinuteRate() {
        // 5 packets in a 30s window extrapolate to 10/min.
        val half = window / 2
        val timestamps = (1..5).map { now - it * 1_000L }
        assertEquals(10, PacketRate.packetsPerMinute(timestamps, now, half))
    }
}
