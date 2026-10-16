package one.meshcore.shared.room

import one.meshcore.shared.remotenode.BehaviorIntervalFields
import one.meshcore.shared.remotenode.CLIResponse
import one.meshcore.shared.remotenode.NodeSettingsController
import one.meshcore.shared.remotenode.NodeSettingsValidation
import one.meshcore.shared.remotenode.RemoteNodeError
import one.meshcore.shared.remotenode.RemoteOperationTimeoutPolicy
import one.meshcore.shared.remotenode.SettingsApplyResult
import one.meshcore.shared.remotenode.adoptSettingsReading
import one.meshcore.shared.remotenode.applyIfChanged
import one.meshcore.shared.remotenode.checkedSettingsCommand
import one.meshcore.shared.remotenode.runSettingsFetch
import kotlin.time.Duration

/**
 * The room-settings logic core (Room Access + Behavior), mirroring the Swift `RoomSettingsViewModel` minus
 * the SwiftUI shell. Wraps a shared [NodeSettingsController] ([helper]) for the device-info/identity/
 * radio/contact-info sections the room management surface reuses, and adds the room-only Room Access
 * (guest password + read-only) and Behavior (advert/flood) sections. Compose- and `L10n`-free like its
 * sibling [one.meshcore.shared.remotenode.RepeaterSettingsController]: it sends over the injected admin CLI
 * closures (bound to an admin-gated [RoomAdminService]), fetches via the [RoomSettingCommand] get commands,
 * parses over [CLIResponse], applies only changed fields, and returns structured [SettingsApplyResult]s.
 */
class RoomSettingsController(
    val helper: NodeSettingsController,
    private val sendCommand: suspend (command: String, timeout: Duration) -> String,
    private val sendRawCommand: suspend (command: String, timeout: Duration) -> String,
) {
    init {
        registerBehaviorLateRecovery()
    }

    // MARK: Room Access
    var guestPassword: String? = null
    var allowReadOnly: Boolean? = null
    private var originalGuestPassword: String? = null
    private var originalAllowReadOnly: Boolean? = null
    var roomAccessError: Boolean = false
        private set
    val roomAccessLoaded: Boolean get() = guestPassword != null || allowReadOnly != null
    val roomAccessModified: Boolean
        get() = (guestPassword != null && guestPassword != originalGuestPassword) ||
            (allowReadOnly != null && allowReadOnly != originalAllowReadOnly)

    // MARK: Behavior
    var advertIntervalMinutes: Int? = null
    var floodAdvertIntervalHours: Int? = null
    var floodMaxHops: Int? = null
    private var originalAdvertIntervalMinutes: Int? = null
    private var originalFloodAdvertIntervalHours: Int? = null
    private var originalFloodMaxHops: Int? = null
    var behaviorError: Boolean = false
        private set
    var behaviorValidation: NodeSettingsValidation.BehaviorValidation? = null
        private set
    val behaviorLoaded: Boolean
        get() = advertIntervalMinutes != null || floodAdvertIntervalHours != null || floodMaxHops != null
    val behaviorSettingsModified: Boolean
        get() = (advertIntervalMinutes != null && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
            (floodAdvertIntervalHours != null && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
            (floodMaxHops != null && floodMaxHops != originalFloodMaxHops)

    // MARK: Room Access fetch / apply

    /**
     * A status word (ok/error/unknown) means "no guest password set" → empty; any other reply is the value
     * with its `"> "` echo prefix stripped. `allow.read.only` reads on when its raw value is `on`.
     */
    suspend fun fetchRoomAccess() {
        roomAccessError = false
        val passwordTimedOut = runSettingsFetch {
            val response = rawReply(RoomSettingCommand.GET_GUEST_PASSWORD)
            val value = when (CLIResponse.parse(response, RoomSettingCommand.GET_GUEST_PASSWORD)) {
                is CLIResponse.Ok, is CLIResponse.Error, is CLIResponse.UnknownCommand -> ""
                else -> response.trim().removePrefix(ECHO_PREFIX)
            }
            adoptSettingsReading(guestPassword, originalGuestPassword, value) { reading ->
                guestPassword = reading
                originalGuestPassword = reading
            }
        }
        val readOnlyTimedOut = runSettingsFetch {
            val response = rawReply(RoomSettingCommand.GET_ALLOW_READ_ONLY)
            (CLIResponse.parse(response, RoomSettingCommand.GET_ALLOW_READ_ONLY) as? CLIResponse.Raw)?.let {
                val isOn = it.text.trim().lowercase() == ON
                adoptSettingsReading(allowReadOnly, originalAllowReadOnly, isOn) { reading ->
                    allowReadOnly = reading
                    originalAllowReadOnly = reading
                }
            }
        }
        if (passwordTimedOut || readOnlyTimedOut) roomAccessError = true
    }

    suspend fun applyRoomAccess(): SettingsApplyResult {
        val sentPassword = guestPassword
        val sentReadOnly = allowReadOnly
        return try {
            val passwordApplied = applyIfChanged(
                sentPassword,
                originalGuestPassword,
                RoomSettingCommand::setGuestPassword,
                ::commandSucceeded,
            ) {
                originalGuestPassword = it
            }
            val readOnlyApplied = applyIfChanged(
                sentReadOnly,
                originalAllowReadOnly,
                RoomSettingCommand::setAllowReadOnly,
                ::commandSucceeded,
            ) {
                originalAllowReadOnly = it
            }
            if (passwordApplied && readOnlyApplied) SettingsApplyResult.Applied else SettingsApplyResult.PartialFailure
        } catch (e: RemoteNodeError) {
            SettingsApplyResult.TransportError(e)
        }
    }

    // MARK: Behavior fetch / apply

    suspend fun fetchBehaviorSettings() {
        behaviorError = false
        val advertTimedOut = runSettingsFetch {
            (contentReply(RoomSettingCommand.GET_ADVERT_INTERVAL) as? CLIResponse.AdvertInterval)?.let {
                adoptSettingsReading(advertIntervalMinutes, originalAdvertIntervalMinutes, it.minutes) { reading ->
                    advertIntervalMinutes = reading
                    originalAdvertIntervalMinutes = reading
                }
            }
        }
        val floodAdvertTimedOut = runSettingsFetch {
            (contentReply(RoomSettingCommand.GET_FLOOD_ADVERT_INTERVAL) as? CLIResponse.FloodAdvertInterval)?.let {
                adoptSettingsReading(floodAdvertIntervalHours, originalFloodAdvertIntervalHours, it.hours) { reading ->
                    floodAdvertIntervalHours = reading
                    originalFloodAdvertIntervalHours = reading
                }
            }
        }
        val floodMaxTimedOut = runSettingsFetch {
            (contentReply(RoomSettingCommand.GET_FLOOD_MAX) as? CLIResponse.FloodMax)?.let {
                adoptSettingsReading(floodMaxHops, originalFloodMaxHops, it.hops) { reading ->
                    floodMaxHops = reading
                    originalFloodMaxHops = reading
                }
            }
        }
        if (advertTimedOut || floodAdvertTimedOut || floodMaxTimedOut) behaviorError = true
    }

    suspend fun applyBehaviorSettings(): SettingsApplyResult {
        val validation =
            NodeSettingsValidation.validateBehaviorFields(advertIntervalMinutes, floodAdvertIntervalHours, floodMaxHops)
        behaviorValidation = validation
        if (validation.hasErrors) return SettingsApplyResult.InvalidFields
        val sentAdvert = advertIntervalMinutes
        val sentFloodAdvert = floodAdvertIntervalHours
        val sentFloodMax = floodMaxHops
        return try {
            val advertApplied = applyIfChanged(
                sentAdvert,
                originalAdvertIntervalMinutes,
                RoomSettingCommand::setAdvertInterval,
                ::commandSucceeded,
            ) { originalAdvertIntervalMinutes = it }
            val floodAdvertApplied = applyIfChanged(
                sentFloodAdvert,
                originalFloodAdvertIntervalHours,
                RoomSettingCommand::setFloodAdvertInterval,
                ::commandSucceeded,
            ) { originalFloodAdvertIntervalHours = it }
            val floodMaxApplied = applyIfChanged(
                sentFloodMax,
                originalFloodMaxHops,
                RoomSettingCommand::setFloodMax,
                ::commandSucceeded,
            ) {
                originalFloodMaxHops = it
            }
            if (advertApplied && floodAdvertApplied && floodMaxApplied) {
                SettingsApplyResult.Applied
            } else {
                SettingsApplyResult.PartialFailure
            }
        } catch (e: RemoteNodeError) {
            SettingsApplyResult.TransportError(e)
        }
    }

    // MARK: Late responses

    /** Routes a late CLI response to the shared arbitration, which now covers the room Behavior section. */
    fun handleLateResponse(response: String): Boolean = helper.handleCommonLateResponse(response)

    private val behaviorSectionComplete: Boolean
        get() = originalAdvertIntervalMinutes != null &&
            originalFloodAdvertIntervalHours != null &&
            originalFloodMaxHops != null

    /**
     * Registers the Behavior fields with the shared arbitration. Every one of these replies is a bare
     * integer, so attributing by shape alone would put a flood-hop count into the advert interval; the
     * helper only adopts a reply when exactly one query it sent is still unanswered.
     */
    private fun registerBehaviorLateRecovery() {
        helper.registerLateRecovery(RoomSettingCommand.GET_ADVERT_INTERVAL) { value ->
            (value as? CLIResponse.AdvertInterval)?.let {
                adoptSettingsReading(advertIntervalMinutes, originalAdvertIntervalMinutes, it.minutes) { reading ->
                    advertIntervalMinutes = reading
                    originalAdvertIntervalMinutes = reading
                }
                behaviorError = !behaviorSectionComplete
            }
        }
        helper.registerLateRecovery(RoomSettingCommand.GET_FLOOD_ADVERT_INTERVAL) { value ->
            (value as? CLIResponse.FloodAdvertInterval)?.let {
                adoptSettingsReading(floodAdvertIntervalHours, originalFloodAdvertIntervalHours, it.hours) { reading ->
                    floodAdvertIntervalHours = reading
                    originalFloodAdvertIntervalHours = reading
                }
                behaviorError = !behaviorSectionComplete
            }
        }
        helper.registerLateRecovery(RoomSettingCommand.GET_FLOOD_MAX) { value ->
            (value as? CLIResponse.FloodMax)?.let {
                adoptSettingsReading(floodMaxHops, originalFloodMaxHops, it.hops) { reading ->
                    floodMaxHops = reading
                    originalFloodMaxHops = reading
                }
                behaviorError = !behaviorSectionComplete
            }
        }
    }

    /**
     * Sends through the shared helper so a query that times out is recorded as unanswered. Without that
     * record the late-reply recovery below has nothing to attribute a stray response to.
     */
    private suspend fun contentReply(command: String): CLIResponse =
        CLIResponse.parse(helper.sendAndWait(command, DEFAULT_TIMEOUT), command)

    private suspend fun commandSucceeded(command: String): Boolean {
        val response = checkedSettingsCommand { sendCommand(command, DEFAULT_TIMEOUT) }
        return CLIResponse.parse(response) is CLIResponse.Ok
    }

    private suspend fun rawReply(command: String): String =
        checkedSettingsCommand { sendRawCommand(command, DEFAULT_TIMEOUT) }

    private companion object {
        val DEFAULT_TIMEOUT: Duration = RemoteOperationTimeoutPolicy.DEFAULT_CLI
        const val ON = "on"
        const val ECHO_PREFIX = "> "
    }
}

internal fun RoomSettingsController.behaviorIntervalFields(): BehaviorIntervalFields = BehaviorIntervalFields(
    loaded = behaviorLoaded,
    sectionError = behaviorError,
    advertIntervalText = advertIntervalMinutes?.toString().orEmpty(),
    floodAdvertIntervalText = floodAdvertIntervalHours?.toString().orEmpty(),
    floodMaxHopsText = floodMaxHops?.toString().orEmpty(),
    modified = behaviorSettingsModified,
)
