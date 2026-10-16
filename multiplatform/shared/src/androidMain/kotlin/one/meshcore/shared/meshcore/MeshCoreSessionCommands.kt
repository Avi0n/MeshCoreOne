package one.meshcore.shared.meshcore

import kotlinx.coroutines.flow.SharedFlow
import one.meshcore.shared.channel.FloodScope
import one.meshcore.shared.channel.ResolvedFloodScope
import one.meshcore.shared.model.ContactType

/** Read-only radio diagnostics on the live bridge session. */
interface MeshCoreSessionStats {
    suspend fun getRepeatFreq(): List<RepeatFreqRange>

    suspend fun getStatsCore(): CoreStats

    suspend fun getStatsRadio(): RadioStats

    suspend fun getStatsPackets(): PacketStats

    suspend fun getSelfTelemetry(): TelemetryResponse
}

/** Privileged bridge commands that are not part of [MeshSession]. */
interface MeshCoreSessionAdmin {
    suspend fun factoryReset()

    /** Exports a contact URI; `null` exports this device's own contact card. */
    suspend fun exportContact(publicKey: ByteArray? = null): String

    suspend fun importContact(cardData: ByteArray)

    suspend fun requestMMA(publicKey: ByteArray, startEpoch: Long, endEpoch: Long): MMAResponse

    suspend fun requestACL(publicKey: ByteArray): ACLResponse

    suspend fun fetchAllNeighbours(publicKey: ByteArray, orderBy: Int, pubkeyPrefixLength: Int): NeighboursResponse

    suspend fun sign(data: ByteArray): ByteArray
}

internal class LinkSessionCommands(private val host: MeshCoreSessionHost) : MeshSessionLink {
    override val events: SharedFlow<MeshEvent>
        get() = host.events

    override suspend fun start(): SelfInfo =
        (host.await { host.bridge.startSession(it) } as CommandResult.SelfInfoResult).info

    override suspend fun queryDevice(): DeviceCapabilities =
        (host.await { host.bridge.queryDevice(it) } as CommandResult.DeviceCapabilitiesResult).capabilities

    override suspend fun getTime(): Long = (host.await { host.bridge.getTime(it) } as CommandResult.TimeResult).time

    override suspend fun getBattery(): BatteryInfo =
        (host.await { host.bridge.getBattery(it) } as CommandResult.BatteryResult).battery

    override suspend fun stop() = host.expectOk { host.bridge.stopSession(it) }

    override fun reportConnected() = host.signalNative { it.reportConnected() }

    override fun reportConnectFailed(message: String) = host.signalNative { it.reportConnectFailed(message) }

    override fun reportDisconnected() = host.signalNative { it.reportDisconnected() }

    override fun deliverReceived(bytes: ByteArray) = host.signalNative { it.receiveBytes(bytes) }

    override fun close() = host.close()
}

internal class MessagingSessionCommands(private val host: MeshCoreSessionHost) : MeshSessionMessaging {
    override suspend fun sendMessage(
        destination: ByteArray,
        text: String,
        timestamp: Long,
        attempt: Int,
    ): MessageSentInfo = (
        host.await {
            host.bridge.sendMessage(it, destination, text, timestamp, attempt)
        } as CommandResult.MessageSentResult
        ).info

    override suspend fun sendChannelMessage(channel: Int, text: String, timestamp: Long) =
        host.expectOk { host.bridge.sendChannelMessage(it, channel, text, timestamp) }

    override suspend fun sendChannelMessage(
        channel: Int,
        text: String,
        timestamp: Long,
        floodScope: ResolvedFloodScope,
    ) {
        val (scopeType, scopeText) = when (floodScope) {
            ResolvedFloodScope.Unscoped -> "unscoped" to ""
            is ResolvedFloodScope.Scope -> when (val scope = floodScope.scope) {
                FloodScope.Disabled -> "disabled" to ""
                is FloodScope.Region -> "region" to scope.name
            }
        }
        host.expectOk { host.bridge.sendChannelMessageWithScope(it, channel, text, timestamp, scopeType, scopeText) }
    }

    override suspend fun getChannel(index: Int): ChannelInfo =
        (host.await { host.bridge.getChannel(it, index) } as CommandResult.ChannelInfoResult).channel

    override suspend fun getChannels(indices: ByteArray): CommandResult.ChannelsResult =
        host.await { host.bridge.getChannels(it, indices) } as CommandResult.ChannelsResult

    override suspend fun setChannel(index: Int, name: String, secret: ByteArray) =
        host.expectOk { host.bridge.setChannel(it, index, name, secret) }

    override suspend fun getMessage(): GetMessageResult =
        (host.await { host.bridge.getMessage(it) } as CommandResult.MessageResult).message

    override suspend fun startAutoMessageFetching() = host.expectOk { host.bridge.startAutoMessageFetching(it) }

    override suspend fun stopAutoMessageFetching() = host.expectOk { host.bridge.stopAutoMessageFetching(it) }

    override suspend fun sendAdvertisement(flood: Boolean) = host.expectOk { host.bridge.sendAdvertisement(it, flood) }
}

internal class ContactsSessionCommands(private val host: MeshCoreSessionHost) : MeshSessionContacts {
    override suspend fun getContacts(since: Long?): List<MeshContact> = getContactsReportingTotal(since).contacts

    override suspend fun getContactsReportingTotal(since: Long?): ContactFetchResult {
        val result = host.await {
            host.bridge.getContacts(it, since ?: BRIDGE_UNSET.toLong())
        } as CommandResult.ContactsResult
        return ContactFetchResult(contacts = result.contacts, reportedTotal = result.reportedTotal)
    }

    override suspend fun getContact(publicKey: ByteArray): MeshContact? =
        (host.await { host.bridge.getContact(it, publicKey) } as CommandResult.ContactOptionalResult).contact

    override suspend fun resetPath(publicKey: ByteArray) = host.expectOk { host.bridge.resetPath(it, publicKey) }

    override suspend fun addContact(contact: MeshContact) =
        host.expectOk { host.bridge.addContactEncoded(it, encodeContactEnvelope(contact)) }

    override suspend fun removeContact(publicKey: ByteArray) =
        host.expectOk { host.bridge.removeContact(it, publicKey) }

    override suspend fun changeContactFlags(contact: MeshContact, flags: Int) =
        host.expectOk { host.bridge.changeContactFlags(it, encodeContactEnvelope(contact), flags) }

    override suspend fun sendPathDiscovery(destination: ByteArray): MessageSentInfo =
        (host.await { host.bridge.sendPathDiscovery(it, destination) } as CommandResult.MessageSentResult).info

    override suspend fun sendNodeDiscoverRequest(
        filter: Int,
        prefixOnly: Boolean,
        tag: Long?,
        sinceEpochSeconds: Long?,
    ): Long = (
        host.await {
            host.bridge.sendNodeDiscoverRequest(
                it,
                filter,
                prefixOnly,
                tag ?: BRIDGE_UNSET.toLong(),
                sinceEpochSeconds ?: BRIDGE_UNSET.toLong(),
            )
        } as CommandResult.DiscoverTagResult
        ).tag

    override suspend fun shareContact(publicKey: ByteArray) = host.expectOk { host.bridge.shareContact(it, publicKey) }
}

internal class RemoteSessionCommands(private val host: MeshCoreSessionHost) : MeshSessionRemote {
    override suspend fun requestRegions(contact: MeshContact): List<String> =
        (host.await { host.bridge.requestRegions(it, encodeContactEnvelope(contact)) } as CommandResult.RegionsResult)
            .regions

    override suspend fun sendTrace(tag: Long?, authCode: Long?, flags: Int, path: ByteArray?): MessageSentInfo = (
        host.await {
            host.bridge.sendTrace(
                it,
                tag ?: BRIDGE_UNSET.toLong(),
                authCode ?: BRIDGE_UNSET.toLong(),
                flags,
                path ?: BRIDGE_EMPTY_BYTES,
            )
        } as CommandResult.MessageSentResult
        ).info

    override suspend fun sendLogin(destination: ByteArray, password: String): MessageSentInfo =
        (host.await { host.bridge.sendLogin(it, destination, password) } as CommandResult.MessageSentResult).info

    override suspend fun sendLogout(destination: ByteArray) = host.expectOk { host.bridge.sendLogout(it, destination) }

    override suspend fun sendKeepAlive(publicKey: ByteArray, syncSince: Long): MessageSentInfo =
        (host.await { host.bridge.sendKeepAlive(it, publicKey, syncSince) } as CommandResult.MessageSentResult).info

    override suspend fun requestStatus(publicKey: ByteArray, type: ContactType): StatusResponse =
        (host.await { host.bridge.requestStatus(it, publicKey, type.raw) } as CommandResult.StatusResponseResult).status

    override suspend fun requestTelemetry(publicKey: ByteArray): TelemetryResponse =
        (host.await { host.bridge.requestTelemetry(it, publicKey) } as CommandResult.TelemetryResponseResult).telemetry

    override suspend fun requestOwnerInfo(publicKey: ByteArray): OwnerInfoResponse =
        (host.await { host.bridge.requestOwnerInfo(it, publicKey) } as CommandResult.OwnerInfoResult).info

    override suspend fun sendCommand(destination: ByteArray, command: String, timestamp: Long): MessageSentInfo =
        (host.await { host.bridge.sendCommand(it, destination, command, timestamp) } as CommandResult.MessageSentResult)
            .info

    override suspend fun requestNeighbours(
        publicKey: ByteArray,
        count: Int,
        offset: Int,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse = (
        host.await { host.bridge.requestNeighbours(it, publicKey, count, offset, orderBy, pubkeyPrefixLength) }
            as CommandResult.NeighboursResult
        ).response
}

internal class RadioSessionCommands(private val host: MeshCoreSessionHost) : MeshSessionRadio {
    override suspend fun getSelfInfo(): SelfInfo =
        (host.await { host.bridge.startSession(it) } as CommandResult.SelfInfoResult).info

    override suspend fun exportPrivateKey(): ByteArray =
        (host.await { host.bridge.exportPrivateKey(it) } as CommandResult.PrivateKeyResult).privateKey

    override suspend fun importPrivateKey(privateKey: ByteArray) =
        host.expectOk { host.bridge.importPrivateKey(it, privateKey) }

    override suspend fun setName(name: String) = host.expectOk { host.bridge.setName(it, name) }

    override suspend fun setCoordinates(latitude: Double, longitude: Double) =
        host.expectOk { host.bridge.setCoordinates(it, latitude, longitude) }

    override suspend fun setTime(epochSeconds: Long) = host.expectOk { host.bridge.setTime(it, epochSeconds) }

    override suspend fun setOtherParams(params: DeviceOtherParams) = host.expectOk {
        host.bridge.setOtherParams(
            it,
            params.manualAddContacts,
            params.telemetryModeEnvironment,
            params.telemetryModeLocation,
            params.telemetryModeBase,
            params.advertisementLocationPolicy,
            params.multiAcks ?: BRIDGE_UNSET,
        )
    }

    override suspend fun setRadio(
        frequency: Double,
        bandwidth: Double,
        spreadingFactor: Int,
        codingRate: Int,
        clientRepeat: Boolean?,
    ) = host.expectOk {
        host.bridge.setRadio(it, frequency, bandwidth, spreadingFactor, codingRate, clientRepeat.toBridgeTriState())
    }

    override suspend fun setTxPower(power: Int) = host.expectOk { host.bridge.setTxPower(it, power) }

    override suspend fun getAutoAddConfig(): AutoAddConfig =
        (host.await { host.bridge.getAutoAddConfig(it) } as CommandResult.AutoAddConfigResult).config
}

internal class FloodSessionCommands(private val host: MeshCoreSessionHost) : MeshSessionFlood {
    override suspend fun setAutoAddConfig(bitmask: Int, maxHops: Int) =
        host.expectOk { host.bridge.setAutoAddConfig(it, bitmask, maxHops) }

    override suspend fun setDevicePin(pin: Long) = host.expectOk { host.bridge.setDevicePin(it, pin) }

    override suspend fun getDefaultFloodScope(): DefaultFloodScope? =
        (host.await { host.bridge.getDefaultFloodScope(it) } as CommandResult.DefaultFloodScopeResult).scope

    override suspend fun setDefaultFloodScope(
        name: String,
        scopeType: String,
        scopeText: String,
        scopeKey: ByteArray,
    ) = host.expectOk { host.bridge.setDefaultFloodScope(it, name, scopeType, scopeText, scopeKey) }

    override suspend fun setFloodScope(scopeType: String, scopeText: String, scopeKey: ByteArray) =
        host.expectOk { host.bridge.setFloodScope(it, scopeType, scopeText, scopeKey) }

    override suspend fun setFloodScopeUnscoped() = host.expectOk { host.bridge.setFloodScopeUnscoped(it) }

    override suspend fun reboot() = host.expectOk { host.bridge.reboot(it) }

    override suspend fun getCustomVars(): Map<String, String> =
        (host.await { host.bridge.getCustomVars(it) } as CommandResult.CustomVarsResult).vars

    override suspend fun setCustomVar(key: String, value: String) =
        host.expectOk { host.bridge.setCustomVar(it, key, value) }

    override suspend fun setPathHashMode(mode: Int) = host.expectOk { host.bridge.setPathHashMode(it, mode) }
}

internal class StatsSessionCommands(private val host: MeshCoreSessionHost) : MeshCoreSessionStats {
    override suspend fun getRepeatFreq(): List<RepeatFreqRange> =
        (host.await { host.bridge.getRepeatFreq(it) } as CommandResult.AllowedRepeatFreqResult).ranges

    override suspend fun getStatsCore(): CoreStats =
        (host.await { host.bridge.getStatsCore(it) } as CommandResult.StatsCoreResult).stats

    override suspend fun getStatsRadio(): RadioStats =
        (host.await { host.bridge.getStatsRadio(it) } as CommandResult.StatsRadioResult).stats

    override suspend fun getStatsPackets(): PacketStats =
        (host.await { host.bridge.getStatsPackets(it) } as CommandResult.StatsPacketsResult).stats

    override suspend fun getSelfTelemetry(): TelemetryResponse =
        (host.await { host.bridge.getSelfTelemetry(it) } as CommandResult.TelemetryResponseResult).telemetry
}

internal class AdminSessionCommands(private val host: MeshCoreSessionHost) : MeshCoreSessionAdmin {
    override suspend fun factoryReset() = host.expectOk { host.bridge.factoryReset(it) }

    override suspend fun exportContact(publicKey: ByteArray?): String = (
        host.await { host.bridge.exportContact(it, publicKey ?: BRIDGE_EMPTY_BYTES) }
            as CommandResult.ContactUriResult
        ).uri

    override suspend fun importContact(cardData: ByteArray) = host.expectOk { host.bridge.importContact(it, cardData) }

    override suspend fun requestMMA(publicKey: ByteArray, startEpoch: Long, endEpoch: Long): MMAResponse =
        (host.await { host.bridge.requestMMA(it, publicKey, startEpoch, endEpoch) } as CommandResult.MMAResult).response

    override suspend fun requestACL(publicKey: ByteArray): ACLResponse =
        (host.await { host.bridge.requestACL(it, publicKey) } as CommandResult.ACLResult).response

    override suspend fun fetchAllNeighbours(
        publicKey: ByteArray,
        orderBy: Int,
        pubkeyPrefixLength: Int,
    ): NeighboursResponse = (
        host.await { host.bridge.fetchAllNeighbours(it, publicKey, orderBy, pubkeyPrefixLength) }
            as CommandResult.NeighboursResult
        ).response

    override suspend fun sign(data: ByteArray): ByteArray =
        (host.await { host.bridge.sign(it, data) } as CommandResult.SignatureResult).signature
}
