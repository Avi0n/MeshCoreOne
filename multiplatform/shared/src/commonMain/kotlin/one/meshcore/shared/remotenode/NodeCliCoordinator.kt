package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.floodedContactFrame
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.meshcore.GetMessageResult
import one.meshcore.shared.model.TextType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingException
import one.meshcore.shared.runReportingThrowable
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.PersistentLogger
import kotlin.time.Duration

/** Hard-maximum wait for a CLI response when the caller does not override it. */
internal val DEFAULT_CLI_TIMEOUT: Duration = RemoteOperationTimeoutPolicy.DEFAULT_CLI

/** Firmware `TXT_TYPE_CLI_DATA`: inbound contact messages with this raw byte are CLI responses. */
internal val CLI_RESPONSE_TEXT_TYPE: Int = TextType.CLI_DATA.raw

/** Mask for the cycling CLI wire prefix ("00|" through "FF|"). */
internal const val CLI_PREFIX_MASK = 0xFF

/**
 * The admin CLI request/response layer for remote nodes, mirroring the Swift `RemoteNodeService+CLI`.
 * Kept a distinct collaborator (rather than folded into [RemoteNodeService]) so its own inbound-message
 * subscription and pending-request state stay out of the login/keep-alive service; it shares the same
 * [RemoteNodeStore] and [NodeSession] seams.
 *
 * Exactly one CLI command is in flight per node (a FIFO slot); a second command for the same node queues
 * behind the first. Each command carries a cycling wire prefix ("XX|") that firmware reflects at the start
 * of the reply, so replies attribute deterministically ([CLIResponse.splitEchoedPrefix]); a reply echoing a
 * different prefix is stale and dropped. Firmware that predates the echo sends an unprefixed reply, matched
 * by shape ([CLIResponse.isPlausibleResponse]) unless the command accepts any response (raw passthrough).
 *
 * A pending request is registered before the command is sent, so the response push can never be missed.
 * The waiter then pumps [NodeSession.getMessage] until the reply (or the timeout), matching Swift
 * `performCLIExchange`: firmware holds CLI frames until GET_MSG, and auto-fetch may not be running
 * yet between Connected and Ready. The [CompletableDeferred] is completed by the event monitor when
 * that drain (or a live push) yields `contactMessageReceived`.
 */
class NodeCliCoordinator(
    private val nodeSession: NodeSession,
    internal val store: RemoteNodeStore,
    private val passwordStore: NodePasswordStore,
    private val scope: CoroutineScope,
    internal val auditLogger: CommandAuditLogger = CommandAuditLogger(),
    internal val logger: PersistentLogger? = null,
) {
    internal class PendingCliRequest(
        val id: Long,
        val command: String,
        val wirePrefix: String,
        val acceptsAnyResponse: Boolean,
        val deferred: CompletableDeferred<String>,
    )

    internal val mutex = Mutex()

    /** The single in-flight CLI request per node, keyed by 6-byte public key prefix. */
    internal val pendingCLIRequests = mutableMapOf<List<Byte>, PendingCliRequest>()

    /**
     * Per-node fair binary semaphore serializing CLI commands, so exactly one is in flight per node and a
     * second queues FIFO behind it. A fair [Semaphore] plus [withPermit] releases the slot even when the
     * waiting coroutine is cancelled, so a cancelled queued command can never wedge the node's slot.
     */
    internal val cliSlots = mutableMapOf<List<Byte>, Semaphore>()

    private var nextRequestId = 0L

    /** Cycling counter for CLI wire prefixes ("00|" through "FF|"). */
    internal var cliPrefixCounter = 0
    private var eventMonitorJob: Job? = null

    /** Copy-on-write list of inbound-CLI observers; see [addCLIResponseObserver]. */
    internal var cliResponseObservers:
        List<suspend (ContactMessage, RemoteNodeSessionDto, ContactDto) -> Unit> = emptyList()

    /**
     * Body the in-flight waiter just accepted, visible only while [notifyCLIResponseObservers] runs.
     * That fan-out runs before the waiter records the text, so a settings screen can leave this reply
     * to the waiter instead of storing it on an older unanswered query.
     */
    internal var claimedCliBody: String? = null

    init {
        startEventMonitoring()
    }

    /** Starts (or restarts) the collector that routes inbound CLI responses to pending requests. */
    fun startEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = scope.launch {
            nodeSession.events.collect { event ->
                deliverCliEvent(
                    deliver = { handleEvent(event) },
                    logWarning = { line -> logger?.warning(line) },
                )
            }
        }
    }

    /** Stops the collector so it releases the collaborators it captures. */
    fun stopEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = null
    }

    /**
     * Runs [operation], and on a direct-path mesh timeout resets the contact path to flood and runs it once
     * more. Already-flood contacts skip the reset; a failed reset rethrows the original timeout. This is the
     * CLI-only path recovery — status/telemetry section recovery lives in the shared core, not here.
     */
    internal suspend fun <T> performWithDirectPathFloodRecovery(
        radioID: Uuid,
        publicKey: ByteArray,
        operation: suspend () -> T,
    ): T {
        fun rethrowRetry(retryError: Throwable): Nothing {
            if (isMeshTimeout(retryError)) {
                logger?.warning("remoteCLI: flood retry also timed out")
            }
            throw retryError
        }

        // A failed flood retry keeps its failure. A mesh timeout is logged first. Cancellation still propagates.
        suspend fun retryOperation(): T = runFloodAttempt(operation) { retryError ->
            rethrowRetry(retryError)
        }

        // A failed path reset is logged and the original timeout is rethrown. Cancellation still propagates.
        suspend fun resetOrKeepOriginal(original: Throwable) {
            runFloodAttempt(
                operation = { resetContactPathToFlood(radioID, publicKey) },
            ) { resetError ->
                logger?.warning(
                    "remoteCLI: path reset failed (${resetError.message ?: resetError}); not retrying",
                )
                throw original
            }
        }

        suspend fun recover(original: Throwable): T {
            if (!isMeshTimeout(original) || !isDirectRouted(radioID, publicKey)) throw original
            logger?.info("remoteCLI: direct-path timeout; resetting path to flood and retrying once")
            resetOrKeepOriginal(original)
            return retryOperation()
        }

        return runFloodAttempt(operation) { error -> recover(error) }
    }

    // A failed first attempt is passed to recovery. Cancellation still propagates.
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> runFloodAttempt(operation: suspend () -> T, recover: suspend (Throwable) -> T): T = try {
        operation()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Throwable) {
        recover(error)
    }

    /** True when the error is a mesh wait timeout (session-level or wrapped). */
    private fun isMeshTimeout(error: Throwable): Boolean = when (error) {
        is RemoteNodeError.Timeout, is MeshError.Timeout -> true
        is RemoteNodeError.SessionError -> error.error is MeshError.Timeout
        else -> false
    }

    /** Clears the companion out-path via [NodeSession.resetPath] and mirrors flood on the local contact row. */
    @OptIn(kotlin.time.ExperimentalTime::class)
    internal suspend fun resetContactPathToFlood(radioID: Uuid, publicKey: ByteArray) {
        nodeSession.resetPath(publicKey)
        val contact = store.fetchContact(radioID, publicKey) ?: return
        val frame = contact.floodedContactFrame(kotlin.time.Clock.System.now().epochSeconds)
        // A failed local save is logged. The radio path is already flood. Cancellation still propagates.
        runReportingThrowable(
            onFailure = { error ->
                // Radio path is already flood; a local save failure must not abort the retry.
                logger?.warning(
                    "Path reset on radio but failed to sync local contact: ${error.message ?: error}",
                )
            },
        ) {
            store.saveContact(radioID, frame)
        }
    }

    internal suspend fun performCLIExchange(
        publicKey: ByteArray,
        prefix: List<Byte>,
        command: String,
        acceptsAnyResponse: Boolean,
        timeout: Duration,
    ): String {
        val deferred = CompletableDeferred<String>()
        // The request id and wire prefix are minted under the lock that registers the pending request, so
        // concurrent commands to different nodes can never mint duplicate ids or wire prefixes.
        val request = mutex.withLock {
            PendingCliRequest(++nextRequestId, command, makeCLIWirePrefix(), acceptsAnyResponse, deferred).also {
                pendingCLIRequests[prefix] = it
            }
        }

        suspend fun awaitReply(effective: Duration): String = try {
            coroutineScope {
                val pump = launch {
                    try {
                        pumpGetMessageUntil(deferred)
                    } catch (cancelled: CancellationException) {
                        deferred.completeExceptionally(cancelled)
                        throw cancelled
                    }
                }
                try {
                    withTimeoutOrNull(effective) { deferred.await() } ?: run {
                        takePendingCLIRequest(prefix, request.id)
                        throw RemoteNodeError.Timeout
                    }
                } finally {
                    pump.cancel()
                }
            }
        } catch (e: CancellationException) {
            takePendingCLIRequest(prefix, request.id)
            throw e
        }

        // A failed CLI send drops the pending request and maps the failure. Cancellation drops it and propagates.
        @Suppress("TooGenericExceptionCaught")
        suspend fun sendRegistered() = try {
            nodeSession.sendCommand(publicKey, request.wirePrefix + command)
        } catch (cancelled: CancellationException) {
            takePendingCLIRequest(prefix, request.id)
            throw cancelled
        } catch (error: Throwable) {
            takePendingCLIRequest(prefix, request.id)
            throw mapSendError(error)
        }

        val sentInfo = sendRegistered()
        return awaitReply(RemoteOperationTimeoutPolicy.cliTimeout(sentInfo, timeout))
    }

    /**
     * Dequeues inbound frames until the pending CLI deferred completes. Firmware holds CLI replies
     * until GET_MSG; ignoring the result matches Swift, which relies on the event dispatcher fan-out.
     * An empty queue is paced at [RemoteOperationTimeoutPolicy.POLL_INTERVAL] so fakes cannot busy-loop.
     */
    private suspend fun pumpGetMessageUntil(deferred: CompletableDeferred<String>) {
        while (!deferred.isCompleted) {
            val result = pollCliGetMessage(
                read = { nodeSession.getMessage() },
                logWarning = { line -> logger?.warning(line) },
            )
            if (deferred.isCompleted) return
            if (result == null || result is GetMessageResult.NoMoreMessages) {
                delay(RemoteOperationTimeoutPolicy.POLL_INTERVAL)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun handlePasswordChangeIfNeeded(command: String, sessionID: Uuid, publicKey: ByteArray) {
        val lower = command.trim().lowercase()
        if (!(lower.startsWith("password ") && !lower.contains("guest.password"))) return
        // A failed password clear is logged. Cancellation still propagates.
        try {
            passwordStore.deletePassword(publicKey)
            logger?.info(clearedStoredPasswordAfterPasswordChangeLine(sessionID))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger?.warning(failedToClearStoredPasswordLine(sessionID, error.message ?: error.toString()))
        }
    }
}

/**
 * Delivers one inbound CLI event. A handler fault is logged so the reply collector stays up.
 * Cancellation and an Error still stop it.
 */
internal suspend fun deliverCliEvent(deliver: suspend () -> Unit, logWarning: (String) -> Unit) {
    runReportingException(
        onFailure = { error ->
            logWarning("remoteCLI: event handler failed: ${error.message ?: error}")
        },
    ) {
        deliver()
    }
}

/**
 * Reads one CLI backlog frame. A read fault is logged and returned as null so the pump keeps
 * waiting. Cancellation and an Error still stop the pump.
 */
internal suspend fun pollCliGetMessage(
    read: suspend () -> GetMessageResult,
    logWarning: (String) -> Unit,
): GetMessageResult? = runReportingException(
    onFailure = { error ->
        logWarning("remoteCLI: get message failed: ${error.message ?: error}")
        null
    },
) {
    read()
}
