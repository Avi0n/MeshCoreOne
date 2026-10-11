// AndroidOnly: WP-407 Deterministic camera stride/crop/rotation families; not camera-device acceptance.
package com.meshcoreone.android.app.platform

import java.nio.ByteBuffer
import org.junit.Assert.*
import org.junit.Test

class QrLumaTest {
    @Test fun rowAndPixelStrideAndBufferOriginPreservePixels() {
        val buffer = ByteBuffer.wrap(byteArrayOf(99, 1, 0, 2, 0, 0, 0, 3, 0, 4))
        buffer.position(1)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), packLuma(buffer, 6, 2, 0, 0, 2, 2, 0).bytes)
        assertEquals(1, buffer.position())
    }
    @Test fun allSensorRotationsAndCropProducePackedQrOrientation() {
        val buffer = ByteBuffer.wrap(byteArrayOf(0, 1, 2, 0, 3, 4))
        val expected = mapOf(0 to byteArrayOf(1, 2, 3, 4), 90 to byteArrayOf(3, 1, 4, 2),
            180 to byteArrayOf(4, 3, 2, 1), 270 to byteArrayOf(2, 4, 1, 3))
        expected.forEach { (rotation, pixels) ->
            assertArrayEquals(pixels, packLuma(buffer, 3, 1, 1, 0, 2, 2, rotation).bytes)
        }
    }
    @Test fun truncatedPlaneFailsRatherThanProducingPartialQrBytes() {
        assertThrows(IllegalArgumentException::class.java) {
            packLuma(ByteBuffer.wrap(byteArrayOf(1)), 2, 1, 0, 0, 2, 2, 0)
        }
    }
}
