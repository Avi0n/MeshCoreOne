package one.meshcore.shared.meshcore

import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.model.ContactType

/** Link state, the app-start handshake, and the frames the transport delivers. */
interface MeshSessionLink : AutoCloseable {
    /** The hot stream of pushed device events, including received messages. */
    val events: SharedFlow<MeshEvent>

    /** Runs the app-start handshake, returning this device's configuration. */
    suspend fun start(): SelfInfo

    /** Queries static device capabilities (firmware, model, limits). */
    suspend fun queryDevice(): DeviceCapabilities

    /**
     * Reads the radio's clock. The WiFi heartbeat uses it as a lightweight liveness probe: ESP32's TCP
     * stack ignores TCP keepalives, so the connection manager periodically issues this to generate
     * traffic the transport's staleness watchdog can measure against.
     */
    suspend fun getTime(): Long

    /**
     * Reads the connected radio's battery/storage. Default throws; production
     * [one.meshcore.shared.meshcore.MeshCoreSession] bridges it.
     */
    suspend fun getBattery(): BatteryInfo {
        error("getBattery is not supported on this session")
    }

    /** Stops the session and disconnects the underlying transport. */
    suspend fun stop()

    // Inbound transport hooks, driven by the link implementation.

    /** Reports the physical link is up, unblocking a pending [start]. */
    fun reportConnected()

    /** Reports the connect attempt failed. */
    fun reportConnectFailed(message: String)

    /** Reports the link dropped after being established. */
    fun reportDisconnected()

    /** Delivers one complete inbound frame from the link. */
    fun deliverReceived(bytes: ByteArray)

    override fun close()
}

/** Channel slots, direct and channel sends, and the backlog drain. */
interface MeshSessionMessaging {
    /** Queues a direct message for transmission, returning the chosen route and expected ack. */
    suspend fun sendMessage(destination: ByteArray, text: String, timestamp: Long, attempt: Int): MessageSentInfo

    /** Broadcasts a channel message; no ack is expected. [timestamp] is epoch seconds. */
    suspend fun sendChannelMessage(channel: Int, text: String, timestamp: Long)

    /** Applies scope and sends atomically through the Swift session. */
    suspend fun sendChannelMessage(channel: Int, text: String, timestamp: Long, floodScope: ResolvedFloodScope)

    /** Reads one channel slot; the returned [ChannelInfo.index] must equal [index]. */
    suspend fun getChannel(index: Int): ChannelInfo

    /**
     * Reads [indices] (packed slot bytes) in one bounded-window exchange; the result carries the slots that
     * answered plus the indices the caller reconciles with serial [getChannel] reads.
     */
    suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult

    /** Writes a channel slot (name + 16-byte secret), waiting for the device ack. */
    suspend fun setChannel(index: Int, name: String, secret: ByteArray)

    /**
     * Fetches the next queued backlog message, or [GetMessageResult.NoMoreMessages] when the drain is
     * done. The getMessage-coalescing lost-wakeup fix lives below the bridge, so concurrent callers are
     * safe without app-level locking.
     */
    suspend fun getMessage(): GetMessageResult

    /**
     * Enables MeshCore's automatic drain when the radio pushes `messagesWaiting` (mirrors iOS
     * `MessagePollingService.startAutoFetch` → `MeshCoreSession.startAutoMessageFetching`). Default is a
     * no-op so pure-JVM fakes need not model the Swift-side loop; production [MeshCoreSession] bridges it.
     */
    suspend fun startAutoMessageFetching() = Unit

    /**
     * Stops the automatic fetch started by [startAutoMessageFetching]. Default no-op for fakes.
     */
    suspend fun stopAutoMessageFetching() = Unit

    /** Broadcasts this radio's advertisement; [flood] floods the mesh, otherwise a zero-hop advert. */
    suspend fun sendAdvertisement(flood: Boolean) {
        error("sendAdvertisement is not supported on this session")
    }
}

/** Contact sync, path reset, and node discovery on the paired radio. */
interface MeshSessionContacts {
    /** Fetches contacts modified since `since` epoch seconds; `null` for the full list. */
    suspend fun getContacts(since: Long? = null): List<MeshContact>

    /**
     * Fetches contacts plus the device's `contactsStart` total. Default treats the received list as
     * complete so fakes prune; the live bridge session overrides with the header value.
     */
    suspend fun getContactsReportingTotal(since: Long? = null): ContactFetchResult {
        val contacts = getContacts(since)
        return ContactFetchResult(contacts = contacts, reportedTotal = contacts.size)
    }

    /** Reads a contact's current device state by 32-byte public key, or `null` if it is not on the device. */
    suspend fun getContact(publicKey: ByteArray): MeshContact?

    /** Resets a contact's out-path to flood routing on the device. */
    suspend fun resetPath(publicKey: ByteArray)

    /** Adds or updates a whole contact on the device. */
    suspend fun addContact(contact: MeshContact)

    /** Removes a contact from the device by its 32-byte public key. */
    suspend fun removeContact(publicKey: ByteArray)

    /** Writes new contact flags (favorite + telemetry permission bits), waiting for the ack. */
    suspend fun changeContactFlags(contact: MeshContact, flags: Int)

    /** Sends a path-discovery request; returns the firmware's estimated timeout. */
    suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo

    /**
     * Active node discovery (`nodeDiscoverRequest`). Returns the 32-bit correlation tag used to match
     * subsequent [MeshEvent.DiscoverResponseReceived] events. [filter] is the firmware type mask
     * (e.g. 0x04 repeaters, 0x10 sensors). Default throws; production [MeshCoreSession] bridges it.
     */
    suspend fun sendNodeDiscoverRequest(
        filter: Int,
        prefixOnly: Boolean = false,
        tag: Long? = null,
        sinceEpochSeconds: Long? = null,
    ): Long {
        error("sendNodeDiscoverRequest is not supported on this session")
    }

    /** Zero-hop broadcast of a contact's advertisement so nearby nodes can add it. */
    suspend fun shareContact(publicKey: ByteArray)
}

/** Login, status, telemetry, and CLI commands for a remote node. */
interface MeshSessionRemote {
    /**
     * Queries a remote repeater's advertised flood-scope region names (`requestRegions`).
     * Default throws; production [MeshCoreSession] bridges it.
     * Used by [one.meshcore.shared.channel.RegionDiscoveryService].
     */
    suspend fun requestRegions(contact: MeshContact): List<String> {
        error("requestRegions is not supported on this session")
    }

    /**
     * Sends a path trace. [tag]/[authCode] null lets the firmware choose; [path] null uses the default
     * route. Used by zero-hop contact ping and the Trace Path tool.
     */
    suspend fun sendTrace(tag: Long?, authCode: Long?, flags: Int, path: ByteArray?): MessageSentInfo

    // Remote-node access commands driven by the node/room service graph. Login/CLI results arrive later on
    // [events] (a login push, or a `contactMessageReceived` with the CLI text type); the rest are
    // request-response. The node/room stores stay commonMain because these live on the seam.

    /** Sends a login to [destination] (full 32-byte key); the result arrives later as a login push event. */
    suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo

    /** Sends a logout to [destination] (full 32-byte key). */
    suspend fun sendLogout(destination: ByteArray)

    /** Sends a keep-alive to [publicKey], carrying [syncSince] as the room-sync force-resync hint. */
    suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo

    /**
     * Requests a status frame from [publicKey]. [type] selects the firmware status layout the frame is
     * read with: a room server packs its posted and post-push counters into the field a repeater uses
     * for RX airtime, so the caller's node type must reach the bridge with the request.
     */
    suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse

    /** Requests telemetry from [publicKey]; the LPP payload is decoded bridge-side. */
    suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse

    /** Requests owner info (firmware version, node name, owner text) from [publicKey]. */
    suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse

    /**
     * Sends a CLI [command] to [destination] (full 32-byte key), stamping [timestamp] (epoch seconds). The
     * response arrives later as a pushed `contactMessageReceived` event with the CLI text type.
     */
    suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Long): MessageSentInfo

    /** Requests one page of a repeater's neighbours from [publicKey]. */
    suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse
}

/** Name, position, clock, keys, and LoRa settings of the paired radio. */
interface MeshSessionRadio {
    // Local-radio config commands driven by [one.meshcore.shared.settings.NodeConfigService].

    /**
     * Re-runs the app-start handshake and returns this device's current configuration (the Swift
     * `SettingsService.getSelfInfo` / `sendAppStart` path).
     */
    suspend fun getSelfInfo(): SelfInfo

    /**
     * Exports the device private key, or throws [MeshBridgeException] with
     * [BridgeErrorCode.FEATURE_DISABLED] on hardened firmware that disables key export.
     */
    suspend fun exportPrivateKey(): ByteArray

    /** Restores a device identity from a 32-byte private key. */
    suspend fun importPrivateKey(privateKey: ByteArray)

    /** Sets the publicly visible node name. */
    suspend fun setName(name: String)

    /** Sets the advertised position in degrees. */
    suspend fun setCoordinates(latitude: Double, longitude: Double)

    /** Sets the radio's clock to [epochSeconds] (UTC). Firmware rejects moving the clock backwards. */
    suspend fun setTime(epochSeconds: Long)

    /**
     * Commits the other-parameters blob. [DeviceOtherParams.multiAcks] null leaves the field unset on the
     * wire (bridge sentinel), matching the Swift optional multiAcks path.
     */
    suspend fun setOtherParams(params: DeviceOtherParams)

    /**
     * Retunes the LoRa radio. [frequency] is MHz and [bandwidth] is kHz on the wire (the same units
     * `SelfInfo.radioFrequency` / `radioBandwidth` use). Callers that hold kHz/Hz plan values must
     * divide by 1000 first (see SettingsService.setRadioParams).
     */
    suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: Int,
        codingRate: Int,
        clientRepeat: Boolean?,
    )

    /** Sets the transmit power in dBm. */
    suspend fun setTxPower(power: Int)

    /**
     * Reads the v1.12+ auto-add bitmask and max-hops the radio is actually running. Deliberately
     * undefaulted: a default returning an all-off policy would look like a real answer, and the connect
     * path persists what this returns, so every implementation must say what it knows.
     */
    suspend fun getAutoAddConfig(): AutoAddConfig
}

/** Flood scope, custom vars, auto-add, and the companion reboot. */
interface MeshSessionFlood {
    /**
     * Writes the v1.12+ auto-add bitmask and optional max-hops. Older firmware ignores this command;
     * callers still set [setOtherParams] manualAddContacts for the all/manual split on all versions.
     */
    suspend fun setAutoAddConfig(bitmask: Int, maxHops: Int)

    /**
     * Sets the BLE pairing PIN on the radio (6-digit custom PIN). Default throws; production
     * [one.meshcore.shared.meshcore.MeshCoreSession] bridges to firmware. The device typically reboots.
     */
    suspend fun setDevicePin(pin: Long) {
        error("setDevicePin is not supported on this session")
    }

    /**
     * Reads the device's persisted default flood scope (firmware v11+), or `null` if unset.
     * Default throws; production [one.meshcore.shared.meshcore.MeshCoreSession] bridges it.
     */
    suspend fun getDefaultFloodScope(): DefaultFloodScope? {
        error("getDefaultFloodScope is not supported on this session")
    }

    /**
     * Persists the device's default flood scope. `scopeType` selects the case: `"disabled"`,
     * `"channelName"` (uses [scopeText]), `"rawKey"` (uses [scopeKey]), or `"region"` (uses
     * [scopeText]). Default throws; production [one.meshcore.shared.meshcore.MeshCoreSession]
     * bridges it.
     */
    suspend fun setDefaultFloodScope(name: String, scopeType: String, scopeText: String, scopeKey: ByteArray) {
        error("setDefaultFloodScope is not supported on this session")
    }

    /**
     * Pushes a session-scoped flood key (firmware sub-command 0). `scopeType` selects the case:
     * `"disabled"`, `"channelName"` (uses [scopeText]), `"rawKey"` (uses [scopeKey]), or `"region"`
     * (uses [scopeText]). Default throws; production [one.meshcore.shared.meshcore.MeshCoreSession]
     * bridges it.
     */
    suspend fun setFloodScope(scopeType: String, scopeText: String, scopeKey: ByteArray) {
        error("setFloodScope is not supported on this session")
    }

    /**
     * Forces un-scoped flood broadcasts (firmware sub-command 1, requires firmware v12+). Default
     * throws; production [one.meshcore.shared.meshcore.MeshCoreSession] bridges it.
     */
    suspend fun setFloodScopeUnscoped() {
        error("setFloodScopeUnscoped is not supported on this session")
    }

    /**
     * Reboots the connected companion device. Default throws; production
     * [one.meshcore.shared.meshcore.MeshCoreSession] bridges it.
     */
    suspend fun reboot() {
        error("reboot is not supported on this session")
    }

    /** Reads all custom firmware variables, keyed by name. Default throws; production bridges it. */
    suspend fun getCustomVars(): Map<String, String> {
        error("getCustomVars is not supported on this session")
    }

    /** Writes a custom firmware variable. Default throws; production bridges it. */
    suspend fun setCustomVar(key: String, value: String) {
        error("setCustomVar is not supported on this session")
    }

    /** Sets the path-hash mode. Default throws; production bridges it. */
    suspend fun setPathHashMode(mode: Int) {
        error("setPathHashMode is not supported on this session")
    }
}
