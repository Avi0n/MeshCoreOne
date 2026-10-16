package one.meshcore.shared.cli.ui

import androidx.compose.ui.graphics.Color
import one.meshcore.shared.remotenode.CLIOutputType
import kotlin.test.Test
import kotlin.test.assertEquals

class CliOutputChromeTest {
    @Test
    fun successAndErrorUseCallerThemeColors() {
        val command = Color.DarkGray
        val response = Color.Black
        val success = Color.Blue
        val error = Color.Red
        assertEquals(success, CLIOutputType.SUCCESS.cliTint(command, response, success, error))
        assertEquals(error, CLIOutputType.ERROR.cliTint(command, response, success, error))
        assertEquals(command, CLIOutputType.COMMAND.cliTint(command, response, success, error))
        assertEquals(response, CLIOutputType.RESPONSE.cliTint(command, response, success, error))
    }
}
