// PortedFrom: MC1Tests/Extensions/DataExtensionsTests.swift@db14559b39d32322b06477c6ae676112f583db50
package com.meshcoreone.android.parity

import com.meshcoreone.android.app.container.OriginalCase
import com.meshcoreone.android.core.model.applicationBytesFromHex
import com.meshcoreone.android.core.model.uppercaseHexString
import com.meshcoreone.android.core.protocol.bytes.Bytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApplicationBytesAcceptanceTest {
    @OriginalCase("DataExtensionsTests::Empty data returns empty string()")
    @OriginalCase("DataExtensionsTests::Hex string with no separator()")
    @OriginalCase("DataExtensionsTests::Hex string with space separator()")
    @OriginalCase("DataExtensionsTests::Hex string with custom separator()")
    @OriginalCase("DataExtensionsTests::Hex string for single byte()")
    @OriginalCase("DataExtensionsTests::Hex string preserves leading zeros()")
    @OriginalCase("DataExtensionsTests::Init from valid hex string()")
    @OriginalCase("DataExtensionsTests::Init from hex string with spaces()")
    @OriginalCase("DataExtensionsTests::Init from lowercase hex string()")
    @OriginalCase("DataExtensionsTests::Init from mixed case hex string()")
    @OriginalCase("DataExtensionsTests::Init from empty hex string()")
    @OriginalCase("DataExtensionsTests::Init from odd-length hex string returns nil()")
    @OriginalCase("DataExtensionsTests::Init filters out non-hex characters()")
    @OriginalCase("DataExtensionsTests::Round-trip preserves data()")
    @OriginalCase("DataExtensionsTests::Round-trip with spaces preserves data()")
    @Test fun originalApplicationHexFamiliesUseProductionFormattingAndParsing() {
        val bytes = Bytes.of(0xAA, 0xBB, 0xCC, 0xDD)
        assertEquals("", Bytes.EMPTY.uppercaseHexString())
        assertEquals("AABBCCDD", bytes.uppercaseHexString())
        assertEquals("AA BB CC DD", bytes.uppercaseHexString(" "))
        assertEquals("AA:BB:CC", Bytes.of(0xAA, 0xBB, 0xCC).uppercaseHexString(":"))
        assertEquals("0F", Bytes.of(0x0F).uppercaseHexString())
        assertEquals("000102", Bytes.of(0, 1, 2).uppercaseHexString())
        for (text in listOf("AABBCCDD", "AA BB CC DD", "aabbccdd", "AaBbCcDd")) {
            assertEquals(text, bytes, applicationBytesFromHex(text))
        }
        assertEquals(Bytes.EMPTY, applicationBytesFromHex(""))
        assertNull(applicationBytesFromHex("ABC"))
        assertEquals(Bytes.of(0xAA, 0xBB, 0xCC), applicationBytesFromHex("AA-BB-CC"))
        val original = Bytes.of(0, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66, 0x77,
            0x88, 0x99, 0xAA, 0xBB, 0xCC, 0xDD, 0xEE, 0xFF)
        assertEquals(original, applicationBytesFromHex(original.uppercaseHexString()))
        val spaced = Bytes.of(0xDE, 0xAD, 0xBE, 0xEF)
        assertEquals(spaced, applicationBytesFromHex(spaced.uppercaseHexString(" ")))
    }
}
