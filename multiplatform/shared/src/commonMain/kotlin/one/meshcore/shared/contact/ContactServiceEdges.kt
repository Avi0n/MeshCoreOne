package one.meshcore.shared.contact

import one.meshcore.shared.model.Uuid
import one.meshcore.shared.settings.PersistentLogger

/** Self key used to protect the V-contact, plus the advert and discover logs. */
data class ContactServiceEdges(
    /** The connected radio's own public key for a radio id, used to protect the ZephCore V-contact. */
    val selfPublicKey: suspend (Uuid) -> ByteArray? = { null },
    val logger: PersistentLogger? = null,
    val discoverTrace: PersistentLogger? = null,
)
