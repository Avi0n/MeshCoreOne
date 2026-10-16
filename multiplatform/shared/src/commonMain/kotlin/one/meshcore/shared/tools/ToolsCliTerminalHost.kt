package one.meshcore.shared.tools

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import one.meshcore.shared.tools.ui.ToolsCliViewModel

/**
 * Identity of a CLI terminal: the source radio name and the per-connection bridges it holds. The bridges
 * compare by reference, so a reconnect (which rebuilds them) or a radio switch yields a new key and
 * therefore a fresh terminal — the two events that reset the iOS `cliToolViewModel`. The raw name stays
 * blank for an unnamed radio so changing the localized display fallback cannot reset the terminal.
 */
data class ToolsCliTerminalKey(
    val deviceName: String,
    val remote: ToolsCliRemoteBridge?,
    val local: ToolsCliLocalBridge?,
)

/**
 * Holds the live Tools CLI terminal outside the composition, the counterpart of the iOS
 * `AppState.cliToolViewModel`. A terminal remembered inside the screen dies the moment the user opens
 * another tool or tab, taking the scrollback, command history, and every remote login with it; held here
 * it survives navigation and is replaced only when [ToolsCliTerminalKey] changes.
 *
 * The retained terminal also needs a scope that outlives the screen, so navigating away cannot cancel an
 * in-flight command. Commands run on a single-threaded dispatcher, matching the one-command-at-a-time
 * terminal they drive.
 */
object ToolsCliTerminalHost {
    @OptIn(ExperimentalCoroutinesApi::class)
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)

    private var currentKey: ToolsCliTerminalKey? = null
    private var current: ToolsCliViewModel? = null
    private var currentScope: CoroutineScope? = null

    /**
     * The terminal for [key], creating one through [create] (which receives the retained scope) when the
     * key changed or on first use.
     */
    fun terminal(key: ToolsCliTerminalKey, create: (CoroutineScope) -> ToolsCliViewModel): ToolsCliViewModel {
        val existing = current
        if (existing != null && currentKey == key) return existing
        discardCurrent()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val fresh = create(scope)
        currentKey = key
        current = fresh
        currentScope = scope
        return fresh
    }

    /** Stops and drops the retained terminal so the next open starts a fresh session. */
    fun reset() = discardCurrent()

    private fun discardCurrent() {
        currentScope?.cancel()
        currentKey = null
        current = null
        currentScope = null
    }
}
