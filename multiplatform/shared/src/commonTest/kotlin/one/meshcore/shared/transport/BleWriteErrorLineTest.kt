package one.meshcore.shared.transport

import kotlin.test.Test
import kotlin.test.assertEquals

class BleWriteErrorLineTest {
    @Test
    fun matchesIosHandleDidWriteValueLine() {
        assertEquals(
            "[BLE] Write error: seq=7, error=GATT INVALID ATTRIBUTE LENGTH",
            bleWriteErrorLine(7, "GATT INVALID ATTRIBUTE LENGTH"),
        )
    }
}
