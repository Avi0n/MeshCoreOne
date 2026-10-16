package one.meshcore.shared.contact

import one.meshcore.shared.meshcore.ContactFetchResult
import one.meshcore.shared.meshcore.MeshContact
import one.meshcore.shared.meshcore.MessageSentInfo

/**
 * Firmware `ProtocolError` sub-codes a contact command can surface. Pinned to the wire values; the
 * production session adapter maps a bridge `DEVICE_ERROR` to a [ContactDeviceException] carrying one of
 * these, and [ContactService] translates the code into a typed [ContactServiceError].
 */
object ContactProtocolErrorCode {
    const val NOT_FOUND: Int = 0x02
    const val TABLE_FULL: Int = 0x03
}

/**
 * A device error surfaced by a [ContactSession] command. [firmwareCode] is the radio's
 * `ProtocolError` sub-code (see [ContactProtocolErrorCode]); [ContactService] maps `TABLE_FULL`/
 * `NOT_FOUND` to typed errors and wraps anything else as [ContactServiceError.SessionError]. Mirrors the
 * Swift `MeshCoreError.deviceError(code)`. The production adapter maps a bridge device error to this;
 * fakes throw it directly.
 */
class ContactDeviceException(val firmwareCode: Int) : Exception("device error $firmwareCode")

/**
 * The device-session seam [ContactService] depends on: the contact commands the service issues. A narrow
 * port over the androidMain-only `MeshCoreSession` so the service stays in commonMain and is
 * independently testable. Each method mirrors a `MeshCoreSession` contact operation; a device failure is
 * raised as a [ContactDeviceException]. [one.meshcore.shared.connection.ContactSessionAdapter] bridges the
 * live session.
 */
interface ContactSession {
    /** Fetches contacts modified after [since] epoch seconds; `null` for the full list. */
    suspend fun getContacts(since: Long? = null): List<MeshContact>

    /**
     * Fetches contacts plus the device's `contactsStart` total. Default treats the received list as
     * complete so existing fakes still prune; production overrides with the header value.
     */
    suspend fun getContactsReportingTotal(since: Long? = null): ContactFetchResult {
        val contacts = getContacts(since)
        return ContactFetchResult(contacts = contacts, reportedTotal = contacts.size)
    }

    /**
     * Reads one contact by full 32-byte public key from the device, or `null` if absent. Used to refresh
     * local path state after a [one.meshcore.shared.meshcore.MeshEvent.PathUpdate] push.
     */
    suspend fun getContact(publicKey: ByteArray): MeshContact?

    /** Adds or updates a whole contact on the device. */
    suspend fun addContact(contact: MeshContact)

    /** Removes a contact from the device by its 32-byte public key. */
    suspend fun removeContact(publicKey: ByteArray)

    /** Resets a contact's out-path to flood routing on the device. */
    suspend fun resetPath(publicKey: ByteArray)

    /** Sends a path-discovery request; returns the firmware's estimated timeout. */
    suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo

    /** Zero-hop broadcast of a contact's advertisement so nearby nodes can add it. */
    suspend fun shareContact(publicKey: ByteArray)

    /** Writes new contact flags (bits control favorite + telemetry permissions), waiting for the ack. */
    suspend fun changeContactFlags(contact: MeshContact, flags: Int)
}
