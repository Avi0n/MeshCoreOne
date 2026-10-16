package one.meshcore.shared.channel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Connect-path mapping for channel-info flood-scope picker inputs from the Device row.
 */
class ChannelFloodScopeDeviceInputsTest {
    @Test
    fun fromDevicePassesThroughRegionsAndDefaultName() {
        val inputs = ChannelFloodScopeDeviceInputs.fromDevice(
            knownRegions = listOf("EU868", "US915"),
            defaultFloodScopeName = "EU868",
            firmwareVersion = 12,
        )
        assertEquals(listOf("EU868", "US915"), inputs.knownRegions)
        assertEquals("EU868", inputs.defaultFloodScopeName)
    }

    @Test
    fun fromDeviceNullsBecomeEmptyRegionsAndNullDefault() {
        val inputs = ChannelFloodScopeDeviceInputs.fromDevice(
            knownRegions = null,
            defaultFloodScopeName = null,
            firmwareVersion = null,
        )
        assertEquals(emptyList(), inputs.knownRegions)
        assertNull(inputs.defaultFloodScopeName)
        assertFalse(inputs.supportsUnscopedFloodSend)
    }

    @Test
    fun supportsUnscopedFloodSendGatesOnFirmwareVersion() {
        assertTrue(
            ChannelFloodScopeDeviceInputs.fromDevice(null, null, firmwareVersion = 12).supportsUnscopedFloodSend,
        )
        assertFalse(
            ChannelFloodScopeDeviceInputs.fromDevice(null, null, firmwareVersion = 11).supportsUnscopedFloodSend,
        )
    }
}
