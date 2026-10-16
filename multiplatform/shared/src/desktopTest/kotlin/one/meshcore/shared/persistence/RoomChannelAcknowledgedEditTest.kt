package one.meshcore.shared.persistence

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoomChannelAcknowledgedEditTest {
    @Test
    fun setQueuedCancellation() = checkEdit(RoomChannelAcknowledgedCase.SET, queued = true, cancel = true)

    @Test
    fun setAdmittedCancellation() = checkEdit(RoomChannelAcknowledgedCase.SET, queued = false, cancel = true)

    @Test
    fun setLive() = checkEdit(RoomChannelAcknowledgedCase.SET, queued = false, cancel = false)

    @Test
    fun clearPresentQueuedCancellation() =
        checkEdit(RoomChannelAcknowledgedCase.CLEAR_PRESENT, queued = true, cancel = true)

    @Test
    fun clearPresentAdmittedCancellation() =
        checkEdit(RoomChannelAcknowledgedCase.CLEAR_PRESENT, queued = false, cancel = true)

    @Test
    fun clearPresentLive() = checkEdit(RoomChannelAcknowledgedCase.CLEAR_PRESENT, queued = false, cancel = false)

    @Test
    fun clearOrphanQueuedCancellation() =
        checkEdit(RoomChannelAcknowledgedCase.CLEAR_ORPHAN, queued = true, cancel = true)

    @Test
    fun clearOrphanAdmittedCancellation() =
        checkEdit(RoomChannelAcknowledgedCase.CLEAR_ORPHAN, queued = false, cancel = true)

    @Test
    fun clearOrphanLive() = checkEdit(RoomChannelAcknowledgedCase.CLEAR_ORPHAN, queued = false, cancel = false)

    private fun checkEdit(kind: RoomChannelAcknowledgedCase, queued: Boolean, cancel: Boolean) {
        val fixture = RoomChannelAcknowledgedFixture(kind, queued)
        try {
            runBlocking(fixture.dispatcher) {
                withTimeout(RoomChannelAcknowledgedFixture.TIMEOUT_MS) {
                    fixture.seed()
                    fixture.start()
                    if (cancel) fixture.cancelOrigin()
                    val cause = fixture.releaseAndJoin()
                    if (cancel) assertTrue(cause is CancellationException) else assertEquals(null, cause)
                    assertEquals(!queued, fixture.mutationObserved)
                    fixture.assertEffects(cancelled = cancel)
                    assertEquals(
                        fixture.rows.expected(kind.expected),
                        fixture.rows.snapshot(),
                        "LAST: normally acknowledged channel edit was not fully captured",
                    )
                }
            }
        } finally {
            fixture.close()
        }
    }
}
