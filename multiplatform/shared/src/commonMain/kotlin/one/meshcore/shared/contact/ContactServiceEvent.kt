package one.meshcore.shared.contact

import one.meshcore.shared.EventBroadcaster
import one.meshcore.shared.meshcore.PathInfo
import one.meshcore.shared.model.Uuid

/**
 * One-to-many notifications broadcast by [ContactService] to its `events()` subscribers. Kotlin port of
 * the Swift `ContactServiceEvent`.
 */
sealed class ContactServiceEvent {
    /** Progress during a contact sync: [received] of [total] contacts persisted. */
    data class SyncProgress(val received: Int, val total: Int) : ContactServiceEvent()

    /** A contact was removed from the device, so node storage is no longer full. */
    data object NodeDeleted : ContactServiceEvent()

    /**
     * Radio contact-table capacity changed. `true` after a 0x90 contactsFull push; `false` after the
     * device (or user) frees a slot. Mirrors iOS `AdvertisementEvent.nodeStorageFullChanged`.
     */
    data class NodeStorageFullChanged(val isFull: Boolean) : ContactServiceEvent()

    /**
     * Direct-conversation messages were wiped for [contactID]. An open thread must reload so it
     * does not keep showing rows the store no longer has (iOS Info-sheet `onClearDirectMessages`).
     */
    data class MessagesCleared(val contactID: Uuid) : ContactServiceEvent()

    /**
     * Orphan incoming DMs were attached to [contactIDs] after an advert delta. The chats list
     * must reload; banners fire separately because those DMs never notified at receipt.
     */
    data class OrphanDirectMessagesAdopted(val contactIDs: List<Uuid>) : ContactServiceEvent()

    /**
     * A Room contact row was written from a 0x80 advertisement (last-advert stamp or auto-add).
     * Discover list listeners upsert a volatile row without a second `getContact` (iOS does contact
     * table + Discover upsert in one `handleAdvertEvent`).
     */
    /**
     * A decodable path-discovery response was accepted by the radio, or the length byte was reserved
     * and nothing was written. The open editor turns this into success or no-path. A raw mesh push is
     * not this signal: the radio write has to finish first.
     */
    data class PathDiscoveryAccepted(val path: PathInfo) : ContactServiceEvent()

    /**
     * The radio rejected a decodable out path, or no local contact matched it. The stored path is
     * unchanged. [error] is the contact-service failure the editor shows while discovery is still open.
     */
    data class PathDiscoveryWriteFailed(val path: PathInfo, val error: ContactServiceError) : ContactServiceEvent()

    data class ContactAdvertisementApplied(val radioID: Uuid, val publicKey: ByteArray) : ContactServiceEvent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is ContactAdvertisementApplied) return false
            return radioID == other.radioID && publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int = 31 * radioID.hashCode() + publicKey.contentHashCode()
    }
}

/**
 * Device table mutations iOS surfaces as `contactsVersion`: 0x80 auto-add / last-advert and 0x8F
 * overwrite-oldest. App-side Add/QR/import still use [listInvalidations].
 */
val ContactServiceEvent.reloadsContactUi: Boolean
    get() = this is ContactServiceEvent.ContactAdvertisementApplied ||
        this is ContactServiceEvent.NodeDeleted

/** Contact-service event multicast. Mirrors the Swift `EventBroadcaster`. */
typealias ContactServiceEventBroadcaster = EventBroadcaster<ContactServiceEvent>
