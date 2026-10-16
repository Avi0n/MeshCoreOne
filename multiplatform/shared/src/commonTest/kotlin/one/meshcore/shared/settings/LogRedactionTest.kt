package one.meshcore.shared.settings

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verbatim security rules for [LogRedaction] (the exported-log redaction gate). Ports the Swift
 * `LogRedaction` behavior: public keys shortened (not redacted), node names partially masked, CLI passwords
 * dropped, long commands truncated.
 */
class LogRedactionTest {
    @Test
    fun publicKeyHexTakesLowercasePrefix() {
        val key = byteArrayOf(
            0x8B.toByte(),
            0x33,
            0x87.toByte(),
            0xE9.toByte(),
            0xC5.toByte(),
            0xCD.toByte(),
            0xEA.toByte(),
        )
        assertEquals("8b3387e9c5cd", LogRedaction.publicKeyHex(key))
        assertEquals("8b3387", LogRedaction.publicKeyHex(key, prefixLength = 3))
    }

    @Test
    fun nodeNameMasksAllShortNames() {
        assertEquals("***", LogRedaction.nodeName(""))
        assertEquals("***", LogRedaction.nodeName("ab"))
        assertEquals("***", LogRedaction.nodeName("abc"))
    }

    @Test
    fun nodeNameKeepsFirstThreeCharsForLongerNames() {
        assertEquals("abc***", LogRedaction.nodeName("abcd"))
        assertEquals("avi***", LogRedaction.nodeName("avion-testing"))
    }

    @Test
    fun cliCommandDropsPasswordValue() {
        assertEquals("password [REDACTED]", LogRedaction.cliCommand("password secret123"))
        assertEquals("set password [REDACTED]", LogRedaction.cliCommand("set password hunter2"))
        // The command words keep their original case; only the value is dropped.
        assertEquals("PASSWORD [REDACTED]", LogRedaction.cliCommand("PASSWORD abc"))
    }

    @Test
    fun cliCommandTruncatesLongNonPasswordCommands() {
        assertEquals("get name", LogRedaction.cliCommand("get name"))
        val long = "a".repeat(45)
        assertEquals("a".repeat(40) + "...", LogRedaction.cliCommand(long))
    }

    @Test
    fun passwordPlaceholderConstant() {
        assertEquals("[REDACTED]", LogRedaction.PASSWORD_PLACEHOLDER)
    }
}
