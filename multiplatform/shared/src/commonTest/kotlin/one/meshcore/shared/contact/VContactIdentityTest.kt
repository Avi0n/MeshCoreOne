package one.meshcore.shared.contact

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Freezes the ZephCore V-contact key derivation `SHA256("zc-vcontact" || self_pub_key)`. The expected hash
 * is computed independently (Python hashlib), not from the code under test, so a drift in salt or ordering
 * is caught rather than confirmed.
 */
class VContactIdentityTest {
    private val selfKey = ByteArray(32) { 0x11 }

    // SHA256(b"zc-vcontact" + bytes([0x11]*32)) computed with Python hashlib.
    private val expected = byteArrayOf(
        0xE5.toByte(), 0xA6.toByte(), 0x5F, 0x01, 0x70, 0x3E, 0xFC.toByte(), 0x92.toByte(),
        0x4C, 0x49, 0xB3.toByte(), 0xB5.toByte(), 0x5C, 0x60, 0x19, 0x92.toByte(),
        0x49, 0x61, 0xE9.toByte(), 0x4F, 0x8D.toByte(), 0xEF.toByte(), 0x4C, 0x78,
        0xC7.toByte(), 0xE1.toByte(), 0xE2.toByte(), 0x8E.toByte(), 0x28, 0x9B.toByte(), 0x2E, 0xA1.toByte(),
    )

    @Test
    fun derivesTheKnownKey() {
        assertContentEquals(expected, VContactIdentity.publicKey(selfKey))
    }

    @Test
    fun isVContactMatchesTheDerivedKey() {
        assertTrue(VContactIdentity.isVContact(publicKey = expected, selfPublicKey = selfKey))
    }

    @Test
    fun isVContactRejectsAnUnrelatedKey() {
        assertFalse(VContactIdentity.isVContact(publicKey = ByteArray(32) { 0x22 }, selfPublicKey = selfKey))
    }

    @Test
    fun failsOpenOnWrongSizes() {
        assertNull(VContactIdentity.publicKey(ByteArray(31)))
        assertFalse(VContactIdentity.isVContact(publicKey = ByteArray(16), selfPublicKey = selfKey))
        assertFalse(VContactIdentity.isVContact(publicKey = expected, selfPublicKey = ByteArray(16)))
    }

    @Test
    fun differsPerSelfKey() {
        assertEquals(32, VContactIdentity.publicKey(ByteArray(32) { 0x33 })?.size)
        assertFalse(
            VContactIdentity.publicKey(selfKey).contentEquals(VContactIdentity.publicKey(ByteArray(32) { 0x33 })),
        )
    }
}
