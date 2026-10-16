package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex

/**
 * Compact public-key hex and the `meshcore://contact/add` URI used by Copy and Share. Matches iOS
 * `ContactShareContent`: Copy writes the unspaced hex; Share sends the URI (not a labeled dump).
 */
object ContactShareContent {
    /** How long Copy stays on the "Copied" label before reverting. */
    const val COPY_FEEDBACK_MS: Long = 2000

    fun compactPublicKeyHex(publicKey: ByteArray): String = publicKey.toUppercaseHex()

    fun uri(name: String, publicKey: ByteArray, type: ContactType): String =
        ContactService.exportContactURI(name = name, publicKey = publicKey, type = type)
}
