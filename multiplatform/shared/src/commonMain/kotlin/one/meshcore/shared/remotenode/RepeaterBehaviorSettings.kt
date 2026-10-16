package one.meshcore.shared.remotenode

/** Repeater behavior fields and the commands that load and apply them. */
interface RepeaterBehavior {
    var repeaterEnabled: Boolean?
    var advertIntervalMinutes: Int?
    var floodAdvertIntervalHours: Int?
    var floodMaxHops: Int?
    val behaviorError: Boolean
    val behaviorValidation: NodeSettingsValidation.BehaviorValidation?
    val behaviorLoaded: Boolean
    val behaviorSettingsModified: Boolean

    suspend fun fetchBehaviorSettings()

    suspend fun applyBehaviorSettings(): SettingsApplyResult
}

internal class RepeaterBehaviorSettings(
    private val helper: NodeSettingsController,
    private val transport: RepeaterCliTransport,
) : RepeaterBehavior {
    init {
        registerBehaviorLateRecovery()
    }

    override var repeaterEnabled: Boolean? = null
    override var advertIntervalMinutes: Int? = null
    override var floodAdvertIntervalHours: Int? = null
    override var floodMaxHops: Int? = null
    private var originalRepeaterEnabled: Boolean? = null
    private var originalAdvertIntervalMinutes: Int? = null
    private var originalFloodAdvertIntervalHours: Int? = null
    private var originalFloodMaxHops: Int? = null
    override var behaviorError: Boolean = false
        private set
    override var behaviorValidation: NodeSettingsValidation.BehaviorValidation? = null
        private set
    override val behaviorLoaded: Boolean get() = repeaterEnabled != null || advertIntervalMinutes != null
    override val behaviorSettingsModified: Boolean
        get() = (repeaterEnabled != null && repeaterEnabled != originalRepeaterEnabled) ||
            (advertIntervalMinutes != null && advertIntervalMinutes != originalAdvertIntervalMinutes) ||
            (floodAdvertIntervalHours != null && floodAdvertIntervalHours != originalFloodAdvertIntervalHours) ||
            (floodMaxHops != null && floodMaxHops != originalFloodMaxHops)

    override suspend fun fetchBehaviorSettings() {
        behaviorError = false
        val repeatTimedOut = runSettingsFetch {
            (contentReply("get repeat") as? CLIResponse.RepeatMode)?.let {
                adoptSettingsReading(repeaterEnabled, originalRepeaterEnabled, it.enabled) { reading ->
                    repeaterEnabled = reading
                    originalRepeaterEnabled = reading
                }
            }
        }
        val advertTimedOut = runSettingsFetch {
            (contentReply("get advert.interval") as? CLIResponse.AdvertInterval)?.let {
                adoptSettingsReading(advertIntervalMinutes, originalAdvertIntervalMinutes, it.minutes) { reading ->
                    advertIntervalMinutes = reading
                    originalAdvertIntervalMinutes = reading
                }
            }
        }
        val floodAdvertTimedOut = runSettingsFetch {
            (contentReply("get flood.advert.interval") as? CLIResponse.FloodAdvertInterval)?.let {
                adoptSettingsReading(floodAdvertIntervalHours, originalFloodAdvertIntervalHours, it.hours) { reading ->
                    floodAdvertIntervalHours = reading
                    originalFloodAdvertIntervalHours = reading
                }
            }
        }
        val floodMaxTimedOut = runSettingsFetch {
            (contentReply("get flood.max") as? CLIResponse.FloodMax)?.let {
                adoptSettingsReading(floodMaxHops, originalFloodMaxHops, it.hops) { reading ->
                    floodMaxHops = reading
                    originalFloodMaxHops = reading
                }
            }
        }
        val intervalTimedOut = advertTimedOut || floodAdvertTimedOut
        if (repeatTimedOut || intervalTimedOut || floodMaxTimedOut) behaviorError = true
    }

    /**
     * Sends through the shared helper so a query that times out is recorded as unanswered. Without that
     * record the late-reply recovery below has nothing to attribute a stray response to.
     */
    private suspend fun contentReply(command: String): CLIResponse =
        CLIResponse.parse(helper.sendAndWait(command, RepeaterCliTransport.DEFAULT_TIMEOUT), command)

    override suspend fun applyBehaviorSettings(): SettingsApplyResult {
        val validation =
            NodeSettingsValidation.validateBehaviorFields(advertIntervalMinutes, floodAdvertIntervalHours, floodMaxHops)
        behaviorValidation = validation
        if (validation.hasErrors) return SettingsApplyResult.InvalidFields
        val sentRepeat = repeaterEnabled
        val sentAdvert = advertIntervalMinutes
        val sentFloodAdvert = floodAdvertIntervalHours
        val sentFloodMax = floodMaxHops
        return try {
            val repeatApplied = applyIfChanged(
                sentRepeat,
                originalRepeaterEnabled,
                ::repeatCommand,
                transport::commandSucceeded,
            ) {
                originalRepeaterEnabled = it
            }
            val advertApplied = applyIfChanged(
                sentAdvert,
                originalAdvertIntervalMinutes,
                { "set advert.interval $it" },
                transport::commandSucceeded,
            ) { originalAdvertIntervalMinutes = it }
            val floodAdvertApplied = applyIfChanged(
                sentFloodAdvert,
                originalFloodAdvertIntervalHours,
                { "set flood.advert.interval $it" },
                transport::commandSucceeded,
            ) { originalFloodAdvertIntervalHours = it }
            val floodMaxApplied = applyIfChanged(
                sentFloodMax,
                originalFloodMaxHops,
                { "set flood.max $it" },
                transport::commandSucceeded,
            ) {
                originalFloodMaxHops = it
            }
            val intervalsApplied = advertApplied && floodAdvertApplied
            if (repeatApplied && intervalsApplied && floodMaxApplied) {
                SettingsApplyResult.Applied
            } else {
                SettingsApplyResult.PartialFailure
            }
        } catch (e: RemoteNodeError) {
            SettingsApplyResult.TransportError(e)
        }
    }

    private fun repeatCommand(enabled: Boolean): String = if (enabled) "set repeat on" else "set repeat off"

    private val behaviorSectionComplete: Boolean
        get() = originalRepeaterEnabled != null &&
            originalAdvertIntervalMinutes != null &&
            originalFloodAdvertIntervalHours != null &&
            originalFloodMaxHops != null

    /**
     * Registers the Behavior fields with the shared arbitration. Every one of these replies is a bare
     * integer, so attributing by shape alone would put a flood-hop count into the advert interval; the
     * helper only adopts a reply when exactly one query it sent is still unanswered.
     */
    private fun registerBehaviorLateRecovery() {
        helper.registerLateRecovery("get repeat") { value ->
            (value as? CLIResponse.RepeatMode)?.let {
                adoptSettingsReading(repeaterEnabled, originalRepeaterEnabled, it.enabled) { reading ->
                    repeaterEnabled = reading
                    originalRepeaterEnabled = reading
                }
                behaviorError = !behaviorSectionComplete
            }
        }
        helper.registerLateRecovery("get advert.interval") { value ->
            (value as? CLIResponse.AdvertInterval)?.let {
                adoptSettingsReading(advertIntervalMinutes, originalAdvertIntervalMinutes, it.minutes) { reading ->
                    advertIntervalMinutes = reading
                    originalAdvertIntervalMinutes = reading
                }
                behaviorError = !behaviorSectionComplete
            }
        }
        helper.registerLateRecovery("get flood.advert.interval") { value ->
            (value as? CLIResponse.FloodAdvertInterval)?.let {
                adoptSettingsReading(floodAdvertIntervalHours, originalFloodAdvertIntervalHours, it.hours) { reading ->
                    floodAdvertIntervalHours = reading
                    originalFloodAdvertIntervalHours = reading
                }
                behaviorError = !behaviorSectionComplete
            }
        }
        helper.registerLateRecovery("get flood.max") { value ->
            (value as? CLIResponse.FloodMax)?.let {
                adoptSettingsReading(floodMaxHops, originalFloodMaxHops, it.hops) { reading ->
                    floodMaxHops = reading
                    originalFloodMaxHops = reading
                }
                behaviorError = !behaviorSectionComplete
            }
        }
    }
}
