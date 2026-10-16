package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.ContactProtocolErrorCode
import one.meshcore.shared.contact.floodedContactFrame
import one.meshcore.shared.contact.toMeshContact
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.RoomPermissionLevel
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.runReportingThrowable
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.PersistentLogger
import kotlin.math.ceil
import kotlin.math.max
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.DurationUnit

private const val EVENT_BUFFER: Int = 32

/**
 * Per-connection remote-node authentication, keep-alive, and status, mirroring Swift `RemoteNodeService`.
 * Login resolves push-driven replies with per-prefix continuations and timeouts, heals missing radio
 * contacts, and re-authenticates saved sessions after BLE reconnection.
 *
 * One instance per live connection (rebuilt on reconnect, torn down on disconnect). Pending logins and
 * keep-alive jobs are protected by [mutex]; background work runs on the injected [scope]. Login-result
 * pushes are routed by a collector over [NodeSession.events], started on construction.
 */
class RemoteNodeService(
    internal val nodeSession: NodeSession,
    internal val store: RemoteNodeStore,
    internal val passwordStore: NodePasswordStore,
    internal val scope: CoroutineScope,
    options: RemoteNodeServiceOptions = RemoteNodeServiceOptions(),
    internal val logger: PersistentLogger = PersistentLogger(scope, category = "RemoteNodeService"),
) {
    internal val newId = options.newId
    internal val auditLogger = options.auditLogger
    internal val mutex = Mutex()
    internal val pendingLogins = mutableMapOf<List<Byte>, PendingLogin>()
    internal val pendingLoginTimeoutJobs = mutableMapOf<List<Byte>, Job>()
    internal val keepAliveJobs = mutableMapOf<Uuid, Job>()

    /** Reentrancy guard so overlapping BLE reconnects don't double re-authenticate. */
    private var isReauthenticating = false

    private val _events = MutableSharedFlow<RemoteNodeEvent>(extraBufferCapacity = EVENT_BUFFER)

    /** Multicast stream of session connection-state changes; every subscriber sees every event. */
    val events: SharedFlow<RemoteNodeEvent> = _events.asSharedFlow()

    internal var eventMonitorJob: Job? = null
    internal val connectionRetired = CompletableDeferred<Unit>()

    init {
        startEventMonitoring()
    }

    /** Stops the login-result collector so it releases the collaborators it captures. */
    fun stopEventMonitoring() {
        eventMonitorJob?.cancel()
        eventMonitorJob = null
    }

    /** The inputs of one login send-and-wait attempt, grouped so the coroutine body takes a single value. */
    internal data class LoginAttempt(
        val sessionID: Uuid,
        val publicKey: ByteArray,
        val radioID: Uuid,
        val prefix: List<Byte>,
        val password: String,
        val pathLength: Int,
        val onTimeoutKnown: (suspend (Int) -> Unit)?,
    )

    internal suspend fun runLoginSendAndTimeout(attempt: LoginAttempt) {
        // A failed login send completes the pending login with the mapped error and skips the wait.
        // Cancellation still propagates.
        val sentInfo = runReportingThrowable(
            onFailure = { error ->
                detachPending(attempt.prefix)?.deferred?.completeExceptionally(mapSendError(error))
                null
            },
        ) {
            sendLoginHealingIfNeeded(attempt.publicKey, attempt.radioID, attempt.password)
        } ?: return

        val timeout = RemoteOperationTimeoutPolicy.loginTimeout(sentInfo, attempt.pathLength)
        val prefixHex = prefixHex(attempt.prefix)
        logger.info(loginSendSucceededLine(timeout, prefixHex))
        attempt.onTimeoutKnown?.invoke(max(1, ceil(timeout.toDouble(DurationUnit.SECONDS)).toInt()))
        val consumed = retransmitLoginWhileWaiting(
            attempt.publicKey,
            attempt.prefix,
            attempt.password,
            timeout,
            sentInfo,
        )
        val pending = detachPending(attempt.prefix)
        if (pending != null) {
            logger.warning("Login timeout after $timeout for session ${attempt.sessionID}, prefix $prefixHex")
            pending.deferred.completeExceptionally(RemoteNodeError.Timeout)
        } else if (!consumed) {
            logger.info(loginTimeoutAlreadyConsumedLine(prefixHex))
        }
    }

    /**
     * Retransmits the login at [RemoteOperationTimeoutPolicy.LOGIN_RETRANSMIT_INTERVAL] spacing (raised to the
     * firmware-suggested round-trip) until the login deadline, so a multi-hop login whose first packet drops
     * is retried in-window instead of failing the whole attempt. Returns early when the `loginSuccess` push
     * consumes the continuation; cancellation (a duplicate login or shutdown) ends the loop through [delay].
     * Returns true when the login push consumed the continuation before the deadline.
     */
    private suspend fun retransmitLoginWhileWaiting(
        publicKey: ByteArray,
        prefix: List<Byte>,
        password: String,
        timeout: Duration,
        sentInfo: MessageSentInfo,
    ): Boolean {
        val retransmitInterval = maxOf(
            RemoteOperationTimeoutPolicy.LOGIN_RETRANSMIT_INTERVAL,
            sentInfo.suggestedTimeoutMs.milliseconds,
        )
        var remaining = timeout
        var retransmitCount = 0
        while (remaining > Duration.ZERO) {
            val sleepFor = if (remaining < retransmitInterval) remaining else retransmitInterval
            delay(sleepFor)
            remaining -= sleepFor
            if (!hasPendingLogin(prefix)) {
                logger.info(loginContinuationConsumedLine(prefixHex(prefix)))
                return true
            }
            if (remaining <= Duration.ZERO) break
            retransmitCount += 1
            // A failed login retransmit is logged and the wait continues. Cancellation still propagates.
            runReportingThrowable(
                onFailure = { error ->
                    logger.warning(
                        "login: retransmit #$retransmitCount failed for ${prefixHex(prefix)}: " +
                            "${error.message ?: error}",
                    )
                },
            ) {
                logger.info(loginRetransmitLine(retransmitCount, prefixHex(prefix)))
                nodeSession.sendLogin(publicKey, password)
                Unit
            }
        }
        return false
    }

    /**
     * Pushes the local contact to the radio's contact table flood-routed, then reconciles the local row so
     * keep-alive routing agrees with the radio. A full table surfaces [RemoteNodeError.RadioContactsFull].
     */
    @OptIn(kotlin.time.ExperimentalTime::class)
    internal suspend fun addLocalContactToRadio(publicKey: ByteArray, radioID: Uuid) {
        val contact = store.fetchContact(radioID, publicKey) ?: throw RemoteNodeError.ContactNotFound
        val frame = contact.floodedContactFrame(kotlin.time.Clock.System.now().epochSeconds)
        try {
            nodeSession.addContact(frame.toMeshContact())
        } catch (e: MeshError.DeviceError) {
            contactAddFailure(e)
        }
        // The radio is healed; failing to sync the local row is bookkeeping that must not abort the retry.
        // Cancellation still propagates.
        runReportingThrowable(
            onFailure = { error ->
                logger.warning(
                    "Re-added contact to radio but failed to sync local row: ${error.message ?: error}",
                )
            },
        ) {
            store.saveContact(radioID, frame)
        }
        logger.info(RE_ADDED_MISSING_CONTACT_DURING_LOGIN)
    }

    /** Explicitly logs out: stops keep-alive, best-effort sends logout, and downgrades to guest. */
    suspend fun logout(sessionID: Uuid) = withConnectionOperation {
        val session = store.fetchSessionById(sessionID) ?: throw RemoteNodeError.SessionNotFound
        stopKeepAlive(sessionID)
        auditLogger.logLogout(auditTarget(session), session.publicKey)
        // A failed logout send is logged and the session still downgrades to guest. Cancellation still propagates.
        runReportingThrowable(
            onFailure = { error ->
                logger.info(logoutSendFailedLine(error.message ?: error.toString()))
            },
        ) {
            nodeSession.sendLogout(session.publicKey)
        }
        store.updateSessionConnection(sessionID, isConnected = false, permissionLevel = RoomPermissionLevel.GUEST)
        currentCoroutineContext().ensureActive()
        emit(RemoteNodeEvent.SessionStateChanged(sessionID, isConnected = false))
    }

    internal data class TickResult(val shouldContinue: Boolean, val consecutiveFailures: Int)

    internal suspend fun performKeepAliveTick(sessionID: Uuid, publicKey: ByteArray, failures: Int): TickResult {
        // A failed keep-alive follows the retry policy. Cancellation still propagates.
        return runReportingThrowable(
            onFailure = { error ->
                val eval = KeepAliveRetryPolicy.evaluate(error, failures)
                when (eval.action) {
                    KeepAliveRetryPolicy.Action.SKIP ->
                        logger.info("Skipping keep-alive for flood-routed session $sessionID")
                    KeepAliveRetryPolicy.Action.RETRY_NEXT_INTERVAL -> {
                        val reason = KeepAliveRetryPolicy.failureReason(error)
                        logger.warning(
                            "Keep-alive ${eval.consecutiveFailures}/${KeepAliveRetryPolicy.MAX_CONSECUTIVE_FAILURES} " +
                                "failed for $sessionID: $reason",
                        )
                    }
                    KeepAliveRetryPolicy.Action.DISCONNECT, KeepAliveRetryPolicy.Action.DISCONNECT_NOW -> {
                        val reason = KeepAliveRetryPolicy.failureReason(error)
                        logger.warning("Keep-alive failed for $sessionID: $reason")
                        persistDisconnected(sessionID)
                        emit(RemoteNodeEvent.SessionStateChanged(sessionID, isConnected = false))
                    }
                    KeepAliveRetryPolicy.Action.STOP -> Unit
                }
                TickResult(
                    shouldContinue = !eval.action.shouldExitLoop,
                    consecutiveFailures = eval.consecutiveFailures,
                )
            },
        ) {
            sendKeepAliveIfDirectRouted(sessionID, publicKey)
            TickResult(shouldContinue = true, consecutiveFailures = 0)
        }
    }

    /**
     * Called when the BLE link is re-established. Re-authenticates the sessions that were connected before
     * the drop (from [handleBLEDisconnection]). An empty set (e.g. after an app restart) re-auths nothing;
     * the user reconnects manually. A re-auth that returns a lower permission than before, or that fails,
     * marks the session disconnected rather than silently downgrading privileges. Reentrancy-guarded.
     */
    suspend fun handleBLEReconnection(sessionIDs: Set<Uuid>) {
        if (isReauthenticating || sessionIDs.isEmpty()) return

        // A failed session read is logged and that session is skipped. Cancellation still propagates.
        suspend fun sessionOrNull(id: Uuid): RemoteNodeSessionDto? = runReportingThrowable(
            onFailure = { _ ->
                logger.warning("Session $id not found for re-auth, skipping")
                null
            },
        ) {
            store.fetchSessionById(id)
        }

        val sessions = sessionIDs.mapNotNull { id -> sessionOrNull(id) }
        if (sessions.isEmpty()) return
        logger.info("BLE reconnection: re-authenticating ${sessions.size} session(s)")

        isReauthenticating = true
        try {
            coroutineScope {
                for (session in sessions) {
                    launch { reauthenticate(session) }
                }
            }
        } finally {
            isReauthenticating = false
        }
    }

    @Suppress("TooGenericExceptionCaught")
    internal suspend fun reauthenticate(session: RemoteNodeSessionDto) = withConnectionOperation {
        val previousPermission = session.permissionLevel
        // A failed re-auth is logged and the session is marked disconnected. Cancellation still propagates.
        try {
            val result = login(session.id)
            if (result.permissionLevel < previousPermission) {
                logger.warning(
                    "Re-auth returned degraded permission for session ${session.id}: " +
                        "$previousPermission -> ${result.permissionLevel}, marking disconnected",
                )
                markDisconnectedWithEvent(session.id)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Throwable) {
            logger.warning("Re-auth failed for session ${session.id}: ${error.message ?: error}")
            markDisconnectedWithEvent(session.id)
        }
    }

    internal suspend fun persistDisconnected(sessionID: Uuid, markSessionLine: Boolean = false) {
        // A failed disconnect write is logged. Cancellation still propagates.
        runReportingThrowable(
            onFailure = { error ->
                val line = if (markSessionLine) {
                    "Failed to mark session $sessionID disconnected: ${error.message ?: error}"
                } else {
                    "Failed to persist disconnected state for session $sessionID: ${error.message ?: error}"
                }
                logger.error(line)
            },
        ) {
            store.markSessionDisconnected(sessionID)
        }
    }

    internal fun emit(event: RemoteNodeEvent) {
        _events.tryEmit(event)
    }
}

private fun contactAddFailure(error: MeshError.DeviceError): Nothing {
    if (error.code == ContactProtocolErrorCode.TABLE_FULL) throw RemoteNodeError.RadioContactsFull
    throw error
}

data class RemoteNodeServiceOptions(
    val newId: () -> Uuid = Uuid::random,
    val auditLogger: CommandAuditLogger = CommandAuditLogger(),
)
