package one.meshcore.shared.tools

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import one.meshcore.shared.contact.CONTACT_FLOOD_PATH_SENTINEL
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.contact.ContactService
import one.meshcore.shared.contact.addOrUpdateContact
import one.meshcore.shared.map.runDiscoveredNodeAdd
import one.meshcore.shared.model.Uuid

internal data class NodeDiscoveryAddOwner(val service: ContactService, val radioID: Uuid, val owns: () -> Boolean)

internal suspend fun performNodeDiscoveryAdd(
    dependencies: NodeDiscoveryViewModel.Dependencies,
    state: MutableStateFlow<NodeDiscoveryState>,
    namesByKeyHex: MutableMap<String, String>,
    result: NodeDiscoveryResult,
    owner: NodeDiscoveryAddOwner,
) {
    val (service, radioID, owns) = owner
    if (!owns()) return
    val caller = currentCoroutineContext()
    val type = discoveryContactType(result.nodeType)
    val hex = result.publicKeyHex
    state.update { it.copy(addingPublicKeyHex = hex, addError = null) }
    runDiscoveredNodeAdd(
        maxContacts = dependencies.maxContacts,
        errorText = dependencies.errorText,
        onError = { addError ->
            caller.ensureActive()
            if (owns()) state.update { it.copy(addError = addError) }
        },
    ) {
        service.addOrUpdateContact(
            radioID = radioID,
            radioIDAtInsert = { if (owns()) radioID else null },
            frame = ContactFrame(
                publicKey = result.publicKey,
                type = type,
                flags = 0,
                outPathLength = CONTACT_FLOOD_PATH_SENTINEL,
                outPath = ByteArray(0),
                name = result.name,
                lastAdvertTimestamp = 0,
                latitude = 0.0,
                longitude = 0.0,
                lastModified = NodeDiscoveryViewModel.ADDED_CONTACT_LAST_MODIFIED,
            ),
        )
        currentCoroutineContext().ensureActive()
        if (!owns()) return@runDiscoveredNodeAdd
        namesByKeyHex[hex] = result.name
        state.update { it.copy(addedPublicKeyHexes = it.addedPublicKeyHexes + hex) }
    }
}
