package one.meshcore.shared.pathedit

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.chat.pathdetail.PathEncoding
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.getContacts
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.prefixBytes
import kotlin.math.min

/**
 * Loads contacts (split into repeaters/rooms), discovered nodes, and recents for [radioID]. Always
 * refreshes recents so switching radios picks up the right scope even when the contact cache lets the
 * fetch be skipped.
 */
suspend fun PathManagementViewModel.loadContacts(radioID: Uuid, forceReload: Boolean = false) {
    val service = dependencies.contactService() ?: return
    loadRecentKeys(radioID)
    if (!forceReload && editState.value.allContacts.isNotEmpty()) return
    try {
        val contacts = service.getContacts(radioID)
        val discovered = dependencies.discoveredNodes(radioID)
        editState.update {
            it.copy(
                allContacts = contacts,
                availableRepeaters = contacts.filter { c -> c.contactType == ContactType.REPEATER },
                availableRooms = contacts.filter { c -> c.contactType == ContactType.ROOM },
                discoveredNodes = discovered,
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        editState.update {
            it.copy(
                allContacts = emptyList(),
                availableRepeaters = emptyList(),
                availableRooms = emptyList(),
                discoveredNodes = emptyList(),
            )
        }
    }
}

/**
 * Rebuilds `editablePath` from [contact]'s stored path, normalizing every hop to the device's current
 * hash size so the editor never shows mixed-width hops. Clears any stale `insertionIntent`.
 */
fun PathManagementViewModel.initializeEditablePath(contact: ContactDto) {
    val decoded = PathEncoding.decodePathLen(contact.outPathLength)
    val storedHashSize = (decoded?.hashSize ?: PathManagementViewModel.DEFAULT_HASH_SIZE)
        .coerceAtLeast(PathManagementViewModel.MIN_HASH_SIZE)
    val byteLength = decoded?.byteLength ?: 0
    val pathData = contact.outPath.copyOfRange(0, min(byteLength, contact.outPath.size))
    val target = hashSize()
    val snapshot = editState.value
    val hops = buildList {
        var offset = 0
        while (offset < pathData.size) {
            val end = min(offset + storedHashSize, pathData.size)
            val bytes = pathData.copyOfRange(offset, end)
            val hop = HopResolution.createPathHop(bytes, snapshot.allContacts, snapshot.discoveredNodes)
            add(PathEditLogic.normalizeHop(hop, target))
            offset += storedHashSize
        }
    }
    editState.update { it.copy(insertionIntent = null, editablePath = hops) }
}

/** Appends a node to the path. No-op when full. Records the pubkey in recents. */
fun PathManagementViewModel.insert(publicKey: ByteArray, resolvableName: String) {
    if (jobs.isClosed) return
    if (isPathFull()) return
    val hop = PathHop(publicKey.prefixBytes(hashSize()), publicKey, resolvableName)
    editState.update { it.copy(editablePath = it.editablePath + hop) }
    jobs.launch { recordRecents(listOf(publicKey)) }
}

/** Removes the hop at [index], bounds-checked. */
fun PathManagementViewModel.removeRepeater(index: Int) = editState.update { state ->
    state.editablePath.removingAt(index)?.let { state.copy(editablePath = it) } ?: state
}

/** Moves the hop at [from] to [to] (drag-to-reorder). */
fun PathManagementViewModel.moveRepeater(from: Int, to: Int) = editState.update { state ->
    state.editablePath.moved(from, to)?.let { state.copy(editablePath = it) } ?: state
}

/** Sets the navigation intent that pushes the Add-Hop picker, or clears it. */
fun PathManagementViewModel.setInsertionIntent(intent: AddHopIntent?) =
    editState.update { it.copy(insertionIntent = intent) }

/** Loads the persisted recents for [radioID] into state. */
suspend fun PathManagementViewModel.loadRecentKeys(radioID: Uuid) {
    val loaded = recents.load(radioID)
    editState.update { it.copy(recentPublicKeys = loaded) }
}

internal suspend fun PathManagementViewModel.recordRecents(publicKeys: List<ByteArray>) {
    val radioID = dependencies.radioId() ?: return
    var current = editState.value.recentPublicKeys
    for (key in publicKeys) {
        current = recents.record(key, current, radioID)
    }
    editState.update { it.copy(recentPublicKeys = current) }
}
