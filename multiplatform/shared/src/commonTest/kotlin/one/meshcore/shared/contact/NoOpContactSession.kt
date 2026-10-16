package one.meshcore.shared.contact

import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MessageSentInfo

/**
 * Status tests need a [ContactSession] so [ContactService] can load a seeded row. They never drive a
 * radio, so every device operation is a no-op.
 */
internal object NoOpContactSession : ContactSession {
    override suspend fun getContacts(since: Long?): List<MeshContact> = emptyList()

    override suspend fun getContact(publicKey: ByteArray): MeshContact? = null

    override suspend fun addContact(contact: MeshContact) = Unit

    override suspend fun removeContact(publicKey: ByteArray) = Unit

    override suspend fun resetPath(publicKey: ByteArray) = Unit

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo =
        MessageSentInfo(route = 0, expectedAck = ByteArray(0), suggestedTimeoutMs = 0)

    override suspend fun shareContact(publicKey: ByteArray) = Unit

    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) = Unit
}
