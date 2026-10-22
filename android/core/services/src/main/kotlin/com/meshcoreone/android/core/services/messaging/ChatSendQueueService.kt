// PortedFrom: MC1Services/Sources/MC1Services/Services/ChatSendQueueService.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.services.messaging

import com.meshcoreone.android.core.contracts.domain.*
import com.meshcoreone.android.core.contracts.domain.errors.*
import com.meshcoreone.android.core.model.*
import com.meshcoreone.android.core.protocol.config.MeshCoreException
import com.meshcoreone.android.core.protocol.session.SessionClock
import com.meshcoreone.android.core.protocol.session.SystemSessionClock
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class ChatSendQueueService(
    override val token: SessionToken,
    private val dataStore: PersistenceStoreProtocol,
    private val messageService: MessageService,
    private val channelQuery: MessagingChannelQuery,
    private val reactionIndexer: OutgoingChannelReactionIndexer,
    private val signals: ConnectionSignals,
    scope: CoroutineScope,
    val config: ChatSendQueueConfig = ChatSendQueueConfig(),
    private val clock: SessionClock = SystemSessionClock(),
    reporter: MessagingIssueReporter = LoggingMessagingIssueReporter,
    private val newID: () -> UUID = UUID::randomUUID,
) : ChatSendQueuePort {
    init { require(messageService.token == token) { "Queues must share their message service generation" } }
    private val ownership = MessagingOwnership(token, scope, signals, reporter)
    private val persistenceJob = SupervisorJob()
    private val persistenceScope = CoroutineScope(scope.coroutineContext.minusKey(Job) + persistenceJob)
    private val lock = Any()
    private val observation = Mutex()
    private val triggers = BLETransportOpenedSignal()
    private val queued = mutableSetOf<UUID>()
    private val failures = linkedMapOf<String, Throwable>()
    private val channelFetchFailures = mutableMapOf<UUID, Long>()
    private val completedDMs = mutableSetOf<UUID>()
    private val completedChannels = mutableMapOf<UUID, UInt>()
    private val indexedChannels = mutableSetOf<UUID>()
    private val persistenceSubmissions = mutableSetOf<Deferred<Result<Unit>>>()
    private val directClaimIDs = mutableMapOf<UUID, UUID>()
    private var hydrated = false
    private var hydration: Deferred<Unit>? = null
    private var connectionTask: Deferred<Unit>? = null
    private var subscription: ConnectionSubscription? = null
    private var teardown: CompletableDeferred<TeardownReport>? = null
    private val dmQueue = SendQueue(ownership.scope, ::sendDM, ::terminalDM, ::drained)
    private val channelQueue = SendQueue(ownership.scope, ::sendChannel, ::terminalChannel, ::drained)
    val queuedEnvelopeCount: Int get() = synchronized(lock) { queued.size }
    val isObservingConnection: Boolean get() = synchronized(lock) { connectionTask?.isActive == true }
    private fun key(id: UUID) = EntityKey(token.radioId, id)
    private fun record(operation: String, failure: Throwable, retain: Boolean = true) {
        if (retain) synchronized(lock) { failures[operation] = failure }
        ownership.failure(operation, failure)
    }
    private fun recovered(operation: String) { synchronized(lock) { failures.remove(operation) } }
    private suspend fun checkQueueLifetime(operation: String) {
        try { ownership.checkLifetime(operation) }
        catch (failure: MessageServiceException) {
            if (failure.error == MessageServiceError.NotConnected) throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
            throw failure
        }
    }

    suspend fun observeConnectionState(): Unit = observation.withLock {
        checkQueueLifetime("observeSendQueue")
        val prior = synchronized(lock) {
            subscription?.close()
            subscription = null
            connectionTask.also { connectionTask = null }
        }
        prior?.cancelAndJoin()
        val current = signals.subscribeTransitions()
        synchronized(lock) { subscription = current }
        var previousReady = current.initial.token == token && current.initial.state.canDrainSendQueue
        if (previousReady) triggers.fire()
        val task = ownership.scope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                current.transitions.collect { snapshot ->
                    val ready = snapshot.token == token && snapshot.state.canDrainSendQueue
                    if (!previousReady && ready) triggers.fire()
                    previousReady = ready
                    if (snapshot.token != null && snapshot.token != token) {
                        ownership.reporter.report(MessagingDiagnostic.StaleResult(token, "queueConnection"))
                        ownership.invalidate()
                        triggers.finish()
                        dmQueue.cancelDrain()
                        channelQueue.cancelDrain()
                    }
                }
            } finally { current.close() }
        }
        synchronized(lock) { connectionTask = task }
        task.invokeOnCompletion { cause ->
            if (cause != null && cause !is CancellationException) record("queueConnection", cause)
        }
        Unit
    }

    fun transportDidOpen() { if (ownership.isReady) triggers.fire() }

    override suspend fun enqueueDM(envelope: DirectMessageEnvelope) {
        submitPersistence(PendingSendDTO.fromEnvelope(envelope, token.radioId, newID(), clock.wallClock.instant())) {
            if (queued.add(envelope.messageID)) dmQueue.enqueue(envelope)
        }
    }
    override suspend fun enqueueChannel(envelope: ChannelMessageEnvelope) {
        submitPersistence(PendingSendDTO.fromEnvelope(envelope, token.radioId, newID(), clock.wallClock.instant())) {
            if (queued.add(envelope.messageID)) channelQueue.enqueue(envelope)
        }
    }
    override suspend fun signalDMEnqueued(envelope: DirectMessageEnvelope) {
        checkQueueLifetime("signalDMEnqueued")
        if (synchronized(lock) { queued.add(envelope.messageID) }) dmQueue.enqueue(envelope)
    }

    private suspend fun submitPersistence(dto: PendingSendDTO, schedule: () -> Unit) {
        checkQueueLifetime("persistPendingSend")
        val task = synchronized(lock) {
            if (teardown != null || ownership.closing) throw ChatSendQueueServiceException(ChatSendQueueServiceError.NotConnected)
            persistenceScope.async<Result<Unit>>(start = CoroutineStart.LAZY) {
                try {
                    dataStore.insertPendingSendAssigningSequence(dto)
                    synchronized(lock) { if (!ownership.closing) schedule() }
                    Result.success(Unit)
                } catch (failure: PersistenceStoreException) {
                    val typed = ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(failure))
                    record("persistPendingSend.${dto.id}", typed)
                    Result.failure(typed)
                }
            }.also { work ->
                persistenceSubmissions += work
                work.invokeOnCompletion { cause ->
                    if (cause != null) record("persistPendingSend.${dto.id}", cause)
                    synchronized(lock) { persistenceSubmissions.remove(work) }
                }
                work.start()
            }
        }
        // Accepted durable submissions outlive observer cancellation, but are joined before generation shutdown returns.
        task.await().getOrThrow()
    }

    suspend fun hydrate() {
        checkQueueLifetime("hydrateSendQueue")
        if (!isObservingConnection) observeConnectionState()
        val task = synchronized(lock) {
            if (hydrated) return
            hydration ?: ownership.scope.async(start = CoroutineStart.LAZY) {
                val rows = dataStore.fetchPendingSends(token.radioId)
                checkQueueLifetime("hydrateSendQueue.read")
                var malformed: PersistenceStoreException? = null
                for (row in rows) {
                    checkQueueLifetime("hydrateSendQueue.enqueue")
                    try {
                        if (row.radioId != token.radioId) throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                        when (row.kind) {
                            PendingSendKind.DM -> {
                                val envelope = row.directMessageEnvelope()
                                    ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                                signalDMEnqueued(envelope)
                            }
                            PendingSendKind.CHANNEL -> {
                                val envelope = row.channelMessageEnvelope()
                                    ?: throw PersistenceStoreException(PersistenceStoreError.InvalidData)
                                if (synchronized(lock) { queued.add(envelope.messageID) }) channelQueue.enqueue(envelope)
                            }
                        }
                        recovered("hydrate.${row.id}")
                    } catch (failure: PersistenceStoreException) {
                        record("hydrate.${row.id}", failure)
                        val first = malformed
                        if (first == null) malformed = failure else first.addSuppressed(failure)
                    }
                }
                malformed?.let { throw it }
                synchronized(lock) { hydrated = true }
                recovered("hydrate")
            }.also { hydration = it; it.start() }
        }
        try { task.await() }
        catch (failure: PersistenceStoreException) {
            record("hydrate", failure)
            synchronized(lock) { if (hydration === task) hydration = null }
            throw ChatSendQueueServiceException(ChatSendQueueServiceError.PersistFailed(failure))
        }
    }

    private suspend fun awaitReady() {
        ownership.checkLifetime("queueReadiness")
        while (!ownership.isReady) {
            clock.messagingDeadline(config.transportWaitTimeout) { triggers.wait() }
            ownership.checkLifetime("queueReadiness.woke")
        }
    }
    private suspend fun park(id: UUID, failure: Throwable): Nothing {
        record("park.$id", failure, retain = failure is PersistenceStoreException ||
            !isTransientDirectMessageError(failure) && !isTransientChannelMessageError(failure))
        clock.messagingDeadline(config.transportWaitTimeout) { triggers.wait() }
        ownership.checkLifetime("park.woke")
        throw CancellationException("Parked send envelope")
    }
    private suspend fun remapPending(id: UUID) = withContext(NonCancellable) {
        ownership.checkPersistenceCompletion("remapPending")
        dataStore.updateMessageStatusUnlessDelivered(key(id), MessageStatus.PENDING)
        ownership.checkPersistenceCompletion("remapPending.saved")
    }
    private suspend fun parkBeforeSend(id: UUID, failure: PersistenceStoreException): Nothing {
        try { remapPending(id) }
        catch (storage: PersistenceStoreException) { record("remap.$id", storage) }
        catch (stale: MessageServiceException) { record("remap.$id", stale) }
        park(id, failure)
    }

    private suspend fun sendDM(envelope: DirectMessageEnvelope) {
        val id = envelope.messageID
        awaitReady()
        val contact = try {
            dataStore.fetchContact(key(envelope.contactID)).also { ownership.check("dm.contact") }
        } catch (failure: PersistenceStoreException) { park(id, failure) }
        if (contact == null) {
            dataStore.updateMessageStatusUnlessDelivered(key(id), MessageStatus.FAILED)
            ownership.check("dm.deletedContact")
            dataStore.deletePendingSendsForMessage(key(id))
            ownership.check("dm.deletedPending")
            complete(id)
            return
        }
        val attempt = try {
            preflightAndBump(dataStore, key(id)).also { ownership.check("dm.bump") }
        } catch (failure: PersistenceStoreException) { parkBeforeSend(id, failure) }
        if (attempt == null) { complete(id); return }
        synchronized(lock) { directClaimIDs[id] = attempt.pendingID }
        try {
            if (!synchronized(lock) { id in completedDMs }) {
                messageService.sendPendingClaim(attempt.pendingID, id, contact, attempt.preserveTimestamp, envelope.isResend)
                ownership.check("dm.completed")
                synchronized(lock) { completedDMs += id }
            }
            ownership.check("dm.sent")
            dataStore.deletePendingSendsForMessage(key(id))
            ownership.check("dm.pendingDeleted")
            triggers.clear()
            complete(id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PersistenceStoreException) { park(id, failure) }
        catch (failure: MessageServiceException) {
            if (!isTransientDirectMessageError(failure)) throw failure
            try { remapPending(id) }
            catch (storage: PersistenceStoreException) { record("remap.$id", storage) }
            park(id, failure)
        } catch (failure: MeshCoreException) {
            if (!isTransientDirectMessageError(failure)) throw failure
            try { remapPending(id) }
            catch (storage: PersistenceStoreException) { record("remap.$id", storage) }
            park(id, failure)
        }
    }

    private suspend fun sendChannel(envelope: ChannelMessageEnvelope) {
        val id = envelope.messageID
        awaitReady()
        val attempt = try {
            preflightAndBump(dataStore, key(id)).also { ownership.check("channel.bump") }
        } catch (failure: PersistenceStoreException) { parkBeforeSend(id, failure) }
        if (attempt == null) { complete(id); return }
        try {
            val stamp = synchronized(lock) { completedChannels[id] } ?: run {
                val sent = if (envelope.isResend) messageService.resendChannelMessage(id, attempt.preserveTimestamp)
                    else { messageService.sendPendingChannelMessage(id); envelope.messageTimestamp }
                ownership.check("channel.completed")
                synchronized(lock) { completedChannels[id] = sent }
                sent
            }
            ownership.check("channel.sent")
            envelope.localNodeName?.let {
                if (!synchronized(lock) { id in indexedChannels }) {
                    try {
                        reactionIndexer.indexMessage(id, envelope.channelIndex, it, envelope.messageText, stamp)
                        ownership.check("channel.indexed")
                        synchronized(lock) { indexedChannels += id }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { record("index.$id", failure); park(id, failure) }
                }
            }
            dataStore.deletePendingSendsForMessage(key(id))
            ownership.check("channel.pendingDeleted")
            triggers.clear()
            synchronized(lock) { channelFetchFailures.remove(id) }
            complete(id)
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: PersistenceStoreException) { park(id, failure) }
        catch (failure: MessageServiceException) { channelFailure(envelope, attempt, failure) }
        catch (failure: MeshCoreException) { channelFailure(envelope, attempt, failure) }
    }

    private suspend fun channelFailure(envelope: ChannelMessageEnvelope, attempt: Attempt, failure: Exception): Nothing {
        val id = envelope.messageID
        if (!isTransientChannelMessageError(failure)) throw failure
        if (isChannelMessageNotFound(failure) && attempt.postBumpCount >= config.disambiguateAfterAttempts) {
            val exists = try {
                val found = channelQuery.fetchChannel(envelope.channelIndex)
                ownership.check("channel.disambiguated")
                synchronized(lock) { channelFetchFailures.remove(id) }
                found != null
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (queryFailure: Exception) {
                // The source query contract counts every thrown error; none becomes a nil channel.
                val count = synchronized(lock) {
                    Math.incrementExact(channelFetchFailures[id] ?: 0).also { channelFetchFailures[id] = it }
                }
                record("channelQuery.$id", queryFailure, retain = queryFailure is PersistenceStoreException)
                if (count >= config.maxConsecutiveFetchChannelFailures) {
                    synchronized(lock) { channelFetchFailures.remove(id) }
                    throw queryFailure
                }
                try { remapPending(id) }
                catch (storage: PersistenceStoreException) { record("remap.$id", storage) }
                park(id, queryFailure)
            }
            if (!exists) throw failure
        }
        try { remapPending(id) }
        catch (storage: PersistenceStoreException) { record("remap.$id", storage) }
        park(id, failure)
    }

    private suspend fun terminalDM(failure: Exception, envelope: DirectMessageEnvelope) = terminal(failure, envelope.messageID)
    private suspend fun terminalChannel(failure: Exception, envelope: ChannelMessageEnvelope) = terminal(failure, envelope.messageID)
    private suspend fun terminal(failure: Exception, id: UUID) {
        record("terminal.$id", failure, retain = false)
        try {
            ownership.check("terminalSend")
            dataStore.updateMessageStatusUnlessDelivered(key(id), MessageStatus.FAILED)
            ownership.check("terminalSend.saved")
            messageService.notifyMessageFailed(id)
            dataStore.deletePendingSendsForMessage(key(id))
            ownership.check("terminalSend.deleted")
        } catch (storage: PersistenceStoreException) { record("terminalPersistence.$id", storage) }
        complete(id)
        synchronized(lock) { channelFetchFailures.remove(id) }
    }
    private suspend fun drained(failure: Exception?) {
        if (failure != null) ownership.failure("sendQueueDrain", failure)
    }
    private fun complete(id: UUID) {
        val claim = synchronized(lock) { directClaimIDs.remove(id) }
        claim?.let(messageService::releaseDirectClaim)
        synchronized(lock) {
            queued.remove(id)
            completedDMs.remove(id)
            completedChannels.remove(id)
            indexedChannels.remove(id)
            failures.remove("park.$id")
            failures.remove("remap.$id")
            failures.remove("channelQuery.$id")
            failures.remove("index.$id")
        }
    }
    suspend fun awaitDrainCompletion() { dmQueue.awaitDrainCompletion(); channelQueue.awaitDrainCompletion() }

    suspend fun shutdown(): TeardownReport {
        val (receipt, claimed) = synchronized(lock) {
            teardown?.let { it to false } ?: CompletableDeferred<TeardownReport>().also {
                teardown = it
                ownership.invalidate()
            }.let { it to true }
        }
        if (!claimed) return withContext(NonCancellable) { receipt.await() }
        return withContext(NonCancellable) {
            val accepted = synchronized(lock) { persistenceSubmissions.toList() }
            for (work in accepted) {
                work.start()
                try { work.await().getOrThrow() }
                catch (failure: Exception) { record("acceptedPersistenceCompletion", failure) }
            }
            triggers.finish()
            val pendingHydration = synchronized(lock) { hydration }
            pendingHydration?.cancelAndJoin()
            synchronized(lock) { subscription?.close(); subscription = null; connectionTask }?.cancelAndJoin()
            dmQueue.shutdown()?.let { record("dmQueueCallback", it) }
            channelQueue.shutdown()?.let { record("channelQueueCallback", it) }
            for (id in synchronized(lock) { queued.toList() }) {
                try {
                    ownership.checkPersistenceCompletion("shutdownPending")
                    val row = dataStore.fetchMessage(key(id))
                    if (row?.status == MessageStatus.RETRYING || row?.status == MessageStatus.FAILED) remapPending(id)
                } catch (storage: PersistenceStoreException) { record("shutdownPending.$id", storage) }
                catch (stale: MessageServiceException) { record("shutdownPending.$id", stale) }
            }
            ownership.job.cancelAndJoin()
            persistenceJob.cancelAndJoin()
            val report = TeardownReport(synchronized(lock) {
                failures.values.map { TeardownIssue(LifecycleStage.STOP_SERVICES, it) }.snapshot()
            })
            receipt.complete(report)
            report
        }
    }

    data class Attempt(val postBumpCount: Long, val pendingID: UUID) { val preserveTimestamp: Boolean get() = postBumpCount > 1 }
    companion object {
        suspend fun preflightAndBump(store: MessagePersisting, key: EntityKey): Attempt? {
            if (!store.hasPendingSend(key)) return null
            val row = store.fetchPendingSendsForMessage(key).firstOrNull() ?: return null
            return store.incrementPendingSendAttemptCount(key)?.let { Attempt(it, row.id) }
        }
        fun isTransientDirectMessageError(failure: Throwable): Boolean = isTransientError(failure, 3u)
        fun isTransientChannelMessageError(failure: Throwable): Boolean = isTransientError(failure, 2u)
        fun isTransientError(failure: Throwable, deviceCode: UByte): Boolean = when (failure) {
            is MessageServiceException -> when (val error = failure.error) {
                is MessageServiceError.SessionError -> isTransientError(error.underlying, deviceCode)
                MessageServiceError.NotConnected -> true
                else -> false
            }
            is ChatSendQueueServiceException -> when (val error = failure.error) {
                is ChatSendQueueServiceError.PersistFailed -> isTransientError(error.underlying, deviceCode)
                ChatSendQueueServiceError.NotConnected -> true
            }
            is MeshCoreException.Timeout, is MeshCoreException.NotConnected, is MeshCoreException.ConnectionLost,
            is MeshCoreException.BluetoothPoweredOff, is MeshCoreException.SessionNotStarted -> true
            is MeshCoreException.DeviceError -> failure.code == deviceCode
            else -> false
        }
        fun isChannelMessageNotFound(failure: Throwable): Boolean = when (failure) {
            is MeshCoreException.DeviceError -> failure.code == 2.toUByte()
            is MessageServiceException -> (failure.error as? MessageServiceError.SessionError)?.underlying?.let(::isChannelMessageNotFound) == true
            else -> false
        }
    }
}
