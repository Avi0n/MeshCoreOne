package one.meshcore.shared.settings.ui

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import one.meshcore.shared.settings.KeyGenerationService
import one.meshcore.shared.settings.VanityPrefix

internal data class IdentityGeneration(
    val prefix: String,
    val lengthError: String,
    val reservedError: String,
    val maxAttemptsError: String,
    val randomFailedError: String,
    val onPrefixError: (String?) -> Unit,
    val onError: (String?) -> Unit,
    val onBusyChange: (Boolean) -> Unit,
    val onGeneratingChange: (Boolean) -> Unit,
    val onGenerated: (KeyGenerationService.GeneratedIdentity) -> Unit,
    val generate: suspend (hexPrefix: String?) -> KeyGenerationService.GeneratedIdentity,
)

internal data class IdentityReplace(
    val identity: KeyGenerationService.GeneratedIdentity?,
    override val featureDisabledMessage: String,
    override val rejectedMessage: String,
    override val sessionText: (Throwable) -> String,
    override val onError: (String?) -> Unit,
    override val onBusyChange: (Boolean) -> Unit,
    override val onImportingChange: (Boolean) -> Unit,
    val onImportPrivateKey: suspend (ByteArray) -> Unit,
    override val onCleared: () -> Unit,
    override val onHapticSuccess: () -> Unit,
    override val onSuccess: () -> Unit,
) : IdentityWriteRequest

@Suppress("TooGenericExceptionCaught")
internal fun launchIdentityGeneration(scope: CoroutineScope, request: IdentityGeneration) {
    val prefix = request.prefix
    if (prefix.isNotEmpty()) {
        when (VanityPrefix.validate(prefix)) {
            VanityPrefix.Validation.InvalidLength, VanityPrefix.Validation.InvalidHex -> {
                request.onPrefixError(request.lengthError)
                return
            }
            VanityPrefix.Validation.Reserved -> {
                request.onPrefixError(request.reservedError)
                return
            }
            VanityPrefix.Validation.Ok -> Unit
        }
    }
    request.onGeneratingChange(true)
    request.onBusyChange(true)
    request.onPrefixError(null)
    request.onError(null)
    scope.launch {
        try {
            request.onGenerated(request.generate(prefix.takeIf { it.isNotEmpty() }))
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            val dialog = keyGenerationFailureDialogMessage(
                error,
                maxAttempts = request.maxAttemptsError,
                randomFailed = request.randomFailedError,
            )
            if (dialog == null) {
                request.onPrefixError(request.reservedError)
            } else {
                request.onError(dialog)
            }
        } finally {
            request.onGeneratingChange(false)
            request.onBusyChange(false)
        }
    }
}

internal fun launchIdentityReplace(scope: CoroutineScope, request: IdentityReplace) {
    val identity = request.identity ?: return
    launchIdentityWrite(scope, request) { request.onImportPrivateKey(identity.expandedPrivateKey) }
}
