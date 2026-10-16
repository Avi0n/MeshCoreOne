package one.meshcore.shared.chat

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.model.MessageStatus
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex
import one.meshcore.shared.runReportingThrowable
import one.meshcore.shared.settings.PersistentLogger
import one.meshcore.shared.settings.formatFixed
import kotlin.math.max
import kotlin.time.Duration.Companion.milliseconds

private const val MILLISECONDS_PER_SECOND = 1000.0

/**
 * Sends direct and channel messages with app-layer retry and end-to-end ACK tracking. The Kotlin port
 * of the Swift `MessageService` actor: its single-owner state ([pendingAcks], [inFlightRetries]) is
 * confined behind [stateMutex], held only around the short read-modify-write of that state and released
 * across every suspending [session]/[store] call — mirroring how a Swift actor releases isolation at each
 * `await`, so the terminal-state guards (not a lock held across I/O) resolve the listener/checker/finalize
 * races.
 *
 * The retry loop, `finalizeSend` exhaustion teardown, and channel send are extensions over this
 * class's [internal] state. Lifecycle: [startEventMonitoring]/[stopEventMonitoring]
 * (ACK listener) and [startAckExpiryChecking]/[stopAckExpiryChecking] (periodic give-up) are independent
 * pairs; a routine disconnect stops the checker only (in-flight DMs stay `.sent`), while
 * [stopAndFailAllPending] is the explicit full teardown.
 */
class MessageService(
    internal val session: ChatSession,
    internal val store: MessageStore,
    internal val scope: CoroutineScope,
    identity: MessageServiceIdentity,
    internal val config: MessageServiceConfig = MessageServiceConfig(),
    internal val logger: PersistentLogger = PersistentLogger(scope, category = LOGGER_CATEGORY),
) {
    internal val now: () -> Double = identity.now
    internal val newMessageId: () -> Uuid = identity.newMessageId
    internal val stateMutex = Mutex()
    internal val pendingAcks = mutableMapOf<Uuid, PendingAck>()
    internal val inFlightRetries = mutableSetOf<Uuid>()
    internal val statusBroadcaster = MessageStatusBroadcaster()

    private var ackCheckJob: Job? = null
    internal var eventListenerJob: Job? = null
    private var checkIntervalSeconds: Double = ChatConstants.ACK_CHECK_INTERVAL_SECONDS

    // MARK: ACK handling

    /**
     * Processes an ACK from the session stream. Finds the in-flight entry whose accumulated [ackCodes]
     * contains this code and is not yet delivered, marks it delivered, writes `.delivered` + round-trip
     * time (only when firmware supplied it — no fabricated fallback), stamps the contact's last-message
     * date, removes the entry, and yields `.statusResolved(.delivered)`.
     *
     * An unmatched code is diagnostic only: either a late ACK after the row already failed, or a
     * duplicate for an already-delivered message. `orphanSentRow` flags a `.sent` DM that lost its
     * pending entry to teardown and would otherwise stay `.sent`; a reconnect sweep is not implemented.
     */
    suspend fun handleAcknowledgement(code: ByteArray, tripTime: Long?) {
        val key = code.ackCodeLeUInt32()
        val live = stateMutex.withLock {
            val entry = pendingAcks.entries.firstOrNull { key in it.value.ackCodes && !it.value.isDelivered }
                ?: return@withLock null
            pendingAcks[entry.key] = entry.value.copy(isDelivered = true)
            Triple(entry.key, entry.value.contactID, entry.value.sentAtEpochSeconds)
        }
        if (live != null) {
            val (messageID, contactID, sentAt) = live
            writeDeliveredStatus(messageID, key, tripTime)
            updateContactLastMessageDate(contactID)
            val livePending = stateMutex.withLock {
                pendingAcks.remove(messageID)
                pendingAcks.size
            }
            statusBroadcaster.yield(MessageStatusEvent.StatusResolved(messageID, MessageStatus.DELIVERED, tripTime))
            val firmwareTrip = tripTime?.let { "${it}ms" } ?: "nil"
            logger.info(
                "[ack-diag] ACK matched deltaSinceLastSend=${formatFixed(
                    now() - sentAt,
                    ACK_DELTA_FRACTION_DIGITS,
                )}s " +
                    "firmwareTrip=$firmwareTrip livePending=$livePending",
            )
            return
        }

        val orphanSentRow = store.hasOutgoingSentDM(key)
        logger.warning(
            "[ack-diag] unmatched ACK code=${code.toUppercaseHex()} livePending=${pendingAckCount()} " +
                "orphanSentRow=$orphanSentRow (late post-teardown or duplicate)",
        )
    }

    private suspend fun writeDeliveredStatus(messageID: Uuid, ackCode: Long, tripTime: Long?) {
        // A delivered-status write fault, including an Error, still finishes the acknowledgement.
        runReportingThrowable<Unit>(
            onFailure = { error ->
                logger.error("Failed to write delivered status: ${error.message ?: error.toString()}")
            },
        ) {
            store.updateMessageAck(messageID, ackCode, MessageStatus.DELIVERED, tripTime)
        }
    }

    private suspend fun updateContactLastMessageDate(contactID: Uuid) {
        // A contact-stamp fault, including an Error, still finishes the acknowledgement.
        runReportingThrowable<Unit>(
            onFailure = { error ->
                logger.error("Failed to update contact lastMessageDate: ${error.message ?: error.toString()}")
            },
        ) {
            store.updateContactLastMessage(contactID, now())
        }
    }

    /**
     * Fails a DM awaiting its ACK once the per-entry give-up deadline elapses. The deadline is
     * `max(ackGiveUpWindow, entry.timeout)`: the window is the floor and post-loop grace on fast presets,
     * while on slow presets the deadline follows the attempt's own ACK wait so the checker never fires
     * mid-attempt. A failure broadcast is gated on the DB write actually happening (so a delivered row
     * is never reported failed). Mirrors `checkExpiredAcks`.
     */
    suspend fun checkExpiredAcks() {
        val nowSeconds = now()
        val window = config.ackGiveUpWindowSeconds
        val expired = stateMutex.withLock {
            pendingAcks.filter { (_, tracking) ->
                !tracking.isDelivered &&
                    (nowSeconds - tracking.sentAtEpochSeconds) > max(window, tracking.timeoutSeconds)
            }.keys.toList()
        }

        for (messageID in expired) {
            val didFail = store.updateMessageStatusUnlessDelivered(messageID, MessageStatus.FAILED)
            val removed = stateMutex.withLock { pendingAcks.remove(messageID) }
            if (removed != null && !removed.isDelivered && didFail) {
                val deadline = max(window, removed.timeoutSeconds)
                logger.warning(
                    "[ack-diag] give-up: failed after ${formatFixed(
                        nowSeconds - removed.sentAtEpochSeconds,
                        ACK_GIVE_UP_FRACTION_DIGITS,
                    )}s " +
                        "window=${formatFixed(window, ACK_GIVE_UP_FRACTION_DIGITS)}s " +
                        "deadline=${formatFixed(deadline, ACK_GIVE_UP_FRACTION_DIGITS)}s " +
                        "livePending=${pendingAckCount()}",
                )
                statusBroadcaster.yield(MessageStatusEvent.Failed(messageID))
            }
        }
    }

    /** Fails every non-delivered in-flight DM. Mirrors `failAllPendingMessages`. */
    suspend fun failAllPendingMessages() {
        val pending = stateMutex.withLock {
            val snapshot = pendingAcks.filterValues { !it.isDelivered }.keys.toList()
            pendingAcks.clear()
            snapshot
        }
        for (messageID in pending) {
            if (store.updateMessageStatusUnlessDelivered(messageID, MessageStatus.FAILED)) {
                statusBroadcaster.yield(MessageStatusEvent.Failed(messageID))
            }
        }
    }

    // MARK: Expiry checker lifecycle

    /**
     * Starts the periodic expiry checker. Independent from the ACK listener; counterparts are
     * [stopAckExpiryChecking] (routine disconnect: leaves in-flight DMs `.sent`) and
     * [stopAndFailAllPending] (full teardown). Mirrors `startAckExpiryChecking`.
     */
    fun startAckExpiryChecking(intervalSeconds: Double = ChatConstants.ACK_CHECK_INTERVAL_SECONDS) {
        checkIntervalSeconds = intervalSeconds
        ackCheckJob?.cancel()
        ackCheckJob = scope.launch {
            while (isActive) {
                delay((checkIntervalSeconds * MILLISECONDS_PER_SECOND).toLong().milliseconds)
                // An expiry-check fault, including an Error, must leave later checks running.
                runReportingThrowable<Unit>(
                    onFailure = { error ->
                        logger.error("ACK expiry check failed: ${error.message ?: error.toString()}")
                    },
                ) {
                    checkExpiredAcks()
                }
            }
        }
    }

    /** Cancels the checker only; in-flight DMs stay `.sent` so a reconnect can still receive their ACK. */
    fun stopAckExpiryChecking() {
        ackCheckJob?.cancel()
        ackCheckJob = null
    }

    /** Stops the checker and fails every in-flight DM. Explicit full-teardown variant. */
    suspend fun stopAndFailAllPending() {
        ackCheckJob?.cancel()
        ackCheckJob = null
        failAllPendingMessages()
    }

    /** The number of pending ACKs currently tracked. */
    suspend fun pendingAckCount(): Int = stateMutex.withLock { pendingAcks.size }

    /** Whether the periodic expiry checker is active. */
    val isAckExpiryCheckingActive: Boolean get() = ackCheckJob != null

    // MARK: Pending-ACK tracking

    /**
     * Records an expected-ACK code for a message awaiting delivery. First call creates the entry;
     * subsequent retry attempts add the new code to the same entry's [PendingAck.ackCodes] set and reset
     * both `sentAt` and `timeout` to the latest attempt so the checker does not fail an in-flight retry.
     * Mirrors `trackPendingAck`.
     */
    suspend fun trackPendingAck(messageID: Uuid, contactID: Uuid, ackCode: ByteArray, timeoutSeconds: Double) =
        stateMutex.withLock {
            val key = ackCode.ackCodeLeUInt32()
            val colliding = pendingAcks.entries.firstOrNull { it.key != messageID && key in it.value.ackCodes }
            if (colliding != null) {
                logger.warning(
                    "[ack-diag] ackCode collision: code=${ackCode.toUppercaseHex()} " +
                        "shared by messages ${colliding.key.value} and ${messageID.value}",
                )
            }
            val existing = pendingAcks[messageID]
            pendingAcks[messageID] = existing?.copy(
                ackCodes = existing.ackCodes + key,
                sentAtEpochSeconds = now(),
                timeoutSeconds = timeoutSeconds,
            ) ?: PendingAck(
                messageID = messageID,
                contactID = contactID,
                ackCodes = setOf(key),
                sentAtEpochSeconds = now(),
                timeoutSeconds = timeoutSeconds,
            )
        }

    private companion object {
        const val LOGGER_CATEGORY: String = "MessageService"
        const val ACK_DELTA_FRACTION_DIGITS: Int = 2
        const val ACK_GIVE_UP_FRACTION_DIGITS: Int = 1
    }
}

data class MessageServiceIdentity(val now: () -> Double, val newMessageId: () -> Uuid)
