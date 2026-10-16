package one.meshcore.shared.contact

import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MessageSentInfo

/**
 * [ContactSession] is only reached by device-driving contact ops. The contact-detail entry tests never
 * call them; they only need a session so [ContactService] can load the seeded row.
 */
internal class NoOpContactSession : ContactSession {
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
