package one.meshcore.shared.tools

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.remotenode.CLIOutputType

internal suspend fun ToolsCliSession.handleLogin(args: String) {
    val bridge = remote
    if (bridge == null || activeRemote != null) {
        appendOutput(messages.remoteLoginHint, CLIOutputType.RESPONSE)
        return
    }
    val request = parsedLoginRequest(args)
    if (request == null) {
        appendOutput(messages.loginUsage, CLIOutputType.ERROR)
        return
    }
    isWaitingForResponse = true
    try {
        applyLogin(bridge, request)
    } finally {
        isWaitingForResponse = false
    }
}

private fun parsedLoginRequest(args: String): LoginRequest? {
    val tokens = args.split(Regex("\\s+")).filter { it.isNotEmpty() }.takeIf { it.isNotEmpty() } ?: return null
    val forget = tokens.any { it == "-f" || it == "--forget" }
    val nodeName = tokens.filterNot { it == "-f" || it == "--forget" }
        .joinToString(" ")
        .takeIf { it.isNotEmpty() } ?: return null
    return LoginRequest(nodeName, forget)
}

private suspend fun ToolsCliSession.applyLogin(bridge: ToolsCliRemoteBridge, request: LoginRequest) {
    val contacts = fetchContacts()
    if (contacts == null) {
        appendOutput(messages.notConnected, CLIOutputType.ERROR)
        return
    }
    val contact = repeaterOrRoomNamed(contacts, request.nodeName)
    if (contact == null) {
        appendOutput("${messages.nodeNotFound} ${request.nodeName}", CLIOutputType.ERROR)
        return
    }
    if (request.forget) bridge.deletePassword(contact.publicKey)
    val stored = bridge.retrievePassword(contact.publicKey)
    if (stored != null) {
        completeLogin(contact, stored)
    } else {
        pendingLoginContact = contact
        appendOutput(messages.passwordRequired, CLIOutputType.RESPONSE)
    }
}

private fun repeaterOrRoomNamed(contacts: List<ContactDto>, nodeName: String): ContactDto? =
    contacts.firstOrNull { it.name.equals(nodeName, ignoreCase = true) && isRepeaterOrRoom(it) }

private fun isRepeaterOrRoom(contact: ContactDto): Boolean =
    contact.contactType == ContactType.REPEATER || contact.contactType == ContactType.ROOM

private data class LoginRequest(val nodeName: String, val forget: Boolean)
