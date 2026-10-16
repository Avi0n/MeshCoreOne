package one.meshcore.shared.meshcore

import one.meshcore.shared.chat.FirmwareDeviceErrorCode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The details here are the exact strings the Swift bridge emits (`BridgeErrorCode.classify`): it writes
 * `device error code=<n>` and appends the case name whenever it recognises the code.
 *
 * That naming covers every code the app can act on, so a parser reading trailing digits missed precisely
 * those and answered [UNKNOWN_FIRMWARE_CODE]. Retry classification compares this value against the
 * transient code for the path, so a miss turns a full message pool into a permanent failure.
 */
class FirmwareSubCodeTest {
    @Test
    fun aNamedCodeIsRead() {
        assertEquals(6, deviceError("device error code=6 (illegalArgument)"))
        assertEquals(3, deviceError("device error code=3 (tableFull)"))
        assertEquals(2, deviceError("device error code=2 (notFound)"))
    }

    /** The bridge omits the name for a code it does not recognise; that form parsed before and still must. */
    @Test
    fun anUnnamedCodeIsRead() {
        assertEquals(42, deviceError("device error code=42"))
    }

    /**
     * The two codes retry depends on. Both sit inside the range the bridge names, so both were unreadable
     * before, and a send that should have been parked was dropped as terminal instead.
     */
    @Test
    fun theTransientCodesAreReadable() {
        assertEquals(
            FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL,
            deviceError("device error code=${FirmwareDeviceErrorCode.DIRECT_MESSAGE_TABLE_FULL} (tableFull)"),
        )
        assertEquals(
            FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND,
            deviceError("device error code=${FirmwareDeviceErrorCode.CHANNEL_MESSAGE_NOT_FOUND} (notFound)"),
        )
    }

    @Test
    fun aDetailWithNoCodeReportsUnknown() {
        assertEquals(UNKNOWN_FIRMWARE_CODE, deviceError("not connected"))
        assertEquals(UNKNOWN_FIRMWARE_CODE, deviceError(""))
    }

    /** A number elsewhere in the detail must not be mistaken for the code. */
    @Test
    fun onlyTheCodeFieldIsRead() {
        assertEquals(4, deviceError("device error code=4 (badState) after 30 retries"))
    }

    private fun deviceError(detail: String): Int =
        MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, detail).firmwareCode()
}
