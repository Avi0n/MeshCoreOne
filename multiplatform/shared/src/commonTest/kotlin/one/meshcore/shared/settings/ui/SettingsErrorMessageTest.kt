package one.meshcore.shared.settings.ui

import one.meshcore.shared.channel.DefaultFloodScopeVerificationFailed
import one.meshcore.shared.connection.OtherParamsVerificationFailed
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_ble_invalid_response
import one.meshcore.shared.resources.localizable_error_ble_write_error
import one.meshcore.shared.resources.localizable_error_device_storage_full
import one.meshcore.shared.resources.localizable_error_device_unknown
import one.meshcore.shared.resources.localizable_error_mesh_core_command_failed
import one.meshcore.shared.resources.localizable_error_mesh_core_connection_lost
import one.meshcore.shared.resources.localizable_error_mesh_core_connection_lost_no_detail
import one.meshcore.shared.resources.localizable_error_mesh_core_data_too_large
import one.meshcore.shared.resources.localizable_error_mesh_core_timeout
import one.meshcore.shared.resources.localizable_error_settings_gps_not_saved_expected_off
import one.meshcore.shared.resources.localizable_error_settings_gps_not_saved_expected_on
import one.meshcore.shared.resources.localizable_error_settings_not_connected
import one.meshcore.shared.resources.localizable_error_settings_verification_failed
import one.meshcore.shared.settings.DeviceGpsVerificationFailed
import one.meshcore.shared.settings.DeviceWriteVerificationFailed
import one.meshcore.shared.ui.MeshBridgeErrorMessage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A failed settings write must reach the user in their own language. The strings exist in every locale;
 * these cases pin which failure resolves to which, mirroring the Swift
 * `SettingsServiceError.userFacingMessage` and the `MeshCoreError` / `ProtocolError` tables it delegates
 * to. Pure, so no composition is needed to assert the mapping.
 */
class SettingsErrorMessageTest {
    @Test
    fun aRefusedWriteReadsAsTheSharedExpectedActualString() {
        val refused = OtherParamsVerificationFailed(expected = "policy=2", actual = "policy=0")

        assertEquals(Res.string.localizable_error_settings_verification_failed, refused.settingsMessageResource())
        assertEquals(listOf("policy=2", "policy=0"), refused.settingsMessageFormatArgs())
    }

    @Test
    fun aRefusedCoordinateWriteReadsAsTheSharedExpectedActualString() {
        val refused = DeviceWriteVerificationFailed(expected = "(1.0, 2.0)", actual = "(3.0, 4.0)")

        assertEquals(Res.string.localizable_error_settings_verification_failed, refused.settingsMessageResource())
        assertEquals(listOf("(1.0, 2.0)", "(3.0, 4.0)"), refused.settingsMessageFormatArgs())
    }

    @Test
    fun aRefusedGpsToggleUsesTheSentenceForTheExpectedState() {
        val expectedOn = DeviceGpsVerificationFailed(expectedEnabled = true, actualEnabled = false)
        val expectedOff = DeviceGpsVerificationFailed(expectedEnabled = false, actualEnabled = true)

        assertEquals(
            Res.string.localizable_error_settings_gps_not_saved_expected_on,
            expectedOn.settingsMessageResource(),
        )
        assertEquals(
            Res.string.localizable_error_settings_gps_not_saved_expected_off,
            expectedOff.settingsMessageResource(),
        )
        assertEquals(emptyList(), expectedOn.settingsMessageFormatArgs())
    }

    @Test
    fun anUnsetFloodScopeStillFillsBothSubstitutions() {
        val refused = DefaultFloodScopeVerificationFailed(expected = "EU", actual = null)

        assertEquals(listOf("EU", ""), refused.settingsMessageFormatArgs())
    }

    @Test
    fun aWriteWithNoRadioReadsAsNotConnected() {
        val noRadio = IllegalStateException("No radio connected")

        assertEquals(Res.string.localizable_error_settings_not_connected, noRadio.settingsMessageResource())
    }

    @Test
    fun aTimedOutCommandReadsAsATimeout() {
        val timeout = MeshBridgeException(BridgeErrorCode.TIMEOUT, "no response in 5000ms")

        assertEquals(Res.string.localizable_error_mesh_core_timeout, timeout.settingsMessageResource())
        assertEquals(emptyList(), timeout.settingsMessageFormatArgs())
    }

    @Test
    fun aFirmwareSubCodeResolvesThroughTheSharedDeviceTable() {
        val tableFull = MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "device error code=3 (tableFull)")

        assertEquals(Res.string.localizable_error_device_storage_full, tableFull.settingsMessageResource())
    }

    @Test
    fun anUnrecognizedSubCodeCarriesItsNumber() {
        val odd = MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "device error code=42")

        assertEquals(Res.string.localizable_error_device_unknown, odd.settingsMessageResource())
        assertEquals(listOf(42), odd.settingsMessageFormatArgs())
    }

    @Test
    fun aDetailBearingFailureCarriesItsDetail() {
        val failed = MeshBridgeException(BridgeErrorCode.COMMAND_FAILED, "SET_RADIO rejected")

        assertEquals(Res.string.localizable_error_mesh_core_command_failed, failed.settingsMessageResource())
        assertEquals(listOf("SET_RADIO rejected"), failed.settingsMessageFormatArgs())
    }

    @Test
    fun aLostConnectionPicksTheVariantItsDetailCanFill() {
        val withDetail = MeshBridgeException(BridgeErrorCode.CONNECTION_LOST, "peripheral disconnected")
        val without = MeshBridgeException(BridgeErrorCode.CONNECTION_LOST, "")

        assertEquals(Res.string.localizable_error_mesh_core_connection_lost, withDetail.settingsMessageResource())
        assertEquals(
            Res.string.localizable_error_mesh_core_connection_lost_no_detail,
            without.settingsMessageResource(),
        )
        assertEquals(emptyList(), without.settingsMessageFormatArgs())
    }

    @Test
    fun aTransportSendFailureUsesTheShippedBluetoothString() {
        val send = MeshBridgeException(BridgeErrorCode.TRANSPORT_SEND_FAILED, "write timed out")

        assertEquals(Res.string.localizable_error_ble_write_error, send.settingsMessageResource())
        assertEquals(listOf("write timed out"), send.settingsMessageFormatArgs())
    }

    @Test
    fun aDataTooLargeFailureFillsActualAndMaximum() {
        val sized = MeshBridgeException(BridgeErrorCode.DATA_TOO_LARGE, "data too large: 4000 > 255")

        assertEquals(Res.string.localizable_error_mesh_core_data_too_large, sized.settingsMessageResource())
        assertEquals(listOf(4000, 255), sized.settingsMessageFormatArgs())
    }

    @Test
    fun aFailureNoShippedStringDescribesKeepsItsOwnText() {
        val unparseable = MeshBridgeException(BridgeErrorCode.DATA_TOO_LARGE, "4000 bytes, max 255")
        assertNull(unparseable.settingsMessageResource())
        assertNull(IllegalArgumentException("not a settings failure").settingsMessageResource())
    }

    /**
     * An unparseable response used to fall back to the exception's own text, because the `mesh_core`
     * string wants expected/got and the bridge has only one collapsed detail. The transport family ships
     * a complete sentence for the same condition, which needs no substitution and beats raw text.
     */
    @Test
    fun anUnparseableResponseUsesTheSentenceThatNeedsNoSubstitution() {
        val invalid = MeshBridgeException(BridgeErrorCode.INVALID_RESPONSE, "expected selfInfo, got ack")

        assertEquals(Res.string.localizable_error_ble_invalid_response, invalid.settingsMessageResource())
        assertEquals(emptyList(), invalid.settingsMessageFormatArgs())
    }

    @Test
    fun everyStringTheMapperCanReturnIsInTheSetTheResolverReads() {
        val fromCodes = BridgeErrorCode.entries.mapNotNull {
            MeshBridgeErrorMessage.resource(MeshBridgeException(it, "detail 3"))
        }

        assertTrue(fromCodes.isNotEmpty())
        assertTrue(MeshBridgeErrorMessage.allResources.containsAll(fromCodes))
        assertTrue(
            MeshBridgeErrorMessage.allResources.contains(Res.string.localizable_error_mesh_core_data_too_large),
            "the sized probe must keep dataTooLarge in the composition set",
        )
    }
}
