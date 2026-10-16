package one.meshcore.shared.settings

import kotlinx.coroutines.runBlocking
import one.meshcore.shared.model.Uuid
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandAuditLoggerTest {
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun formatsMatchSwiftTemplates() {
        val f = CommandAuditLogger.Formats
        val traffic = CommandAuditLogger.Traffic
        assertEquals(
            "[CMD] -> REPEATER LOGIN to=${LogRedaction.publicKeyHex(key)} pathLen=2",
            f.loginRequest(CommandAuditLogger.Target.REPEATER, key, pathLength = 2),
        )
        assertEquals(
            "[CMD] <- ROOM LOGIN_OK from=${LogRedaction.publicKeyHex(key)} admin=true",
            f.loginSuccess(CommandAuditLogger.Target.ROOM, key, isAdmin = true),
        )
        assertTrue(
            traffic.cliCommand(key, "password secret-value").contains(LogRedaction.PASSWORD_PLACEHOLDER),
        )
        assertTrue(traffic.cliResponse(key, "x".repeat(150)).endsWith("...\""))
        assertEquals(
            "[CMD] -> REPEATER NEIGHBORS_REQ to=${LogRedaction.publicKeyHex(key)} count=10 offset=0",
            traffic.neighborsRequest(key, count = 10, offset = 0),
        )
        assertEquals(
            "[CMD] -> ROOM MSG to=${LogRedaction.publicKeyHex(key)} len=12",
            traffic.roomMessagePosted(key, messageLength = 12),
        )
    }

    @Test
    fun recordsInfoEntriesOnSharedSink() = runBlocking {
        val captured = mutableListOf<DebugLogEntryDto>()
        val logger =
            CommandAuditLogger(
                nowMs = { 1_700_000_000_000L },
                newId = { Uuid.normalized("00000000-0000-0000-0000-0000000000AA") },
                record = { captured += it },
            )
        logger.logStatusRequest(CommandAuditLogger.Target.REPEATER, key)
        logger.logLoginFailed(CommandAuditLogger.Target.ROOM, key, reason = "bad password")

        assertEquals(2, captured.size)
        assertEquals(DebugLogLevel.INFO, captured[0].level)
        assertEquals(DebugLogLevel.WARNING, captured[1].level)
        assertEquals(CommandAuditLogger.CATEGORY, captured[0].category)
        assertTrue(captured[0].message.contains("STATUS_REQ"))
        assertTrue(captured[1].message.contains("LOGIN_FAIL"))
    }
}
