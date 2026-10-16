package one.meshcore.shared.remotenode

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.ContactProtocolErrorCode
import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.remotenode.RemoteNodeService.LoginAttempt
import one.meshcore.shared.settings.CommandAuditLogger

/**
 * Logs in to a remote node. Registers a per-prefix continuation before sending so the `loginSuccess`
 * push can never be missed, derives the timeout from the firmware's suggested round-trip (reported via
 * [onTimeoutKnown]), and resolves on the push, timeout, or cancellation. A second login for the same
 * prefix cancels the first.
 */
suspend fun RemoteNodeService.login(
    sessionID: Uuid,
    password: String? = null,
    pathLength: Int = 0,
    onTimeoutKnown: (suspend (Int) -> Unit)? = null,
): LoginResult {
    val session = store.fetchSessionById(sessionID) ?: throw RemoteNodeError.SessionNotFound
    val pwd = resolvedLoginPassword(password, passwordStore.retrievePassword(session.publicKey))
    val prefix = session.publicKey.take(PUBLIC_KEY_PREFIX_LENGTH)
    val deferred = CompletableDeferred<LoginResult>()
    val pending = PendingLogin(session.id, deferred)
    auditLogger.logLoginRequest(auditTarget(session), session.publicKey, pathLength)

    mutex.withLock {
        pendingLogins.remove(prefix)?.let { existing ->
            logger.warning("Overwriting pending login for prefix ${prefixHex(prefix)}")
            pendingLoginTimeoutJobs.remove(prefix)?.cancel()
            existing.deferred.completeExceptionally(RemoteNodeError.Cancelled)
        }
        pendingLogins[prefix] = pending
    }
    logger.info(loginRegisteringPendingLine(prefixHex(prefix)))

    val timeoutJob = scope.launch(start = CoroutineStart.LAZY) {
        runLoginSendAndTimeout(
            LoginAttempt(
                sessionID,
                session.publicKey,
                session.radioID,
                prefix,
                pwd,
                pathLength,
                onTimeoutKnown,
            ),
        )
    }
    mutex.withLock { pendingLoginTimeoutJobs[prefix] = timeoutJob }
    timeoutJob.start()

    return try {
        deferred.await()
    } catch (e: CancellationException) {
        cancelPendingLogin(prefix, expected = pending)
        throw e
    }
}

/**
 * Sends the login; if the radio reports the contact is missing from its table (`notFound`), pushes the
 * local copy flood-routed and retries login once. A contact can be in the app database but absent on the
 * radio after a backup restore or radio swap. A full radio table surfaces [RemoteNodeError.RadioContactsFull].
 */
internal suspend fun RemoteNodeService.sendLoginHealingIfNeeded(
    publicKey: ByteArray,
    radioID: Uuid,
    password: String,
): MessageSentInfo = try {
    nodeSession.sendLogin(publicKey, password)
} catch (e: MeshError.DeviceError) {
    if (e.code != ContactProtocolErrorCode.NOT_FOUND) throw e
    addLocalContactToRadio(publicKey, radioID)
    // Bounded to a single retry: a second notFound after a successful add is a firmware inconsistency.
    nodeSession.sendLogin(publicKey, password)
}

internal suspend fun RemoteNodeService.hasPendingLogin(prefix: List<Byte>): Boolean =
    mutex.withLock { pendingLogins.containsKey(prefix) }

@Suppress("TooGenericExceptionCaught")
internal suspend fun RemoteNodeService.handleLoginResult(result: LoginResult, fromPrefix: ByteArray) {
    if (fromPrefix.size < PUBLIC_KEY_PREFIX_LENGTH) {
        logger.warning("Login result has invalid prefix length: ${fromPrefix.size}")
        return
    }
    val prefix = fromPrefix.take(PUBLIC_KEY_PREFIX_LENGTH)
    val hex = prefixHex(prefix)
    val pendingKeys = mutex.withLock { pendingLogins.keys.map { prefixHex(it) } }
    logger.info(handleLoginResultLookingLine(hex, pendingKeys))
    val pending = takePendingLogin(prefix, expected = null)
    if (pending == null) {
        logger.warning("Login result with no pending request. Prefix: $hex")
        return
    }
    logger.info(handleLoginResultFoundLine(hex))
    val deferred = pending.deferred
    suspend fun applyLoginResult() {
        if (!result.success) {
            // Resolve room vs repeater from the saved session so a failed room login isn't mis-audited.
            val target = store.fetchSessionById(pending.sessionID)?.let { auditTarget(it) }
                ?: CommandAuditLogger.Target.REPEATER
            auditLogger.logLoginFailed(target, prefix.toByteArray(), reason = "authentication failed")
            deferred.completeExceptionally(RemoteNodeError.LoginFailed("authentication failed"))
            return
        }
        val session = store.fetchSessionById(pending.sessionID)
        if (session == null) {
            logger.error("handleLoginResult: no session found for prefix $hex - database may be corrupted")
            deferred.complete(result)
            return
        }
        recordConnectedLogin(session, result, deferred)
    }
    try {
        applyLoginResult()
    } catch (cancelled: CancellationException) {
        deferred.completeExceptionally(cancelled)
        throw cancelled
    } catch (error: Throwable) {
        logger.error("handleLoginResult: failed to update session state: ${error.message ?: error}")
        if (!deferred.isCompleted) {
            if (result.success) {
                deferred.complete(result)
            } else {
                deferred.completeExceptionally(RemoteNodeError.LoginFailed("authentication failed"))
            }
        }
    }
}

/** A successful login marks the saved session connected and completes the waiting caller. */
private suspend fun RemoteNodeService.recordConnectedLogin(
    session: RemoteNodeSessionDto,
    result: LoginResult,
    deferred: CompletableDeferred<LoginResult>,
) {
    auditLogger.logLoginSuccess(auditTarget(session), session.publicKey, isAdmin = result.isAdmin)
    logger.info(handleLoginResultUpdatingLine(session.id, result.permissionLevel.raw))
    store.updateSessionConnection(session.id, isConnected = true, permissionLevel = result.permissionLevel)
    val verified = store.fetchSessionById(session.id)
    if (verified != null && !verified.isConnected) {
        logger.error(
            "handleLoginResult: session ${session.id} still shows isConnected=false after update!",
        )
    } else if (verified != null) {
        logger.info(handleLoginResultVerifiedLine(session.id))
    }
    emit(RemoteNodeEvent.SessionStateChanged(session.id, isConnected = true))
    deferred.complete(result)
}

internal fun RemoteNodeService.prefixHex(prefix: List<Byte>): String = prefix.toByteArray().toLowercaseHex()

internal suspend fun RemoteNodeService.cancelPendingLogin(prefix: List<Byte>, expected: PendingLogin?) {
    takePendingLogin(prefix, expected)?.deferred?.completeExceptionally(RemoteNodeError.Cancelled)
}

/** Removes the matching pending login and its timeout; null authority selects the current prefix entry. */
private suspend fun RemoteNodeService.takePendingLogin(prefix: List<Byte>, expected: PendingLogin?): PendingLogin? =
    mutex.withLock {
        if (expected != null && pendingLogins[prefix] !== expected) return@withLock null
        pendingLoginTimeoutJobs.remove(prefix)?.cancel()
        pendingLogins.remove(prefix)
    }

/**
 * Removes and returns the pending login for [prefix] and drops its timeout-job reference without
 * cancelling it. Used from inside the timeout job's own terminal paths, which must not self-cancel.
 */
internal suspend fun RemoteNodeService.detachPending(prefix: List<Byte>): PendingLogin? = mutex.withLock {
    pendingLoginTimeoutJobs.remove(prefix)
    pendingLogins.remove(prefix)
}

private fun resolvedLoginPassword(password: String?, stored: String?): String =
    password ?: stored ?: throw RemoteNodeError.PasswordNotFound
