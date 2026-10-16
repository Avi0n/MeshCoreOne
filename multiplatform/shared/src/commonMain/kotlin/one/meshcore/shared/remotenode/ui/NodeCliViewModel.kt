package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.CLIOutputLine
import one.meshcore.shared.remotenode.NodeCliMessages
import one.meshcore.shared.remotenode.RepeaterAdminService
import kotlin.time.Duration

/** The observable snapshot of the node CLI terminal the composable renders. */
data class NodeCliState(
    val outputLines: List<CLIOutputLine> = emptyList(),
    val promptText: String = "",
    val currentInput: String = "",
    val ghostText: String = "",
    val tabSuggestions: List<String>? = null,
    val tabSelectionIndex: Int? = null,
    val isWaitingForResponse: Boolean = false,
)

/**
 * Drives the node CLI terminal, ported from the iOS `NodeCLIViewModel`. It owns no terminal logic of its
 * own: the committed [NodeCliSession] holds the output buffer, history, prompt, and completion state, and
 * this view model only adapts that mutable state into a [StateFlow] for Compose and launches the send in
 * [scope]. Every mutating call publishes so the flow mirrors the session, matching how the Swift
 * `@Observable` view model and its state are one object.
 *
 * The terminal reaches the node through [RepeaterAdminService.sendRawCommand], resolved live from the
 * providers at each send so a reconnect-minted service is used; a null service or session id surfaces the
 * not-connected error the session renders as an error line.
 */
class NodeCliViewModel private constructor(private val scope: CoroutineScope, runtime: NodeCliRuntime) :
    NodeCliInput by runtime.input {
    private val session = runtime.session
    private val publisher = runtime.publisher

    constructor(
        scope: CoroutineScope,
        sessionName: String,
        messages: () -> NodeCliMessages,
        dependencies: Dependencies,
    ) : this(scope, NodeCliRuntime.open(sessionName, messages, dependencies))

    class Dependencies(
        val repeaterAdminService: () -> RepeaterAdminService?,
        val sessionId: () -> Uuid?,
        /**
         * Send path for a node the repeater admin service does not serve, notably a room, whose CLI is
         * gated by [one.meshcore.shared.room.RoomAdminService]. Null keeps the repeater path.
         */
        val sendRawCommand: (suspend (command: String, timeout: Duration) -> String)? = null,
    )

    val state: StateFlow<NodeCliState> = publisher.state

    private var commandJob: Job? = null
    private var isClosed = false

    /**
     * Submits the current input: applies a highlighted tab suggestion if one is selected, otherwise runs
     * the command, mirroring `NodeCLIView`'s onSubmit.
     */
    fun submit() {
        if (isClosed) return
        if (session.applySelectedSuggestion()) {
            publisher.publish()
            return
        }
        execute(session.currentInput)
    }

    /** Runs the `clear` command, emptying the buffer. */
    fun clear() = execute("clear")

    private fun execute(command: String) {
        if (isClosed) return
        commandJob?.cancel()
        commandJob = scope.launch {
            val runner = launch { session.executeCommand(command) }
            // The echo and the waiting flag are applied synchronously before the send suspends, so a yield
            // lets them land before the first publish; the second reflects the completed response.
            yield()
            currentCoroutineContext().ensureActive()
            publisher.publish()
            runner.join()
            currentCoroutineContext().ensureActive()
            publisher.publish()
        }
    }

    /** Cancels an in-flight command, appending the cancellation line the session owns. */
    fun cancelCurrentCommand() {
        commandJob?.cancel()
        commandJob = null
        session.cancelCurrentCommand()
        publisher.publish()
    }

    internal fun updateSessionName(name: String) {
        session.updateSessionName(name)
        refreshLocalizedCopy()
    }

    /** Republishes derived prompt copy after the host's locale changes without resetting terminal state. */
    fun refreshLocalizedCopy() {
        if (!isClosed) publisher.publish()
    }

    /** Stops work owned by a CLI surface that has left composition. */
    fun close() {
        if (isClosed) return
        isClosed = true
        cancelCurrentCommand()
    }

    /** The full response block containing [line] (prompt/`> ` prefixes stripped), for copy actions. */
    fun getResponseBlock(line: CLIOutputLine): String = session.getResponseBlock(line)
}
