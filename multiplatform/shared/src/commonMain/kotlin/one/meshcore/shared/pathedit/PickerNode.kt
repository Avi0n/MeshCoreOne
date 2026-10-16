package one.meshcore.shared.pathedit

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.contact.DiscoveredNodeRecord
import one.meshcore.shared.contact.contactType
import one.meshcore.shared.contact.displayName
import one.meshcore.shared.map.LatLng
import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.Uuid
import one.meshcore.shared.model.toUppercaseHex

/**
 * A passively-discovered node available to add as a hop. This is the path editor's projection of a
 * discovered node: identity, key, name, role, last-advert time, and optional position.
 */
class DiscoveredNode(
    val id: Uuid,
    val publicKey: ByteArray,
    val name: String,
    val isRepeater: Boolean = true,
    /** Advert timestamp, for breaking a prefix collision by recency as the iOS resolver does. */
    val lastAdvertTimestamp: Long = 0L,
    /** The last heard position, or null when the node has never reported a fix. */
    val location: LatLng? = null,
) {

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DiscoveredNode) return false
        return id == other.id &&
            publicKey.contentEquals(other.publicKey) &&
            name == other.name &&
            isRepeater == other.isRepeater
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + publicKey.contentHashCode()
        result = 31 * result + name.hashCode()
        result = 31 * result + isRepeater.hashCode()
        return result
    }
}

/**
 * Adapts a passively-heard [DiscoveredNodeRecord] (the Discover-screen store) to the Add-Hop picker's
 * minimal [DiscoveredNode] shape, so the picker's "Discovered" section reads real advert-heard nodes
 * instead of the permanently-empty default.
 */
fun DiscoveredNodeRecord.toPathEditDiscoveredNode(): DiscoveredNode = DiscoveredNode(
    id = id,
    publicKey = publicKey,
    name = name,
    isRepeater = contactType == ContactType.REPEATER,
    // Carried, not dropped: a hop known only from an advert otherwise resolves a name but never a pin,
    // which makes the trace distance readout fall back and the map omit the node.
    lastAdvertTimestamp = lastAdvertTimestamp,
    // (0,0) is the firmware's "no fix", not a position in the Atlantic.
    location = LatLng(latitude, longitude).takeIf { latitude != 0.0 || longitude != 0.0 },
)

/**
 * Unified node type for the Add-Hop picker, ported from the iOS `PickerNode`. Wraps either a known
 * [ContactDto] or a [DiscoveredNode] and exposes the derived fields the picker rows, filter, and
 * matching read, so callers never branch on the underlying kind.
 */
sealed interface PickerNode {
    val id: Uuid
    val displayName: String

    /** Full public key as uppercase hex. */
    val publicKeyHex: String

    /** The raw public key, for appending as a hop and recording into recents. */
    val publicKey: ByteArray

    /** The name resolution uses when this node becomes a hop (`displayName` for contacts, name for discovered). */
    val resolvableName: String

    val isRoom: Boolean
    val isDiscovered: Boolean

    /** Contacts flagged favorite; discovered nodes are never favorites (they carry no such field). */
    val isFavorite: Boolean

    data class Contact(val contact: ContactDto) : PickerNode {
        override val id: Uuid get() = contact.id
        override val displayName: String get() = contact.displayName
        override val publicKeyHex: String get() = contact.publicKey.toUppercaseHex()
        override val publicKey: ByteArray get() = contact.publicKey
        override val resolvableName: String get() = contact.displayName
        override val isRoom: Boolean get() = contact.contactType == ContactType.ROOM
        override val isDiscovered: Boolean get() = false
        override val isFavorite: Boolean get() = contact.isFavorite
    }

    data class Discovered(val node: DiscoveredNode) : PickerNode {
        override val id: Uuid get() = node.id
        override val displayName: String get() = node.name
        override val publicKeyHex: String get() = node.publicKey.toUppercaseHex()
        override val publicKey: ByteArray get() = node.publicKey
        override val resolvableName: String get() = node.name
        override val isRoom: Boolean get() = false
        override val isDiscovered: Boolean get() = true
        override val isFavorite: Boolean get() = false
    }
}
