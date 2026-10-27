// PortedFrom: MeshCore/Tests/MeshCoreTests/Protocol/DecodePathLenTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.core.protocol.primitives

import com.meshcoreone.android.core.protocol.model.PathEncoding
import com.meshcoreone.android.core.protocol.model.PathLenDecoded
import com.meshcoreone.android.core.protocol.model.decodePathLen
import com.meshcoreone.android.core.protocol.model.encodePathLen
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class PathEncodingTest {
    @Test
    fun `mode 0 with 5 hops`() = assertEquals(PathLenDecoded(1, 5, 5), decodePathLen(0x05u))

    @Test
    fun `mode 0 with 0 hops`() = assertEquals(PathLenDecoded(1, 0, 0), decodePathLen(0x00u))

    @Test
    fun `mode 0 with max hops 63`() = assertEquals(PathLenDecoded(1, 63, 63), decodePathLen(0x3fu))

    @Test
    fun `mode 1 with 3 hops`() = assertEquals(PathLenDecoded(2, 3, 6), decodePathLen(0x43u))

    @Test
    fun `mode 1 with max hops 63`() = assertEquals(PathLenDecoded(2, 63, 126), decodePathLen(0x7fu))

    @Test
    fun `mode 2 with 4 hops`() = assertEquals(PathLenDecoded(3, 4, 12), decodePathLen(0x84u))

    @Test
    fun `mode 2 with 0 hops`() = assertEquals(PathLenDecoded(3, 0, 0), decodePathLen(0x80u))

    @Test
    fun `mode 2 with max hops 63`() = assertEquals(PathLenDecoded(3, 63, 189), decodePathLen(0xbfu))

    @Test
    fun `mode 3 returns nil`() = assertNull(decodePathLen(0xc1u))

    @Test
    fun `mode 3 with zero hops returns nil`() = assertNull(decodePathLen(0xc0u))

    @Test
    fun `0xFF flood sentinel returns nil mode 3`() = assertNull(decodePathLen(0xffu))

    @Test
    fun `encode mode 0`() = assertEquals(0x05.toUByte(), encodePathLen(1, 5))

    @Test
    fun `encode mode 1`() = assertEquals(0x4a.toUByte(), encodePathLen(2, 10))

    @Test
    fun `encode mode 2 max hops`() = assertEquals(0xbf.toUByte(), encodePathLen(3, 63))

    @Test
    fun `encode clamps hop count to 63`() = assertEquals(63.toUByte(), encodePathLen(1, 100))

    @Test
    fun `encode decode round-trip`() {
        for (hashSize in 1..3) {
            for (hopCount in listOf(0, 1, 31, 63)) {
                assertEquals(
                    PathLenDecoded(hashSize, hopCount, hashSize * hopCount),
                    decodePathLen(encodePathLen(hashSize, hopCount)),
                )
            }
        }
    }

    @Test
    fun `encode accepts all valid hash sizes`() {
        for (hashSize in listOf(1, 2, 3)) {
            assertEquals(hashSize, decodePathLen(encodePathLen(hashSize, 1))?.hashSize)
        }
    }

    @Test
    fun `all raw encodings retain the exact reserved-mode behavior`() {
        for (value in 0..255) {
            val decoded = decodePathLen(value.toUByte())
            if (value >= 192) {
                assertNull(decoded)
            } else {
                assertEquals((value ushr 6) + 1, decoded?.hashSize)
                assertEquals(value and 63, decoded?.hopCount)
            }
        }
        assertEquals(2, PathEncoding.MAX_PATH_HASH_MODE)
        assertEquals(63, PathEncoding.MAX_HOP_COUNT)
        assertEquals(64, PathEncoding.MAX_PATH_BYTES)
        assertEquals(63.toUByte(), encodePathLen(1, Int.MAX_VALUE))
    }

    @Test
    fun `invalid hash sizes fail before path length packing`() {
        assertFailsWith<IllegalArgumentException> { encodePathLen(0, 1) }
        assertFailsWith<IllegalArgumentException> { encodePathLen(4, 1) }
    }

    @Test
    fun `negative hop count is rejected before path length packing`() {
        assertFailsWith<IllegalArgumentException> { encodePathLen(1, -1) }
    }
}
