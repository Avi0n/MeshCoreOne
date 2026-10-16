package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** iOS `SyncCoordinator.timestampToleranceFuture` for phone-clock last-heard stamps. */
private const val TIMESTAMP_TOLERANCE_FUTURE_SECONDS: Long = 5L * 60L

/** Owns contact and Discover recency writes for adverts, paths, and successful pings. */
internal class ContactHeardRecorder(
    private val store: ContactStore,
    private val discoveredNodes: DiscoveredNodeStore?,
    private val logger: PersistentLogger?,
    private val now: () -> Long,
) {
    fun clampedPhoneClockTimestamp(stamp: Long): Long {
        val upperBound = now() + TIMESTAMP_TOLERANCE_FUTURE_SECONDS
        return minOf(stamp, upperBound).coerceAtLeast(0L)
    }

    // A failed ping stamp is logged and the ping still counts. Cancellation still propagates.
    suspend fun markHeard(radioID: Uuid, publicKey: ByteArray) {
        runLoggedContactEffect(
            onFailure = { error ->
                logger?.error("Ping lastHeard stamp failed: ${error.message ?: error}")
            },
            fallback = {},
        ) {
            stampHeardOnAir(radioID, publicKey, clampedPhoneClockTimestamp(now()))
            Unit
        }
    }

    suspend fun stampHeardOnAir(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
        val existed = store.touchContactHeard(radioID, publicKey, atEpochSeconds)
        return if (!existed) false else stampDiscoverFromHeard(radioID, publicKey, atEpochSeconds)
    }

    private suspend fun stampDiscoverFromHeard(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean {
        val nodes = discoveredNodes
        val contact = if (nodes == null) null else store.fetchContact(radioID, publicKey)
        if (nodes != null && contact != null) {
            recordDiscoverHeard(radioID, contact, atEpochSeconds)
        }
        return true
    }

    // A failed Discover stamp is logged and the heard contact stays. Cancellation still propagates.
    private suspend fun recordDiscoverHeard(radioID: Uuid, contact: ContactDto, atEpochSeconds: Long) {
        val nodes = discoveredNodes ?: return
        runLoggedContactEffect(
            onFailure = { error ->
                logger?.error("Discover lastHeard stamp failed: ${error.message ?: error}")
            },
            fallback = {},
        ) {
            upsertDiscoveredFromLocalContact(radioID, contact, nodes) { atEpochSeconds }
            Unit
        }
    }
}
