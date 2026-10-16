package one.meshcore.shared.contact

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.startsWithPrefix
import one.meshcore.shared.model.toUppercaseHex

/** Bytes of the public-key prefix iOS interpolates in overwrite-oldest dump lines. */
private const val OVERWRITE_KEY_PREFIX_BYTES: Int = 6

/** iOS `advertSyncDebounce` (5s) before an advert-driven GET_CONTACTS round. */
private const val ADVERT_SYNC_DEBOUNCE_MS: Long = 5_000

/** iOS `advertSyncMinInterval` (30s) after a synced or failed round. */
private const val ADVERT_SYNC_MIN_INTERVAL_SECONDS: Long = 30
private const val MILLIS_PER_SECOND: Long = 1_000

/** iOS `advertSyncBusyBackoff` (5s) after a `.busy` outcome. */
private const val ADVERT_SYNC_BUSY_BACKOFF_MS: Long = 5_000

/** iOS `maxConsecutiveDeltaSyncFailures` before drained advert keys are dropped. */
private const val MAX_CONSECUTIVE_DELTA_SYNC_FAILURES: Int = 5

/**
 * Manages mesh-network contacts: sync from the radio, add/update/remove, path reset/discovery, favorite
 * and telemetry-permission flags, and local preference edits. The Kotlin port of the Swift
 * `ContactService` actor, rebuilt per connection. It owns the advert queues and multicast
 * [eventBroadcaster], while focused collaborators operate on the injected [session] and [store] so the
 * graph is unit-testable against a fake session and [InMemoryContactStore].
 *
 * Three-axis identity invariant: `radioID` is the data-partition key, owned by the `Device` row and
 * supplied by every caller (`device.radioID`). No method here mints a `radioID`; contact rows are keyed
 * by `(radioID, publicKey)`, both frozen after creation ([ContactUpsert]).
 */
class ContactService(
    private val session: ContactSession,
    internal val store: ContactStore,
    private val now: () -> Long,
    collaborators: ContactServiceCollaborators = ContactServiceCollaborators(),
    advert: ContactServiceAdvert = ContactServiceAdvert(),
    edges: ContactServiceEdges = ContactServiceEdges(),
) {
    private val syncCoordinator = collaborators.syncCoordinator
    private val cleanupCoordinator = collaborators.cleanupCoordinator
    private val discoveryNotifier = collaborators.discoveryNotifier
    private val orphanDmAdopter = collaborators.orphanDmAdopter
    private val discoveredNodes = collaborators.discoveredNodes
    private val deltaScope = advert.deltaScope
    private val advertSyncClaim = advert.advertSyncClaim
    private val isInForeground = advert.isInForeground
    private val selfPublicKey = edges.selfPublicKey
    private val logger = edges.logger
    private val discoverTrace = edges.discoverTrace

    internal val contactSyncRetired = CompletableDeferred<Unit>()
    internal val eventBroadcaster = ContactServiceEventBroadcaster()
    internal val contactNodeStorage = ContactNodeStorage(eventBroadcaster, logger)

    /**
     * Latest 0x90 / overwrite-oldest storage-full flag. A StateFlow so a host that binds after
     * [notifyNodeStorageFull] still sees the current value; the event channel has no replay.
     */
    val isNodeStorageFull: StateFlow<Boolean> = contactNodeStorage.isFull

    /**
     * Defers advert-driven [session.getContact] while a bulk contact sync is on the radio (iOS
     * `AdvertisementService.setSyncingContacts` / `pendingAdvertKeys`). Avoids BLE contention
     * between `getContacts` and per-key fetches when 0x80 ads arrive mid-sync.
     */
    private val advertMutex = Mutex()
    internal var isSyncingContacts = false

    /** Nested [setSyncingContacts] holds so inner `syncContacts` cannot release a connect-window deferral. */
    private var syncingContactsHoldCount = 0
    private var deltaSyncHandler: (suspend (Boolean) -> AdvertContactSyncOutcome)? = null
    private var deltaJob: Job? = null
    private var escalateToFullRefetch = false
    private var lastDeltaSyncEndSeconds: Long? = null
    private var consecutiveDeltaSyncFailures = 0
    private var deltaRadioID: Uuid? = null
    private val pendingAdvertKeys = linkedMapOf<String, ByteArray>()

    /**
     * Phone-clock epoch seconds for queued 0x80 keys, applied to `lastHeardTimestamp` once a Contact
     * row exists. Without this, `ContactUpsert.insert` leaves lastHeard null and a stale radio
     * lastModified makes [ContactDto.matchesStaleNodePrune] true right after hear. Mirrors iOS
     * `pendingAdvertReceiveTimes`.
     */
    private val pendingAdvertReceiveTimes = linkedMapOf<String, Long>()
    private val pendingPathUpdateKeys = linkedMapOf<String, ByteArray>()

    /**
     * Public-key hexes whose in-flight or queued 0x80/path fetch must not persist. 0x8F overwrite-oldest
     * adds a key here so a late `getContact` cannot resurrect the row (iOS `inFlightCancelled`).
     */
    private val cancelledAdvertFetchKeys = linkedSetOf<String>()

    /**
     * Keys the radio deleted (0x8F) since this round drained. A GET_CONTACTS commit can re-save
     * them; rollback drops unreferenced rows after the handler returns. Cleared when the next
     * round drains. Mirrors iOS `contactsDeletedDuringSync`.
     */
    private val contactsDeletedDuringSync = linkedMapOf<String, ByteArray>()

    /**
     * Drain-surviving 0x8F queue. Not [contactsDeletedDuringSync] (that is still round-scoped
     * and cleared at drain). Flushed on foreground or teardown. Mirrors iOS `pendingDeletedKeys`.
     */
    private val pendingDeletedKeys = linkedMapOf<String, ByteArray>()

    /**
     * Keys a 0x80 marked at event start so an in-flight [flushPendingDeletedKeys] can skip
     * them. Cleared after `deleteContacts` returns. Do not dequeue [pendingDeletedKeys] on
     * the 0x80 itself: a failed touch must not drop a queued 0x8F.
     */
    private val revivedMutex = Mutex()
    private val revivedDuringDeleteFlush = mutableSetOf<String>()
    private val deletionFlushMutex = Mutex()

    /**
     * Resets a contact's path to flood routing on the device, then overwrites the local row with a
     * flooded frame. A device `NOT_FOUND` becomes [ContactServiceError.ContactNotFound]. Mirrors
     * `resetPath(radioID:publicKey:)`.
     */
    suspend fun resetPath(radioID: Uuid, publicKey: ByteArray) = contactRadioCommands.resetPath(radioID, publicKey)

    /** A device `NOT_FOUND` maps to [ContactServiceError.ContactNotFound]. */
    private fun notFoundError(code: Int): ContactServiceError? =
        if (code == ContactProtocolErrorCode.NOT_FOUND) ContactServiceError.ContactNotFound() else null

    /** A share command maps `TABLE_FULL` to share-unavailable and `NOT_FOUND` to contact-not-found. */
    private fun shareContactError(code: Int): ContactServiceError? = when (code) {
        ContactProtocolErrorCode.TABLE_FULL -> ContactServiceError.ShareContactUnavailable()
        ContactProtocolErrorCode.NOT_FOUND -> ContactServiceError.ContactNotFound()
        else -> null
    }

    /** Wraps any session failure from [block] as [ContactServiceError.SessionError] (no code mapping). */
    private suspend inline fun <T> mapSessionError(block: () -> T): T =
        runContactSession(mapDeviceCode = null, block = block)

    internal val advertDeltaSchedule = AdvertDeltaSchedule()
    internal val advertDeltaRound = AdvertDeltaRound()
    internal val advertDeltaFollowUp = AdvertDeltaFollowUp()
    internal val syncHold = SyncHold()
    internal val contactHeardRecorder = ContactHeardRecorder(
        store = store,
        discoveredNodes = discoveredNodes,
        logger = logger,
        now = now,
    )
    internal val contactIdentityGuard = ContactIdentityGuard(selfPublicKey)
    internal val contactDirectory = ContactDirectory(
        ContactDirectory.Dependencies(
            session = session,
            store = store,
            syncCoordinator = syncCoordinator,
            cleanupCoordinator = cleanupCoordinator,
            identityGuard = contactIdentityGuard,
            nodeStorage = contactNodeStorage,
            logger = logger,
        ),
    )
    internal val overwriteDeletion = OverwriteDeletion()
    internal val contactAdvertMaterializer = ContactAdvertMaterializer(
        ContactAdvertMaterializer.Dependencies(
            session = session,
            store = store,
            heardRecorder = contactHeardRecorder,
            now = now,
            eventBroadcaster = eventBroadcaster,
            discoveryNotifier = discoveryNotifier,
            logger = logger,
            discoverTrace = discoverTrace,
            isCancelled = ::isAdvertFetchCancelledState,
            clearCancellation = ::clearAdvertFetchCancellation,
        ),
    )
    internal val contactPathLoader = ContactPathLoader(
        ContactPathLoader.Dependencies(
            session = session,
            store = store,
            syncCoordinator = syncCoordinator,
            isCancelled = ::isAdvertFetchCancelledState,
            rollbackInsert = contactAdvertMaterializer::rollbackInsertIfNeeded,
        ),
    )
    internal val contactPathRefresh = ContactPathRefresh()
    internal val contactAdvertFetch = ContactAdvertFetch()
    internal val contactPruner = ContactPruner(
        store = store,
        selfPublicKey = selfPublicKey,
        logger = logger,
        cleanupCoordinator = cleanupCoordinator,
    )
    internal val contactBulkSync = ContactBulkSync(
        session = session,
        store = store,
        eventBroadcaster = eventBroadcaster,
        advertSyncClaim = advertSyncClaim,
        pruner = contactPruner,
        setSyncingContacts = { radioID, isSyncing -> syncHold.setSyncingContacts(radioID, isSyncing) },
    )
    internal val contactMessageClearer = ContactMessageClearer(
        store = store,
        cleanupCoordinator = cleanupCoordinator,
        syncCoordinator = syncCoordinator,
        eventBroadcaster = eventBroadcaster,
    )
    internal val contactRadioCommands = ContactRadioCommands(
        ContactRadioCommands.Dependencies(
            session = session,
            store = store,
            now = now,
            syncCoordinator = syncCoordinator,
            mapNotFound = ::notFoundError,
            mapShareError = ::shareContactError,
            addOrUpdateContact = { radioID, frame -> contactDirectory.addOrUpdateContact(radioID, frame) },
        ),
    )
    internal val contactPathResponseHandler = ContactPathResponseHandler(
        ContactPathResponseHandler.Dependencies(
            store = store,
            heardRecorder = contactHeardRecorder,
            now = now,
            syncCoordinator = syncCoordinator,
            logger = logger,
            eventBroadcaster = eventBroadcaster,
        ),
        writeAcceptedPath = { radioID, publicKey, path, pathLength ->
            contactRadioCommands.setPath(radioID, publicKey, path, pathLength)
        },
    )
    internal val contactPreferences = ContactPreferences(
        session = session,
        store = store,
        syncCoordinator = syncCoordinator,
        cleanupCoordinator = cleanupCoordinator,
    )

    internal inner class AdvertDeltaSchedule {
        fun setDeltaSyncHandler(handler: (suspend (Boolean) -> AdvertContactSyncOutcome)?) {
            deltaSyncHandler = handler
            if (handler != null) {
                scheduleDeltaSync()
            }
        }

        suspend fun hasPendingAdvertKey(publicKey: ByteArray): Boolean = advertMutex.withLock {
            pendingAdvertKeys.containsKey(publicKey.toUppercaseHex())
        }

        suspend fun hasPendingPathKey(publicKey: ByteArray): Boolean = advertMutex.withLock {
            pendingPathUpdateKeys.containsKey(publicKey.toUppercaseHex())
        }

        suspend fun hasPendingDeletedKey(publicKey: ByteArray): Boolean = advertMutex.withLock {
            pendingDeletedKeys.containsKey(publicKey.toUppercaseHex())
        }

        suspend fun handleReturnToForeground() {
            flushPendingDeletedKeys()
            if (hasPendingDeltaSyncWork()) {
                scheduleDeltaSync()
            }
        }

        suspend fun stopAdvertDeltaSync() {
            deltaSyncHandler = null
            deltaJob?.cancel()
            deltaJob = null
            flushPendingDeletedKeys()
            val receiveTimes: Map<String, Long>
            val advertKeys: List<ByteArray>
            val radioID: Uuid?
            advertMutex.withLock {
                receiveTimes = pendingAdvertReceiveTimes.toMap()
                advertKeys = pendingAdvertKeys.values.map { it.copyOf() }
                radioID = deltaRadioID
                pendingAdvertKeys.clear()
                pendingPathUpdateKeys.clear()
                pendingAdvertReceiveTimes.clear()
                pendingDeletedKeys.clear()
                cancelledAdvertFetchKeys.clear()
                escalateToFullRefetch = false
            }
            stampAdvertReceiveTimes(advertKeys, receiveTimes, radioID)
        }

        @Suppress("TooGenericExceptionCaught")
        suspend fun flushPendingDeletedKeys() {
            deletionFlushMutex.withLock {
                val keys: List<ByteArray>
                val radioID: Uuid?
                advertMutex.withLock {
                    keys = pendingDeletedKeys.values.map { it.copyOf() }
                    pendingDeletedKeys.clear()
                    radioID = deltaRadioID
                }
                if (keys.isEmpty() || radioID == null) return@withLock
                try {
                    val snapshots = keys.mapNotNull { store.fetchContact(radioID, it) }
                    val deletedIDs = try {
                        store.deleteContacts(
                            radioID = radioID,
                            publicKeys = keys,
                            skippingPublicKeys = { revivedMutex.withLock { revivedDuringDeleteFlush.toSet() } },
                        )
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (error: Throwable) {
                        advertMutex.withLock {
                            for (key in keys) {
                                val hex = key.toUppercaseHex()
                                if (pendingAdvertKeys.containsKey(hex)) continue
                                pendingDeletedKeys.putIfAbsent(hex, key)
                            }
                        }
                        logger?.error(
                            "Overwrite oldest: deferred delete flush failed: ${error.message ?: error}",
                        )
                        return@withLock
                    }
                    if (deletedIDs.isEmpty()) return@withLock
                    logger?.notice("Overwrite oldest: applying ${deletedIDs.size} deferred deletion(s)")
                    val deleted = snapshots.filter { it.id in deletedIDs }
                    for (contact in deleted) {
                        cleanupCoordinator.handleCleanup(contact.id, ContactCleanupReason.DELETED, contact.publicKey)
                    }
                    publishNodeDeleted()
                    publishNodeStorageFull(isFull = false)
                    syncCoordinator.notifyContactsChanged()
                } finally {
                    revivedMutex.withLock { revivedDuringDeleteFlush.clear() }
                }
            }
        }

        suspend fun snapshotPathLastModified(keys: List<ByteArray>, radioID: Uuid?): Map<String, Long> {
            if (radioID == null || keys.isEmpty()) return emptyMap()
            val snapshot = linkedMapOf<String, Long>()
            for (publicKey in keys) {
                val contact = store.fetchContact(radioID, publicKey) ?: continue
                snapshot[publicKey.toUppercaseHex()] = contact.lastModified
            }
            return snapshot
        }

        suspend fun escalateUndeliveredPathUpdates(
            drainedPath: List<ByteArray>,
            preRoundLastModified: Map<String, Long>,
            radioID: Uuid?,
        ) {
            if (radioID == null || drainedPath.isEmpty()) return
            var undelivered = 0
            for (publicKey in drainedPath) {
                val hex = publicKey.toUppercaseHex()
                val contact = store.fetchContact(radioID, publicKey)
                if (contact == null) {
                    undelivered += 1
                    continue
                }
                val before = preRoundLastModified[hex]
                if (before != null && contact.lastModified <= before) {
                    undelivered += 1
                }
            }
            if (undelivered > 0) {
                logger?.info(
                    "Advert path update escalation: $undelivered path key(s) not refreshed by incremental fetch",
                )
                advertMutex.withLock { escalateToFullRefetch = true }
            }
        }

        fun scheduleDeltaSync(busy: Boolean = false) {
            val scope = deltaScope ?: return
            if (deltaSyncHandler == null) return
            if (deltaJob?.isActive == true) return
            val debounceMs = if (busy) ADVERT_SYNC_BUSY_BACKOFF_MS else ADVERT_SYNC_DEBOUNCE_MS
            val minWaitMs = if (busy) {
                0L
            } else {
                lastDeltaSyncEndSeconds?.let { lastEnd ->
                    val elapsedMs = (now() - lastEnd).coerceAtLeast(0L) * MILLIS_PER_SECOND
                    (ADVERT_SYNC_MIN_INTERVAL_SECONDS * MILLIS_PER_SECOND - elapsedMs).coerceAtLeast(0L)
                } ?: 0L
            }
            val waitMs = maxOf(debounceMs, minWaitMs)
            deltaJob = scope.launch {
                delay(waitMs)
                runAdvertDeltaSync()
            }
        }
    }

    internal inner class AdvertDeltaRound {
        suspend fun runAdvertDeltaSync() {
            val handler = deltaSyncHandler ?: return
            if (isSyncingContacts) return
            if (!isInForeground()) {
                deltaJob = null
                return
            }
            val drained = drainAdvertDeltaWork() ?: return
            val radioID = deltaRadioID
            val unknownAtStart = if (radioID == null) {
                emptyList()
            } else {
                drained.advert.filter { store.fetchContact(radioID, it) == null }
            }
            val ready = drained.copy(
                unknownAtStart = unknownAtStart,
                pathLastModified = snapshotPathLastModified(drained.path, radioID),
            )
            val outcome = handler(ready.fullRefetch)
            rollBackContactsDeletedDuringSync(radioID)
            applyAdvertDeltaOutcome(outcome, ready, radioID)
        }

        suspend fun drainAdvertDeltaWork(): DrainedAdvertDelta? = advertMutex.withLock {
            if (pendingAdvertKeys.isEmpty() && pendingPathUpdateKeys.isEmpty() && !escalateToFullRefetch) {
                null
            } else {
                val advert = pendingAdvertKeys.values.map { it.copyOf() }
                val path = pendingPathUpdateKeys.values.map { it.copyOf() }
                val receiveTimes = takeAdvertReceiveTimesLocked(advert)
                pendingAdvertKeys.clear()
                pendingPathUpdateKeys.clear()
                val fullRefetch = escalateToFullRefetch
                escalateToFullRefetch = false
                // Only mid-round 0x8F keys after this clear are rolled back.
                contactsDeletedDuringSync.clear()
                DrainedAdvertDelta(
                    advert = advert,
                    path = path,
                    receiveTimes = receiveTimes,
                    fullRefetch = fullRefetch,
                    unknownAtStart = emptyList(),
                    pathLastModified = emptyMap(),
                )
            }
        }

        suspend fun applyAdvertDeltaOutcome(
            outcome: AdvertContactSyncOutcome,
            drained: DrainedAdvertDelta,
            radioID: Uuid?,
        ) {
            when (outcome) {
                AdvertContactSyncOutcome.SYNCED -> {
                    consecutiveDeltaSyncFailures = 0
                    lastDeltaSyncEndSeconds = now()
                    stampAdvertReceiveTimes(drained.advert, drained.receiveTimes, radioID)
                    if (!drained.fullRefetch) {
                        escalateUndeliveredPathUpdates(drained.path, drained.pathLastModified, radioID)
                        escalateMissingUnknownKeys(drained.unknownAtStart, radioID)
                    }
                    adoptOrphanedMessages(drained.advert, radioID)
                    reconcileAdvertDeltaDiscover(drained.advert, drained.unknownAtStart, radioID)
                    // iOS yields `.contactUpdated` after a synced advert delta so an open map
                    // (contactsVersion) reloads pins with GET_CONTACTS coords. Hear-time
                    // ContactAdvertisementApplied fires before this round, so without this bump
                    // a first location or a move stays off/stale until another mutation.
                    syncCoordinator.notifyContactsChanged()
                }
                AdvertContactSyncOutcome.BUSY -> {
                    requeueDrainedWork(drained.advert, drained.path, drained.fullRefetch)
                    deltaJob = null
                    scheduleDeltaSync(busy = true)
                    return
                }
                AdvertContactSyncOutcome.FAILED -> settleFailedAdvertDelta(drained, radioID)
                AdvertContactSyncOutcome.NOT_READY -> {
                    logger?.notice(
                        "Advert delta sync not ready: dropping ${drained.advert.size} pending " +
                            "key(s) until a full contact fetch completes",
                    )
                    stampAdvertReceiveTimes(drained.advert, drained.receiveTimes, radioID)
                }
            }
            deltaJob = null
            if (hasPendingDeltaSyncWork()) {
                scheduleDeltaSync()
            }
        }

        suspend fun settleFailedAdvertDelta(drained: DrainedAdvertDelta, radioID: Uuid?) {
            consecutiveDeltaSyncFailures += 1
            lastDeltaSyncEndSeconds = now()
            if (consecutiveDeltaSyncFailures < MAX_CONSECUTIVE_DELTA_SYNC_FAILURES) {
                requeueDrainedWork(drained.advert, drained.path, drained.fullRefetch)
            } else {
                consecutiveDeltaSyncFailures = 0
                logger?.error(
                    "Advert delta sync failed $MAX_CONSECUTIVE_DELTA_SYNC_FAILURES times " +
                        "in a row; dropping ${drained.advert.size} pending key(s)",
                )
                advertMutex.withLock {
                    if (drained.fullRefetch) escalateToFullRefetch = true
                    for (key in drained.path) {
                        val hex = key.toUppercaseHex()
                        if (isRadioDeletedLocked(hex)) continue
                        pendingPathUpdateKeys.putIfAbsent(hex, key)
                    }
                }
                // Dropped keys still heard the contact; stamp so stale-RTC prune cannot
                // CMD_REMOVE a row the radio just advertised.
                stampAdvertReceiveTimes(drained.advert, drained.receiveTimes, radioID)
            }
        }

        fun hasPendingDeltaSyncWork(): Boolean =
            pendingAdvertKeys.isNotEmpty() || pendingPathUpdateKeys.isNotEmpty() || escalateToFullRefetch

        fun isRadioDeletedLocked(hex: String): Boolean =
            contactsDeletedDuringSync.containsKey(hex) || pendingDeletedKeys.containsKey(hex)

        suspend fun requeueDrainedWork(
            drainedAdvert: List<ByteArray>,
            drainedPath: List<ByteArray>,
            fullRefetch: Boolean,
        ) {
            advertMutex.withLock {
                if (fullRefetch) escalateToFullRefetch = true
                for (key in drainedAdvert) {
                    val hex = key.toUppercaseHex()
                    if (isRadioDeletedLocked(hex)) continue
                    recordPendingAdvertKeyLocked(key, now())
                }
                for (key in drainedPath) {
                    val hex = key.toUppercaseHex()
                    if (isRadioDeletedLocked(hex)) continue
                    pendingPathUpdateKeys.putIfAbsent(hex, key)
                }
            }
        }

        suspend fun recordPendingAdvertKeyLocked(publicKey: ByteArray, receivedAt: Long) {
            val hex = publicKey.toUppercaseHex()
            pendingAdvertKeys.putIfAbsent(hex, publicKey.copyOf())
            contactsDeletedDuringSync.remove(hex)
            pendingDeletedKeys.remove(hex)
            pendingAdvertReceiveTimes[hex] = receivedAt
            revivedMutex.withLock { revivedDuringDeleteFlush += hex }
        }

        suspend fun recordPendingAdvertKey(publicKey: ByteArray, receivedAt: Long) {
            advertMutex.withLock { recordPendingAdvertKeyLocked(publicKey, receivedAt) }
        }

        fun takeAdvertReceiveTimesLocked(keys: List<ByteArray>): Map<String, Long> {
            val times = linkedMapOf<String, Long>()
            for (key in keys) {
                val hex = key.toUppercaseHex()
                pendingAdvertReceiveTimes.remove(hex)?.let { times[hex] = it }
            }
            return times
        }
    }

    internal inner class AdvertDeltaFollowUp {
        suspend fun stampAdvertReceiveTimes(
            drainedAdvert: List<ByteArray>,
            receiveTimes: Map<String, Long>,
            radioID: Uuid?,
        ) {
            if (radioID == null || receiveTimes.isEmpty()) return
            val tombstoned = advertMutex.withLock {
                contactsDeletedDuringSync.keys.toSet() + pendingDeletedKeys.keys
            }
            for (publicKey in drainedAdvert) {
                stampOneAdvertReceiveTime(radioID, publicKey, tombstoned, receiveTimes)
            }
        }

        // A failed post-delta stamp is logged and later keys still stamp. Cancellation still propagates.
        suspend fun stampOneAdvertReceiveTime(
            radioID: Uuid,
            publicKey: ByteArray,
            tombstoned: Set<String>,
            receiveTimes: Map<String, Long>,
        ) {
            val hex = publicKey.toUppercaseHex()
            val receivedAt = if (hex in tombstoned) null else receiveTimes[hex]
            if (receivedAt != null) {
                runLoggedContactEffect(
                    onFailure = { error ->
                        logger?.error("Post-delta lastHeard stamp failed for $hex: ${error.message ?: error}")
                    },
                    fallback = {},
                ) {
                    stampHeardOnAir(radioID, publicKey, clampedPhoneClockTimestamp(receivedAt))
                    Unit
                }
            }
        }

        suspend fun escalateMissingUnknownKeys(unknownAtStart: List<ByteArray>, radioID: Uuid?) {
            if (radioID == null || unknownAtStart.isEmpty()) return
            val tombstoned = advertMutex.withLock {
                contactsDeletedDuringSync.keys.toSet() + pendingDeletedKeys.keys
            }
            val missing = unknownAtStart.filter { key ->
                val hex = key.toUppercaseHex()
                hex !in tombstoned && store.fetchContact(radioID, key) == null
            }
            if (missing.isEmpty()) return
            logger?.info("Advert delta sync escalation: ${missing.size} unknown key(s) still missing")
            advertMutex.withLock {
                escalateToFullRefetch = true
                // Re-record the phone clock. The first incremental drain already took the 0x80
                // receive times; without a stamp here the prune-free refetch inserts lastHeard
                // null and a stale radio lastModified can match stale-node prune. iOS
                // `recordPendingAdvertKey` on this same path.
                val receivedAt = now()
                for (key in missing) {
                    recordPendingAdvertKeyLocked(key, receivedAt)
                }
            }
        }

        suspend fun reconcileAdvertDeltaDiscover(
            drainedAdvert: List<ByteArray>,
            unknownAtStart: List<ByteArray>,
            radioID: Uuid?,
        ) {
            if (radioID == null || drainedAdvert.isEmpty()) return
            val tombstoned = advertMutex.withLock {
                contactsDeletedDuringSync.keys.toSet() + pendingDeletedKeys.keys
            }
            val insertedHex = unknownAtStart.map { it.toUppercaseHex() }.toSet()
            for (publicKey in drainedAdvert) {
                reconcileOneDrainedAdvert(radioID, publicKey, tombstoned, insertedHex)
            }
        }

        // A failed discover reconcile is logged and the advert is still announced. Cancellation still propagates.
        suspend fun reconcileOneDrainedAdvert(
            radioID: Uuid,
            publicKey: ByteArray,
            tombstoned: Set<String>,
            insertedHex: Set<String>,
        ) {
            val hex = publicKey.toUppercaseHex()
            val contact = if (hex in tombstoned) null else store.fetchContact(radioID, publicKey)
            val deletedDuringRound = contact != null &&
                hex in advertMutex.withLock { contactsDeletedDuringSync.keys + pendingDeletedKeys.keys }
            if (contact != null && !deletedDuringRound) {
                val nodes = discoveredNodes
                if (nodes != null) {
                    runLoggedContactEffect(
                        onFailure = { error ->
                            logger?.error("Discover reconcile upsert failed for $hex: ${error.message ?: error}")
                        },
                        fallback = {},
                    ) {
                        upsertDiscoveredFromLocalContact(radioID, contact, nodes, now)
                        Unit
                    }
                }
                eventBroadcaster.yield(
                    ContactServiceEvent.ContactAdvertisementApplied(radioID, publicKey.copyOf()),
                )
                if (hex in insertedHex) {
                    discoveryNotifier.notifyNewContact(contact.id, contact.name, contact.contactType)
                }
            }
        }

        suspend fun adoptOrphanedMessages(drainedAdvert: List<ByteArray>, radioID: Uuid?) {
            if (radioID == null || drainedAdvert.isEmpty()) return
            val adopted = adoptedOrphanMessages(radioID, orphanContactsForAdvert(drainedAdvert, radioID))
            if (adopted.isEmpty()) return
            logger?.info("Adopted orphaned DMs for ${adopted.size} contact(s)")
            syncCoordinator.notifyConversationsChanged()
            eventBroadcaster.yield(ContactServiceEvent.OrphanDirectMessagesAdopted(adopted.toList()))
        }

        suspend fun orphanContactsForAdvert(drainedAdvert: List<ByteArray>, radioID: Uuid): List<OrphanDmContact> {
            val tombstoned = advertMutex.withLock {
                contactsDeletedDuringSync.keys.toSet() + pendingDeletedKeys.keys
            }
            return drainedAdvert.mapNotNull { key ->
                if (key.toUppercaseHex() in tombstoned) return@mapNotNull null
                val dto = store.fetchContact(radioID, key) ?: return@mapNotNull null
                OrphanDmContact(id = dto.id, publicKey = dto.publicKey, isBlocked = dto.isBlocked)
            }
        }

        // A failed orphan adoption is logged and the delta round stays alive. Cancellation still propagates.
        suspend fun adoptedOrphanMessages(radioID: Uuid, contacts: List<OrphanDmContact>): Set<Uuid> {
            if (contacts.isEmpty()) return emptySet()
            return runLoggedContactEffect(
                onFailure = { error ->
                    logger?.error("Orphaned DM adoption failed: ${error.message ?: error}")
                },
                fallback = { emptySet() },
            ) {
                orphanDmAdopter.adopt(radioID, contacts)
            }
        }

        suspend fun rollBackContactsDeletedDuringSync(radioID: Uuid?) {
            if (radioID == null) return
            val keys = advertMutex.withLock { contactsDeletedDuringSync.values.map { it.copyOf() } }
            if (keys.isEmpty()) return
            for (publicKey in keys) {
                val contact = store.fetchContact(radioID, publicKey) ?: continue
                store.deleteContactIfUnreferenced(contact.id)
                logger?.info(
                    "Delta sync rollback: removed contact ${publicKey.toUppercaseHex()} deleted by the radio mid-sync",
                )
                publishNodeDeleted()
            }
        }
    }

    internal inner class SyncHold {
        suspend fun materializeContactForPendingAdvert(matchingPrefix: ByteArray, radioID: Uuid): ContactDto? =
            withContactMaterializationOperation {
                if (matchingPrefix.isEmpty()) return@withContactMaterializationOperation null
                val publicKey = advertMutex.withLock {
                    val matches = pendingAdvertKeys.values.filter { it.startsWithPrefix(matchingPrefix) }
                    if (matches.size == 1) matches.first().copyOf() else null
                } ?: return@withContactMaterializationOperation null
                val pubKeyHex = publicKey.toUppercaseHex()
                runLoggedContactEffect(
                    onFailure = { error ->
                        logger?.error("materialize pending advert failed key=$pubKeyHex: ${error.message ?: error}")
                    },
                    fallback = { null },
                ) {
                    val meshContact = mapSessionError { session.getContact(publicKey) }
                    if (meshContact == null) {
                        logger?.info("materialize pending advert: getContact nil key=$pubKeyHex")
                        return@runLoggedContactEffect null
                    }
                    val frame = meshContact.toContactFrame()
                    val contactID = store.saveContact(radioID = radioID, frame = frame)
                    val heardAt = advertMutex.withLock { pendingAdvertReceiveTimes[pubKeyHex] } ?: now()
                    // A failed materialize stamp is logged and the saved contact still returns.
                    // Cancellation still propagates.
                    runLoggedContactEffect(
                        onFailure = { error ->
                            logger?.error(
                                "materialize pending advert lastHeard stamp failed key=$pubKeyHex: " +
                                    "${error.message ?: error}",
                            )
                        },
                        fallback = {},
                    ) {
                        stampHeardOnAir(radioID, publicKey, clampedPhoneClockTimestamp(heardAt))
                        Unit
                    }
                    currentCoroutineContext().ensureActive()
                    eventBroadcaster.yield(
                        ContactServiceEvent.ContactAdvertisementApplied(radioID, publicKey.copyOf()),
                    )
                    syncCoordinator.notifyContactsChanged()
                    store.fetchContact(id = contactID)
                }
            }

        suspend fun setSyncingContacts(radioID: Uuid, isSyncing: Boolean) {
            val release = releaseSyncingContactsHold(isSyncing)
            if (release.becameIdle && deltaSyncHandler != null && isInForeground()) {
                scheduleDeltaSync()
            }
            if (release.toFetch.isEmpty() && release.pathUpdates.isEmpty()) return
            retryDrainedAdvertFetches(radioID, release.toFetch, release.drainedHearTimes)
            refreshDrainedPaths(radioID, release.pathUpdates)
        }

        suspend fun releaseSyncingContactsHold(isSyncing: Boolean): SyncHoldRelease = advertMutex.withLock {
            when {
                isSyncing -> {
                    syncingContactsHoldCount += 1
                    isSyncingContacts = true
                    SyncHoldRelease.stillHeld()
                }
                syncingContactsHoldCount > 1 -> {
                    syncingContactsHoldCount -= 1
                    SyncHoldRelease.stillHeld()
                }
                else -> drainIdleAdvertQueuesLocked()
            }
        }

        fun drainIdleAdvertQueuesLocked(): SyncHoldRelease {
            syncingContactsHoldCount = 0
            isSyncingContacts = false
            return if (deltaSyncHandler != null) {
                SyncHoldRelease.idleKeepingQueues()
            } else {
                val toFetch = pendingAdvertKeys.values.map { it.copyOf() }
                val hearTimes = takeAdvertReceiveTimesLocked(toFetch)
                pendingAdvertKeys.clear()
                val pathUpdates = pendingPathUpdateKeys.values.map { it.copyOf() }
                pendingPathUpdateKeys.clear()
                SyncHoldRelease(
                    becameIdle = true,
                    toFetch = toFetch,
                    pathUpdates = pathUpdates,
                    drainedHearTimes = hearTimes,
                )
            }
        }

        suspend fun retryDrainedAdvertFetches(
            radioID: Uuid,
            toFetch: List<ByteArray>,
            drainedHearTimes: Map<String, Long>,
        ) {
            val failed = linkedMapOf<String, ByteArray>()
            for (publicKey in toFetch) {
                if (isAdvertFetchCancelled(publicKey)) continue
                val saved = runLoggedContactEffect(
                    onFailure = { error -> logger?.warning("Drained advert fetch failed: ${error.message ?: error}") },
                    fallback = { false },
                ) {
                    fetchAndSaveUnknownContact(
                        radioID,
                        publicKey,
                        heardAt = drainedHearTimes[publicKey.toUppercaseHex()],
                    )
                }
                if (!saved && !isAdvertFetchCancelled(publicKey)) {
                    failed[publicKey.toUppercaseHex()] = publicKey
                }
            }
            if (failed.isEmpty()) return
            advertMutex.withLock {
                for ((hex, key) in failed) {
                    pendingAdvertKeys.putIfAbsent(hex, key)
                }
            }
        }

        suspend fun refreshDrainedPaths(radioID: Uuid, pathUpdates: List<ByteArray>) {
            for (publicKey in pathUpdates) {
                if (isAdvertFetchCancelled(publicKey)) continue
                runLoggedContactEffect(
                    onFailure = { error -> logger?.warning("Drained path refresh failed: ${error.message ?: error}") },
                    fallback = {},
                ) {
                    refreshPathFromDevice(radioID, publicKey)
                    Unit
                }
            }
        }
    }

    internal inner class OverwriteDeletion {
        suspend fun handleDeviceContactDeleted(radioID: Uuid, publicKey: ByteArray): Boolean {
            // ZephCore `set v.contact off` also pushes 0x8F for the V-key. That is not overwrite-oldest.
            // Keep the local V row and leave storage-full bookkeeping unchanged (iOS handleContactDeletedEvent).
            val pubKeyPrefix = publicKey.copyOfRange(
                0,
                minOf(OVERWRITE_KEY_PREFIX_BYTES, publicKey.size),
            ).toUppercaseHex()
            return if (isProtectedVContact(radioID, publicKey)) {
                logger?.info(
                    "Contact deleted push for ZephCore V-contact ($pubKeyPrefix...); " +
                        "preserving local row, not clearing storage-full",
                )
                false
            } else {
                deleteQueuedDeviceContact(radioID, publicKey, pubKeyPrefix)
            }
        }

        suspend fun deleteQueuedDeviceContact(radioID: Uuid, publicKey: ByteArray, pubKeyPrefix: String): Boolean {
            queueDeviceContactDeleted(radioID, publicKey)
            return if (claimForegroundDeviceDeletion(publicKey)) {
                deleteOverwriteOldestContact(radioID, publicKey, pubKeyPrefix)
            } else {
                false
            }
        }

        suspend fun queueDeviceContactDeleted(radioID: Uuid, publicKey: ByteArray) {
            deltaRadioID = radioID
            revivedMutex.withLock { revivedDuringDeleteFlush.remove(publicKey.toUppercaseHex()) }
            advertMutex.withLock {
                val hex = publicKey.toUppercaseHex()
                pendingAdvertKeys.remove(hex)
                pendingPathUpdateKeys.remove(hex)
                cancelledAdvertFetchKeys.add(hex)
                contactsDeletedDuringSync[hex] = publicKey.copyOf()
                // Enqueue before `isInForeground` so a concurrent flush cannot miss this
                // key while the mutex is released.
                pendingDeletedKeys[hex] = publicKey.copyOf()
            }
        }

        suspend fun claimForegroundDeviceDeletion(publicKey: ByteArray): Boolean = if (!isInForeground()) {
            false
        } else {
            advertMutex.withLock {
                pendingDeletedKeys.remove(publicKey.toUppercaseHex()) != null
            }
        }

        suspend fun deleteOverwriteOldestContact(radioID: Uuid, publicKey: ByteArray, pubKeyPrefix: String): Boolean {
            logger?.info("Overwrite oldest: device deleted contact with key $pubKeyPrefix...")
            return runLoggedContactEffect(
                onFailure = { error ->
                    // iOS re-inserts so a later foreground flush can retry; the radio already dropped the contact.
                    requeueFailedDeviceDeletion(publicKey, pubKeyPrefix, error)
                },
                fallback = { false },
            ) {
                val contact = store.fetchContact(radioID, publicKey)
                if (contact == null) {
                    logger?.warning(
                        "Overwrite oldest: contact not found in local database for key $pubKeyPrefix... " +
                            "(may have been deleted already)",
                    )
                    false
                } else {
                    removeOverwriteOldestContact(contact, publicKey)
                }
            }
        }

        suspend fun removeOverwriteOldestContact(contact: ContactDto, publicKey: ByteArray): Boolean {
            val contactName = contact.name.ifEmpty { "(unnamed)" }
            val contactTypeDesc = ContactType.fromRaw(contact.typeRawValue)?.name?.lowercase()
                ?: "unknown(${contact.typeRawValue})"
            logger?.notice(
                "Overwrite oldest: deleting contact '$contactName' " +
                    "[key=${publicKey.toUppercaseHex()}, type=$contactTypeDesc, favorite=${contact.isFavorite}, " +
                    "pathLen=${contact.outPathLength}, lastModified=${contact.lastModified}, " +
                    "lastAdvert=${contact.lastAdvertTimestamp}]",
            )
            contactDirectory.removeLocalContact(contact.id, publicKey)
            logger?.info("Overwrite oldest: deleted contact '$contactName' and its messages from local database")
            logger?.info("Overwrite oldest: cleanup complete for '$contactName', storage full flag cleared")
            return true
        }

        suspend fun requeueFailedDeviceDeletion(publicKey: ByteArray, pubKeyPrefix: String, error: Throwable) {
            advertMutex.withLock {
                pendingDeletedKeys[publicKey.toUppercaseHex()] = publicKey.copyOf()
            }
            logger?.error(
                "Overwrite oldest: failed to delete contact $pubKeyPrefix...: ${error.message ?: error}",
            )
        }
    }

    internal inner class ContactPathRefresh {
        suspend fun refreshPathFromDevice(radioID: Uuid, publicKey: ByteArray): Boolean {
            deltaRadioID = radioID
            val hex = publicKey.toUppercaseHex()
            val inForeground = isInForeground()
            val deferred = deferPathRefreshLocked(hex, publicKey, inForeground)
            return if (deferred) {
                if (inForeground) scheduleDeltaSync()
                false
            } else {
                persistPathFromDevice(radioID, publicKey)
            }
        }

        suspend fun deferPathRefreshLocked(hex: String, publicKey: ByteArray, inForeground: Boolean): Boolean =
            advertMutex.withLock {
                when {
                    hex in cancelledAdvertFetchKeys -> false
                    !inForeground -> {
                        pendingPathUpdateKeys.putIfAbsent(hex, publicKey.copyOf())
                        true
                    }
                    !isSyncingContacts && deltaSyncHandler == null -> false
                    else -> {
                        pendingPathUpdateKeys.putIfAbsent(hex, publicKey.copyOf())
                        true
                    }
                }
            }

        suspend fun persistPathFromDevice(radioID: Uuid, publicKey: ByteArray): Boolean =
            contactPathLoader.persist(radioID, publicKey)
    }

    internal inner class ContactAdvertFetch {
        suspend fun handleAdvertisement(radioID: Uuid, publicKey: ByteArray): Boolean {
            deltaRadioID = radioID
            val pubKeyHex = publicKey.toUppercaseHex()
            discoverTrace?.info("B1 0x80 ADVERT received key=$pubKeyHex")
            val receivedAt = now()
            revivedMutex.withLock { revivedDuringDeleteFlush += pubKeyHex }
            if (!isInForeground()) {
                recordPendingAdvertKey(publicKey, receivedAt)
                return false
            }
            return applyForegroundAdvertisement(radioID, publicKey, pubKeyHex, receivedAt)
        }

        suspend fun applyForegroundAdvertisement(
            radioID: Uuid,
            publicKey: ByteArray,
            pubKeyHex: String,
            receivedAt: Long,
        ): Boolean {
            val existing = when (val lookup = fetchContactRetrying(radioID, publicKey)) {
                ContactLookup.Failed -> {
                    if (deltaSyncHandler != null) scheduleDeltaSync()
                    return false
                }
                is ContactLookup.Ok -> lookup.contact
            }
            val wrote = if (existing != null) {
                // Radio lastModified / lastAdvertTimestamp stay radio-sourced. iOS stamps only
                // lastHeard (phone clock) then queues a delta GET_CONTACTS.
                val touched = touchContactHeardRetrying(
                    radioID,
                    publicKey,
                    clampedPhoneClockTimestamp(receivedAt),
                )
                if (touched == true) {
                    discoverTrace?.info("B2 0x80 known-contact hear key=$pubKeyHex isNew=false")
                    true
                } else {
                    false
                }
            } else {
                logger?.info("ADVERT received for unknown contact - enqueueing fetch")
                val deferred = advertMutex.withLock {
                    if (isSyncingContacts || deltaSyncHandler != null) {
                        recordPendingAdvertKeyLocked(publicKey, receivedAt)
                        true
                    } else {
                        cancelledAdvertFetchKeys.remove(pubKeyHex)
                        false
                    }
                }
                discoverTrace?.info("B2 0x80 no local contact key=$pubKeyHex syncing=$isSyncingContacts")
                if (deferred) {
                    false
                } else {
                    // fetchAndSaveUnknownContact already yields ContactAdvertisementApplied.
                    fetchAndSaveUnknownContact(radioID, publicKey, heardAt = receivedAt)
                }
            }
            if (wrote && existing != null) {
                eventBroadcaster.yield(
                    ContactServiceEvent.ContactAdvertisementApplied(radioID, publicKey.copyOf()),
                )
            }
            if (deltaSyncHandler != null) {
                // Record only after a successful known-row touch so a store fault cannot
                // announce a long-known contact as new. Always schedule so other pending
                // keys still run; empty-round no-ops when this advert alone failed both
                // attempts.
                if (existing != null && wrote) {
                    recordPendingAdvertKey(publicKey, receivedAt)
                }
                scheduleDeltaSync()
            }
            return wrote
        }

        suspend fun fetchContactRetrying(radioID: Uuid, publicKey: ByteArray): ContactLookup = retryContactAdvertOnce(
            logError = { line -> logger?.error(line) },
            fallback = { ContactLookup.Failed },
        ) {
            ContactLookup.Ok(store.fetchContact(radioID, publicKey))
        }

        suspend fun touchContactHeardRetrying(radioID: Uuid, publicKey: ByteArray, atEpochSeconds: Long): Boolean? =
            retryContactAdvertOnce(
                logError = { line -> logger?.error(line) },
                fallback = { null },
            ) {
                stampHeardOnAir(radioID, publicKey, atEpochSeconds)
            }

        suspend fun fetchAndSaveUnknownContact(radioID: Uuid, publicKey: ByteArray, heardAt: Long? = null): Boolean =
            contactAdvertMaterializer.fetchAndSave(radioID, publicKey, heardAt)

        suspend fun isAdvertFetchCancelled(publicKey: ByteArray): Boolean = isAdvertFetchCancelledState(publicKey)
    }

    private suspend fun isAdvertFetchCancelledState(publicKey: ByteArray): Boolean =
        advertMutex.withLock { publicKey.toUppercaseHex() in cancelledAdvertFetchKeys }

    private suspend fun clearAdvertFetchCancellation(publicKey: ByteArray) {
        advertMutex.withLock { cancelledAdvertFetchKeys.remove(publicKey.toUppercaseHex()) }
    }

    companion object {
        /**
         * Builds the `meshcore://contact/add` URI for a contact (pure; no device). Delegates to
         * [ContactCardUri.export] so emit stays byte-identical to iOS. Mirrors the static
         * `exportContactURI(name:publicKey:type:)`.
         */
        fun exportContactURI(name: String, publicKey: ByteArray, type: ContactType): String =
            ContactCardUri.export(name = name, publicKey = publicKey, type = type)
    }
}
