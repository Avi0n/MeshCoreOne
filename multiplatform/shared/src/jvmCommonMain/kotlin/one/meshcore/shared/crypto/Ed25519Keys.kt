package one.meshcore.shared.crypto

import java.math.BigInteger
import java.util.Arrays

/**
 * Ed25519 key material used for identity regeneration.
 *
 * Matches MeshCore's 64-byte expanded private key (SHA-512 of the 32-byte seed with RFC 8032 bit
 * clamping) and the corresponding 32-byte public key. Parity target: iOS
 * `KeyGenerationService` (CryptoKit `Curve25519.Signing.PrivateKey`).
 */
object Ed25519Keys {
    const val SEED_SIZE = 32
    const val PUBLIC_KEY_SIZE = 32
    const val EXPANDED_PRIVATE_KEY_SIZE = 64

    private const val CLAMP_CLEAR_LOW3 = 0xF8
    private const val CLAMP_CLEAR_HIGH = 0x7F
    private const val CLAMP_SET_SECOND_HIGH = 0x40
    private const val SCALAR_BITS = 256
    private const val SIGN_BIT = 0x80
    private const val LAST_KEY_BYTE_INDEX = 31
    private const val BITS_PER_BYTE_SHIFT = 3
    private const val BYTE_MASK = 0xFF
    private const val BIT_INDEX_MASK = 7

    /**
     * Expands a 32-byte seed to MeshCore's 64-byte private-key format (SHA-512 + clamp on the
     * scalar half).
     */
    fun expandSeed(seed: ByteArray): ByteArray {
        require(seed.size == SEED_SIZE) { "Ed25519 seed must be $SEED_SIZE bytes" }
        val expanded = sha512(seed)
        expanded[0] = (expanded[0].toInt() and CLAMP_CLEAR_LOW3).toByte()
        expanded[LAST_KEY_BYTE_INDEX] = (expanded[LAST_KEY_BYTE_INDEX].toInt() and CLAMP_CLEAR_HIGH).toByte()
        expanded[LAST_KEY_BYTE_INDEX] = (expanded[LAST_KEY_BYTE_INDEX].toInt() or CLAMP_SET_SECOND_HIGH).toByte()
        return expanded
    }

    /** Derives the 32-byte Ed25519 public key for a 32-byte seed (RFC 8032). */
    fun publicKeyFromSeed(seed: ByteArray): ByteArray {
        require(seed.size == SEED_SIZE) { "Ed25519 seed must be $SEED_SIZE bytes" }
        val expanded = expandSeed(seed)
        val scalar = Arrays.copyOfRange(expanded, 0, SEED_SIZE)
        return scalarBaseMult(scalar)
    }

    // --- Curve25519 field + Edwards base-point multiply (ref10-style, BigInteger) ---

    private val P: BigInteger =
        BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)
    private val D: BigInteger =
        BigInteger("52036cee2b6ffe738cc740797779e89800700a4d4141d8ab75eb4dca135978a3", 16)

    // Base point B = (x, 4/5) in extended coordinates (X:Y:Z:T)
    private val BX: BigInteger =
        BigInteger("216936d3cd6e53fec0a4e231fdd6dc5c692cc7609525a7b2c9562d608f25d51a", 16)
    private val BY: BigInteger =
        BigInteger("6666666666666666666666666666666666666666666666666666666666666658", 16)

    private data class ExtPoint(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    private fun scalarBaseMult(scalar: ByteArray): ByteArray {
        var result = ExtPoint(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)
        var base = ExtPoint(BX, BY, BigInteger.ONE, BX.multiply(BY).mod(P))
        for (i in 0 until SCALAR_BITS) {
            if (bit(scalar, i) == 1) {
                result = edAdd(result, base)
            }
            base = edAdd(base, base)
        }
        return encodePoint(result)
    }

    private fun bit(s: ByteArray, i: Int): Int {
        val byte = s[i shr BITS_PER_BYTE_SHIFT].toInt() and BYTE_MASK
        return (byte shr (i and BIT_INDEX_MASK)) and 1
    }

    private fun edAdd(p: ExtPoint, q: ExtPoint): ExtPoint {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(BigInteger.valueOf(2)).multiply(D).multiply(q.t).mod(P)
        val d = p.z.multiply(BigInteger.valueOf(2)).multiply(q.z).mod(P)
        val e = b.subtract(a).mod(P)
        val f = d.subtract(c).mod(P)
        val g = d.add(c).mod(P)
        val h = b.add(a).mod(P)
        return ExtPoint(
            x = e.multiply(f).mod(P),
            y = g.multiply(h).mod(P),
            z = f.multiply(g).mod(P),
            t = e.multiply(h).mod(P),
        )
    }

    private fun encodePoint(p: ExtPoint): ByteArray {
        val zInv = p.z.modInverse(P)
        val x = p.x.multiply(zInv).mod(P)
        val y = p.y.multiply(zInv).mod(P)
        val out = y.toFixed32Le()
        if (x.testBit(0)) {
            out[LAST_KEY_BYTE_INDEX] = (out[LAST_KEY_BYTE_INDEX].toInt() or SIGN_BIT).toByte()
        }
        return out
    }

    private fun BigInteger.toFixed32Le(): ByteArray {
        val raw = toByteArray() // big-endian two's complement, may include a sign byte
        val le = ByteArray(PUBLIC_KEY_SIZE)
        val srcEnd = raw.size
        // Skip leading zero sign byte if present.
        val srcStart = if (raw.isNotEmpty() && raw[0] == 0.toByte()) 1 else 0
        val len = (srcEnd - srcStart).coerceAtMost(PUBLIC_KEY_SIZE)
        for (i in 0 until len) {
            le[i] = raw[srcEnd - 1 - i]
        }
        return le
    }
}
