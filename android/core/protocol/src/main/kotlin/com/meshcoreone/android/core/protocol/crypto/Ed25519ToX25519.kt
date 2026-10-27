// PortedFrom: MeshCore/Sources/MeshCore/Protocol/Ed25519ToX25519.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.crypto

import com.meshcoreone.android.core.protocol.bytes.Bytes
import org.bouncycastle.math.ec.rfc7748.X25519Field
import org.bouncycastle.math.ec.rfc8032.Ed25519

object Ed25519ToX25519 {
    /**
     * Converts a canonical Ed25519 public key to the corresponding X25519 public key.
     *
     * @throws CryptoException.InvalidKeyLength when the input is not 32 bytes.
     * @throws CryptoException.InvalidEd25519PublicKey when the input is not a canonical,
     * non-identity prime-order Ed25519 point.
     */
    fun convertPublicKey(ed25519PublicKey: Bytes): Bytes {
        ed25519PublicKey.requireKeyFormat(KeyFormat.ED25519_PUBLIC)
        val encoded = ed25519PublicKey.toByteArray()
        if (!Ed25519.validatePublicKeyFull(encoded, 0)) throw CryptoException.InvalidEd25519PublicKey()
        encoded[31] = (encoded[31].toInt() and 0x7f).toByte()
        val y = X25519Field.create()
        val one = X25519Field.create()
        val numerator = X25519Field.create()
        val denominator = X25519Field.create()
        val inverse = X25519Field.create()
        val u = X25519Field.create()
        X25519Field.decode255(encoded, 0, y, 0)
        X25519Field.one(one)
        // Use the source's u = (1 + y) / (1 - y), with vetted field operations, not local limb arithmetic.
        X25519Field.add(one, y, numerator)
        X25519Field.sub(one, y, denominator)
        X25519Field.inv(denominator, inverse)
        X25519Field.mul(numerator, inverse, u)
        X25519Field.normalize(u)
        val output = ByteArray(KeyFormat.X25519_PUBLIC.size)
        X25519Field.encode(u, output, 0)
        return Bytes(output)
    }
}
