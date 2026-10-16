package one.meshcore.shared.channel.ui

import one.meshcore.shared.channel.ChannelDeviceException
import one.meshcore.shared.channel.ChannelSendTimeoutException
import one.meshcore.shared.channel.ChannelServiceError
import one.meshcore.shared.channel.ChannelTimeoutException
import one.meshcore.shared.channel.ChannelTransportException
import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The localized surface for a channel create/join/sync/delete failure, mirroring the Swift
 * `ChannelServiceError.userFacingMessage`. The English `errorDescription` text is the developer
 * fallback on both platforms; rendering it to the user leaves eight of nine locales in English.
 */
class ChannelServiceErrorMessageTest {
    @Test
    fun everyTypedCaseResolvesToItsTranslatedKey() {
        assertEquals(
            "localizable_error_channel_service_not_connected",
            ChannelServiceError.NotConnected().messageResource()?.key,
        )
        assertEquals(
            "localizable_error_channel_service_channel_not_found",
            ChannelServiceError.ChannelNotFound().messageResource()?.key,
        )
        assertEquals(
            "localizable_error_channel_service_invalid_channel_index",
            ChannelServiceError.InvalidChannelIndex().messageResource()?.key,
        )
        assertEquals(
            "localizable_error_channel_service_secret_hashing_failed",
            ChannelServiceError.SecretHashingFailed().messageResource()?.key,
        )
        assertEquals(
            "localizable_error_channel_service_sync_already_in_progress",
            ChannelServiceError.SyncAlreadyInProgress().messageResource()?.key,
        )
    }

    @Test
    fun theFormattedCasesCarryTheirDetail() {
        val save = ChannelServiceError.SaveFailed("disk full")
        assertEquals("localizable_error_channel_service_save_failed", save.messageResource()?.key)
        assertEquals(listOf("disk full"), save.messageFormatArgs())

        val send = ChannelServiceError.SendFailed("link down")
        assertEquals("localizable_error_channel_service_send_failed", send.messageResource()?.key)
        assertEquals(listOf("link down"), send.messageFormatArgs())

        val breaker = ChannelServiceError.CircuitBreakerOpen(3)
        assertNull(breaker.messageResource())
        assertEquals(
            "localizable_error_channel_service_circuit_breaker_open",
            breaker.messagePluralResource()?.key,
        )
        assertEquals(listOf(3), breaker.messageFormatArgs())
    }

    /** A wrapped session failure resolves through its cause, as Swift resolves `MeshCoreError`. */
    @Test
    fun aTimedOutSessionReadsAsATimeout() {
        assertEquals(
            "localizable_error_mesh_core_timeout",
            ChannelServiceError.SessionError(ChannelTimeoutException()).messageResource()?.key,
        )
        assertEquals(
            "localizable_error_mesh_core_timeout",
            ChannelServiceError.SessionError(ChannelSendTimeoutException()).messageResource()?.key,
        )
    }

    @Test
    fun aFirmwareErrorCodeResolvesToItsDeviceString() {
        val notFound = ChannelServiceError.SessionError(ChannelDeviceException(DEVICE_CODE_NOT_FOUND))
        assertEquals("localizable_error_device_not_found", notFound.messageResource()?.key)

        val unknown = ChannelServiceError.SessionError(ChannelDeviceException(DEVICE_CODE_UNMODELED))
        assertEquals("localizable_error_device_unknown", unknown.messageResource()?.key)
        assertEquals(listOf(DEVICE_CODE_UNMODELED), unknown.messageFormatArgs())
    }

    @Test
    fun aTransportCauseReadsAsConnectionLost() {
        assertEquals(
            "localizable_error_mesh_core_connection_lost_no_detail",
            ChannelServiceError.SessionError(ChannelTransportException("socket closed")).messageResource()?.key,
        )
    }

    @Test
    fun aWrappedBridgeTimeoutReadsAsATimeout() {
        val wrapped = ChannelServiceError.SessionError(MeshBridgeException(BridgeErrorCode.TIMEOUT, ""))
        assertEquals("localizable_error_mesh_core_timeout", wrapped.messageResource()?.key)
    }

    @Test
    fun aWrappedBridgeDataTooLargeFillsActualAndMaximum() {
        val wrapped = ChannelServiceError.SessionError(
            MeshBridgeException(BridgeErrorCode.DATA_TOO_LARGE, "data too large: 4000 > 255"),
        )
        assertEquals("localizable_error_mesh_core_data_too_large", wrapped.messageResource()?.key)
        assertEquals(listOf(4000, 255), wrapped.messageFormatArgs())
    }

    @Test
    fun anUnmodeledCauseHasNoResourceSoTheSurfaceUsesErrorOccurred() {
        assertNull(ChannelServiceError.SessionError(IllegalStateException("socket closed")).messageResource())
    }

    private companion object {
        const val DEVICE_CODE_NOT_FOUND = 2
        const val DEVICE_CODE_UNMODELED = 99
    }
}
