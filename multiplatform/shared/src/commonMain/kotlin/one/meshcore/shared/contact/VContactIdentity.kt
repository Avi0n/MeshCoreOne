package one.meshcore.shared.contact

import one.meshcore.shared.crypto.sha256

/**
 * Identifies ZephCore's loopback admin "V-contact" (chat CLI over BLE/USB), mirroring the Swift
 * `VContactIdentity`. Firmware derives its public key as `SHA256("zc-vcontact" || self_pub_key)` with no
 * private key; the contact is a virtual GET_CONTACTS tail entry, never a real contact-table slot. Used to
 * skip prune/overwrite cleanup and disable remove paths that would `CMD_REMOVE` and turn the feature off.
 */
object VContactIdentity {
    /** Salt used by ZephCore `CompanionMesh::begin()` (11 bytes, no NUL terminator). */
    private val salt: ByteArray = "zc-vcontact".encodeToByteArray()

    private const val PUBLIC_KEY_SIZE = 32

    /** The V-contact public key derived from a companion's own 32-byte public key, or null if the size is wrong. */
    fun publicKey(selfPublicKey: ByteArray): ByteArray? {
        if (selfPublicKey.size != PUBLIC_KEY_SIZE) return null
        return sha256(salt + selfPublicKey)
    }

    /**
     * Whether [publicKey] is the V-contact for [selfPublicKey]. Fail-open: returns false if either key is not
     * 32 bytes, so real overwrite cleanup is never skipped for a missing identity.
     */
    fun isVContact(publicKey: ByteArray, selfPublicKey: ByteArray): Boolean {
        if (publicKey.size != PUBLIC_KEY_SIZE) return false
        return publicKey.contentEquals(publicKey(selfPublicKey))
    }
}
