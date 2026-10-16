package one.meshcore.shared.remotenode.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.pathdetail.loadContacts
import one.meshcore.shared.chat.pathdetail.loadDiscoveredNodes
import one.meshcore.shared.contact.isFloodRouted
import one.meshcore.shared.contact.pathHops
import one.meshcore.shared.model.Uuid

/** Flood-routing path-length byte. A login with this length has no stored route left to reset. */
internal const val NODE_AUTH_FLOOD_SENTINEL = 0xFF

/**
 * Stored-route reads and the once-per-session firmware reset for the authentication sheet.
 * After any reset, later logins flood even if the contact snapshot still shows a direct path.
 */
internal class NodeAuthPath(
    private val contact: ContactDto,
    private val dependencies: NodeAuthViewModel.Dependencies,
    private val state: MutableStateFlow<NodeAuthState>,
) {
    private var didResetPath = false

    /**
     * Loads the radio's contacts and discovered rows so the path preview can resolve each stored hop to a
     * repeater name. A flood-routed contact or one with no stored path has nothing to resolve, mirroring
     * the iOS load guard.
     */
    internal suspend fun load() {
        if (contact.isFloodRouted || contact.pathHops.isEmpty()) return
        val radioID = dependencies.radioId() ?: return
        val contacts = loadContacts(dependencies.contactService(), radioID)
        val discovered = loadDiscoveredNodes(radioID) { id ->
            dependencies.discoveredNodeStore()?.list(id).orEmpty()
        }
        state.update {
            it.copy(
                pathContacts = contacts,
                pathDiscovered = discovered,
                pathUserLocation = dependencies.userLocation(),
            )
        }
    }

    /**
     * The path-length byte for the login send. A flood-routing connect resets the firmware's stored path
     * once per session (so the login packet floods) and uses the flood sentinel; otherwise it keeps the
     * contact's stored `outPathLength`. Once any reset has run, the radio's path is gone regardless of the
     * contact snapshot, so later attempts flood.
     */
    internal suspend fun resolveLength(radioID: Uuid): Int = when {
        state.value.useFloodRouting && !contact.isFloodRouted && !didResetPath -> {
            resetStoredRoute(radioID)
            NODE_AUTH_FLOOD_SENTINEL
        }
        state.value.useFloodRouting || didResetPath -> NODE_AUTH_FLOOD_SENTINEL
        else -> contact.outPathLength
    }

    /** Clears the firmware route once per session so later logins flood. */
    internal suspend fun resetStoredRoute(radioID: Uuid) {
        dependencies.contactService()?.resetPath(radioID, contact.publicKey)
        didResetPath = true
    }
}
