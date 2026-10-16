package one.meshcore.shared.trace

import kotlinx.coroutines.test.runTest
import one.meshcore.shared.backup.TracePathRunDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.sequentialTestUuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round-trips the saved-path model through the in-memory store and checks the derived run summaries. */
class SavedTracePathStoreTest {
    private val radio = Uuid.normalized("AAAAAAAA-AAAA-AAAA-AAAA-AAAAAAAAAAAA")
    private val otherRadio = Uuid.normalized("BBBBBBBB-BBBB-BBBB-BBBB-BBBBBBBBBBBB")

    private class Fixture {
        var idCounter = 0
        var clock = 0.0
        val store = InMemorySavedTracePathStore(
            newId = { sequentialTestUuid(++idCounter) },
            now = { clock },
        )
    }

    private fun runId(label: String): Uuid {
        val hex = label.encodeToByteArray().toLowercaseHex()
        return Uuid.normalized("00000000-0000-0000-0000-" + hex.padStart(12, '0').takeLast(12))
    }

    private fun run(id: String, date: Double, success: Boolean, rtt: Int, snrs: List<Double> = emptyList()) =
        TracePathRunDto(id = runId(id), date = date, success = success, roundTripMs = rtt, hopsSNR = snrs)

    private suspend fun SavedTracePathStore.newPath(
        name: String,
        bytes: ByteArray,
        hashSize: Int = 1,
        initialRun: TracePathRunDto? = null,
        radioID: Uuid = radio,
    ) = createSavedTracePath(radioID, name, bytes, hashSize, initialRun)

    @Test
    fun createFetchAndListNewestFirst() = runTest {
        val fixture = Fixture()
        fixture.clock = 10.0
        val first = fixture.store.newPath("Tower", byteArrayOf(0x31, 0x4F))
        fixture.clock = 20.0
        val second = fixture.store.newPath("Barn", byteArrayOf(0x11, 0x22))
        fixture.store.newPath("Other", byteArrayOf(0x99.toByte()), radioID = otherRadio)

        assertEquals(first, fixture.store.fetchSavedTracePath(first.id))

        val listed = fixture.store.fetchSavedTracePaths(radio)
        assertEquals(listOf(second.id, first.id), listed.map { it.id }) // newest createdDate first
        assertTrue(listed.none { it.radioID == otherRadio })
    }

    @Test
    fun createWithInitialRunSeedsTheRun() = runTest {
        val fixture = Fixture()
        val seed = run("A", date = 1.0, success = true, rtt = 120, snrs = listOf(4.0))
        val path = fixture.store.newPath("Seeded", byteArrayOf(0x31), hashSize = 2, initialRun = seed)
        assertEquals(1, path.runCount)
        assertEquals(seed, path.runs.single())
        assertEquals(2, path.hashSize)
    }

    @Test
    fun appendRunRecordsReRunHistory() = runTest {
        val fixture = Fixture()
        val path = fixture.store.newPath("Path", byteArrayOf(0x31))
        fixture.store.appendTracePathRun(path.id, run("A", date = 1.0, success = true, rtt = 100))
        fixture.store.appendTracePathRun(path.id, run("B", date = 2.0, success = true, rtt = 300))

        val reloaded = fixture.store.fetchSavedTracePath(path.id)!!
        assertEquals(2, reloaded.runCount)
        assertEquals(200, reloaded.averageRoundTripMs) // (100 + 300) / 2
        assertEquals(2.0, reloaded.lastRunDate)
    }

    @Test
    fun updateNameAndDeleteCascade() = runTest {
        val fixture = Fixture()
        val path = fixture.store.newPath("Old", byteArrayOf(0x31))
        fixture.store.appendTracePathRun(path.id, run("A", date = 1.0, success = true, rtt = 100))

        fixture.store.updateSavedTracePathName(path.id, "New")
        assertEquals("New", fixture.store.fetchSavedTracePath(path.id)!!.name)

        fixture.store.deleteSavedTracePath(path.id)
        assertNull(fixture.store.fetchSavedTracePath(path.id))
        assertTrue(fixture.store.fetchSavedTracePaths(radio).isEmpty())
    }

    @Test
    fun successRateIsFullWithNoRunsAndProportionalOtherwise() = runTest {
        val fixture = Fixture()
        val path = fixture.store.newPath("Path", byteArrayOf(0x31))
        assertEquals(FULL_SUCCESS_RATE, fixture.store.fetchSavedTracePath(path.id)!!.successRate)

        fixture.store.appendTracePathRun(path.id, run("A", date = 1.0, success = true, rtt = 100))
        fixture.store.appendTracePathRun(path.id, run("B", date = 2.0, success = false, rtt = 0))
        assertEquals(50, fixture.store.fetchSavedTracePath(path.id)!!.successRate)
    }

    @Test
    fun recentRttsAreOldestFirstAndCappedToRecentSuccesses() = runTest {
        val fixture = Fixture()
        val path = fixture.store.newPath("Path", byteArrayOf(0x31))
        // 12 successful runs with ascending dates and rtt = date * 10; only the last 10 survive.
        for (i in 1..12) {
            fixture.store.appendTracePathRun(path.id, run("R$i", date = i.toDouble(), success = true, rtt = i * 10))
        }
        fixture.store.appendTracePathRun(path.id, run("F", date = 99.0, success = false, rtt = 0)) // ignored
        val recent = fixture.store.fetchSavedTracePath(path.id)!!.recentRTTs
        // Last 10 successful, oldest-first: runs 3..12 -> rtts 30..120.
        assertEquals((3..12).map { it * 10 }, recent)
    }

    @Test
    fun previousSuccessfulRunPicksSecondMostRecentSuccess() = runTest {
        val fixture = Fixture()
        val path = fixture.store.newPath("Path", byteArrayOf(0x31))
        fixture.store.appendTracePathRun(path.id, run("A", date = 1.0, success = true, rtt = 100))
        fixture.store.appendTracePathRun(path.id, run("B", date = 3.0, success = true, rtt = 300))
        fixture.store.appendTracePathRun(path.id, run("C", date = 2.0, success = true, rtt = 200))
        // Sorted by date desc: B(3), C(2), A(1) -> previous = C.
        assertEquals(200, fixture.store.fetchSavedTracePath(path.id)!!.previousSuccessfulRun!!.roundTripMs)
    }

    @Test
    fun successfulAverageDoesNotOverflowPositiveIntSamples() = runTest {
        val fixture = Fixture()
        val path = fixture.store.newPath("Path", byteArrayOf(0x31))
        fixture.store.appendTracePathRun(path.id, run("A", date = 1.0, success = true, rtt = 1))
        fixture.store.appendTracePathRun(path.id, run("B", date = 2.0, success = false, rtt = 0))
        fixture.store.appendTracePathRun(path.id, run("C", date = 3.0, success = true, rtt = Int.MAX_VALUE))
        assertEquals(1_073_741_824, fixture.store.fetchSavedTracePath(path.id)!!.averageRoundTripMs)
    }

    @Test
    fun bestRoundTripIsLowestSuccessfulRtt() = runTest {
        val fixture = Fixture()
        val path = fixture.store.newPath("Path", byteArrayOf(0x31))
        fixture.store.appendTracePathRun(path.id, run("A", date = 1.0, success = true, rtt = 400))
        fixture.store.appendTracePathRun(path.id, run("B", date = 2.0, success = false, rtt = 0))
        fixture.store.appendTracePathRun(path.id, run("C", date = 3.0, success = true, rtt = 120))
        val loaded = fixture.store.fetchSavedTracePath(path.id)!!
        assertEquals(120, loaded.bestRoundTripMs)
        assertEquals(listOf(400, 120), loaded.successfulRunsOldestFirst.map { it.roundTripMs })
        assertEquals(listOf(120, 0, 400), loaded.runsNewestFirst.map { it.roundTripMs })
    }

    @Test
    fun findMatchingSavedPathMatchesExactBytesNewestRun() = runTest {
        val fixture = Fixture()
        val bytes = byteArrayOf(0x31, 0x4F, 0x31)
        fixture.clock = 5.0
        val older = fixture.store.newPath("Older", bytes)
        fixture.store.appendTracePathRun(older.id, run("A", date = 5.0, success = true, rtt = 100))
        fixture.clock = 6.0
        val newer = fixture.store.newPath("Newer", bytes)
        fixture.store.appendTracePathRun(newer.id, run("B", date = 9.0, success = true, rtt = 100))
        fixture.store.newPath("Different", byteArrayOf(0x00))

        val all = fixture.store.fetchSavedTracePaths(radio)
        val match = findMatchingSavedPath(all, bytes)
        assertEquals(newer.id, match!!.id) // most-recently-run wins
        assertNull(findMatchingSavedPath(all, byteArrayOf(0x77)))
    }

    @Test
    fun dedupeKeyIsRadioPathBytesAndHashSize() = runTest {
        val fixture = Fixture()
        val a = fixture.store.newPath("A", byteArrayOf(0x31, 0x4F), hashSize = 2)
        val b = fixture.store.newPath("B", byteArrayOf(0x31, 0x4F), hashSize = 2)
        val differentSize = fixture.store.newPath("C", byteArrayOf(0x31, 0x4F))
        assertEquals(savedPathDedupeKey(a), savedPathDedupeKey(b)) // same radio + bytes + size
        assertTrue(savedPathDedupeKey(a) != savedPathDedupeKey(differentSize))
    }
}
