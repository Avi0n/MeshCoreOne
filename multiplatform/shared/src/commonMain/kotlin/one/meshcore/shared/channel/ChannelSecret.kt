package one.meshcore.shared.channel

import one.meshcore.shared.crypto.sha256
import one.meshcore.shared.navigation.MeshCoreUrlParser

/**
 * Channel secret derivation and the slot-configured test — the cross-device wire contract that lets a
 * channel joined by passphrase or hashtag on one device decrypt the same traffic on another. Ported
 * byte-for-byte from the iOS `ChannelService` static helpers (`hashSecret`, `validateSecret`,
 * `isChannelConfigured`, `setupPublicChannel`). The digest must be byte-identical to Swift's
 * `CryptoKit.SHA256`, so it routes through the shared [sha256] platform delegate rather than a
 * hand-rolled hash.
 */
object ChannelSecret {
    /** Channel secret length in bytes (128-bit shared secret). One source of truth with the URL parser. */
    const val SIZE_BYTES: Int = MeshCoreUrlParser.CHANNEL_SECRET_SIZE_BYTES

    /** Reserved public-channel slot; user channels only ever occupy slots `1 until maxChannels`. */
    const val PUBLIC_CHANNEL_SLOT: Int = 0

    /** The public channel's fixed name. */
    const val PUBLIC_CHANNEL_NAME: String = "Public"

    /**
     * The public channel's fixed 16-byte secret. It is a hard-coded wire constant, not derived, and
     * must match byte-for-byte across every client — the wrong bytes silently decrypt no traffic.
     * Kept private and mutable-safe: callers get a fresh copy from [publicChannelSecret].
     */
    private val PUBLIC_CHANNEL_SECRET_BYTES: ByteArray = byteArrayOf(
        0x8B.toByte(), 0x33, 0x87.toByte(), 0xE9.toByte(),
        0xC5.toByte(), 0xCD.toByte(), 0xEA.toByte(), 0x6A,
        0xC9.toByte(), 0xE5.toByte(), 0xED.toByte(), 0xBA.toByte(),
        0xA1.toByte(), 0x15, 0xCD.toByte(), 0x72,
    )

    /**
     * Hashes a passphrase into a 16-byte channel secret: the first 16 bytes of its SHA-256, matching
     * the firmware. An empty passphrase is the sentinel for an unconfigured slot and returns 16 zero
     * bytes (never a hash), so it round-trips with [isChannelConfigured].
     */
    fun hashSecret(passphrase: String): ByteArray {
        if (passphrase.isEmpty()) return ByteArray(SIZE_BYTES)
        return sha256(passphrase.encodeToByteArray()).copyOf(SIZE_BYTES)
    }

    /** A secret is well-formed only at exactly [SIZE_BYTES]; used to gate `setChannelWithSecret`. */
    fun validateSecret(secret: ByteArray): Boolean = secret.size == SIZE_BYTES

    /**
     * Whether a channel slot should be treated as configured. A slot is unconfigured only when the
     * name is empty AND the secret is all zeros; an empty-named channel with a non-zero secret is
     * still configured (the firmware permits it and the app keeps it).
     */
    fun isChannelConfigured(name: String, secret: ByteArray): Boolean = name.isNotEmpty() || !isZeroSecret(secret)

    /** A fresh copy of the public-channel secret, safe for the caller to mutate or send. */
    fun publicChannelSecret(): ByteArray = PUBLIC_CHANNEL_SECRET_BYTES.copyOf()

    /**
     * Whether a secret carries stable cryptographic identity for `(radioID, secret)` reconciliation: a
     * non-empty, non-all-zero secret. An all-zero (or empty) secret marks an unconfigured slot, which is
     * keyed by slot index instead, so it never anchors a secret match. The public channel's well-known
     * constant is a stable secret like any other.
     */
    fun hasStableSecret(secret: ByteArray): Boolean = secret.isNotEmpty() && !isZeroSecret(secret)

    /**
     * The wire string for a hashtag channel named [cleanName] (without the leading `#`): the `#` plus
     * the clean name. Both the channel name and the hashed passphrase are this exact string, so the
     * `#` is part of the hashed input — stripping it before hashing derives a key no other client can
     * match. Callers must sanitize and validate [cleanName] before deriving the wire value.
     */
    fun hashtagChannelString(cleanName: String): String = "#$cleanName"

    /**
     * The secret for a hashtag channel `#cleanName`, derived so any device that types the same
     * hashtag decrypts the same traffic: `hashSecret("#cleanName")`.
     */
    fun hashtagChannelSecret(cleanName: String): ByteArray = hashSecret(hashtagChannelString(cleanName))

    private fun isZeroSecret(secret: ByteArray): Boolean = secret.all { it == ZERO_BYTE }

    private const val ZERO_BYTE: Byte = 0
}
