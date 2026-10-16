package one.meshcore.shared.contact

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/** Protects the connected radio's ZephCore V-contact from ordinary removal and pruning. */
internal class ContactIdentityGuard(private val selfPublicKey: suspend (Uuid) -> ByteArray?) {
    suspend fun isProtectedVContact(radioID: Uuid, publicKey: ByteArray): Boolean {
        val selfKey = runSelfPublicKeyRead { selfPublicKey(radioID) } ?: return false
        return VContactIdentity.isVContact(publicKey, selfKey)
    }

    suspend fun excludingProtectedVContact(radioID: Uuid, contacts: List<ContactDto>): List<ContactDto> =
        contacts.filterNot { isProtectedVContact(radioID, it.publicKey) }
}
