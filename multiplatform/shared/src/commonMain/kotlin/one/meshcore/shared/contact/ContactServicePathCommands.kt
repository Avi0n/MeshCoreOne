package one.meshcore.shared.contact

import one.meshcore.shared.meshcore.MessageSentInfo
import one.meshcore.shared.model.Uuid

/**
 * Sends a path-discovery request to find an optimal route to a contact on the connected radio, returning
 * the firmware's estimated timeout. A device `NOT_FOUND` becomes [ContactServiceError.ContactNotFound].
 */
suspend fun ContactService.sendPathDiscovery(publicKey: ByteArray): MessageSentInfo =
    contactRadioCommands.sendPathDiscovery(publicKey)

/**
 * Sets a specific out-path for a contact, preserving all other fields and stamping `lastModified`.
 * Mirrors `setPath`.
 */
suspend fun ContactService.setPath(radioID: Uuid, publicKey: ByteArray, path: ByteArray, pathLength: Int) =
    contactRadioCommands.setPath(radioID, publicKey, path, pathLength)
