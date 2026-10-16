package one.meshcore.shared.tools

import kotlin.test.Test
import kotlin.test.assertEquals

/** Firmware-parity output formatting, mirroring the Swift `LocalCommandOutput` behavior. */
class LocalCommandOutputTest {
    @Test
    fun decimalTrimsTrailingZeros() {
        assertEquals("869.525", LocalCommandOutput.decimal(869.525))
        assertEquals("250", LocalCommandOutput.decimal(250.0))
        assertEquals("0", LocalCommandOutput.decimal(0.0))
    }

    @Test
    fun coordinateTrimsToSixPlaces() {
        assertEquals("47.49", LocalCommandOutput.coordinate(47.49))
        assertEquals("-120.33", LocalCommandOutput.coordinate(-120.33))
    }

    @Test
    fun hexIsUppercaseTwoDigitsPerByte() {
        assertEquals("0AFF00", LocalCommandOutput.hex(byteArrayOf(0x0A, 0xFF.toByte(), 0x00)))
    }

    @Test
    fun clockFormatsUtc() {
        assertEquals("00:00 - 1/1/1970 UTC", LocalCommandOutput.clock(0))
        assertEquals("01:46 - 9/9/2001 UTC", LocalCommandOutput.clock(1_000_000_000))
    }

    @Test
    fun clockSetEchoesWrittenTime() {
        assertEquals("OK - clock set: 01:46 - 9/9/2001 UTC", LocalCommandOutput.clockSet(1_000_000_000))
    }

    @Test
    fun customVarListSortsKeysWithHeader() {
        assertEquals("2 vars\na=1\nb=2", LocalCommandOutput.customVarList(mapOf("b" to "2", "a" to "1")))
        assertEquals("no custom var", LocalCommandOutput.customVarList(emptyMap()))
    }

    @Test
    fun customVarMessages() {
        assertEquals("> hi", LocalCommandOutput.value("hi"))
        assertEquals("Unknown var x", LocalCommandOutput.unknownVar("x"))
        assertEquals("Var k set to v", LocalCommandOutput.customVarSet("k", "v"))
    }
}
