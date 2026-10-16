package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.backup.RemoteNodeSessionDto
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.meshcore.MeshEvent

/**
 * The reply body the in-flight waiter accepted, or null when this fan-out is uncorrelated.
 * Only meaningful inside a CLI observer: the value is cleared when fan-out returns.
 */
fun NodeCliCoordinator.cliBodyClaimedByWaiter(): String? = claimedCliBody

/**
 * Registers an observer of every inbound CLI reply. Observation is independent of request correlation:
 * a reply that lands after its request timed out is still delivered here, which is how a settings
 * section recovers a field without spending more airtime. Mirrors the Swift `SyncCoordinator` CLI
 * fan-out to the admin services, which runs alongside the pending-request match.
 *
 * Registration happens once per connection while the service graph is built; the copy-on-write swap
 * keeps the collector's read of the list consistent with a concurrent registration.
 */
fun NodeCliCoordinator.addCLIResponseObserver(
    observer: suspend (ContactMessage, RemoteNodeSessionDto, ContactDto) -> Unit,
) {
    cliResponseObservers = cliResponseObservers + observer
}

internal suspend fun NodeCliCoordinator.handleEvent(event: MeshEvent) {
    if (event is MeshEvent.ContactMessageReceived && event.message.textType == CLI_RESPONSE_TEXT_TYPE) {
        claimedCliBody = handleCLIResponse(event.message.senderPublicKeyPrefix, event.message.text)
        try {
            notifyCLIResponseObservers(event.message)
        } finally {
            claimedCliBody = null
        }
    }
}

/**
 * Delivers [message] to the observers with the firmware's echoed wire prefix stripped, so downstream
 * parsers see the same text whether or not the firmware echoes. A reply from a sender with no saved
 * session or no local contact row is dropped, matching the Swift "no contact found" branch.
 */
private suspend fun NodeCliCoordinator.notifyCLIResponseObservers(message: ContactMessage) {
    val observers = cliResponseObservers
    if (observers.isEmpty()) return
    val session = store.fetchSessionByPrefix(message.senderPublicKeyPrefix)
    val contact = session?.let { store.fetchContact(it.radioID, it.publicKey) }
    if (session == null || contact == null) {
        logger?.warning(DROPPING_CLI_RESPONSE_NO_CONTACT)
        return
    }
    val body = CLIResponse.splitEchoedPrefix(message.text)?.body ?: message.text
    val routed = message.copy(text = body)
    for (observer in observers) observer(routed, session, contact)
}
