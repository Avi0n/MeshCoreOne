package one.meshcore.shared.remotenode

import kotlin.time.Duration

/**
 * Records the CLI commands a settings controller sends and returns scripted responses. [respond] may throw
 * a [RemoteNodeError] to simulate a per-command timeout or failure; unmatched commands fall back to "OK".
 */
class ScriptedCliSender {
    val sent = mutableListOf<String>()

    /** The timeout each command was sent with, parallel to [sent]. */
    val timeouts = mutableListOf<Duration>()
    var respond: (command: String) -> String = { "OK" }
    var onSend: ((String) -> Unit)? = null

    val send: suspend (command: String, timeout: Duration) -> String = { command, timeout ->
        sent += command
        timeouts += timeout
        onSend?.invoke(command)
        respond(command)
    }

    /** The timeout the last send used, or null when nothing has been sent. */
    val lastTimeout: Duration? get() = timeouts.lastOrNull()

    /** Commands sent that start with "set ". */
    val setCommands: List<String> get() = sent.filter { it.startsWith("set ") }
}
