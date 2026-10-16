package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/**
 * Process-lifetime JPEG bytes for channel cluster-end photos. Replace-all before bake or patch so
 * first paint never hits an empty map with a live revision. Tests bind [isolatedStorage] so parallel
 * suites do not share this map. Mirrors iOS `IncomingAvatarJPEGStore`.
 */
object IncomingAvatarJPEGStore {
    class Storage {
        var jpegByContactID: Map<Uuid, ByteArray> = emptyMap()
    }

    private val isolation = ProcessIsolation { Storage() }

    /** Test-only override. Production reads the process map. */
    internal var isolatedStorage: Storage?
        get() = isolation.isolatedStorage
        set(value) {
            isolation.isolatedStorage = value
        }

    private val storage: Storage
        get() = isolation.storage

    fun replace(contacts: List<ContactDto>, identities: Collection<IncomingAvatarIdentity>) {
        val jpegByID = buildMap {
            for (contact in contacts) {
                val data = contact.avatarImageData
                if (data != null && data.isNotEmpty()) put(contact.id, data.copyOf())
            }
        }
        val next = buildMap {
            for (identity in identities) {
                val contactID = identity.matchedContactID
                val data = if (contactID == null) null else jpegByID[contactID]
                if (contactID != null && data != null) put(contactID, data)
            }
        }
        storage.jpegByContactID = next
    }

    fun data(contactID: Uuid?): ByteArray? {
        if (contactID == null) return null
        return storage.jpegByContactID[contactID]
    }

    /** Runs [block] against a fresh map, then restores the previous isolation. */
    fun withIsolatedStorage(block: () -> Unit) = isolation.withIsolatedStorage(block)

    /** Suspend variant so a prime can run inside the isolated map. */
    suspend fun <T> withIsolatedStorageSuspending(block: suspend () -> T): T =
        isolation.withIsolatedStorageSuspending(block)
}
