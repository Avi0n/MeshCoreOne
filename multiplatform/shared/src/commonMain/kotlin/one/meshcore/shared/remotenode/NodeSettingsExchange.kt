package one.meshcore.shared.remotenode

import kotlin.time.Duration

/** Late-reply registration for settings commands that already left this screen. */
interface NodeSettingsTransport {
    /**
     * Registers a field applier for a recoverable query. The repeater and room controllers add their own
     * section fields on top of the shared ones, so one arbitration covers every query this screen sent.
     */
    fun registerLateRecovery(query: String, apply: (CLIResponse) -> Unit)

    /**
     * Adopts an out-of-band CLI reply for the one unanswered query it can only belong to. The reply
     * already spent its airtime, so recovering it beats refetching; ambiguous or duplicate replies are
     * ignored rather than written into whichever field happens to parse them. Returns true if consumed.
     */
    fun handleCommonLateResponse(response: String): Boolean
}

internal class NodeSettingsExchange(
    private val sendCommand: suspend (command: String, timeout: Duration) -> String,
    private val sendRawCommand: suspend (command: String, timeout: Duration) -> String,
) : NodeSettingsTransport {
    /** Structured queries this screen sent that timed out unanswered. */
    private val unansweredQueries = mutableSetOf<String>()

    /**
     * Recently observed reply texts; a mesh duplicate of an already-answered command must not be recovered
     * for a different query.
     */
    private val recentResponses = ArrayDeque<String>()

    /** Appliers for recovered late replies, keyed by query. */
    private val lateRecoveryAppliers = mutableMapOf<String, (CLIResponse) -> Unit>()

    /**
     * Sends a command and records what the exchange tells the late-reply recovery below: the answer this
     * screen has already seen, and — when the wait expires — the query still owed an answer.
     */
    suspend fun sendAndWait(
        command: String,
        timeout: Duration = DEFAULT_TIMEOUT,
        rawMatching: Boolean = false,
    ): String = try {
        val command = RemoteCLICommandRewriter.rewrite(command)
        val response = checkedSettingsCommand {
            if (rawMatching) sendRawCommand(command, timeout) else sendCommand(command, timeout)
        }
        rememberSeenResponse(response)
        unansweredQueries.remove(command)
        response
    } catch (e: RemoteNodeError.Timeout) {
        if (CLIResponse.isStructuredQuery(command)) unansweredQueries.add(command)
        throw e
    }

    internal suspend fun commandSucceeded(command: String): Boolean =
        CLIResponse.parse(sendAndWait(command)) is CLIResponse.Ok

    override fun registerLateRecovery(query: String, apply: (CLIResponse) -> Unit) {
        lateRecoveryAppliers[query] = apply
    }

    private fun rememberSeenResponse(response: String) {
        recentResponses.addLast(response)
        if (recentResponses.size > RECENT_RESPONSES_LIMIT) recentResponses.removeFirst()
    }

    override fun handleCommonLateResponse(response: String): Boolean {
        if (response in recentResponses) return false
        val recovered = NodeSettingsResponseParser.recoveredResponse(response, unansweredQueries) ?: return false
        val apply = lateRecoveryAppliers[recovered.query] ?: return false

        unansweredQueries.remove(recovered.query)
        rememberSeenResponse(response)
        apply(recovered.value)
        return true
    }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = RemoteOperationTimeoutPolicy.DEFAULT_CLI

        /** How many recent replies are remembered for the duplicate check, matching the iOS screen. */
        const val RECENT_RESPONSES_LIMIT = 16
    }
}
