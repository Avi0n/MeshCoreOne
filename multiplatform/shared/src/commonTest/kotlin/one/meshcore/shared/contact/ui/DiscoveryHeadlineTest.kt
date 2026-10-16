package one.meshcore.shared.contact.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class DiscoveryHeadlineTest {
    @Test
    fun advertisedNameIsShownWhenPresent() {
        assertEquals(
            "Hilltop",
            discoveryHeadlineText(name = "Hilltop", unknownLabel = "Unknown", publicKey = KEY),
        )
    }

    @Test
    fun blankNameUsesUnknownPlusFourBytePrefix() {
        assertEquals(
            "Unknown (0A1B2C3D)",
            discoveryHeadlineText(name = "  ", unknownLabel = "Unknown", publicKey = KEY),
        )
    }

    private companion object {
        val KEY = byteArrayOf(0x0A, 0x1B, 0x2C, 0x3D) + ByteArray(28)
    }
}
