package one.meshcore.shared.contact

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdvertContactDeltaTest {
    @Test
    fun busyWhenFullSyncHoldsTheClaim() = runTest {
        val claim = AdvertContactSyncClaim()
        claim.setFullSyncActive(true)
        var synced = false
        val outcome = delta(
            claim = claim,
            sync = {
                synced = true
                20L
            },
        )
        assertEquals(AdvertContactSyncOutcome.BUSY, outcome)
        assertFalse(synced)
    }

    @Test
    fun busyWhenManualRefreshHoldsTheClaim() = runTest {
        val claim = AdvertContactSyncClaim()
        claim.setManualSyncActive(true)
        var synced = false
        val outcome = delta(
            claim = claim,
            sync = {
                synced = true
                20L
            },
        )
        assertEquals(AdvertContactSyncOutcome.BUSY, outcome)
        assertFalse(synced)
    }

    @Test
    fun advertWaitTimeoutIsTwoHundredSeconds() {
        assertEquals(200_000L, AdvertContactSyncClaim.ADVERT_CLAIM_WAIT_TIMEOUT_MS)
    }

    @Test
    fun waitForAdvertIdleReturnsAfterTheRoundEnds() = runTest {
        val claim = AdvertContactSyncClaim()
        assertTrue(claim.tryBeginAdvert())
        launch {
            delay(20)
            claim.endAdvert()
        }
        assertTrue(claim.waitForAdvertIdle(timeoutMs = 1_000))
    }

    @Test
    fun waitForAdvertIdleTimesOutWhileTheRoundIsHeld() = runTest {
        val claim = AdvertContactSyncClaim()
        assertTrue(claim.tryBeginAdvert())
        assertFalse(claim.waitForAdvertIdle(timeoutMs = 20))
        claim.endAdvert()
    }

    @Test
    fun claimManualContactSyncIsAtomicWithTheWait() = runTest {
        val claim = AdvertContactSyncClaim()
        assertTrue(claim.claimManualContactSync(timeoutMs = 20))
        assertFalse(claim.tryBeginAdvert())
        claim.setManualSyncActive(false)
        assertTrue(claim.tryBeginAdvert())
        claim.endAdvert()
    }

    @Test
    fun claimManualContactSyncTimesOutWhileAdvertHolds() = runTest {
        val claim = AdvertContactSyncClaim()
        assertTrue(claim.tryBeginAdvert())
        assertFalse(claim.claimManualContactSync(timeoutMs = 20))
        claim.endAdvert()
        assertTrue(claim.tryBeginAdvert())
        claim.endAdvert()
    }

    @Test
    fun notReadyWhenNoWatermarkAndNoCompletedFullSync() = runTest {
        val claim = AdvertContactSyncClaim()
        var synced = false
        val outcome = delta(
            claim = claim,
            watermark = 0L,
            sync = {
                synced = true
                20L
            },
        )
        assertEquals(AdvertContactSyncOutcome.NOT_READY, outcome)
        assertFalse(synced)
        assertTrue(claim.tryBeginAdvert())
        claim.endAdvert()
    }

    @Test
    fun fullRefetchWithoutCompletedFullSyncIsNotReady() = runTest {
        val claim = AdvertContactSyncClaim()
        var synced = false
        val outcome = delta(
            claim = claim,
            watermark = 0L,
            fullRefetch = true,
            sync = {
                synced = true
                7L
            },
        )
        assertEquals(AdvertContactSyncOutcome.NOT_READY, outcome)
        assertFalse(synced)
    }

    @Test
    fun emptyTableAfterCompletedFullSyncUsesPruneFreeSince() = runTest {
        val claim = AdvertContactSyncClaim()
        claim.markFullContactSyncCompleted()
        var sinceUsed: Long? = null
        val outcome = delta(
            claim = claim,
            watermark = 0L,
            sync = { since ->
                sinceUsed = since
                0L
            },
        )
        assertEquals(AdvertContactSyncOutcome.SYNCED, outcome)
        assertEquals(0L, sinceUsed)
    }

    @Test
    fun syncedPersistsWatermark() = runTest {
        val claim = AdvertContactSyncClaim()
        var sinceUsed: Long? = null
        var persisted: Long? = null
        val outcome = delta(
            claim = claim,
            watermark = 10L,
            sync = { since ->
                sinceUsed = since
                42L
            },
            persist = { persisted = it },
        )
        assertEquals(AdvertContactSyncOutcome.SYNCED, outcome)
        assertEquals(9L, sinceUsed)
        assertEquals(42L, persisted)
        assertTrue(claim.tryBeginAdvert())
        claim.endAdvert()
    }

    @Test
    fun fullRefetchAfterCompletedFullSyncUsesPruneFreeSince() = runTest {
        val claim = AdvertContactSyncClaim()
        claim.markFullContactSyncCompleted()
        var sinceUsed: Long? = null
        val outcome = delta(
            claim = claim,
            watermark = 10L,
            fullRefetch = true,
            sync = { since ->
                sinceUsed = since
                7L
            },
        )
        assertEquals(AdvertContactSyncOutcome.SYNCED, outcome)
        assertEquals(0L, sinceUsed)
    }

    @Test
    fun invalidWatermarkUsesOneShotPruneFreeRecovery() = runTest {
        val claim = AdvertContactSyncClaim()
        val farFuture = NOW + (3L * 24L * 60L * 60L)
        var firstSince: Long? = null
        val first = delta(
            claim = claim,
            watermark = farFuture,
            sync = { since ->
                firstSince = since
                farFuture
            },
        )
        assertEquals(AdvertContactSyncOutcome.SYNCED, first)
        assertEquals(0L, firstSince)

        var secondSince: Long? = null
        val second = delta(
            claim = claim,
            watermark = farFuture,
            sync = { since ->
                secondSince = since
                farFuture
            },
        )
        assertEquals(AdvertContactSyncOutcome.SYNCED, second)
        assertEquals(farFuture - 1L, secondSince)
    }

    @Test
    fun failedDoesNotPersistWatermark() = runTest {
        val claim = AdvertContactSyncClaim()
        var persisted = false
        val outcome = delta(
            claim = claim,
            watermark = 10L,
            sync = { error("radio") },
            persist = { persisted = true },
        )
        assertEquals(AdvertContactSyncOutcome.FAILED, outcome)
        assertFalse(persisted)
        assertTrue(claim.tryBeginAdvert())
        claim.endAdvert()
    }

    private suspend fun delta(
        claim: AdvertContactSyncClaim,
        watermark: Long = 10L,
        fullRefetch: Boolean = false,
        sync: suspend (Long) -> Long,
        persist: suspend (Long) -> Unit = {},
    ): AdvertContactSyncOutcome = runAdvertContactDelta(
        AdvertContactDeltaRun(
            claim = claim,
            watermark = watermark,
            fullRefetch = fullRefetch,
            referenceNowSeconds = NOW,
            sync = sync,
            persistWatermark = persist,
        ),
    )

    private companion object {
        const val NOW = 1_700_000_000L
    }
}
