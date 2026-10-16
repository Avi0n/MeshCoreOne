package one.meshcore.shared.remotenode

import kotlin.time.Duration

/** Outcome of applying a settings section that sends one or more `set` commands. */
sealed interface SettingsApplyResult {
    /** Every changed field's command returned OK (or nothing had changed). */
    data object Applied : SettingsApplyResult

    /** At least one command returned a non-OK response. */
    data object PartialFailure : SettingsApplyResult

    /** Validation rejected the input; the section's validation is on the controller. */
    data object InvalidFields : SettingsApplyResult

    /** Radio apply needs all five fields loaded first. */
    data object NotLoaded : SettingsApplyResult
    data class TransportError(val error: RemoteNodeError) : SettingsApplyResult
}

/** Outcome of a password change. */
sealed interface PasswordApplyResult {
    data object Applied : PasswordApplyResult
    data object Empty : PasswordApplyResult
    data object Mismatch : PasswordApplyResult
    data object Failed : PasswordApplyResult
    data class TransportError(val error: RemoteNodeError) : PasswordApplyResult
}

/** Outcome of a reply-less device action (reboot, advert). */
sealed interface ActionResult {
    data object Sent : ActionResult
    data class TransportError(val error: RemoteNodeError) : ActionResult
}

/** Outcome of a `clock sync`, wrapping the parser's classification plus transport failure. */
sealed interface SyncTimeResult {
    data object Synced : SyncTimeResult
    data object ClockAhead : SyncTimeResult
    data class Failed(val message: String) : SyncTimeResult
    data class Unexpected(val response: String) : SyncTimeResult
    data class TransportError(val error: RemoteNodeError) : SyncTimeResult
}

/**
 * The shared node-settings logic core, mirroring the Swift `NodeSettingsViewModel` minus the SwiftUI shell:
 * it fetches the device-info / identity / radio / contact-info sections over the CLI, tracks the loaded
 * originals, and applies only the fields the user changed. Kept Compose- and `L10n`-free the way
 * [NodeCliSession] is: it talks to the node through injected [sendCommand]/[sendRawCommand] closures
 * (already bound to the session), returns structured results, and exposes validation as flags the UI maps
 * to messages. The room and repeater settings surfaces wrap one instance.
 */
@OptIn(kotlin.time.ExperimentalTime::class)
class NodeSettingsController private constructor(private val parts: NodeSettingsParts) :
    NodeSettingsTransport by parts.exchange,
    NodeDeviceInfo by parts.device,
    NodeIdentityFields by parts.identity,
    NodeRadioFields by parts.radio,
    NodeContactInfo by parts.contact,
    NodeMaintenance by parts.actions {
    /** Called before a section fetch when its info may already be available via a binary pre-fetch. */
    var onPreFetchNodeInfo: (suspend () -> Unit)?
        get() = parts.hooks.onPreFetchNodeInfo
        set(value) {
            parts.hooks.onPreFetchNodeInfo = value
        }

    constructor(
        sendCommand: suspend (command: String, timeout: Duration) -> String,
        sendRawCommand: suspend (command: String, timeout: Duration) -> String,
        nowEpochSeconds: () -> Long = { kotlin.time.Clock.System.now().epochSeconds },
    ) : this(NodeSettingsParts(sendCommand, sendRawCommand, nowEpochSeconds))

    /**
     * Sends a command and records what the exchange tells the late-reply recovery: the answer this
     * screen has already seen, and — when the wait expires — the query still owed an answer.
     */
    suspend fun sendAndWait(
        command: String,
        timeout: Duration = RemoteOperationTimeoutPolicy.DEFAULT_CLI,
        rawMatching: Boolean = false,
    ): String = parts.exchange.sendAndWait(command, timeout, rawMatching)

    /** Sets name/owner-info/firmware from an external source (e.g. the binary owner-info pre-fetch). */
    fun setNodeInfo(firmwareVersion: String?, name: String?, ownerInfo: String?) {
        firmwareVersion?.let { parts.device.firmwareVersion = it }
        name?.let {
            adoptSettingsReading(parts.identity.name, parts.identity.originalName, it) { reading ->
                parts.identity.name = reading
                parts.identity.originalName = reading
            }
        }
        ownerInfo?.let {
            adoptSettingsReading(parts.contact.ownerInfo, parts.contact.originalOwnerInfo, it) { reading ->
                parts.contact.ownerInfo = reading
                parts.contact.originalOwnerInfo = reading
            }
        }
    }
}

/** Mutable pre-fetch hook shared by the section fetches. */
internal class NodeSettingsHooks {
    var onPreFetchNodeInfo: (suspend () -> Unit)? = null
}

private class NodeSettingsParts(
    sendCommand: suspend (command: String, timeout: Duration) -> String,
    sendRawCommand: suspend (command: String, timeout: Duration) -> String,
    nowEpochSeconds: () -> Long,
) {
    val hooks = NodeSettingsHooks()
    val exchange = NodeSettingsExchange(sendCommand, sendRawCommand)
    val device = NodeDeviceSettings(exchange, hooks, nowEpochSeconds)
    val identity = NodeIdentitySettings(exchange, hooks)
    val radio = NodeRadioSettings(exchange)
    val contact = NodeContactSettings(exchange, hooks)
    val actions = NodeSettingsActions(exchange)

    init {
        registerSharedLateRecovery()
    }

    private fun registerSharedLateRecovery() {
        exchange.registerLateRecovery("get radio") { value ->
            (value as? CLIResponse.Radio)?.let {
                radio.adoptRadio(it)
                radio.radioError = false
            }
        }
        exchange.registerLateRecovery("get lat") { value ->
            (value as? CLIResponse.Latitude)?.let {
                adoptSettingsReading(identity.latitude, identity.originalLatitude, it.value) { reading ->
                    identity.latitude = reading
                    identity.originalLatitude = reading
                }
                identity.identityError = !identity.identitySectionComplete
            }
        }
        exchange.registerLateRecovery("get lon") { value ->
            (value as? CLIResponse.Longitude)?.let {
                adoptSettingsReading(identity.longitude, identity.originalLongitude, it.value) { reading ->
                    identity.longitude = reading
                    identity.originalLongitude = reading
                }
                identity.identityError = !identity.identitySectionComplete
            }
        }
        exchange.registerLateRecovery("clock") { value ->
            (value as? CLIResponse.DeviceTime)?.let {
                device.applyDeviceTime(it.text)
                device.deviceInfoError = device.firmwareVersion == null
            }
        }
    }
}
