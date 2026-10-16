package one.meshcore.shared.crypto

/**
 * [count] cryptographically-secure random bytes. Used to mint a private channel's 16-byte secret, the
 * Kotlin analogue of the iOS `SecRandomCopyBytes` seed in `CreatePrivateChannelView`. The actual delegates
 * to the platform CSPRNG (`java.security.SecureRandom`) rather than the general-purpose `kotlin.random.Random`,
 * so a channel key is unpredictable.
 */
expect fun secureRandomBytes(count: Int): ByteArray
