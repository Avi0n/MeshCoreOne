package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runThrowableResult

/**
 * Enforced cooldown between automatic stale-node cleanups. The Swift doc comment says "6-hour" but the
 * code enforces three hours (`3 * 3600` seconds); this ports the enforced value, not the comment.
 */
const val STALE_CLEANUP_COOLDOWN_SECONDS: Long = 3L * 3600L

/** Seconds in a day, for the stale-age cutoff. */
private const val SECONDS_PER_DAY: Long = 86_400L

/** iOS shows last-run only when cleanup is on and a stamp exists. */
fun shouldShowStaleCleanupLastRun(thresholdDays: Int, lastRunSeconds: Long): Boolean =
    thresholdDays > 0 && lastRunSeconds > 0

/** The count of removed versus total stale nodes from one cleanup pass. Mirrors `RemoveUnfavoritedResult`. */
data class StaleNodeRemovalResult(val removed: Int, val total: Int)

/**
 * Removes stale non-favorite nodes from the radio. The favorites exemption and age cutoff live here, not
 * in the scheduler. Mirrors `ConnectionManager.removeStaleNodes`; the production impl for a connected
 * radio is [ContactStaleNodeRemover].
 */
fun interface StaleNodeRemoving {
    /** Removes non-favorite contacts not heard from in [olderThanDays] days; returns removed-of-total. */
    suspend fun removeStaleNodes(olderThanDays: Int): StaleNodeRemovalResult
}

/**
 * The persisted stale-cleanup settings the scheduler reads and stamps. The threshold and last-run clock
 * are DataStore-backed in production (`autoDeleteStaleNodesDays`, `lastStaleCleanupDate`); a narrow seam
 * so the scheduler is unit-testable. Times are epoch seconds, matching the scheduler's `nowSeconds` clock.
 */
interface StaleNodeCleanupPreferences {
    /** The configured age threshold in days; `0` disables cleanup. */
    suspend fun thresholdDays(): Int

    /** The last successful cleanup time in epoch seconds; `0` means never run. */
    suspend fun lastRunSeconds(): Long

    /** Records the last successful cleanup time in epoch seconds. */
    suspend fun setLastRunSeconds(seconds: Long)
}

/** The result of a scheduled cleanup attempt. */
sealed interface StaleNodeCleanupOutcome {
    /** The threshold is unset (`0`); cleanup is disabled and nothing ran. */
    data object Disabled : StaleNodeCleanupOutcome

    /** The cooldown had not elapsed and [force] was false; nothing ran. */
    data object CooldownActive : StaleNodeCleanupOutcome

    /** Cleanup ran; [result] carries the removed-of-total count. */
    data class Ran(val result: StaleNodeRemovalResult) : StaleNodeCleanupOutcome

    /** Cleanup ran but the removal failed; the last-run time is not stamped so the next attempt retries. */
    data class Failed(val error: Throwable) : StaleNodeCleanupOutcome
}

/**
 * Schedules automatic stale-node cleanup: threshold-gated, cooldown-throttled, and favorite-exempt (the
 * exemption is enforced by the [remover]). Kotlin port of the Swift `AppState.performStaleNodeCleanup`.
 * Triggered on connect (throttled) and, with [force] set, immediately when the user changes the threshold.
 *
 * The last-run time is stamped only after a successful removal, so a failed pass retries on the next
 * trigger rather than being silenced by the cooldown.
 */
class StaleNodeCleanupScheduler(
    private val preferences: StaleNodeCleanupPreferences,
    private val remover: StaleNodeRemoving,
    private val nowSeconds: () -> Long,
) {
    private val mutex = Mutex()

    /** Runs cleanup if enabled and (unless [force]) the cooldown has elapsed. */
    suspend fun performCleanup(force: Boolean = false): StaleNodeCleanupOutcome = mutex.withLock {
        val threshold = preferences.thresholdDays()
        cleanupBlock(threshold, force) ?: runRemoval(threshold)
    }

    private suspend fun cleanupBlock(threshold: Int, force: Boolean): StaleNodeCleanupOutcome? = when {
        threshold <= 0 -> StaleNodeCleanupOutcome.Disabled
        cooldownBlocks(force) -> StaleNodeCleanupOutcome.CooldownActive
        else -> null
    }

    private suspend fun cooldownBlocks(force: Boolean): Boolean {
        if (force) return false
        val lastRun = preferences.lastRunSeconds()
        return lastRun > 0 && nowSeconds() - lastRun < STALE_CLEANUP_COOLDOWN_SECONDS
    }

    /**
     * Runs one cleanup pass. A thrown failure is [StaleNodeCleanupOutcome.Failed] and is not stamped.
     * Cancellation still stops the cleanup.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runRemoval(threshold: Int): StaleNodeCleanupOutcome = try {
        val result = remover.removeStaleNodes(threshold)
        preferences.setLastRunSeconds(nowSeconds())
        StaleNodeCleanupOutcome.Ran(result)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        StaleNodeCleanupOutcome.Failed(error)
    }
}

/**
 * The production [StaleNodeRemoving] for a connected radio: removes non-favorite contacts whose
 * recency (`max(lastModified, lastHeardTimestamp)`) is older than the cutoff, each via the real
 * radio-backed [ContactService.removeContact]. A contact the radio already forgot
 * ([ContactServiceError.ContactNotFound]) is cleaned up locally; any other removal failure stops the
 * pass and returns the partial count, matching the Swift `ConnectionManager.removeContacts`.
 */
class ContactStaleNodeRemover(
    private val radioID: Uuid,
    private val contactStore: ContactStore,
    private val contactService: ContactService,
    private val nowSeconds: () -> Long,
) : StaleNodeRemoving {
    override suspend fun removeStaleNodes(olderThanDays: Int): StaleNodeRemovalResult {
        val cutoff = nowSeconds() - olderThanDays.toLong() * SECONDS_PER_DAY
        val targets = contactStore.fetchContacts(radioID).filter { it.matchesStaleNodePrune(cutoff) }
        return removeTargets(targets)
    }

    /**
     * Removes every non-favorite contact for the radio (Danger Zone "Remove Non-Favorite Nodes").
     * Age is ignored — favorites stay; everything else is deleted with its messages.
     */
    suspend fun removeAllNonFavorites(): StaleNodeRemovalResult {
        val targets = contactStore.fetchContacts(radioID).filter { !it.isFavorite }
        return removeTargets(targets)
    }

    /** Count of non-favorite contacts that would be removed by [removeAllNonFavorites]. */
    suspend fun unfavoritedCount(): Int = contactStore.fetchContacts(radioID).count { !it.isFavorite }

    private suspend fun removeTargets(targets: List<ContactDto>): StaleNodeRemovalResult {
        val eligible = contactService.excludingProtectedVContact(radioID, targets)
        if (eligible.isEmpty()) return StaleNodeRemovalResult(removed = 0, total = 0)
        return removeEligible(eligible)
    }

    private suspend fun removeEligible(eligible: List<ContactDto>): StaleNodeRemovalResult {
        var removed = 0
        for (target in eligible) {
            when (deleteStaleContact(target)) {
                StaleDelete.Removed -> removed++
                StaleDelete.Skipped -> Unit
                StaleDelete.Stopped -> return StaleNodeRemovalResult(removed = removed, total = eligible.size)
            }
        }
        return StaleNodeRemovalResult(removed = removed, total = eligible.size)
    }

    private suspend fun deleteStaleContact(target: ContactDto): StaleDelete = try {
        contactService.removeContact(radioID = radioID, publicKey = target.publicKey)
        StaleDelete.Removed
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: ContactServiceError.ContactNotFound) {
        if (removeLocally(target)) StaleDelete.Removed else StaleDelete.Skipped
    } catch (_: Throwable) {
        StaleDelete.Stopped
    }

    /**
     * Cleans up a contact the radio already forgot. A local failure lets the pass continue.
     * Cancellation still stops the pass.
     */
    private suspend fun removeLocally(target: ContactDto): Boolean = runThrowableResult({ false }) {
        contactService.removeLocalContact(contactID = target.id, publicKey = target.publicKey)
        true
    }

    private sealed interface StaleDelete {
        data object Removed : StaleDelete
        data object Skipped : StaleDelete
        data object Stopped : StaleDelete
    }
}

/** Prefs-backed [StaleNodeCleanupPreferences] over [PreferencesStore] keys. */
class PrefsStaleNodeCleanupPreferences(private val preferences: one.meshcore.shared.prefs.PreferencesStore) :
    StaleNodeCleanupPreferences {
    override suspend fun thresholdDays(): Int =
        preferences.getInt(one.meshcore.shared.prefs.PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS) ?: 0

    override suspend fun lastRunSeconds(): Long {
        val stored = preferences.getDouble(one.meshcore.shared.prefs.PersistenceKeys.LAST_STALE_CLEANUP_DATE) ?: 0.0
        return stored.toLong()
    }

    override suspend fun setLastRunSeconds(seconds: Long) {
        preferences.putDouble(
            one.meshcore.shared.prefs.PersistenceKeys.LAST_STALE_CLEANUP_DATE,
            seconds.toDouble(),
        )
    }

    suspend fun setThresholdDays(days: Int) {
        preferences.putInt(one.meshcore.shared.prefs.PersistenceKeys.AUTO_DELETE_STALE_NODES_DAYS, days)
    }
}
