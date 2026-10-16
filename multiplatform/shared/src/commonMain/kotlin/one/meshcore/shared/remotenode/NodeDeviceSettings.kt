package one.meshcore.shared.remotenode

/** Device clock, firmware text, and the commands that read or sync them. */
interface NodeDeviceInfo {
    val firmwareVersion: String?
    val deviceInfoError: Boolean
    val deviceInfoLoaded: Boolean

    /** The raw UTC clock string, or its epoch-seconds parse for the app target to localize. */
    val deviceTimeUtcString: String?
    val deviceTimeEpochSeconds: Long?

    /**
     * Seconds the node is ahead of the injected clock at the last apply. Independent of the raw UTC
     * string so a bare `OK - clock set` can hide the drift warning without wiping the last shown time.
     */
    val clockDriftSeconds: Double?

    /** Adopts firmware clock text into the device time and drift. No-op without a clock shape. */
    fun applyDeviceTime(raw: String)

    suspend fun fetchDeviceInfo()

    suspend fun syncTime(): SyncTimeResult
}

internal class NodeDeviceSettings(
    private val exchange: NodeSettingsExchange,
    private val hooks: NodeSettingsHooks,
    private val nowEpochSeconds: () -> Long,
) : NodeDeviceInfo {
    override var firmwareVersion: String? = null
        internal set
    private var deviceTimeUtc: String? = null
    override var deviceInfoError: Boolean = false
        internal set
    override val deviceInfoLoaded: Boolean get() = deviceTimeUtc != null
    override val deviceTimeUtcString: String? get() = deviceTimeUtc
    override val deviceTimeEpochSeconds: Long?
        get() = deviceTimeUtc?.let { NodeSettingsResponseParser.utcEpochSeconds(it) }
    override var clockDriftSeconds: Double? = null
        private set

    override fun applyDeviceTime(raw: String) {
        val text = NodeSettingsResponseParser.clockResponseText(raw) ?: return
        deviceTimeUtc = text
        clockDriftSeconds = NodeSettingsResponseParser.clockDriftSeconds(text, nowEpochSeconds())
    }

    override suspend fun fetchDeviceInfo() {
        deviceInfoError = false
        if (firmwareVersion == null) hooks.onPreFetchNodeInfo?.invoke()
        val versionTimedOut = if (firmwareVersion == null) {
            runSettingsFetch {
                val reply = CLIResponse.parse(exchange.sendAndWait("ver"), "ver")
                (reply as? CLIResponse.Version)?.let { firmwareVersion = it.text }
            }
        } else {
            false
        }
        val clockTimedOut = runSettingsFetch {
            val reply = CLIResponse.parse(exchange.sendAndWait("clock"), "clock")
            (reply as? CLIResponse.DeviceTime)?.let { applyDeviceTime(it.text) }
        }
        if (versionTimedOut || clockTimedOut) deviceInfoError = true
    }

    override suspend fun syncTime(): SyncTimeResult = try {
        val response = exchange.sendAndWait("clock sync")
        when (val outcome = NodeSettingsResponseParser.classifyClockSyncResponse(response)) {
            is NodeSettingsResponseParser.ClockSyncOutcome.Synced -> {
                if (NodeSettingsResponseParser.clockResponseText(response) != null) {
                    applyDeviceTime(response)
                } else {
                    clockDriftSeconds = null
                }
                SyncTimeResult.Synced
            }
            is NodeSettingsResponseParser.ClockSyncOutcome.ClockAhead -> SyncTimeResult.ClockAhead
            is NodeSettingsResponseParser.ClockSyncOutcome.Failed -> SyncTimeResult.Failed(outcome.message)
            is NodeSettingsResponseParser.ClockSyncOutcome.Unexpected ->
                SyncTimeResult.Unexpected(outcome.response)
        }
    } catch (e: RemoteNodeError) {
        SyncTimeResult.TransportError(e)
    }
}
