package one.meshcore.shared.chat

import one.meshcore.shared.backup.ContactDto
import one.meshcore.shared.model.Uuid

/**
 * Unique-name channel match for an incoming cluster-end avatar. [matchedContactID] is the local
 * contact id when [name] belongs to exactly one stored contact; otherwise the bubble draws initials.
 * Mirrors iOS `IncomingAvatarIdentity`.
 */
data class IncomingAvatarIdentity(
    val name: String,
    val matchedContactID: Uuid? = null,
    val imageRevision: Long? = null,
) {
    companion object {
        fun initials(name: String): IncomingAvatarIdentity =
            IncomingAvatarIdentity(name = name, matchedContactID = null, imageRevision = null)

        /**
         * Unique-name photo only when [senderNodeName] is non-empty after trim. [displayName] is
         * initials chrome, never a table key.
         */
        fun resolve(
            senderNodeName: String?,
            displayName: String,
            table: Map<String, IncomingAvatarIdentity>,
        ): IncomingAvatarIdentity {
            val raw = senderNodeName?.trim().orEmpty()
            if (raw.isNotEmpty()) {
                table[raw.lowercase()]?.let { return it }
            }
            return initials(name = displayName)
        }

        /** Process-lifetime token from JPEG bytes; do not persist. */
        fun revision(of: ByteArray?): Long? {
            if (of == null || of.isEmpty()) return null
            return of.contentHashCode().toLong()
        }

        /**
         * Unique lowered `contact.name` → avatar identity. A nickname is not required. Colliding
         * names are omitted. Mirrors iOS `MessageBubbleConfiguration.incomingAvatarIdentities`.
         */
        fun identitiesFrom(contacts: List<ContactDto>): Map<String, IncomingAvatarIdentity> {
            val counts = mutableMapOf<String, Int>()
            for (contact in contacts) {
                val key = contact.name.lowercase()
                counts[key] = (counts[key] ?: 0) + 1
            }
            val lookup = mutableMapOf<String, IncomingAvatarIdentity>()
            for (contact in contacts) {
                val key = contact.name.lowercase()
                if (counts[key] != 1) continue
                lookup[key] = IncomingAvatarIdentity(
                    name = contact.name,
                    matchedContactID = contact.id,
                    imageRevision = revision(contact.avatarImageData),
                )
            }
            return lookup
        }
    }
}
