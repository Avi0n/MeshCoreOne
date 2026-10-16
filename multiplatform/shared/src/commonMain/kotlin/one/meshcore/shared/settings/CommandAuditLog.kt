package one.meshcore.shared.settings

import one.meshcore.shared.model.Uuid

/** Login, status, telemetry, and keep-alive lines recorded by [CommandAuditLogger]. */
interface CommandAuditSession {
    suspend fun logLoginRequest(target: CommandAuditLogger.Target, publicKey: ByteArray, pathLength: Int)

    suspend fun logLoginSuccess(target: CommandAuditLogger.Target, publicKey: ByteArray, isAdmin: Boolean)

    suspend fun logLoginFailed(target: CommandAuditLogger.Target, publicKey: ByteArray, reason: String)

    suspend fun logLogout(target: CommandAuditLogger.Target, publicKey: ByteArray)

    suspend fun logStatusRequest(target: CommandAuditLogger.Target, publicKey: ByteArray)

    suspend fun logStatusResponse(
        target: CommandAuditLogger.Target,
        publicKey: ByteArray,
        batteryMv: Int?,
        uptimeSec: Long?,
    )

    suspend fun logTelemetryRequest(target: CommandAuditLogger.Target, publicKey: ByteArray)

    suspend fun logTelemetryResponse(target: CommandAuditLogger.Target, publicKey: ByteArray, pointCount: Int)

    suspend fun logKeepAlive(target: CommandAuditLogger.Target, publicKey: ByteArray)
}

/** CLI, neighbor, and room-message lines recorded by [CommandAuditLogger]. */
interface CommandAuditTraffic {
    suspend fun logCLICommand(publicKey: ByteArray, command: String)

    suspend fun logCLIResponse(publicKey: ByteArray, response: String)

    suspend fun logNeighborsRequest(publicKey: ByteArray, count: Int, offset: Int)

    suspend fun logNeighborsResponse(publicKey: ByteArray, totalCount: Int, returnedCount: Int)

    suspend fun logRoomMessagePosted(publicKey: ByteArray, messageLength: Int)

    suspend fun logRoomMessageReceived(roomPublicKey: ByteArray, authorPrefix: ByteArray, messageLength: Int)
}

internal fun commandAuditSession(
    nowMs: () -> Long,
    newId: () -> Uuid,
    record: suspend (DebugLogEntryDto) -> Unit,
): CommandAuditSession = CommandAuditSessionRecorder(
    info = { message -> recordAudit(record, nowMs, newId, DebugLogLevel.INFO, message) },
    warn = { message -> recordAudit(record, nowMs, newId, DebugLogLevel.WARNING, message) },
)

internal fun commandAuditTraffic(
    nowMs: () -> Long,
    newId: () -> Uuid,
    record: suspend (DebugLogEntryDto) -> Unit,
): CommandAuditTraffic = CommandAuditTrafficRecorder(
    info = { message -> recordAudit(record, nowMs, newId, DebugLogLevel.INFO, message) },
)

private class CommandAuditSessionRecorder(
    private val info: suspend (String) -> Unit,
    private val warn: suspend (String) -> Unit,
) : CommandAuditSession {
    override suspend fun logLoginRequest(target: CommandAuditLogger.Target, publicKey: ByteArray, pathLength: Int) {
        info(CommandAuditLogger.Formats.loginRequest(target, publicKey, pathLength))
    }

    override suspend fun logLoginSuccess(target: CommandAuditLogger.Target, publicKey: ByteArray, isAdmin: Boolean) {
        info(CommandAuditLogger.Formats.loginSuccess(target, publicKey, isAdmin))
    }

    override suspend fun logLoginFailed(target: CommandAuditLogger.Target, publicKey: ByteArray, reason: String) {
        warn(CommandAuditLogger.Formats.loginFailed(target, publicKey, reason))
    }

    override suspend fun logLogout(target: CommandAuditLogger.Target, publicKey: ByteArray) {
        info(CommandAuditLogger.Formats.logout(target, publicKey))
    }

    override suspend fun logStatusRequest(target: CommandAuditLogger.Target, publicKey: ByteArray) {
        info(CommandAuditLogger.Formats.statusRequest(target, publicKey))
    }

    override suspend fun logStatusResponse(
        target: CommandAuditLogger.Target,
        publicKey: ByteArray,
        batteryMv: Int?,
        uptimeSec: Long?,
    ) {
        info(CommandAuditLogger.Formats.statusResponse(target, publicKey, batteryMv, uptimeSec))
    }

    override suspend fun logTelemetryRequest(target: CommandAuditLogger.Target, publicKey: ByteArray) {
        info(CommandAuditLogger.Formats.telemetryRequest(target, publicKey))
    }

    override suspend fun logTelemetryResponse(
        target: CommandAuditLogger.Target,
        publicKey: ByteArray,
        pointCount: Int,
    ) {
        info(CommandAuditLogger.Formats.telemetryResponse(target, publicKey, pointCount))
    }

    override suspend fun logKeepAlive(target: CommandAuditLogger.Target, publicKey: ByteArray) {
        info(CommandAuditLogger.Formats.keepAlive(target, publicKey))
    }
}

private class CommandAuditTrafficRecorder(private val info: suspend (String) -> Unit) : CommandAuditTraffic {
    override suspend fun logCLICommand(publicKey: ByteArray, command: String) {
        info(CommandAuditLogger.Traffic.cliCommand(publicKey, command))
    }

    override suspend fun logCLIResponse(publicKey: ByteArray, response: String) {
        info(CommandAuditLogger.Traffic.cliResponse(publicKey, response))
    }

    override suspend fun logNeighborsRequest(publicKey: ByteArray, count: Int, offset: Int) {
        info(CommandAuditLogger.Traffic.neighborsRequest(publicKey, count, offset))
    }

    override suspend fun logNeighborsResponse(publicKey: ByteArray, totalCount: Int, returnedCount: Int) {
        info(CommandAuditLogger.Traffic.neighborsResponse(publicKey, totalCount, returnedCount))
    }

    override suspend fun logRoomMessagePosted(publicKey: ByteArray, messageLength: Int) {
        info(CommandAuditLogger.Traffic.roomMessagePosted(publicKey, messageLength))
    }

    override suspend fun logRoomMessageReceived(
        roomPublicKey: ByteArray,
        authorPrefix: ByteArray,
        messageLength: Int,
    ) {
        info(CommandAuditLogger.Traffic.roomMessageReceived(roomPublicKey, authorPrefix, messageLength))
    }
}

private suspend fun recordAudit(
    record: suspend (DebugLogEntryDto) -> Unit,
    nowMs: () -> Long,
    newId: () -> Uuid,
    level: DebugLogLevel,
    message: String,
) {
    record(
        DebugLogEntryDto.of(
            id = newId(),
            timestamp = nowMs(),
            level = level,
            source = DebugLogSource(CommandAuditLogger.SUBSYSTEM, CommandAuditLogger.CATEGORY),
            message = message,
        ),
    )
}
