package one.meshcore.shared.settings

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import one.meshcore.shared.crypto.secureRandomBytes
import one.meshcore.shared.model.toUppercaseHex

/**
 * Generates MeshCore-compatible Ed25519 identities, optionally matching a vanity public-key hex
 * prefix. Mirrors iOS `KeyGenerationService` (local generation; radio import is a separate step).
 */
object KeyGenerationService {
    const val SEED_SIZE = 32
    const val PUBLIC_KEY_SIZE = 32
    const val EXPANDED_PRIVATE_KEY_SIZE = 64

    private const val RESERVED_BYTE_00: Byte = 0x00
    private const val RESERVED_BYTE_FF: Byte = 0xFF.toByte()
    private const val BASE_MAX_ATTEMPTS = 10_000
    private const val ATTEMPTS_MULTIPLIER = 20
    private const val HEX_BASE = 16
    private const val BYTE_MASK = 0xFF
    private const val SCALAR_LAST_INDEX = 31
    private const val CLAMP_LOW_MASK = 0x07
    private const val CLAMP_HIGH_CLEAR_MASK = 0x80
    private const val CLAMP_HIGH_SET_MASK = 0x40

    data class GeneratedIdentity(val expandedPrivateKey: ByteArray, val publicKey: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is GeneratedIdentity) return false
            return expandedPrivateKey.contentEquals(other.expandedPrivateKey) &&
                publicKey.contentEquals(other.publicKey)
        }

        override fun hashCode(): Int = 31 * expandedPrivateKey.contentHashCode() + publicKey.contentHashCode()
    }

    sealed class Error : Exception() {
        data object MaxAttemptsExceeded : Error()
        data object ReservedPrefix : Error()
    }

    /**
     * Derives public key and expanded private key from a 32-byte seed.
     * Production uses platform Ed25519; tests inject fakes.
     */
    fun interface KeypairFromSeed {
        fun derive(seed: ByteArray): GeneratedIdentity
    }

    /**
     * @param hexPrefix optional 1–4 hex chars (already validated / uppercased preferred). Null or
     * empty accepts any non-reserved public key.
     * @param randomSeed supplies 32 random bytes per attempt (default: secure RNG).
     * @param keypairFromSeed seed → key material (default: platform Ed25519).
     */
    suspend fun generateIdentity(
        hexPrefix: String?,
        randomSeed: () -> ByteArray = { secureRandomBytes(SEED_SIZE) },
        keypairFromSeed: KeypairFromSeed = PlatformKeypairFromSeed,
    ): GeneratedIdentity {
        val prefix = validatedPrefixOrNull(hexPrefix)
        val attempts = maxAttempts(forPrefixLength = prefix?.length ?: 0)

        repeat(attempts) {
            currentCoroutineContext().ensureActive()
            val seed = randomSeed()
            require(seed.size == SEED_SIZE) { "Seed must be $SEED_SIZE bytes" }
            val pair = keypairFromSeed.derive(seed)
            require(pair.publicKey.size == PUBLIC_KEY_SIZE)
            require(pair.expandedPrivateKey.size == EXPANDED_PRIVATE_KEY_SIZE)

            val first = pair.publicKey[0]
            if (first == RESERVED_BYTE_00 || first == RESERVED_BYTE_FF) {
                return@repeat
            }
            if (prefix != null) {
                val publicHex = pair.publicKey.toUppercaseHex()
                if (!VanityPrefix.matchesPrefix(publicHex, prefix)) {
                    return@repeat
                }
            }
            return pair
        }
        throw Error.MaxAttemptsExceeded
    }

    /** ~20× the expected attempts for a uniform hex prefix of the given length. */
    fun maxAttempts(forPrefixLength: Int): Int {
        if (forPrefixLength <= 0) return BASE_MAX_ATTEMPTS
        // 16^length expected trials for a uniform hex prefix.
        var expected = 1
        repeat(forPrefixLength) { expected *= HEX_BASE }
        return maxOf(BASE_MAX_ATTEMPTS, expected * ATTEMPTS_MULTIPLIER)
    }

    /**
     * Validates RFC 8032 bit clamping on a [EXPANDED_PRIVATE_KEY_SIZE]-byte expanded private key's
     * scalar half: byte 0's lowest 3 bits clear, byte 31's highest bit clear and second-highest set.
     * Mirrors iOS `KeyGenerationService.validateExpandedKey`.
     */
    fun validateExpandedKey(data: ByteArray): Boolean {
        if (data.size != EXPANDED_PRIVATE_KEY_SIZE) return false
        val scalarFirst = data[0].toInt() and BYTE_MASK
        val scalarLast = data[SCALAR_LAST_INDEX].toInt() and BYTE_MASK
        return scalarFirst and CLAMP_LOW_MASK == 0 &&
            scalarLast and CLAMP_HIGH_CLEAR_MASK == 0 &&
            scalarLast and CLAMP_HIGH_SET_MASK == CLAMP_HIGH_SET_MASK
    }

    private fun validatedPrefixOrNull(hexPrefix: String?): String? {
        val cleaned = hexPrefix?.trim().orEmpty()
        if (cleaned.isEmpty()) return null
        return when (VanityPrefix.validate(cleaned)) {
            VanityPrefix.Validation.Reserved -> throw Error.ReservedPrefix
            VanityPrefix.Validation.InvalidLength,
            VanityPrefix.Validation.InvalidHex,
            -> throw IllegalArgumentException("Invalid vanity prefix: $cleaned")
            VanityPrefix.Validation.Ok -> cleaned
        }
    }
}
