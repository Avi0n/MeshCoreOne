package one.meshcore.shared.tools

import one.meshcore.shared.contact.contactType
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.runThrowableResult

/**
 * Feeds repeater/room contact names to the completion engine so `login <TAB>` / `session <TAB>`
 * complete node names. Mirrors iOS `updateNodeNamesForCompletion`.
 */
suspend fun ToolsCliSession.refreshNodeNames() {
    val contacts = fetchContacts() ?: return
    val names = contacts
        .filter { it.contactType == ContactType.REPEATER || it.contactType == ContactType.ROOM }
        .map { it.name }
    completionEngine.updateNodeNames(names)
}

/**
 * Prefetches custom-var names for bare `get`/`set` completion. A failure, including an [Error],
 * leaves the keys empty: old firmware can't enumerate vars. Cancellation stops the refresh.
 * Mirrors iOS `updateCustomVarKeysForCompletion`.
 */
suspend fun ToolsCliSession.refreshCustomVarKeys() {
    val bridge = local ?: return
    val vars = runThrowableResult({ null }) {
        bridge.getCustomVars()
    } ?: return
    completionEngine.updateCustomVarKeys(vars.keys.toList())
}

/**
 * Reloads node names and custom-var keys. iOS runs both from `configure` on every Tools CLI appearance.
 */
suspend fun ToolsCliSession.refreshCompletionCatalog() {
    refreshNodeNames()
    refreshCustomVarKeys()
}

fun ToolsCliSession.setDeviceName(name: String) {
    if (name.isNotBlank()) deviceName = name
}
