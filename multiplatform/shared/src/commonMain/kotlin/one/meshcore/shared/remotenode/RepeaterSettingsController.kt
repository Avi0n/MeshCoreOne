package one.meshcore.shared.remotenode

import kotlin.time.Duration

/** Outcome of a single region tree operation, mirroring the Swift region-command error branches. */
sealed interface RegionOpResult {
    data object Applied : RegionOpResult

    /** `region put` with a name that failed local validation (and was not merely empty). */
    data object AddValidationFailed : RegionOpResult

    /** An empty add name — a silent no-op in the Swift VM. */
    data object EmptyName : RegionOpResult

    /** Tree was not loaded yet, so the command was not sent. */
    data object NotLoaded : RegionOpResult

    /** toggle-flood or set-default: the node rejected the command (unknown region). */
    data object UnknownRegion : RegionOpResult

    /** `region put` rejected by the node. */
    data object AddFailed : RegionOpResult

    /** `region remove` rejected because the region still has members. */
    data object NotEmpty : RegionOpResult
    data object RemoveFailed : RegionOpResult
    data object SaveFailed : RegionOpResult
    data class TransportError(val error: RemoteNodeError) : RegionOpResult
}

/**
 * The repeater-only settings logic core (behavior + regions), mirroring the Swift
 * `RepeaterSettingsViewModel` minus the SwiftUI shell. Wraps a shared [NodeSettingsController] ([helper])
 * for the device-info/identity/radio/contact-info sections and adds the repeater behavior fields and the
 * region tree. Compose- and `L10n`-free: it sends over the injected closures and returns structured
 * results. [handleLateResponse] routes an uncorrelated CLI response to the shared sections first, then
 * behavior, then regions.
 */
class RepeaterSettingsController private constructor(private val parts: RepeaterSettingsParts) :
    RepeaterBehavior by parts.behavior,
    RepeaterRegionCommands by parts.regions {
    val helper: NodeSettingsController
        get() = parts.helper

    constructor(
        helper: NodeSettingsController,
        sendCommand: suspend (command: String, timeout: Duration) -> String,
        sendRawCommand: suspend (command: String, timeout: Duration) -> String,
    ) : this(RepeaterSettingsParts(helper, sendCommand, sendRawCommand))

    suspend fun addRegion(
        name: String,
        parent: RepeaterRegionEntry.Parent = RepeaterRegionEntry.Parent.Unscoped,
    ): RegionOpResult = parts.regions.addRegion(name, parent)

    /**
     * Replaces the live tree after a CLI reply has already been sent. Tests use this to simulate a
     * concurrent refetch between send and apply, matching iOS mutating `regions` in `onSend`.
     */
    internal fun replaceRegions(entries: List<RepeaterRegionEntry>) {
        parts.regions.regions = entries
    }
}

private class RepeaterSettingsParts(
    val helper: NodeSettingsController,
    sendCommand: suspend (command: String, timeout: Duration) -> String,
    sendRawCommand: suspend (command: String, timeout: Duration) -> String,
) {
    private val transport = RepeaterCliTransport(sendCommand)
    val behavior = RepeaterBehaviorSettings(helper, transport)
    val regions = RepeaterRegionSettings(helper, transport, sendCommand, sendRawCommand)
}

/** Unset (`<null>` or `*`) versus a named region. */
sealed interface ParsedDefaultScope {
    data object Cleared : ParsedDefaultScope
    data class Named(val name: String) : ParsedDefaultScope
}
