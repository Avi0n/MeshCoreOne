package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertEquals

class CLIOutputResponseBlockTest {
    @Test
    fun commandLineStripsPromptPrefix() {
        val line = CLIOutputLine(1, "local > get name", CLIOutputType.COMMAND)
        assertEquals("get name", listOf(line).responseBlock(line))
    }

    @Test
    fun responseWalksConsecutiveLinesAndStripsFirmwarePrefix() {
        val command = CLIOutputLine(1, "local > get", CLIOutputType.COMMAND)
        val header = CLIOutputLine(2, "> name", CLIOutputType.RESPONSE)
        val body = CLIOutputLine(3, "> MeshCore", CLIOutputType.RESPONSE)
        val next = CLIOutputLine(4, "local > nodes", CLIOutputType.COMMAND)
        val lines = listOf(command, header, body, next)
        assertEquals("name\nMeshCore", lines.responseBlock(header))
        assertEquals("name\nMeshCore", lines.responseBlock(body))
    }

    @Test
    fun missingLineKeepsItsText() {
        val stored = CLIOutputLine(1, "local > get", CLIOutputType.COMMAND)
        val missing = CLIOutputLine(2, "> no longer stored", CLIOutputType.RESPONSE)
        assertEquals("> no longer stored", listOf(stored).responseBlock(missing))
    }
}
