package one.meshcore.shared.crypto

/**
 * SHA-512 of [input]. Used for Ed25519 expanded-private-key construction (RFC 8032), which must
 * match Swift CryptoKit / swift-crypto byte-for-byte.
 */
expect fun sha512(input: ByteArray): ByteArray
