package one.meshcore.shared.remotenode

/** Appends terminal lines and rebuilds a response block for copy. */
interface NodeCliOutput {
    fun appendOutput(text: String, type: CLIOutputType)

    /**
     * The full response block containing [line], stripping prompt and firmware `"> "` prefixes. Twin of
     * the app CLI's `getResponseBlock`; keep the two in sync.
     */
    fun getResponseBlock(line: CLIOutputLine): String
}

internal class NodeCliTranscript(
    private val state: NodeCliState,
    internal var sessionName: String,
    private val messages: () -> NodeCliMessages,
) : NodeCliOutput {
    override fun appendOutput(text: String, type: CLIOutputType) {
        val line = CLIOutputLine(state.nextLineId++, text, type)
        state.outputLines = (state.outputLines + line).takeLast(MAX_OUTPUT_LINES)
    }

    override fun getResponseBlock(line: CLIOutputLine): String = state.outputLines.responseBlock(line)

    internal fun showBanner() {
        if (state.hasBannered) return
        state.hasBannered = true
        val current = messages()
        appendOutput(current.bannerConnected(sessionName), CLIOutputType.RESPONSE)
        appendOutput(current.bannerHint, CLIOutputType.RESPONSE)
        appendOutput("", CLIOutputType.RESPONSE)
    }

    private companion object {
        const val MAX_OUTPUT_LINES = 1000
    }
}
