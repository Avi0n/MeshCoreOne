package one.meshcore.shared.crypto

/**
 * Converts a 32-byte Ed25519 public key to a 32-byte X25519 public key (birational map).
 * Ports Swift `Ed25519ToX25519.convertPublicKey` / libsodium `crypto_sign_ed25519_pk_to_curve25519`.
 *
 * The key agreement this feeds runs below the bridge, in MeshCore's `DirectMessageCrypto`. This
 * conversion stays here because it selects which candidate keys to hand down, which is the same
 * app-tier job iOS `RxLogService` does.
 */
expect fun ed25519PublicToX25519(ed25519PublicKey: ByteArray): ByteArray?
