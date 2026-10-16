package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Mirrors the iOS `AppState.performStaleNodeCleanup` scheduler (threshold gate, 3-hour cooldown, force
 * override, no last-run stamp on failure) and the `ConnectionManager.removeStaleNodes` removal semantics
 * (non-favorite and stale only, radio-backed delete with a local fallback, stop-and-partial on error).
 */
class StaleNodeCleanupTest {
    // MARK: Scheduler

    @Test
    fun cleanupIsDisabledWhenThresholdUnset() = runTest {
        val remover = FakeStaleNodeRemover()
        val scheduler = StaleNodeCleanupScheduler(
            preferences = FakeStaleNodeCleanupPreferences(thresholdDays = 0),
            remover = remover,
            nowSeconds = { 1_000L },
        )

        assertIs<StaleNodeCleanupOutcome.Disabled>(scheduler.performCleanup())
        assertTrue(remover.requestedDays.isEmpty())
    }

    @Test
    fun cleanupIsSkippedWhileCooldownActive() = runTest {
        val now = 1_000_000L
        val remover = FakeStaleNodeRemover()
        val prefs = FakeStaleNodeCleanupPreferences(
            thresholdDays = 7,
            lastRunSeconds = now - (STALE_CLEANUP_COOLDOWN_SECONDS - 1),
        )
        val scheduler = StaleNodeCleanupScheduler(prefs, remover, nowSeconds = { now })

        assertIs<StaleNodeCleanupOutcome.CooldownActive>(scheduler.performCleanup())
        assertTrue(remover.requestedDays.isEmpty())
    }

    @Test
    fun cleanupRunsWhenCooldownHasElapsed() = runTest {
        val now = 1_000_000L
        val remover = FakeStaleNodeRemover(StaleNodeRemovalResult(removed = 2, total = 3))
        val prefs = FakeStaleNodeCleanupPreferences(
            thresholdDays = 7,
            lastRunSeconds = now - STALE_CLEANUP_COOLDOWN_SECONDS,
        )
        val scheduler = StaleNodeCleanupScheduler(prefs, remover, nowSeconds = { now })

        val outcome = scheduler.performCleanup()

        val ran = assertIs<StaleNodeCleanupOutcome.Ran>(outcome)
        assertEquals(StaleNodeRemovalResult(removed = 2, total = 3), ran.result)
        assertEquals(listOf(7), remover.requestedDays)
        assertEquals(now, prefs.lastRunSeconds())
    }

    @Test
    fun cleanupRunsWhenNeverRunBefore() = runTest {
        val remover = FakeStaleNodeRemover()
        val prefs = FakeStaleNodeCleanupPreferences(thresholdDays = 30, lastRunSeconds = 0L)
        val scheduler = StaleNodeCleanupScheduler(prefs, remover, nowSeconds = { 500L })

        assertIs<StaleNodeCleanupOutcome.Ran>(scheduler.performCleanup())
        assertEquals(listOf(30), remover.requestedDays)
    }

    @Test
    fun overlappingAutomaticCleanupsShareTheUpdatedCooldown() = runTest {
        val removalStarted = CompletableDeferred<Unit>()
        val releaseRemoval = CompletableDeferred<Unit>()
        var removalCount = 0
        val prefs = FakeStaleNodeCleanupPreferences(thresholdDays = 7, lastRunSeconds = 0L)
        val scheduler = StaleNodeCleanupScheduler(
            preferences = prefs,
            remover = StaleNodeRemoving {
                removalCount += 1
                removalStarted.complete(Unit)
                releaseRemoval.await()
                StaleNodeRemovalResult(removed = 1, total = 1)
            },
            nowSeconds = { 500L },
        )

        val first = async { scheduler.performCleanup() }
        removalStarted.await()
        val second = async { scheduler.performCleanup() }
        yield()
        assertEquals(1, removalCount)

        releaseRemoval.complete(Unit)
        assertIs<StaleNodeCleanupOutcome.Ran>(first.await())
        assertIs<StaleNodeCleanupOutcome.CooldownActive>(second.await())
        assertEquals(1, removalCount)
    }

    @Test
    fun forceSkipsTheCooldown() = runTest {
        val now = 1_000_000L
        val remover = FakeStaleNodeRemover()
        val prefs = FakeStaleNodeCleanupPreferences(thresholdDays = 7, lastRunSeconds = now - 1)
        val scheduler = StaleNodeCleanupScheduler(prefs, remover, nowSeconds = { now })

        assertIs<StaleNodeCleanupOutcome.Ran>(scheduler.performCleanup(force = true))
        assertEquals(listOf(7), remover.requestedDays)
    }

    @Test
    fun failedRemovalDoesNotStampLastRun() = runTest {
        val remover = FakeStaleNodeRemover(error = IllegalStateException("radio dropped"))
        val prefs = FakeStaleNodeCleanupPreferences(thresholdDays = 7, lastRunSeconds = 0L)
        val scheduler = StaleNodeCleanupScheduler(prefs, remover, nowSeconds = { 5_000L })

        assertIs<StaleNodeCleanupOutcome.Failed>(scheduler.performCleanup())
        assertEquals(0L, prefs.lastRunSeconds())
    }

    @Test
    fun aCancelledRemovalPropagatesAndDoesNotStamp() = runTest {
        val cancelled = CancellationException("stopped")
        val remover = FakeStaleNodeRemover(error = cancelled)
        val prefs = FakeStaleNodeCleanupPreferences(thresholdDays = 7, lastRunSeconds = 0L)
        val scheduler = StaleNodeCleanupScheduler(prefs, remover, nowSeconds = { 5_000L })

        assertSame(cancelled, assertFailsWith<CancellationException> { scheduler.performCleanup() })
        assertEquals(0L, prefs.lastRunSeconds())
    }

    @Test
    fun anErrorRemovalIsFailedAndDoesNotStamp() = runTest {
        val probe = StaleCleanupProbeError()
        val remover = FakeStaleNodeRemover(error = probe)
        val prefs = FakeStaleNodeCleanupPreferences(thresholdDays = 7, lastRunSeconds = 0L)
        val scheduler = StaleNodeCleanupScheduler(prefs, remover, nowSeconds = { 5_000L })

        val failed = assertIs<StaleNodeCleanupOutcome.Failed>(scheduler.performCleanup())
        assertSame(probe, failed.error)
        assertEquals(0L, prefs.lastRunSeconds())
    }

    @Test
    fun lastRunLineShowsOnlyWhenCleanupIsOnAndAStampExists() {
        assertFalse(shouldShowStaleCleanupLastRun(thresholdDays = 0, lastRunSeconds = 1_000L))
        assertFalse(shouldShowStaleCleanupLastRun(thresholdDays = 7, lastRunSeconds = 0L))
        assertTrue(shouldShowStaleCleanupLastRun(thresholdDays = 7, lastRunSeconds = 1_000L))
    }

    // MARK: Remover (favorite exemption + age cutoff)

    private val cleanupNow = 1_000_000_000L

    private fun stale(keyByte: Int, name: String, isFavorite: Boolean): ContactDto = contactDto(
        id = ContactTestIds.fresh(),
        radioID = ContactTestIds.RADIO,
        keyByte = keyByte,
        name = name,
        isFavorite = isFavorite,
    ).copy(lastModified = cleanupNow - 700_000L) // older than a 7-day cutoff

    private fun fresh(keyByte: Int, name: String): ContactDto = contactDto(
        id = ContactTestIds.fresh(),
        radioID = ContactTestIds.RADIO,
        keyByte = keyByte,
        name = name,
    ).copy(lastModified = cleanupNow - 100L) // heard recently

    @Test
    fun removerDeletesStaleNonFavoritesAndExemptsFavoritesAndFreshNodes() = runTest {
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val service = testContactService(session = session, store = store)
        val staleNode = stale(0x01, "Stale", isFavorite = false)
        val staleFavorite = stale(0x02, "Stale Favorite", isFavorite = true)
        val freshNode = fresh(0x03, "Fresh")
        store.saveContact(staleNode)
        store.saveContact(staleFavorite)
        store.saveContact(freshNode)
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        val result = remover.removeStaleNodes(olderThanDays = 7)

        assertEquals(StaleNodeRemovalResult(removed = 1, total = 1), result)
        assertEquals(1, session.removedPublicKeys.size)
        assertTrue(session.removedPublicKeys[0].contentEquals(staleNode.publicKey))
        val remaining = store.fetchContacts(ContactTestIds.RADIO).map { it.name }.toSet()
        assertEquals(setOf("Stale Favorite", "Fresh"), remaining)
    }

    @Test
    fun removerExemptsStaleLastModifiedWhenLastHeardIsFresh() = runTest {
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val service = testContactService(session = session, store = store)
        val heardFresh = stale(0x04, "HeardFresh", isFavorite = false)
            .copy(lastHeardTimestamp = cleanupNow)
        store.saveContact(heardFresh)
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        val result = remover.removeStaleNodes(olderThanDays = 7)

        assertEquals(StaleNodeRemovalResult(removed = 0, total = 0), result)
        assertEquals("HeardFresh", store.fetchContacts(ContactTestIds.RADIO).single().name)
    }

    @Test
    fun removerIsANoOpWhenNoStaleNodesMatch() = runTest {
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val service = testContactService(session = session, store = store)
        store.saveContact(fresh(0x03, "Fresh"))
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        val result = remover.removeStaleNodes(olderThanDays = 7)

        assertEquals(StaleNodeRemovalResult(removed = 0, total = 0), result)
        assertTrue(session.removedPublicKeys.isEmpty())
    }

    @Test
    fun removerCleansUpLocallyWhenTheRadioAlreadyForgotTheContact() = runTest {
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val store = InMemoryContactStore()
        val service = testContactService(session = session, store = store)
        val staleNode = stale(0x01, "Ghost", isFavorite = false)
        store.saveContact(staleNode)
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        val result = remover.removeStaleNodes(olderThanDays = 7)

        assertEquals(StaleNodeRemovalResult(removed = 1, total = 1), result)
        assertTrue(store.fetchContacts(ContactTestIds.RADIO).isEmpty())
    }

    @Test
    fun removerStopsAndReturnsPartialOnAnUnexpectedError() = runTest {
        val session = FakeContactSession().apply { removeContactError = 0x01 } // maps to SessionError
        val store = InMemoryContactStore()
        val service = testContactService(session = session, store = store)
        store.saveContact(stale(0x01, "Stale", isFavorite = false))
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        val result = remover.removeStaleNodes(olderThanDays = 7)

        assertEquals(0, result.removed)
        assertEquals(1, result.total)
        assertFalse(store.fetchContacts(ContactTestIds.RADIO).isEmpty()) // the row was not deleted
    }

    @Test
    fun removerExemptsTheVContactEvenWhenStale() = runTest {
        val selfKey = ByteArray(32) { 0x11 }
        val vKey = requireNotNull(VContactIdentity.publicKey(selfKey))
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val service = testContactService(session = session, store = store, selfPublicKey = { selfKey })
        store.saveContact(
            stale(0x01, "Stale", isFavorite = false),
        )
        store.saveContact(
            stale(0x02, "V", isFavorite = false).copy(publicKey = vKey),
        )
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        val result = remover.removeStaleNodes(olderThanDays = 7)

        assertEquals(StaleNodeRemovalResult(removed = 1, total = 1), result)
        assertEquals(1, session.removedPublicKeys.size)
        assertEquals(setOf("V"), store.fetchContacts(ContactTestIds.RADIO).map { it.name }.toSet())
    }

    @Test
    fun removeAllNonFavoritesSkipsTheVContact() = runTest {
        val selfKey = ByteArray(32) { 0x11 }
        val vKey = requireNotNull(VContactIdentity.publicKey(selfKey))
        val session = FakeContactSession()
        val store = InMemoryContactStore()
        val service = testContactService(session = session, store = store, selfPublicKey = { selfKey })
        store.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, keyByte = 0x22, name = "Alice"),
        )
        store.saveContact(
            contactDto(id = ContactTestIds.fresh(), radioID = ContactTestIds.RADIO, name = "V").copy(publicKey = vKey),
        )
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        assertEquals(2, remover.unfavoritedCount())
        val result = remover.removeAllNonFavorites()

        assertEquals(StaleNodeRemovalResult(removed = 1, total = 1), result)
        assertEquals(1, session.removedPublicKeys.size)
        assertEquals("V", store.fetchContacts(ContactTestIds.RADIO).single().name)
        assertEquals(1, remover.unfavoritedCount())
    }

    @Test
    fun removerStopsWhenAStoreErrorEscapesTheRadioDelete() = runTest {
        val backing = InMemoryContactStore()
        backing.saveContact(stale(0x01, "Alice", isFavorite = false))
        backing.saveContact(stale(0x02, "Bob", isFavorite = false))
        var deletes = 0
        val store = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) {
                deletes += 1
                throw StaleCleanupProbeError()
            }
        }
        val session = FakeContactSession()
        val service = testContactService(session = session, store = store)
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        val result = remover.removeStaleNodes(olderThanDays = 7)

        assertEquals(StaleNodeRemovalResult(removed = 0, total = 2), result)
        assertEquals(1, deletes)
        assertEquals(1, session.removedPublicKeys.size)
    }

    @Test
    fun aCancelledRadioRemovePropagates() = runTest {
        val store = InMemoryContactStore()
        store.saveContact(stale(0x01, "Alice", isFavorite = false))
        val cancelled = CancellationException("stopped")
        val session = FakeContactSession().apply { removeContactThrowable = cancelled }
        val service = testContactService(session = session, store = store)
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { remover.removeStaleNodes(olderThanDays = 7) },
        )
    }

    @Test
    fun aLocalCleanupFailureSkipsAndContinues() = runTest {
        val backing = InMemoryContactStore()
        backing.saveContact(stale(0x01, "Alice", isFavorite = false))
        backing.saveContact(stale(0x02, "Bob", isFavorite = false))
        var deletes = 0
        val store = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid) {
                deletes += 1
                if (deletes == 1) error("disk full")
                backing.deleteContact(id)
            }
        }
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val service = testContactService(session = session, store = store)
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        val result = remover.removeStaleNodes(olderThanDays = 7)

        assertEquals(StaleNodeRemovalResult(removed = 1, total = 2), result)
        assertEquals(2, deletes)
        assertEquals(1, store.fetchContacts(ContactTestIds.RADIO).size)
    }

    @Test
    fun aCancelledLocalCleanupPropagates() = runTest {
        val backing = InMemoryContactStore()
        backing.saveContact(stale(0x01, "Alice", isFavorite = false))
        val cancelled = CancellationException("stopped")
        val store = object : ContactStore by backing {
            override suspend fun deleteContact(id: Uuid): Unit = throw cancelled
        }
        val session = FakeContactSession().apply { removeContactError = ContactProtocolErrorCode.NOT_FOUND }
        val service = testContactService(session = session, store = store)
        val remover = ContactStaleNodeRemover(ContactTestIds.RADIO, store, service, nowSeconds = { cleanupNow })

        assertSame(
            cancelled,
            assertFailsWith<CancellationException> { remover.removeStaleNodes(olderThanDays = 7) },
        )
    }
}

private class StaleCleanupProbeError : Error("probe")
