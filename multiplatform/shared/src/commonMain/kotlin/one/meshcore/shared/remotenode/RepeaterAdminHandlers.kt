package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.meshcore.NeighboursResponse
import one.meshcore.shared.meshcore.StatusResponse
import one.meshcore.shared.meshcore.TelemetryResponse
import one.meshcore.shared.model.toLowercaseHex
import one.meshcore.shared.settings.CommandAuditLogger

/** Push handlers the status and settings screens register on a repeater admin service. */
interface RepeaterPushHandlers {
    fun setStatusHandler(handler: suspend (StatusResponse) -> Unit)

    fun setNeighboursHandler(handler: suspend (NeighboursResponse) -> Unit)

    fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit)

    fun setCLIHandler(handler: suspend (ContactMessage, ContactDto) -> Unit)

    suspend fun invokeStatusHandler(status: StatusResponse)

    suspend fun invokeNeighboursHandler(response: NeighboursResponse)

    suspend fun invokeTelemetryHandler(response: TelemetryResponse)

    suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDto)

    /** Clears every handler (full teardown). */
    fun clearHandlers()

    /**
     * Clears only the status-surface handlers so the merged admin surface can tear down its status segment
     * without dropping the settings view model's CLI handler on the shared per-connection service.
     */
    fun clearStatusHandlers()
}

internal class RepeaterAdminHandlers(edges: RepeaterAdminEdges) : RepeaterPushHandlers {
    private val auditLogger = edges.auditLogger
    private val logger = edges.logger
    private var statusHandler: (suspend (StatusResponse) -> Unit)? = null
    private var neighboursHandler: (suspend (NeighboursResponse) -> Unit)? = null
    private var telemetryHandler: (suspend (TelemetryResponse) -> Unit)? = null
    private var cliHandler: (suspend (ContactMessage, ContactDto) -> Unit)? = null

    override fun setStatusHandler(handler: suspend (StatusResponse) -> Unit) {
        statusHandler = handler
    }

    override fun setNeighboursHandler(handler: suspend (NeighboursResponse) -> Unit) {
        neighboursHandler = handler
    }

    override fun setTelemetryHandler(handler: suspend (TelemetryResponse) -> Unit) {
        telemetryHandler = handler
    }

    override fun setCLIHandler(handler: suspend (ContactMessage, ContactDto) -> Unit) {
        cliHandler = handler
    }

    override suspend fun invokeStatusHandler(status: StatusResponse) {
        auditLogger.logStatusResponse(
            CommandAuditLogger.Target.REPEATER,
            status.publicKeyPrefix,
            batteryMv = status.battery,
            uptimeSec = status.uptime,
        )
        val handler = statusHandler
        if (handler == null) {
            logger?.debug(
                "No status handler registered for response from ${status.publicKeyPrefix.toLowercaseHex()}, ignoring",
            )
            return
        }
        handler(status)
    }

    override suspend fun invokeNeighboursHandler(response: NeighboursResponse) {
        auditLogger.logNeighborsResponse(response.publicKeyPrefix, response.totalCount, response.neighbours.size)
        val handler = neighboursHandler
        if (handler == null) {
            logger?.debug(
                "No neighbours handler registered, ignoring response with ${response.neighbours.size} neighbours",
            )
            return
        }
        handler(response)
    }

    override suspend fun invokeTelemetryHandler(response: TelemetryResponse) {
        auditLogger.logTelemetryResponse(
            CommandAuditLogger.Target.REPEATER,
            response.publicKeyPrefix,
            pointCount = response.rawData.size,
        )
        val handler = telemetryHandler
        if (handler == null) {
            logger?.debug("No telemetry handler registered, ignoring response")
            return
        }
        handler(response)
    }

    override suspend fun invokeCLIHandler(message: ContactMessage, contact: ContactDto) {
        auditLogger.logCLIResponse(contact.publicKey, message.text)
        val handler = cliHandler
        if (handler == null) {
            logger?.debug("No CLI handler registered, ignoring response from ${contact.displayName}")
            return
        }
        handler(message, contact)
    }

    override fun clearHandlers() {
        statusHandler = null
        neighboursHandler = null
        telemetryHandler = null
        cliHandler = null
    }

    override fun clearStatusHandlers() {
        statusHandler = null
        neighboursHandler = null
        telemetryHandler = null
    }
}
