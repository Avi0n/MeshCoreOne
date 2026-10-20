// PortedFrom: MC1Services/Tests/MC1ServicesTests/Services/DebugLogRetentionPruneTests.swift@db14559b39d32322b06477c6ae676112f583db50
// Adaptation: fixed relative ages and the production Room store replace wall-clock sampling and SwiftData.
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.app.container.RoomProcessTest
import com.meshcoreone.android.core.contracts.domain.DebugLogRetention
import com.meshcoreone.android.core.model.DebugLogEntryDTO
import com.meshcoreone.android.core.model.DebugLogLevel
import com.meshcoreone.android.core.model.SnapshotList
import com.meshcoreone.android.core.model.snapshot
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.Test

class DebugLogRetentionOriginalPredicateTest : RoomProcessTest() {
    private val now = Instant.parse("2025-01-01T00:00:00Z")

    private fun entry(ageSeconds: Long): DebugLogEntryDTO = DebugLogEntryDTO.create(
        level = DebugLogLevel.INFO,
        subsystem = "test",
        category = "retention",
        message = "entry aged ${ageSeconds.toDouble()}s",
        timestamp = now.minusSeconds(ageSeconds),
    )

    @OriginalCase("DebugLogRetentionPruneTests::prune keeps a recent entry and drops one older than the window()")
    @Test fun originalRecent60SecondsAndStaleWindowPlus3600RetainOnlyTheActualRecentId() = runTest {
        assertEquals(Duration.ofDays(7), DebugLogRetention.window)
        assertEquals(50_000L, DebugLogRetention.MAX_ENTRIES)
        val recent = entry(60)
        val stale = entry(DebugLogRetention.window.seconds + 3600)
        store.saveDebugLogEntries(SnapshotList.of(recent, stale))
        assertEquals(2L, store.countDebugLogEntries())

        store.pruneDebugLogEntries(
            olderThan = now.minus(DebugLogRetention.window),
            keepCount = DebugLogRetention.MAX_ENTRIES,
        )

        val remaining = store.fetchDebugLogEntries(since = Instant.MIN, limit = 10)
        assertEquals(listOf(recent.id), remaining.map { it.id })
    }

    @OriginalCase("DebugLogRetentionPruneTests::prune enforces the ceiling when the window alone exceeds it()")
    @Test fun originalTenInsideWindowMinuteSpacedRowsRetainExactlyTheNewestFourActualIds() = runTest {
        val entries = (0 until 10).map { entry(it * 60L) }
        store.saveDebugLogEntries(entries.snapshot())
        assertEquals(10L, store.countDebugLogEntries())

        store.pruneDebugLogEntries(
            olderThan = now.minus(DebugLogRetention.window),
            keepCount = 4,
        )

        val remaining = store.fetchDebugLogEntries(since = Instant.MIN, limit = 20)
        assertEquals(4, remaining.size)
        assertEquals(entries.take(4).map { it.id }.toSet(), remaining.map { it.id }.toSet())
    }
}
