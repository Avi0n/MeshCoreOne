package one.meshcore.shared.settings.ui

import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.resources.Res
import one.meshcore.shared.resources.localizable_error_mesh_core_timeout
import one.meshcore.shared.settings.KeyGenerationService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A validly RFC 8032-clamped 64-byte expanded private key in hex (byte 0 = 0xF8, byte 31 = 0x40). */
private val VALID_EXPANDED_KEY_HEX = "f8" + "11".repeat(30) + "40" + "22".repeat(32)

/**
 * Guards the destructive-import gate: a valid key must resolve to [ImportKeyStep.Confirm] (which the UI
 * routes through the Replace-identity dialog) rather than being imported on the spot. Invalid input
 * never produces a confirmable key.
 */
class DeviceInfoImportKeyTest {
    @Test
    fun validKeyProducesConfirmStepNotImmediateImport() {
        val step = importKeyStep(VALID_EXPANDED_KEY_HEX)
        val confirm = assertIs<ImportKeyStep.Confirm>(step)
        assertEquals(64, confirm.key.size)
        assertEquals(0xF8.toByte(), confirm.key[0])
        assertEquals(0x40.toByte(), confirm.key[31])
    }

    @Test
    fun leading0xAndWhitespaceStillConfirm() {
        assertTrue(importKeyStep("  0x$VALID_EXPANDED_KEY_HEX  ") is ImportKeyStep.Confirm)
    }

    @Test
    fun shortOrNonHexInputIsInvalidHex() {
        assertEquals(ImportKeyStep.InvalidHex, importKeyStep(""))
        assertEquals(ImportKeyStep.InvalidHex, importKeyStep("aabb"))
        assertEquals(ImportKeyStep.InvalidHex, importKeyStep("11".repeat(32)))
    }

    @Test
    fun unclampedScalarIsInvalidKey() {
        assertEquals(ImportKeyStep.InvalidKey, importKeyStep("07" + VALID_EXPANDED_KEY_HEX.drop(2)))
    }

    @Test
    fun identityWriteErrorMapsFirmwareDisabledAndDeviceReject() {
        assertEquals(
            FEATURE,
            identityWriteErrorMessage(
                MeshBridgeException(BridgeErrorCode.FEATURE_DISABLED, "no import"),
                featureDisabled = FEATURE,
                deviceRejected = REJECTED,
                sessionText = { SESSION },
            ),
        )
        assertEquals(
            REJECTED,
            identityWriteErrorMessage(
                MeshBridgeException(BridgeErrorCode.DEVICE_ERROR, "nak"),
                featureDisabled = FEATURE,
                deviceRejected = REJECTED,
                sessionText = { SESSION },
            ),
        )
    }

    @Test
    fun identityWriteUsesSettingsCopyInsteadOfJniEnglish() {
        val timeout = MeshBridgeException(BridgeErrorCode.TIMEOUT, "radio mute")
        assertEquals(Res.string.localizable_error_mesh_core_timeout, timeout.settingsMessageResource())
        assertEquals("TIMEOUT: radio mute", timeout.message)
        assertEquals(
            SESSION,
            identityWriteErrorMessage(
                timeout,
                featureDisabled = FEATURE,
                deviceRejected = REJECTED,
                sessionText = { SESSION },
            ),
        )
        assertEquals(
            SESSION,
            identityWriteErrorMessage(
                RuntimeException("boom"),
                featureDisabled = FEATURE,
                deviceRejected = REJECTED,
                sessionText = { SESSION },
            ),
        )
    }

    @Test
    fun unexpectedKeyGenerationUsesRandomFailedNotDeviceRejected() {
        assertEquals(
            MAX,
            keyGenerationFailureDialogMessage(
                KeyGenerationService.Error.MaxAttemptsExceeded,
                maxAttempts = MAX,
                randomFailed = RANDOM,
            ),
        )
        assertEquals(
            null,
            keyGenerationFailureDialogMessage(
                KeyGenerationService.Error.ReservedPrefix,
                maxAttempts = MAX,
                randomFailed = RANDOM,
            ),
        )
        assertEquals(
            RANDOM,
            keyGenerationFailureDialogMessage(
                RuntimeException("boom"),
                maxAttempts = MAX,
                randomFailed = RANDOM,
            ),
        )
    }

    private companion object {
        const val FEATURE = "import not supported"
        const val REJECTED = "device rejected"
        const val SESSION = "Request timed out."
        const val MAX = "could not generate"
        const val RANDOM = "secure random failed"
    }
}
