package one.meshcore.shared.crypto

import java.math.BigInteger

private const val KEY_SIZE = 32
private const val SIGN_BYTE_INDEX = 31
private const val CLEAR_SIGN_MASK = 0x7F
private val P: BigInteger =
    BigInteger("7fffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffed", 16)

actual fun ed25519PublicToX25519(ed25519PublicKey: ByteArray): ByteArray? {
    if (ed25519PublicKey.size != KEY_SIZE) return null
    return runCatching {
        val yBytes = ed25519PublicKey.copyOf()
        yBytes[SIGN_BYTE_INDEX] = (yBytes[SIGN_BYTE_INDEX].toInt() and CLEAR_SIGN_MASK).toByte()
        val y = decodeLittleEndian(yBytes)
        val one = BigInteger.ONE
        val numerator = one.add(y).mod(P)
        val denominator = one.subtract(y).mod(P)
        if (denominator.signum() == 0) return null
        val u = numerator.multiply(denominator.modInverse(P)).mod(P)
        encodeLittleEndian(u)
    }.getOrNull()
}

private fun decodeLittleEndian(bytes: ByteArray): BigInteger {
    // BigInteger expects big-endian two's complement.
    return BigInteger(1, bytes.reversedArray())
}

private fun encodeLittleEndian(n: BigInteger): ByteArray {
    val be = n.toByteArray() // may include a leading zero sign byte
    val le = ByteArray(KEY_SIZE)
    val start = if (be.isNotEmpty() && be[0] == 0.toByte()) 1 else 0
    val len = (be.size - start).coerceAtMost(KEY_SIZE)
    for (i in 0 until len) {
        le[i] = be[be.size - 1 - i]
    }
    return le
}
