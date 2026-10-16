package one.meshcore.android

import kotlinx.coroutines.CancellationException
import one.meshcore.shared.connection.connectedDeviceRowId
import one.meshcore.shared.connection.locationSettingsSnapshot
import one.meshcore.shared.connection.persistDeviceCoordinates
import one.meshcore.shared.meshcore.MeshCoreSession
import one.meshcore.shared.navigation.SendAdvertShortcutOutcome
import one.meshcore.shared.settings.SelfAdvertRadio

/**
 * Broadcasts a self-advert, refreshing the advertised position first when the radio's advert policy
 * and the user's per-radio preference allow it (iOS `AppState.sendSelfAdvert`). [allowLocationPrompt]
 * is false for callers that cannot raise a permission dialog, such as the Tools CLI.
 */
suspend fun AppContainer.sendSelfAdvert(flood: Boolean, allowLocationPrompt: Boolean): SendAdvertShortcutOutcome {
    val radio = selfAdvertRadio() ?: return SendAdvertShortcutOutcome.NotConnected
    return runSelfAdvertSend(
        send = { selfAdvertSender.send(radio, flood, allowLocationPrompt) },
        logWarning = settingsLogger::warning,
    )
}

/** Cancellation propagates. Any other failure is logged and reported as a failed send. */
@Suppress("TooGenericExceptionCaught")
internal suspend fun runSelfAdvertSend(
    send: suspend () -> Unit,
    logWarning: (String) -> Unit,
): SendAdvertShortcutOutcome = try {
    send()
    SendAdvertShortcutOutcome.Sent
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    logWarning("Self-advert failed: ${error.message ?: "unknown error"}")
    SendAdvertShortcutOutcome.Failed
}

/** The live radio behind a self-advert; `null` when no MeshCore session is connected. */
internal fun AppContainer.selfAdvertRadio(): SelfAdvertRadio? {
    val session = connectionManager.services?.session as? MeshCoreSession ?: return null
    return SessionSelfAdvertRadio(
        session = session,
        resolveDeviceId = { connectionManager.connectedDeviceRowId()?.value },
        readLocationSettings = { connectionManager.locationSettingsSnapshot() },
        persistCoordinates = { lat, lon -> connectionManager.persistDeviceCoordinates(lat, lon) },
        logger = settingsLogger,
    )
}
