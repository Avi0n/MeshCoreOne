package one.meshcore.shared.contact

import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.model.Uuid

/**
 * Device push 0x80 advertisement — contact-table half of iOS `handleAdvertEvent`.
 * Known local row: do not rewrite radio lastModified / lastAdvertTimestamp (those stay
 * radio-sourced; iOS stamps only lastHeard). Unknown: `getContact` then persist the device
 * frame so auto-add mode actually adds the contact.
 * While [setSyncingContacts] is true, unknown keys are queued and fetched after sync ends (BLE
 * contention guard). A delta handler queues both known and unknown keys for GET_CONTACTS so a
 * hear can refresh lastAdvert/name/coords from the radio table. Discover upserts from
 * lastHeard dual-write and from the post-GET_CONTACTS [reconcileAdvertDeltaDiscover] pass.
 *
 * @return true when a Room contact row was written.
 */
suspend fun ContactService.handleAdvertisement(radioID: Uuid, publicKey: ByteArray): Boolean =
    contactAdvertFetch.handleAdvertisement(radioID, publicKey)

/**
 * Fetches one unknown contact from the device and persists it. Returns false when the device has
 * no row or the session call fails. Used by live 0x80 auto-add and the post-sync deferred drain.
 * Yields [ContactServiceEvent.ContactAdvertisementApplied] so Discover can upsert without a second
 * device round-trip.
 */
internal suspend fun ContactService.fetchAndSaveUnknownContact(
    radioID: Uuid,
    publicKey: ByteArray,
    heardAt: Long? = null,
): Boolean = contactAdvertFetch.fetchAndSaveUnknownContact(radioID, publicKey, heardAt)

internal suspend fun ContactService.isAdvertFetchCancelled(publicKey: ByteArray): Boolean =
    contactAdvertFetch.isAdvertFetchCancelled(publicKey)

/**
 * Applies a path-discovery response. A decodable out path is sent to the radio before the phone row
 * changes; a rejection leaves the stored path alone and yields
 * [ContactServiceEvent.PathDiscoveryWriteFailed]. A reserved length byte is not written. Mirrors iOS
 * `handlePathDiscoveryResponse`.
 *
 * @return true when the response is reported as accepted, including a reserved length that wrote nothing.
 */
suspend fun ContactService.applyPathDiscoveryResponse(radioID: Uuid, path: PathInfo): Boolean =
    contactPathResponseHandler.apply(radioID, path)
