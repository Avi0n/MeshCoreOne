package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.meshcore.ContactMessage
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.room.RoomAdminService
import one.meshcore.shared.settings.PersistentLogger

/**
 * Routes a backlog CLI reply to the room or repeater admin handler for the sending contact.
 *
 * Live CLI pushes are collected by [NodeCliCoordinator] from `MeshEvent.ContactMessageReceived`. The
 * connect `getMessage` drain is a request/response path that never emits that event, so those frames
 * must be handed here or they vanish after the radio pops them. Mirrors Swift
 * `SyncCoordinator.handleIncomingCLIMessage`: strip a firmware-echoed wire prefix, then room vs
 * everything else. A missing contact is dropped, matching the Swift "no contact found for sender"
 * branch.
 */
internal suspend fun handleIncomingCliMessage(
    message: ContactMessage,
    contact: ContactDto?,
    roomAdmin: RoomAdminService,
    repeaterAdmin: RepeaterAdminService,
    logger: PersistentLogger? = null,
) {
    if (contact == null) {
        logger?.warning(DROPPING_CLI_RESPONSE_NO_CONTACT)
        return
    }
    val routed = message.withCliPrefixStripped()
    if (contact.contactType == ContactType.ROOM) {
        roomAdmin.invokeCLIHandler(contact, routed.text)
    } else {
        repeaterAdmin.invokeCLIHandler(routed, contact)
    }
}

/** Strips a firmware-echoed `XX|` prefix so downstream parsers see the same body as a live reply. */
internal fun ContactMessage.withCliPrefixStripped(): ContactMessage {
    val echoed = CLIResponse.splitEchoedPrefix(text) ?: return this
    return copy(text = echoed.body)
}
