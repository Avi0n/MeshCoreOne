package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import one.meshcore.shared.remotenode.NodeCliMessages
import one.meshcore.shared.remotenode.NodeCliSession
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RepeaterAdminService
import kotlin.time.Duration

/** Keyboard and completion edits. Each one updates the session, then publishes. */
interface NodeCliInput {
    /** Updates the working input line (cursor edits, typing) and republishes. */
    fun setInput(value: String)
    fun historyUp()
    fun historyDown()
    fun acceptGhostText()
    fun tabComplete()
    fun applySuggestionAt(index: Int)
    fun updateGhostText(cursorAtEnd: Boolean)
    fun clearTabState()
}

internal class NodeCliInputActions(private val session: NodeCliSession, private val publish: () -> Unit) :
    NodeCliInput {
    override fun setInput(value: String) {
        session.currentInput = value
        publish()
    }

    override fun historyUp() {
        session.historyUp()
        publish()
    }

    override fun historyDown() {
        session.historyDown()
        publish()
    }

    override fun acceptGhostText() {
        session.acceptGhostText()
        publish()
    }

    override fun tabComplete() {
        session.tabComplete()
        publish()
    }

    override fun applySuggestionAt(index: Int) {
        session.applySuggestionAt(index)
        publish()
    }

    override fun updateGhostText(cursorAtEnd: Boolean) {
        session.updateGhostText(cursorAtEnd)
        publish()
    }

    override fun clearTabState() {
        session.clearTabState()
        publish()
    }
}

internal class NodeCliSender(private val dependencies: NodeCliViewModel.Dependencies) {
    suspend fun sendRawCommand(command: String, timeout: Duration): String {
        dependencies.sendRawCommand?.let { return it(command, timeout) }
        val service: RepeaterAdminService = dependencies.repeaterAdminService() ?: throw RemoteNodeError.NotConnected
        val sessionId = dependencies.sessionId() ?: throw RemoteNodeError.NotConnected
        return service.sendRawCommand(sessionId, command, timeout)
    }
}

internal class NodeCliPublisher(private val session: NodeCliSession) {
    private val stateFlow = MutableStateFlow(snapshot())
    val state: StateFlow<NodeCliState> = stateFlow.asStateFlow()

    fun publish() {
        stateFlow.value = snapshot()
    }

    private fun snapshot() = NodeCliState(
        outputLines = session.outputLines,
        promptText = session.promptText,
        currentInput = session.currentInput,
        ghostText = session.ghostText,
        tabSuggestions = session.tabSuggestions,
        tabSelectionIndex = session.tabSelectionIndex,
        isWaitingForResponse = session.isWaitingForResponse,
    )
}

internal class NodeCliRuntime private constructor(
    val session: NodeCliSession,
    val publisher: NodeCliPublisher,
    val input: NodeCliInputActions,
) {
    companion object {
        fun open(
            sessionName: String,
            messages: () -> NodeCliMessages,
            dependencies: NodeCliViewModel.Dependencies,
        ): NodeCliRuntime {
            val sender = NodeCliSender(dependencies)
            val session = NodeCliSession(sessionName, messages, sendRawCommand = sender::sendRawCommand)
            val publisher = NodeCliPublisher(session)
            return NodeCliRuntime(session, publisher, NodeCliInputActions(session, publisher::publish))
        }
    }
}
