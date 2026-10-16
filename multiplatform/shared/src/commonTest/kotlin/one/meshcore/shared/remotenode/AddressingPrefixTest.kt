package one.meshcore.shared.remotenode

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class AddressingPrefixTest {
    @Test
    fun prefixStopsAtSixBytesAndKeepsAShorterKey() {
        val key = ByteArray(8) { it.toByte() }
        assertContentEquals(key.copyOf(PUBLIC_KEY_PREFIX_LENGTH), key.addressingPrefix())
        assertContentEquals(byteArrayOf(1, 2, 3), byteArrayOf(1, 2, 3).addressingPrefix())
        assertEquals(0, ByteArray(0).addressingPrefix().size)
    }

    @Test
    fun sessionPrefixUsesTheSameBytes() {
        val key = ByteArray(8) { (it + 1).toByte() }
        assertContentEquals(key.addressingPrefix(), testSession(publicKey = key).publicKeyPrefix)
    }
}
