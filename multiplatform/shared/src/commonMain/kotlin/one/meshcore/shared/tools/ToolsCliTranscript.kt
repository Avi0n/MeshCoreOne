package one.meshcore.shared.tools

import one.meshcore.shared.remotenode.CLIOutputLine
import one.meshcore.shared.remotenode.CLIOutputType

internal fun ToolsCliSession.output(text: String) = appendOutput(text, CLIOutputType.RESPONSE)

internal fun ToolsCliSession.showWelcome() {
    if (hasWelcomed) return
    hasWelcomed = true
    appendOutput(messages.welcomeLine1, CLIOutputType.RESPONSE)
    appendOutput(messages.welcomeConnected(deviceName), CLIOutputType.RESPONSE)
    appendOutput(messages.welcomeHint, CLIOutputType.RESPONSE)
}

internal fun ToolsCliSession.appendOutput(text: String, type: CLIOutputType) {
    outputLines = (outputLines + CLIOutputLine(nextLineId++, text, type)).takeLast(ToolsCliSession.MAX_OUTPUT)
}
