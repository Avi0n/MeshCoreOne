package one.meshcore.shared.cli.ui

import androidx.compose.ui.graphics.Color
import one.meshcore.shared.remotenode.CLIOutputType

/** Theme CLI line colors; command/response stay the caller’s secondary/on-surface. */
internal fun CLIOutputType.cliTint(command: Color, response: Color, success: Color, error: Color): Color = when (this) {
    CLIOutputType.COMMAND -> command
    CLIOutputType.RESPONSE -> response
    CLIOutputType.ERROR -> error
    CLIOutputType.SUCCESS -> success
}
