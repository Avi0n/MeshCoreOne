package one.meshcore.shared.settings.ui

import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.settings.KeyGenerationService
import one.meshcore.shared.settings.isPrivateKeyHexLength
import one.meshcore.shared.settings.parsePrivateKeyHex

/**
 * The result of validating pasted private-key hex before it can replace the device identity.
 * Mirrors iOS `ImportKeyViewModel.validateAndConfirm`: valid input yields [Confirm], which the UI
 * routes through the destructive Replace-identity dialog rather than importing on the spot.
 */
sealed interface ImportKeyStep {
    /** Not 128 hex characters (iOS `ImportKey.Error.invalidHex`). */
    data object InvalidHex : ImportKeyStep

    /** Hex length is right but Ed25519 clamping failed (iOS `ImportKey.Error.invalidKey`). */
    data object InvalidKey : ImportKeyStep

    /** The key parsed and validated; hold it until the user confirms the destructive replace. */
    class Confirm(val key: ByteArray) : ImportKeyStep
}

/** Parses and validates [hexInput]; a valid key must clear the confirm dialog before it is imported. */
fun importKeyStep(hexInput: String): ImportKeyStep {
    if (!isPrivateKeyHexLength(hexInput)) return ImportKeyStep.InvalidHex
    val parsed = parsePrivateKeyHex(hexInput) ?: return ImportKeyStep.InvalidKey
    return ImportKeyStep.Confirm(parsed)
}

/**
 * Maps a failed identity write to the iOS Import/Regenerate copy: firmware-disabled,
 * device-rejected, or the settings session table ([rememberSettingsErrorText]) rather than JNI
 * `"CODE: detail"` text.
 */
fun identityWriteErrorMessage(
    error: Throwable,
    featureDisabled: String,
    deviceRejected: String,
    sessionText: (Throwable) -> String,
): String {
    val bridge = error as? MeshBridgeException
    return when (bridge?.code) {
        BridgeErrorCode.FEATURE_DISABLED -> featureDisabled
        BridgeErrorCode.DEVICE_ERROR -> deviceRejected
        else -> sessionText(error)
    }
}

/**
 * Dialog copy for a failed local generate. Reserved-prefix stays on the prefix field (null here),
 * matching iOS `RegenerateIdentityViewModel.generateKey`. Unexpected faults use the random-generation
 * string rather than device-rejected.
 */
fun keyGenerationFailureDialogMessage(error: Throwable, maxAttempts: String, randomFailed: String): String? =
    when (error) {
        is KeyGenerationService.Error.MaxAttemptsExceeded -> maxAttempts
        is KeyGenerationService.Error.ReservedPrefix -> null
        else -> randomFailed
    }
