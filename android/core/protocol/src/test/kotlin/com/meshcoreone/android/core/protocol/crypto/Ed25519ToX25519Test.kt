// PortedFrom: MeshCore/Tests/MeshCoreTests/Ed25519ToX25519Tests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.crypto

import com.meshcoreone.android.core.protocol.bytes.Bytes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class Ed25519ToX25519Test {
    @Test
    fun `Public key conversion round-trip with independent RFC keys`() {
        val alice = CryptoFixtures.keys[0]
        val bob = CryptoFixtures.keys[1]
        val aliceScalar = Ed25519Crypto.x25519PrivateKeyFromSeed(alice.seed)
        val bobScalar = Ed25519Crypto.x25519PrivateKeyFromSeed(bob.seed)
        assertEquals(alice.xPrivate, aliceScalar)
        assertEquals(bob.xPrivate, bobScalar)
        val alicePublic = Ed25519ToX25519.convertPublicKey(alice.edPublic)
        val bobPublic = Ed25519ToX25519.convertPublicKey(bob.edPublic)
        assertEquals(alice.xPublic, alicePublic)
        assertEquals(bob.xPublic, bobPublic)
        val sharedAB = X25519Crypto.sharedSecret(aliceScalar, bobPublic)
        val sharedBA = X25519Crypto.sharedSecret(bobScalar, alicePublic)
        assertEquals(sharedAB, sharedBA)
        assertTrue(sharedAB != Bytes.EMPTY.paddedOrTruncated(32))
    }

    @Test
    fun `DM decrypt with converted Ed25519 keys`() {
        val sender = CryptoFixtures.keys[0]
        val recipient = CryptoFixtures.keys[1]
        val vector = CryptoFixtures.message("ed-converted-direct")
        val senderPublic = Ed25519ToX25519.convertPublicKey(sender.edPublic)
        val recipientScalar = Ed25519Crypto.x25519PrivateKeyFromExpanded(recipient.expanded)
        assertEquals(vector.publicKey, senderPublic)
        assertEquals(vector.privateKey, recipientScalar)
        assertEquals(vector.secret, X25519Crypto.sharedSecret(sender.xPrivate, recipient.xPublic))
        CryptoFixtures.assertDirect(vector)
    }

    @Test
    fun `Conversion rejects invalid inputs`() {
        for (length in listOf(0, 16)) {
            val error = assertFailsWith<CryptoException.InvalidKeyLength> {
                Ed25519ToX25519.convertPublicKey(Bytes.EMPTY.paddedOrTruncated(length))
            }
            assertEquals(KeyFormat.ED25519_PUBLIC, error.format)
            assertEquals(length, error.actual)
        }
        val malformedPoints = listOf(
            Bytes.EMPTY.paddedOrTruncated(32),
            Bytes.of(1).paddedOrTruncated(32),
            Bytes.fromHex("edffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff7f"),
        )
        for (point in malformedPoints) {
            assertFailsWith<CryptoException.InvalidEd25519PublicKey> {
                Ed25519ToX25519.convertPublicKey(point)
            }
        }
    }
}
