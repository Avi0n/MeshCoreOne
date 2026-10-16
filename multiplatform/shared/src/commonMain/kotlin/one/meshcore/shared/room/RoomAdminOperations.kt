package one.meshcore.shared.room

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.remotenode.NodeCliCoordinator
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteOperationTimeoutPolicy
import one.meshcore.shared.remotenode.cliBodyClaimedByWaiter
import one.meshcore.shared.remotenode.isAdmin
import one.meshcore.shared.remotenode.isRoom
import one.meshcore.shared.settings.CommandAuditLogger
import one.meshcore.shared.settings.PersistentLogger
import kotlin.time.Duration

/** Status and telemetry reads. Any connected viewer may call these; there is no admin gate. */
interface RoomStatusRequests {
    /** Requests a room status frame. Any connected viewer may read status; no admin gate. */
    suspend fun requestStatus(sessionID: Uuid, timeout: Duration? = null): StatusResponse

    /** Requests a room telemetry frame. Any connected viewer may read telemetry; no admin gate. */
    suspend fun requestTelemetry(sessionID: Uuid, timeout: Duration? = null): TelemetryResponse
}

internal class RoomAdminStatusClient(private val adminSession: RoomAdminSession, private val store: RoomStore) :
    RoomStatusRequests {
    override suspend fun requestStatus(sessionID: Uuid, timeout: Duration?): StatusResponse =
        requestFrame(sessionID, timeout, adminSession::requestStatus)

    override suspend fun requestTelemetry(sessionID: Uuid, timeout: Duration?): TelemetryResponse =
        requestFrame(sessionID, timeout, adminSession::requestTelemetry)

    /** A missing session fails before the radio call. A null timeout uses the binary maximum. */
    private suspend fun <T> requestFrame(
        sessionID: Uuid,
        timeout: Duration?,
        read: suspend (publicKey: ByteArray, timeout: Duration) -> T,
    ): T {
        val session = store.fetchSession(sessionID) ?: throw RoomServerError.SessionNotFound
        return read(session.publicKey, timeout ?: RemoteOperationTimeoutPolicy.BINARY_MAXIMUM)
    }
}

/** Admin-gated room CLI. Configuration commands require an admin room session. */
interface RoomAdminCli {
    /** Sends a content-matched CLI command. Requires the session to be an admin room, else permission denied. */
    suspend fun sendCommand(
        sessionID: Uuid,
        command: String,
        timeout: Duration = RemoteOperationTimeoutPolicy.DEFAULT_CLI,
    ): String

    /** Sends a FIFO-matched raw CLI command. Requires the session to be an admin room, else permission denied. */
    suspend fun sendRawCommand(
        sessionID: Uuid,
        command: String,
        timeout: Duration = RemoteOperationTimeoutPolicy.DEFAULT_CLI,
    ): String
}

internal class RoomAdminCliClient(private val adminSession: RoomAdminSession, private val store: RoomStore) :
    RoomAdminCli {
    override suspend fun sendCommand(sessionID: Uuid, command: String, timeout: Duration): String {
        val session = requireAdminSession(sessionID)
        return adminSession.sendCommand(session.publicKey, command, timeout)
    }

    override suspend fun sendRawCommand(sessionID: Uuid, command: String, timeout: Duration): String {
        val session = requireAdminSession(sessionID)
        return adminSession.sendRawCommand(session.publicKey, command, timeout)
    }

    private suspend fun requireAdminSession(sessionID: Uuid): RemoteNodeSessionDto {
        val session = store.fetchSession(sessionID) ?: throw RoomServerError.SessionNotFound
        if (!session.isRoom || !session.isAdmin) throw RemoteNodeError.PermissionDenied
        return session
    }
}

/**
 * Status, telemetry, and CLI handler slots. The three slots stay behind one mutex, and a status
 * teardown can drop its two handlers without clearing the CLI handler.
 */
interface RoomAdminHandlers {
    suspend fun setStatusHandler(handler: suspend (StatusResponse) -> Unit)

    suspend fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit)

    suspend fun setCLIHandler(handler: suspend (ContactDto, String) -> Unit)

    /**
     * Reply body the in-flight waiter just accepted, or null when this fan-out is uncorrelated.
     * Only meaningful inside the CLI handler: the coordinator clears it when fan-out returns.
     */
    fun cliBodyClaimedByWaiter(): String?

    /** Folds a status frame through the registered handler, if any. */
    suspend fun invokeStatusHandler(status: StatusResponse)

    /** Folds a telemetry frame through the registered handler, if any. */
    suspend fun invokeTelemetryHandler(response: TelemetryResponse)

    /** Folds a late CLI response through the registered handler, if any. */
    suspend fun invokeCLIHandler(contact: ContactDto, response: String)

    /** Clears every handler; called on true teardown. */
    suspend fun clearHandlers()

    /**
     * Clears only the status-surface handlers so a merged status/settings surface can tear down its status
     * segment without dropping the settings view model's CLI handler on the shared per-connection service.
     */
    suspend fun clearStatusHandlers()
}

internal class RoomAdminHandlerSlots(
    private val cliCoordinator: NodeCliCoordinator?,
    private val auditLogger: CommandAuditLogger,
    private val logger: PersistentLogger?,
) : RoomAdminHandlers {
    private val mutex = Mutex()
    private var statusHandler: (suspend (StatusResponse) -> Unit)? = null
    private var telemetryHandler: (suspend (TelemetryResponse) -> Unit)? = null
    private var cliHandler: (suspend (ContactDto, String) -> Unit)? = null

    override suspend fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) =
        mutex.withLock { statusHandler = handler }

    override suspend fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit) =
        mutex.withLock { telemetryHandler = handler }

    override suspend fun setCLIHandler(handler: suspend (ContactDto, String) -> Unit) =
        mutex.withLock { cliHandler = handler }

    override fun cliBodyClaimedByWaiter(): String? = cliCoordinator?.cliBodyClaimedByWaiter()

    override suspend fun invokeStatusHandler(status: StatusResponse) {
        auditLogger.logStatusResponse(
            CommandAuditLogger.Target.ROOM,
            status.publicKeyPrefix,
            batteryMv = status.battery,
            uptimeSec = status.uptime,
        )
        val handler = mutex.withLock { statusHandler }
        if (handler == null) {
            logger?.debug(
                "No status handler registered for room response from " +
                    "${status.publicKeyPrefix.toLowercaseHex()}, ignoring",
            )
            return
        }
        handler(status)
    }

    override suspend fun invokeTelemetryHandler(response: TelemetryResponse) {
        auditLogger.logTelemetryResponse(
            CommandAuditLogger.Target.ROOM,
            response.publicKeyPrefix,
            pointCount = response.points.size,
        )
        val handler = mutex.withLock { telemetryHandler }
        if (handler == null) {
            logger?.debug("No telemetry handler registered for room, ignoring response")
            return
        }
        handler(response)
    }

    override suspend fun invokeCLIHandler(contact: ContactDto, response: String) {
        auditLogger.logCLIResponse(contact.publicKey, response)
        val handler = mutex.withLock { cliHandler }
        if (handler == null) {
            logger?.debug("No CLI handler registered for room, ignoring response from ${contact.displayName}")
            return
        }
        handler(contact, response)
    }

    override suspend fun clearHandlers() = mutex.withLock {
        statusHandler = null
        telemetryHandler = null
        cliHandler = null
    }

    override suspend fun clearStatusHandlers() = mutex.withLock {
        statusHandler = null
        telemetryHandler = null
    }
}
