package one.meshcore.shared.settings

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toLowercaseHex
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Dedicated audit trail for remote-node / room commands, mirroring Swift `CommandAuditLogger`.
 * Lines are formatted deterministically (unit-tested) and recorded on the shared [DebugLogBuffer]
 * so diagnostics export includes the same `[CMD]` trail as iOS.
 */
@OptIn(ExperimentalTime::class)
class CommandAuditLogger(
    nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    newId: () -> Uuid = Uuid::random,
    record: suspend (DebugLogEntryDto) -> Unit = { DebugLogBuffer.record(it) },
) : CommandAuditSession by commandAuditSession(nowMs, newId, record),
    CommandAuditTraffic by commandAuditTraffic(nowMs, newId, record) {
    enum class Direction(val token: String) {
        OUT("->"),
        IN("<-"),
    }

    enum class Target(val token: String) {
        REPEATER("REPEATER"),
        ROOM("ROOM"),
    }

    /**
     * Pure line builders for login, status, telemetry, and keep-alive. Kept public for tests and any
     * caller that needs the text without recording.
     */
    object Formats {
        fun loginRequest(target: Target, publicKey: ByteArray, pathLength: Int): String =
            auditLine(Direction.OUT, target, "LOGIN to=${auditKey(publicKey)} pathLen=$pathLength")

        fun loginSuccess(target: Target, publicKey: ByteArray, isAdmin: Boolean): String =
            auditLine(Direction.IN, target, "LOGIN_OK from=${auditKey(publicKey)} admin=$isAdmin")

        fun loginFailed(target: Target, publicKey: ByteArray, reason: String): String =
            auditLine(Direction.IN, target, "LOGIN_FAIL from=${auditKey(publicKey)} reason=$reason")

        fun logout(target: Target, publicKey: ByteArray): String =
            auditLine(Direction.OUT, target, "LOGOUT to=${auditKey(publicKey)}")

        fun statusRequest(target: Target, publicKey: ByteArray): String =
            auditLine(Direction.OUT, target, "STATUS_REQ to=${auditKey(publicKey)}")

        fun statusResponse(target: Target, publicKey: ByteArray, batteryMv: Int?, uptimeSec: Long?): String {
            val battery = batteryMv?.let { "${it}mV" } ?: "n/a"
            val uptime = uptimeSec?.let { "${it}s" } ?: "n/a"
            return auditLine(Direction.IN, target, "STATUS from=${auditKey(publicKey)} battery=$battery uptime=$uptime")
        }

        fun telemetryRequest(target: Target, publicKey: ByteArray): String =
            auditLine(Direction.OUT, target, "TELEM_REQ to=${auditKey(publicKey)}")

        fun telemetryResponse(target: Target, publicKey: ByteArray, pointCount: Int): String =
            auditLine(Direction.IN, target, "TELEM from=${auditKey(publicKey)} points=$pointCount")

        fun keepAlive(target: Target, publicKey: ByteArray): String =
            auditLine(Direction.OUT, target, "KEEPALIVE to=${auditKey(publicKey)}")
    }

    /** Pure line builders for CLI, neighbor, and room-message lines. */
    object Traffic {
        fun cliCommand(publicKey: ByteArray, command: String): String {
            val redacted = LogRedaction.cliCommand(command)
            return auditLine(Direction.OUT, Target.REPEATER, "CLI to=${auditKey(publicKey)} cmd=\"$redacted\"")
        }

        fun cliResponse(publicKey: ByteArray, response: String): String {
            val truncated =
                if (response.length <= CLI_RESPONSE_MAX) {
                    response
                } else {
                    response.take(CLI_RESPONSE_MAX) + "..."
                }
            return auditLine(Direction.IN, Target.REPEATER, "CLI_RESP from=${auditKey(publicKey)} resp=\"$truncated\"")
        }

        fun neighborsRequest(publicKey: ByteArray, count: Int, offset: Int): String = auditLine(
            Direction.OUT,
            Target.REPEATER,
            "NEIGHBORS_REQ to=${auditKey(publicKey)} count=$count offset=$offset",
        )

        fun neighborsResponse(publicKey: ByteArray, totalCount: Int, returnedCount: Int): String = auditLine(
            Direction.IN,
            Target.REPEATER,
            "NEIGHBORS from=${auditKey(publicKey)} total=$totalCount returned=$returnedCount",
        )

        fun roomMessagePosted(publicKey: ByteArray, messageLength: Int): String =
            auditLine(Direction.OUT, Target.ROOM, "MSG to=${auditKey(publicKey)} len=$messageLength")

        fun roomMessageReceived(roomPublicKey: ByteArray, authorPrefix: ByteArray, messageLength: Int): String {
            val authorHex = authorPrefix.toLowercaseHex()
            return auditLine(
                Direction.IN,
                Target.ROOM,
                "MSG from=${auditKey(roomPublicKey)} author=$authorHex len=$messageLength",
            )
        }
    }

    companion object {
        const val PREFIX: String = "[CMD]"
        const val SUBSYSTEM: String = "com.mc1"
        const val CATEGORY: String = "CommandAudit"
        private const val CLI_RESPONSE_MAX: Int = 100
    }
}

private fun auditKey(publicKey: ByteArray): String = LogRedaction.publicKeyHex(publicKey)

private fun auditLine(
    direction: CommandAuditLogger.Direction,
    target: CommandAuditLogger.Target,
    body: String,
): String = "${CommandAuditLogger.PREFIX} ${direction.token} ${target.token} $body"
