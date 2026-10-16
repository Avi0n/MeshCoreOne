package one.meshcore.shared.tools

import one.meshcore.shared.meshcore.BridgeErrorCode
import one.meshcore.shared.meshcore.DeviceCapabilities
import one.meshcore.shared.meshcore.MeshBridgeException
import one.meshcore.shared.meshcore.MeshSession
import one.meshcore.shared.meshcore.SelfInfo
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Production [ToolsCliLocalBridge] over the live [MeshSession], mirroring the subset of the Swift
 * `SettingsService` the local CLI commands drive. Frequency-only and multi-acks writes read the current
 * [SelfInfo] first so the unchanged fields survive, matching the Swift `setFrequency`/`setOtherParams`
 * closures.
 */
@OptIn(ExperimentalTime::class)
class ServiceToolsCliLocalBridge(
    private val session: MeshSession,
    clock: Clock = Clock.System,
    /**
     * The advert transmit. The app passes the position-refreshing send the device menu uses, since the
     * CLI `advert` command is the Swift `CLIToolView` caller of `sendSelfAdvert`; the default is the bare
     * radio advert for callers with no location context.
     */
    advertSend: suspend (Boolean) -> Unit = { session.sendAdvertisement(it) },
    /**
     * The verified manual-position write, which switches the on-board GPS off before writing. A radio with
     * its GPS on overwrites a written coordinate with its own fix within seconds, so a bare `setCoordinates`
     * lets `set lat` report success and then quietly lose the value. No default: a caller that cannot supply
     * the verified write must not silently get the unsafe one.
     */
    writes: CliVerifiedWrites,
) : ToolsCliLocalBridge,
    ToolsCliDeviceCommands by ServiceToolsCliDeviceCommands(session, clock, advertSend),
    ToolsCliRadioCommands by ServiceToolsCliRadioCommands(session, writes) {
    /**
     * Identity is the live [MeshSession], not the wrapper: the app builds a bridge per provider read, and
     * [ToolsCliTerminalKey] must see those reads as one terminal or every return to the CLI discards the
     * scrollback and the remote logins. A reconnect rebuilds the session, which is a new terminal.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ServiceToolsCliLocalBridge) return false
        return session === other.session
    }

    override fun hashCode(): Int = session.hashCode()
}

@OptIn(ExperimentalTime::class)
private class ServiceToolsCliDeviceCommands(
    private val session: MeshSession,
    private val clock: Clock,
    private val advertSend: suspend (Boolean) -> Unit,
) : ToolsCliDeviceCommands {
    override suspend fun selfInfo(): SelfInfo = session.getSelfInfo()

    override suspend fun deviceCapabilities(): DeviceCapabilities = session.queryDevice()

    override suspend fun getTime(): Long = session.getTime()

    override suspend fun syncClock(): Long {
        val now = clock.now().epochSeconds
        session.setTime(now)
        return now
    }

    override suspend fun batteryMillivolts(): Int = session.getBattery().level

    override suspend fun getCustomVars(): Map<String, String> = session.getCustomVars()

    override suspend fun setCustomVar(key: String, value: String): Boolean = try {
        session.setCustomVar(key, value)
        true
    } catch (e: MeshBridgeException) {
        // Firmware rejects an unknown custom-var name with a device/input error; other failures propagate.
        if (e.code == BridgeErrorCode.DEVICE_ERROR || e.code == BridgeErrorCode.INVALID_INPUT) false else throw e
    }

    override suspend fun sendAdvert(flood: Boolean) = advertSend(flood)

    override suspend fun reboot() = session.reboot()
}

private class ServiceToolsCliRadioCommands(private val session: MeshSession, private val writes: CliVerifiedWrites) :
    ToolsCliRadioCommands {
    override suspend fun setName(name: String): String = writes.setName(name)

    override suspend fun setLatitude(latitude: Double) {
        val current = session.getSelfInfo()
        writes.setCoordinates(latitude, current.longitude)
    }

    override suspend fun setLongitude(longitude: Double) {
        val current = session.getSelfInfo()
        writes.setCoordinates(current.latitude, longitude)
    }

    override suspend fun setTxPower(power: Int) = writes.setTxPower(power)

    override suspend fun setRadio(frequencyMHz: Double, bandwidthKHz: Double, spreadingFactor: Int, codingRate: Int) {
        writes.applyRadioParams(frequencyMHz, bandwidthKHz, spreadingFactor, codingRate, null)
    }

    override suspend fun setFrequency(frequencyMHz: Double) {
        val current = session.getSelfInfo()
        writes.applyRadioParams(
            frequencyMHz,
            current.radioBandwidth,
            current.radioSpreadingFactor,
            current.radioCodingRate,
            null,
        )
    }

    override suspend fun setMultiAcks(value: Int) = writes.setMultiAcks(value)

    override suspend fun setPathHashMode(mode: Int) = writes.setPathHashMode(mode)
}
