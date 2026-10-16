package one.meshcore.shared.model

import one.meshcore.shared.tools.LocalCommandOutput
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ByteHexTest {
    @Test
    fun `every unsigned byte retains two digits and requested casing`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertEquals(ALL_BYTES_HEX, bytes.toLowercaseHex())
        assertEquals(ALL_BYTES_HEX.uppercase(), bytes.toUppercaseHex())
        assertEquals(ALL_BYTES_HEX.uppercase(), LocalCommandOutput.hex(bytes))
    }

    @Test
    fun `both cases decode all unsigned bytes`() {
        val bytes = ByteArray(256) { it.toByte() }
        assertContentEquals(bytes, decodeHex(ALL_BYTES_HEX))
        assertContentEquals(bytes, decodeHex(ALL_BYTES_HEX.uppercase()))
        assertContentEquals(byteArrayOf(0x0A, 0x80.toByte(), 0xFF.toByte()), decodeHex("0a80Ff"))
    }

    @Test
    fun `empty input remains empty`() {
        assertEquals("", byteArrayOf().toLowercaseHex())
        assertEquals("", byteArrayOf().toUppercaseHex())
        assertContentEquals(byteArrayOf(), decodeHex(""))
    }

    @Test
    fun `malformed hex returns null without trimming or accepting separators`() {
        for (input in listOf("0", "abc", "0g", "gg", " 00", "00 ", "00-ff", "0x00", "00\n")) {
            assertNull(decodeHex(input), input)
        }
    }

    @Test
    fun `prefix hex stops at the requested width and the array length`() {
        val bytes = byteArrayOf(0xA3.toByte(), 0x7F, 0x42)
        assertEquals("A3", bytes.prefixHex(1))
        assertEquals("A37F42", bytes.prefixHex(3))
        assertEquals("A37F42", bytes.prefixHex(8))
        assertEquals("", bytes.prefixHex(0))
        assertContentEquals(byteArrayOf(0xA3.toByte()), bytes.prefixBytes(1))
        assertContentEquals(bytes, bytes.prefixBytes(8))
    }

    @Test
    fun `decoder retains unicode digit acceptance`() {
        assertContentEquals(byteArrayOf(0x12), decodeHex("\u0661\u0662"))
        assertContentEquals(byteArrayOf(0x12), decodeHex("\uFF11\uFF12"))
    }

    @Test
    fun `a prefix matches only the leading bytes`() {
        val key = byteArrayOf(0x01, 0x02, 0x03)
        assertTrue(key.startsWithPrefix(byteArrayOf(0x01, 0x02)))
        assertTrue(key.startsWithPrefix(byteArrayOf()))
        assertFalse(key.startsWithPrefix(byteArrayOf(0x01, 0x09)))
        assertFalse(key.startsWithPrefix(byteArrayOf(0x01, 0x02, 0x03, 0x04)))
    }
}

private const val ALL_BYTES_HEX =
    "000102030405060708090a0b0c0d0e0f" +
        "101112131415161718191a1b1c1d1e1f" +
        "202122232425262728292a2b2c2d2e2f" +
        "303132333435363738393a3b3c3d3e3f" +
        "404142434445464748494a4b4c4d4e4f" +
        "505152535455565758595a5b5c5d5e5f" +
        "606162636465666768696a6b6c6d6e6f" +
        "707172737475767778797a7b7c7d7e7f" +
        "808182838485868788898a8b8c8d8e8f" +
        "909192939495969798999a9b9c9d9e9f" +
        "a0a1a2a3a4a5a6a7a8a9aaabacadaeaf" +
        "b0b1b2b3b4b5b6b7b8b9babbbcbdbebf" +
        "c0c1c2c3c4c5c6c7c8c9cacbcccdcecf" +
        "d0d1d2d3d4d5d6d7d8d9dadbdcdddedf" +
        "e0e1e2e3e4e5e6e7e8e9eaebecedeeef" +
        "f0f1f2f3f4f5f6f7f8f9fafbfcfdfeff"
