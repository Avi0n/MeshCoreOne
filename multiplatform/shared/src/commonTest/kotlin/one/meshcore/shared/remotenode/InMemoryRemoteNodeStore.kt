package one.meshcore.shared.remotenode

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.ContactFrame
import one.meshcore.shared.model.Uuid

/**
 * In-memory [RemoteNodeStore] behind a [kotlinx.coroutines.sync.Mutex]. The reference implementation of the
 * session reads/writes and the duplicate-cleanup rule the Room adapter must reproduce, and the test backing.
 * Contact routing (which a real store reads from the radio contact table) is modelled by a settable map so
 * the keep-alive direct-path check is testable in isolation.
 */
class InMemoryRemoteNodeStore private constructor(private val parts: InMemoryRemoteNodeParts) :
    RemoteNodeStore by parts.sessions {
    constructor() : this(InMemoryRemoteNodeParts())

    /** Every frame passed to [RemoteNodeContactStore.saveContact], for asserting a login-heal reroute. Test-only. */
    val savedContactFrames: MutableList<ContactFrame>
        get() = parts.contacts.savedContactFrames

    /** Seeds a contact's flood-routed flag for `(radioID, publicKey)`; absent means "contact not found". Test-only. */
    suspend fun setContactRouting(radioID: Uuid, publicKey: ByteArray, isFloodRouted: Boolean) =
        parts.contacts.setContactRouting(radioID, publicKey, isFloodRouted)

    /** Seeds the `(radioID, publicKey)` contact row [RemoteNodeContactStore.fetchContact] returns. Test-only. */
    suspend fun setContact(radioID: Uuid, dto: ContactDto) = parts.contacts.setContact(radioID, dto)
}
