package one.meshcore.shared.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import one.meshcore.shared.persistence.RoomContactCascadeCancellationCase.COLLECTION
import one.meshcore.shared.persistence.RoomContactCascadeCancellationCase.MESSAGES
import one.meshcore.shared.persistence.RoomContactCascadeCancellationCase.SINGLE
import one.meshcore.shared.persistence.RoomContactCascadeCancellationCase.UNREFERENCED
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RoomContactCascadeCancellationOwnershipTest {
    @Test
    fun queuedSingleDeleteRetainsRowsAfterOriginCancellation() = canceled(SINGLE, queued = true)

    @Test
    fun queuedCollectionDeleteRetainsRowsAfterOriginCancellation() = canceled(COLLECTION, queued = true)

    @Test
    fun queuedUnreferencedDeleteRetainsRowsAfterOriginCancellation() = canceled(UNREFERENCED, queued = true)

    @Test
    fun queuedMessageDeleteRetainsRowsAfterOriginCancellation() = canceled(MESSAGES, queued = true)

    @Test
    fun admittedSingleDeleteRollsBackAfterOriginCancellation() = canceled(SINGLE, queued = false)

    @Test
    fun admittedCollectionDeleteRollsBackAfterOriginCancellation() = canceled(COLLECTION, queued = false)

    @Test
    fun admittedUnreferencedDeleteRollsBackAfterOriginCancellation() = canceled(UNREFERENCED, queued = false)

    @Test
    fun admittedMessageDeleteRollsBackAfterOriginCancellation() = canceled(MESSAGES, queued = false)

    @Test
    fun liveSingleDeleteCommitsItsCascade() = live(SINGLE)

    @Test
    fun liveCollectionDeleteCommitsItsCascade() = live(COLLECTION)

    @Test
    fun liveUnreferencedDeleteCommitsItsCascade() = live(UNREFERENCED)

    @Test
    fun liveMessageDeleteCommitsItsCascadeAndRetainsContact() = live(MESSAGES)

    private fun canceled(kind: RoomContactCascadeCancellationCase, queued: Boolean) = exercise(kind, queued) {
        seed()
        val before = rows.snapshot()
        val unaffected = rows.survivors()
        start()
        cancelOrigin()
        assertTrue(releaseAndJoin() is CancellationException)
        assertEquals(unaffected, rows.survivors())
        assertEquals(before, rows.snapshot(), "LAST: canceled $kind queued=$queued cascade committed rows")
    }

    private fun live(kind: RoomContactCascadeCancellationCase) = exercise(kind, queued = false) {
        seed()
        val before = rows.snapshot()
        val unaffected = rows.survivors()
        start()
        assertNull(releaseAndJoin())
        assertEquals(unaffected, rows.survivors())
        assertLivePolicy(before)
    }

    private fun exercise(
        kind: RoomContactCascadeCancellationCase,
        queued: Boolean,
        block: suspend RoomContactCascadeCancellationFixture.() -> Unit,
    ) {
        val fixture = RoomContactCascadeCancellationFixture(kind, queued)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomContactCascadeCancellationFixture.TIMEOUT_MS) { fixture.block() }
            }
        } finally {
            fixture.close()
        }
    }
}
