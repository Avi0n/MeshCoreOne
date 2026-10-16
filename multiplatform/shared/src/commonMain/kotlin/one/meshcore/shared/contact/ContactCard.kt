package one.meshcore.shared.contact

import one.meshcore.shared.model.ContactType
import one.meshcore.shared.model.toUppercaseHex

/**
 * A contact identity carried between devices by the two cross-device wire formats: the
 * `meshcore://contact/add` deep-link / QR URI ([ContactCardUri]) and the `<hex:type:name>` chat
 * share token ([ContactShareToken]). Mirrors the iOS `ContactResult`; both wire formats produce and
 * consume this type so emit and decode never disagree on shape.
 *
 * [id] is the uppercase-hex public key — the stable identity used for equality and navigation, matching
 * iOS `ContactResult.id`.
 */
class ContactCard(val name: String, val publicKey: ByteArray, val contactType: ContactType) {
    val id: String get() = publicKey.toUppercaseHex()

    override fun equals(other: Any?): Boolean = this === other ||
        (
            other is ContactCard &&
                name == other.name &&
                contactType == other.contactType &&
                publicKey.contentEquals(other.publicKey)
            )

    override fun hashCode(): Int = 31 * (31 * name.hashCode() + publicKey.contentHashCode()) + contactType.hashCode()

    override fun toString(): String = "ContactCard(name=$name, id=$id, type=$contactType)"
}
