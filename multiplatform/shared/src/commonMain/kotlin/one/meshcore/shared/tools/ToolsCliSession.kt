package one.meshcore.shared.tools

import one.meshcore.shared.backup.ChannelDto
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.cli.CLICompletionEngine
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.remotenode.CLIOutputLine

/**
 * Localized copy for the Tools → CLI terminal. Injected so the session stays free of Compose resources.
 */
data class ToolsCliMessages(
    val welcomeLine1: String,
    val welcomeConnected: (deviceName: String) -> String,
    val welcomeHint: String,
    val promptSuffix: String,
    val unknownCommand: String,
    val notConnected: String,
    /** Prompt name when no local radio session is armed (iOS `Tools.Cli.disconnected`). */
    val disconnected: String,
    val nodesHeader: (count: Int) -> String,
    val channelsHeader: (count: Int) -> String,
    val noNodes: String,
    val noChannels: String,
    val channelEmpty: String,
    val kindRepeater: String,
    val kindRoom: String,
    val routeFlood: String,
    val routeDirect: String,
    val routeHops: (hops: Int) -> String,
    /** Session-independent help lines shown first (header, login/logout/session, clear, help). */
    val helpCommonLines: List<String>,
    /** Help lines appended on the local session (nodes/channels + local radio command list). */
    val helpLocalLines: List<String>,
    /** Help lines appended on a remote session (repeater passthrough commands). */
    val helpRepeaterLines: List<String>,
    val sessionListHeader: String,
    val sessionLocal: String,
    val sessionSwitched: String,
    /** Shown when remote bridge is not wired (disconnected / no service). */
    val remoteLoginHint: String,
    val loginUsage: String,
    val nodeNotFound: String,
    val passwordRequired: String,
    /** Shown as the prompt while a `login` waits for the password line. */
    val passwordPrompt: String,
    /** Prompt while a remote login waits; [seconds] is the firmware timeout. */
    val loggingIn: (seconds: Int) -> String,
    val loginSuccess: String,
    val loginFailed: String,
    val loginFailedAuth: String,
    val logoutSuccess: String,
    val notLoggedIn: String,
    val sessionNotFound: String,
    val timeout: String,
    /**
     * The translated text for a failure the radio or bridge returned, or `null` when nothing shipped
     * describes it. Resolved during composition because `stringResource` cannot be read from the
     * coroutine that catches the failure. Without it the terminal prints the exception's developer
     * English, where iOS prints `error.localizedDescription`.
     */
    val deviceErrorText: (Throwable) -> String?,
    /** Non-reboot remote command timeout (distinct from the fire-and-forget reboot success). */
    val commandTimeout: String,
    /** Success line for a fire-and-forget `reboot` whose repeater never replies. */
    val rebootSent: String,
    /** Shown when the user cancels an in-flight remote/local waiting command. */
    val cancelled: String,
    // Local radio command copy (iOS `Tools.Cli.*`).
    val usageGet: String,
    val usageSet: String,
    val usageSetRadio: String,
    val invalidValue: String,
    val invalidCustomVarToken: String,
    /** The y/n prompt for a destructive local command; the argument is the command's display name. */
    val confirmPrompt: (displayName: String) -> String,
)

/**
 * Tools CLI session: local help/nodes/channels/session commands plus optional remote login and
 * passthrough (iOS `CLIToolViewModel`). When [remote] is null, `login` prints [ToolsCliMessages.remoteLoginHint].
 */
class ToolsCliSession(
    internal var deviceName: String,
    internal var messages: ToolsCliMessages,
    internal val fetchContacts: suspend () -> List<ContactDto>?,
    internal val fetchChannels: suspend () -> List<ChannelDto>?,
    options: Options = Options(),
) {
    internal val remote = options.remote
    internal val local = options.local
    internal val completionEngine = options.completionEngine

    data class Options(
        val remote: ToolsCliRemoteBridge? = null,
        val local: ToolsCliLocalBridge? = null,
        val completionEngine: CLICompletionEngine = CLICompletionEngine(),
    )

    internal data class RemoteCliSession(val id: Uuid, val name: String, val outPathLength: Int)

    var outputLines: List<CLIOutputLine> = emptyList()
        internal set
    var commandHistory: List<String> = emptyList()
        internal set
    var historyIndex: Int? = null
        internal set
    var currentInput: String = ""
    var ghostText: String = ""
        internal set
    var tabSuggestions: List<String>? = null
        internal set
    var tabSelectionIndex: Int? = null
        internal set
    var isWaitingForResponse: Boolean = false
        internal set

    internal var nextLineId = 0L
    internal var hasWelcomed = false
    internal val remoteSessions = mutableListOf<RemoteCliSession>()
    internal var activeRemote: RemoteCliSession? = null
    internal var pendingLoginContact: ContactDto? = null
    internal var pendingConfirmation: CliLocalCommand? = null

    init {
        showWelcome()
    }

    var remainingSeconds: Int? = null
        internal set

    val promptText: String
        get() {
            remainingSeconds?.let { return messages.loggingIn(it) }
            if (isWaitingForResponse) return ""
            pendingConfirmation?.let { return "${messages.confirmPrompt(it.displayName)} " }
            if (pendingLoginContact != null) return "${messages.passwordPrompt} "
            val remote = activeRemote
            return when {
                remote != null -> "@${remote.name}${messages.promptSuffix} "
                local != null -> "$deviceName${messages.promptSuffix} "
                else -> "${messages.disconnected}${messages.promptSuffix} "
            }
        }

    val isLocalSession: Boolean get() = activeRemote == null

    companion object {
        internal const val MAX_OUTPUT = 1000
        internal const val MAX_HISTORY = 100
        internal const val NAME_PAD = 20
        internal const val KIND_PAD = 10
    }
}
